package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.desktopDisplayMetrics
import coil3.BitmapImage
import coil3.Image
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.NullRequestDataException
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.CubicResampler
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val MinCustomDownscaleRatio = 1.08f
private const val MaxDesktopSourceSizePx = 1536
private const val MaxScaledBitmapPixels = 1_250_000L

// See [rememberHeroSourceSize]. The headroom covers HERO_SCROLL_MAX_SCALE (1.3x) without paying for
// all of it; the quantum keeps a window drag from re-requesting on every frame; the ceiling means a
// 4K source is still capped on a 4K display, where it is already 1:1.
private const val HeroScrollScaleHeadroom = 1.15f
private const val HeroSourceSizeQuantumPx = 128
private const val MinHeroSourceSizePx = 640
// 4K plus its headroom: the ceiling must never land below what a 4K display draws 1:1.
private const val MaxHeroSourceSizePx = 4480

// How far the requested draw size may drift from the cached bitmap before it is worth paying for a
// fresh resample. A card that has settled asks for the same size every frame and hits the cache
// exactly; only an in-flight animation lands inside the tolerance band, and there a one-frame
// mipmapped rescale is much cheaper than a full CPU resample per frame.
private const val CachedBitmapReuseTolerance = 0.06f

// Mitchell (b = c = 1/3) rather than Catmull-Rom (b = 0, c = 0.5): both are cubics, but
// Catmull-Rom's much deeper negative lobes sharpen whatever survives the reduction — including the
// aliasing the resample is supposed to suppress. That sharpening is why poster lettering came out
// crunchy at 1080p, where a 500px source lands in a 210px card. Mitchell is the standard
// minification cubic and leaves nothing to over-sharpen now that the pre-reduction below does the
// heavy lifting.
internal val HighQualityDesktopResampler = CubicResampler(b = 1f / 3f, c = 1f / 3f)

// A cubic resampler reads a fixed 4x4 source neighbourhood no matter how far it is reducing, so
// past ~2x minification it simply discards most of the source and aliases. Halving with a linear
// filter is an exact 2x2 box average, so repeated halving is a correct (and cheap) way to get
// within 2x of the target and leave the cubic only the last, well-conditioned step.
internal val BoxHalvingSampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

private val IsWindowsDesktop: Boolean =
    System.getProperty("os.name")
        ?.startsWith("Windows", ignoreCase = true)
        ?: false

@Composable
internal actual fun NuvioAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    placeholder: Painter?,
    error: Painter?,
    fallback: Painter?,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)?,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)?,
    onError: ((AsyncImagePainter.State.Error) -> Unit)?,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
    filterQuality: FilterQuality?,
    clipToBounds: Boolean,
    desktopImageScaling: NuvioDesktopImageScaling,
) {
    val context = LocalPlatformContext.current
    val effectiveDesktopImageScaling = remember(desktopImageScaling) {
        if (IsWindowsDesktop) desktopImageScaling else NuvioDesktopImageScaling.Disabled
    }
    val heroSourceSize = rememberHeroSourceSize()
    val requestModel = remember(context, model, effectiveDesktopImageScaling, heroSourceSize) {
        when {
            effectiveDesktopImageScaling != NuvioDesktopImageScaling.Disabled ->
                model.withDesktopSize(context, MaxDesktopSourceSizePx, MaxDesktopSourceSizePx)
            // Scaling "disabled" only ever meant "do not run the custom painter on this". It also
            // silently meant "send no size", which left the hero decoding backdrops at whatever the
            // source happened to be - see [rememberHeroSourceSize].
            heroSourceSize != null ->
                model.withDesktopSize(context, heroSourceSize.width, heroSourceSize.height)
            else -> model
        }
    }
    val transform: (AsyncImagePainter.State) -> AsyncImagePainter.State = remember(
        placeholder,
        error,
        fallback,
        effectiveDesktopImageScaling,
    ) {
        { state ->
            when (state) {
                is AsyncImagePainter.State.Loading -> {
                    placeholder?.let { state.copy(painter = it) } ?: state
                }
                is AsyncImagePainter.State.Success -> {
                    val image = state.result.image
                    if (image is SkiaAnimatedImage) {
                        state.copy(painter = SkiaAnimatedPainter(image))
                    } else {
                        image.toScaledBitmapPainter(
                            desktopImageScaling = effectiveDesktopImageScaling,
                            // Identity of the *source*, so a card scrolled away and back reuses the
                            // reduction the previous painter already paid for.
                            sourceKey = state.result.memoryCacheKey?.key ?: state.result.diskCacheKey,
                        )
                            ?.let { state.copy(painter = it) }
                            ?: state
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    val fallbackPainter = if (state.result.throwable is NullRequestDataException) {
                        fallback ?: error
                    } else {
                        error
                    }
                    fallbackPainter?.let { state.copy(painter = it) } ?: state
                }
                AsyncImagePainter.State.Empty -> state
            }
        }
    }
    val onState: ((AsyncImagePainter.State) -> Unit)? = remember(onLoading, onSuccess, onError) {
        if (onLoading == null && onSuccess == null && onError == null) {
            null
        } else {
            { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> onLoading?.invoke(state)
                    is AsyncImagePainter.State.Success -> onSuccess?.invoke(state)
                    is AsyncImagePainter.State.Error -> onError?.invoke(state)
                    AsyncImagePainter.State.Empty -> Unit
                }
            }
        }
    }

    AsyncImage(
        model = requestModel,
        contentDescription = contentDescription,
        modifier = modifier,
        transform = transform,
        onState = onState,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality ?: FilterQuality.High,
        clipToBounds = clipToBounds,
    )
}

internal actual fun ImageRequest.Builder.nuvioArtworkRequestSize(): ImageRequest.Builder =
    size(MaxDesktopSourceSizePx)

private fun Any?.withDesktopSize(context: PlatformContext, width: Int, height: Int): Any? {
    if (this == null) return null

    return if (this is ImageRequest) {
        newBuilder()
            .size(width, height)
            .build()
    } else {
        ImageRequest.Builder(context)
            .data(this)
            .size(width, height)
            .build()
    }
}

/**
 * The resolution a hero backdrop is worth decoding at, from the display it will be drawn on.
 *
 * Every hero path passes [NuvioDesktopImageScaling.Disabled], which was meant to say "do not run the
 * custom resampling painter on this". It also had the side effect of sending no size at all, and
 * `AsyncImagePainter` answers a request with no size resolver by applying `SizeResolver.ORIGINAL` -
 * so backdrops were decoded and cached at full source resolution. Measured over one live disk cache:
 * 13.1% of images exceed 1536 px and average 15.8 MB decoded, and 284 of them were 3840x2160 at
 * 31.6 MB each. Ten of those is an entire 327 MB memory cache.
 *
 * **Physical pixels, from [desktopDisplayMetrics], not `LocalWindowInfo.containerSize`.** That
 * distinction is the whole correctness argument here. On a 4K display at 200% scaling, AWT and the
 * window state both report 1920x1080 - a request sized from those would have the hero decoded at
 * 2304 px and then drawn across 3840 physical pixels, which is a 1.67x upsample and a visibly soft
 * backdrop. [DesktopDisplayMetrics.sizePx] is explicitly `bounds * transform.scaleX`, so it is the
 * real framebuffer size, and it is snapshot-backed and re-evaluated when the window changes monitor.
 *
 * Capping at the *display* rather than the window is deliberately conservative: a small window on a
 * large display then saves nothing, but no configuration can ever end up asking for less than it
 * draws. Softness on the largest image in the app is a much worse outcome than a missed saving.
 *
 * Both axes are sent because `ContentScale.Crop` resolves to `Scale.FILL`, which takes the *larger*
 * of the two ratios - a square size lets the short axis decide and reduces nothing at all.
 *
 * [HeroScrollScaleHeadroom] covers the parallax zoom, which reaches `HERO_SCROLL_MAX_SCALE` (1.3x)
 * as the hero scrolls away. Deliberately less than the full 1.3: at rest, where the image is
 * actually looked at, this is at or above 1:1, and the remaining ~13% upsample only ever applies at
 * the extreme of a transient zoom on an element moving off screen.
 *
 * Quantised so that dragging a window across monitors does not re-request per intermediate DPI.
 *
 * On a display whose own resolution matches the source there is nothing to remove and this correctly
 * becomes a no-op: a 3840x2160 backdrop on a 3840x2160 display is already 1:1. The saving is real on
 * 1080p and 1440p displays, where a 4K backdrop is being decoded at two to four times the pixels
 * that can ever be shown.
 */
@Composable
private fun rememberHeroSourceSize(): IntSize? {
    val displaySizePx = desktopDisplayMetrics()?.sizePx
    return remember(displaySizePx) {
        if (displaySizePx == null || displaySizePx.width <= 0 || displaySizePx.height <= 0) {
            return@remember null
        }
        IntSize(
            width = quantizeHeroSourceAxis(displaySizePx.width),
            height = quantizeHeroSourceAxis(displaySizePx.height),
        )
    }
}

internal fun quantizeHeroSourceAxis(pixels: Int): Int {
    val withHeadroom = pixels * HeroScrollScaleHeadroom
    val quantised = ceil(withHeadroom / HeroSourceSizeQuantumPx) * HeroSourceSizeQuantumPx
    return quantised.toInt().coerceIn(MinHeroSourceSizePx, MaxHeroSourceSizePx)
}

@Composable
internal actual fun rememberNuvioDownscaledPainter(bitmap: ImageBitmap): Painter =
    remember(bitmap) { ScaledBitmapPainter(bitmap) }

private fun Image.toScaledBitmapPainter(
    desktopImageScaling: NuvioDesktopImageScaling,
    sourceKey: String? = null,
): Painter? {
    if (desktopImageScaling == NuvioDesktopImageScaling.Disabled) return null

    return (this as? BitmapImage)
        ?.bitmap
        ?.asComposeImageBitmap()
        ?.let { imageBitmap -> ScaledBitmapPainter(imageBitmap, sourceKey) }
}

/**
 * Reductions already performed, shared across painters and keyed by source identity plus target size.
 *
 * [ScaledBitmapPainter] caches its result in the painter instance, which is exactly as long-lived as
 * the card that owns it. A lazy row disposes a card the moment it leaves the viewport and builds a
 * fresh one when it comes back, so scrolling away and back threw the reduction away and paid for it
 * again - measured at 2.0-5.0 ms per card, on the draw thread, inside a 16.7 ms frame budget.
 *
 * Coil's memory cache returns the same *source* bitmap instantly in that situation; this is the
 * matching cache for the work done to it afterwards.
 *
 * Fixed size rather than a fraction of RAM, unlike the decoded-animation and Coil caches: an entry
 * here is a card-sized bitmap (a 210x315 poster is 265 KB, a large landscape card 518 KB), so useful
 * capacity is a number of cards on screen, which does not grow with the machine. 64 MB is several
 * screenfuls at every preset.
 */
internal object ScaledBitmapCache {
    internal const val MaxBytes = 64L * 1024 * 1024

    private val map = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {}
    private var currentBytes = 0L

    fun key(sourceKey: String, size: IntSize): String = "$sourceKey|${size.width}x${size.height}"

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        val bytes = bitmap.width.toLong() * bitmap.height.toLong() * 4
        map.put(key, bitmap)?.let { currentBytes -= it.width.toLong() * it.height.toLong() * 4 }
        currentBytes += bytes
        // accessOrder = true, so iteration starts at the least recently used.
        val iterator = map.entries.iterator()
        while (currentBytes > MaxBytes && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentBytes -= eldest.value.width.toLong() * eldest.value.height.toLong() * 4
        }
    }

    /** Evicting only drops this reference; a painter still drawing an entry keeps it alive. */
    @Synchronized
    fun clear() {
        map.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun debugState(): Pair<Int, Long> = map.size to currentBytes
}

private class ScaledBitmapPainter(
    private val image: ImageBitmap,
    /** Null when the request carried no cache key; the painter then caches only in itself. */
    private val sourceKey: String? = null,
) : Painter() {
    private var cachedSize: IntSize? = null
    private var cachedBitmap: ImageBitmap? = null
    private var alpha: Float = DefaultAlpha
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size =
        Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        val drawSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
        )
        if (!shouldUseScaledBitmap(drawSize)) {
            drawSource(drawSize)
            return
        }

        val bitmap = cachedBitmapFor(drawSize)
        val bitmapSize = cachedSize ?: drawSize

        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = bitmapSize,
            dstOffset = IntOffset.Zero,
            dstSize = drawSize,
            alpha = alpha,
            colorFilter = colorFilter,
            // Low (linear), not None (nearest), even for the exact-size blit. The bitmap matches
            // the *layout* size, but an ancestor graphicsLayer scale — the 1.04x hover/focus
            // enlarge in NuvioShelfItemSlot — replays this recorded draw through a magnifying
            // matrix, and setting the layer's scale does not re-record the child, so the painter
            // cannot see it coming. Nearest sampling under that matrix duplicates every ~25th
            // pixel row, which is the crunchy poster edges reported on hover. At rest the CTM is
            // identity and sample centres land on texel centres, so linear returns the exact
            // texels: unchanged output, just no longer pinned to it.
            filterQuality = if (bitmapSize == drawSize) FilterQuality.Low else FilterQuality.Medium,
        )
    }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    /**
     * The source resampled to exactly [drawSize], so the draw itself is a 1:1 blit.
     *
     * The previous implementation quantized the cache key up to a 2-16px grid, which no poster
     * preset ever landed on — 210x315 became 212x316, 126x189 became 128x192 — so every image in
     * the app was resampled a second time on the way to the screen and the exact-match fast path
     * below was unreachable. Caching on the exact size costs a rescale only when the size actually
     * changes, and [CachedBitmapReuseTolerance] absorbs the animating case the grid was there for.
     */
    private fun cachedBitmapFor(drawSize: IntSize): ImageBitmap {
        // Instance first: a settled card asks for the same size every frame, and this also absorbs
        // an in-flight resize animation via the tolerance band without touching the shared map.
        cachedBitmap?.let { bitmap ->
            val cached = cachedSize
            if (cached == drawSize) return bitmap
            if (cached != null && cached.isWithinReuseToleranceOf(drawSize)) return bitmap
        }
        val sharedKey = sourceKey?.let { ScaledBitmapCache.key(it, drawSize) }
        sharedKey?.let(ScaledBitmapCache::get)?.let { shared ->
            cachedSize = drawSize
            cachedBitmap = shared
            return shared
        }
        return image.downscaleTo(drawSize).also { bitmap ->
            cachedSize = drawSize
            cachedBitmap = bitmap
            sharedKey?.let { ScaledBitmapCache.put(it, bitmap) }
        }
    }

    private fun IntSize.isWithinReuseToleranceOf(other: IntSize): Boolean {
        val widthDrift = (width - other.width).toFloat() / other.width.toFloat()
        val heightDrift = (height - other.height).toFloat() / other.height.toFloat()
        return abs(widthDrift) <= CachedBitmapReuseTolerance &&
            abs(heightDrift) <= CachedBitmapReuseTolerance
    }

    private fun DrawScope.drawSource(drawSize: IntSize) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = drawSize,
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.High,
        )
    }

    private fun shouldUseScaledBitmap(drawSize: IntSize): Boolean {
        if (drawSize.pixelCount() > MaxScaledBitmapPixels) return false

        val widthScale = image.width.toFloat() / drawSize.width.toFloat()
        val heightScale = image.height.toFloat() / drawSize.height.toFloat()
        return max(widthScale, heightScale) >= MinCustomDownscaleRatio
    }

    private fun IntSize.pixelCount(): Long =
        width.toLong() * height.toLong()

    /**
     * Box-halves the source until the remaining reduction is under 2x, then does one cubic pass.
     *
     * Doing the whole reduction in a single cubic pass is what made large minifications alias: the
     * kernel's footprint does not grow with the ratio, so reducing a 1536px poster straight to a
     * 210px card sampled a small fraction of the source. Each halving step is an exact box average
     * and throws nothing away, so the cubic only ever sees a well-conditioned final step.
     */
    private fun ImageBitmap.downscaleTo(target: IntSize): ImageBitmap {
        var intermediate: Bitmap? = null
        try {
            while (true) {
                val source = intermediate ?: asSkiaBitmap()
                val halfWidth = source.width / 2
                val halfHeight = source.height / 2
                // Stop before either axis would undershoot; the cubic handles the remainder.
                if (halfWidth < target.width || halfHeight < target.height) break
                val halved = source.resampleTo(halfWidth, halfHeight, BoxHalvingSampling)
                intermediate?.close()
                intermediate = halved
            }

            val source = intermediate ?: asSkiaBitmap()
            if (source.width == target.width && source.height == target.height) {
                // Halving landed exactly on the target; a further cubic pass would only soften it.
                // Ownership of the intermediate transfers to the returned ImageBitmap.
                intermediate = null
                return source.asComposeImageBitmap()
            }
            return source
                .resampleTo(target.width, target.height, HighQualityDesktopResampler)
                .asComposeImageBitmap()
        } finally {
            intermediate?.close()
        }
    }

    private fun Bitmap.resampleTo(width: Int, height: Int, sampling: SamplingMode): Bitmap {
        val image = SkiaImage.makeFromBitmap(this)
        return try {
            val scaled = Bitmap()
            scaled.allocN32Pixels(width, height)
            image.scalePixels(scaled.peekPixels()!!, sampling, false)
            scaled
        } finally {
            image.close()
        }
    }
}
