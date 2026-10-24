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
import coil3.BitmapImage
import coil3.Image
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.decode.BlackholeDecoder
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.size.Precision
import coil3.size.Size as CoilSize
import coil3.size.SizeResolver
import coil3.request.ImageRequest
import coil3.request.NullRequestDataException
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Plain holder, not snapshot state: writing it must not invalidate the composition reading it. */
private class ArtworkSizeBaseline {
    var size: CoilSize? = null
    /** The size the request was last re-keyed to, held while the slot stays inside its bucket. */
    var key: CoilSize? = null
}

// Below this ratio the draw path just blits, above it [ScaledBitmapPainter] resamples on the draw
// thread. Requests are sized exactly from the destination, so normal artwork arrives at 1:1 and the
// painter never resamples it — the reduction happens in [HighQualityBitmapDecoder] instead. What
// still reaches it: sources a fetcher sized itself.
private const val MinCustomDownscaleRatio = 1.20f
private const val MaxScaledBitmapPixels = 1_250_000L


// How far the requested draw size may drift from the cached bitmap before it is worth paying for a
// fresh resample. A card that has settled asks for the same size every frame and hits the cache
// exactly; only an in-flight animation lands inside the tolerance band, and there a one-frame
// mipmapped rescale is much cheaper than a full CPU resample per frame.
private const val CachedBitmapReuseTolerance = 0.06f

// Halving with a linear filter is an exact 2x2 box average, so repeated halving is a correct (and
// cheap) way to bring a large source near the target before the Lanczos pass in [reduceHighQuality].
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
    val artworkSizeResolver = remember { DesktopArtworkSizeResolver() }
    // The resolver answers the request from layout directly, so the first bucket needs no new
    // request - keying the model on it would cancel the load layout has just enabled and start the
    // identical one again (measured: two starts, one cancel, per card). Only a *later* bucket
    // change - a window resize or a monitor DPI change - is worth a fresh request, so the first
    // answer is absorbed as the baseline and only drift from it re-keys the model below.
    val artworkSizeBaseline = remember(artworkSizeResolver) { ArtworkSizeBaseline() }
    val artworkSize = artworkSizeResolver.requestSize
    if (artworkSizeBaseline.size == null) artworkSizeBaseline.size = artworkSize
    // Compared by bucket: the size itself is exact now, and a one-pixel drift is not worth a reload.
    val artworkSizeKey = when {
        artworkSize == null || artworkSize.sameArtworkBucketAs(artworkSizeBaseline.size) -> null
        artworkSize.sameArtworkBucketAs(artworkSizeBaseline.key) -> artworkSizeBaseline.key
        else -> artworkSize.also { artworkSizeBaseline.key = it }
    }
    val requestModel = remember(context, model, effectiveDesktopImageScaling, artworkSizeKey) {
        when {
            effectiveDesktopImageScaling != NuvioDesktopImageScaling.Disabled ->
                model.withDesktopArtworkSize(
                    context,
                    artworkSizeKey?.let { SizeResolver(it) } ?: artworkSizeResolver,
                )
            // Scaling "disabled" only ever meant "do not run the custom painter on this". It also
            // silently meant "send no size", which left the hero decoding backdrops at whatever the
            // source happened to be. It now takes its size from LAYOUT like every other surface -
            // see [rememberHeroSourceSize] for why that used to come from the monitor instead.
            else ->
                model.withDesktopArtworkSize(
                    context,
                    artworkSizeKey?.let { SizeResolver(it) } ?: artworkSizeResolver,
                    // The hero draws no custom painter, so nothing downstream needs a per-size cache
                    // key; and the backdrop is one image on screen at a time, not a grid of cards.
                    tagForCacheKey = false,
                )
        }
    }
    // The last painter this surface successfully drew, kept so a reload does not blank it.
    //
    // Keyed on [model], NOT on the request: the request also carries the resolved artwork size, so
    // a size-bucket change re-keys it and restarts the load. Measured from a screen recording of TV
    // Mode, that blanked already-loaded cards to their grey `surface` colour for ~250 ms at a time —
    // brightness 144 to 30 and back, with the card showing the identical picture either side. An
    // animated card hits this on every recomposition, because animated images are deliberately
    // excluded from Coil's memory cache.
    //
    // Keying on [model] is what makes it safe in a recycling row: a card reused for a different
    // folder gets a different model, so the holder resets and cannot show the previous item's
    // artwork. Only a reload of *the same picture* keeps drawing it.
    val retained = remember(model) { RetainedArtwork() }
    val transform: (AsyncImagePainter.State) -> AsyncImagePainter.State = remember(
        placeholder,
        error,
        fallback,
        effectiveDesktopImageScaling,
        retained,
    ) {
        { state ->
            when (state) {
                is AsyncImagePainter.State.Loading -> {
                    retained.painter?.let { state.copy(painter = it) }
                        ?: placeholder?.let { state.copy(painter = it) }
                        ?: state
                }
                is AsyncImagePainter.State.Success -> {
                    val image = state.result.image
                    DesktopArtworkTelemetry.recordLoad(
                        sourceUrl = state.result.memoryCacheKey?.key ?: state.result.diskCacheKey,
                        fromMemoryCache = state.result.dataSource == DataSource.MEMORY_CACHE,
                        widthPx = image.width,
                        heightPx = image.height,
                        dataSource = state.result.dataSource.name,
                    )
                    if (image is SkiaAnimatedImage) {
                        state.copy(painter = SkiaAnimatedPainter(image)).also {
                            retained.painter = it.painter
                        }
                    } else {
                        image.toScaledBitmapPainter(
                            desktopImageScaling = effectiveDesktopImageScaling,
                            // Identity of the *source*, so a card scrolled away and back reuses the
                            // reduction the previous painter already paid for.
                            sourceKey = state.result.memoryCacheKey?.toString() ?: state.result.diskCacheKey,
                        )
                            ?.let { state.copy(painter = it) }
                            ?.also { retained.painter = it.painter }
                            ?: state.also { retained.painter = it.painter }
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
        // Attached for every path now: the hero sizes from layout as well.
        modifier = modifier.then(artworkSizeResolver),
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

internal actual fun ImageRequest.Builder.nuvioArtworkRequestSize(
    widthPx: Int?,
    heightPx: Int?,
): ImageRequest.Builder = apply {
    if (widthPx != null && heightPx != null) {
        size(desktopArtworkDimension(widthPx), desktopArtworkDimension(heightPx))
        precision(Precision.INEXACT)
        desktopArtworkCacheSize()
    } else {
        // No layout yet: warm compressed disk data without decoding an oversized placeholder.
        size(1)
        memoryCachePolicy(CachePolicy.DISABLED)
        decoderFactory(BlackholeDecoder.Factory())
    }
}

private fun Any?.withDesktopArtworkSize(
    context: PlatformContext,
    resolver: SizeResolver,
    tagForCacheKey: Boolean = true,
): Any? {
    if (this == null) return null
    return (if (this is ImageRequest) newBuilder() else ImageRequest.Builder(context).data(this))
        .size(resolver)
        .precision(Precision.INEXACT)
        .apply { if (tagForCacheKey) desktopArtworkCacheSize() }
        .build()
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
 * **Mostly idle now.** This existed to make [ScaledBitmapPainter] cheap to re-enter, and that painter
 * is bypassed for artwork sized from its destination — see [MinCustomDownscaleRatio]. It is kept for
 * the surfaces that still reach the painter, and the numbers below describe that original workload.
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
    private var maxBytes = MaxBytes

    @Synchronized
    fun setBackgroundMode(restricted: Boolean) {
        maxBytes = if (restricted) PlaybackScaledCacheBytes else MaxBytes
        evictToBudget()
    }

    fun key(sourceKey: String, size: IntSize): String = "$sourceKey|${size.width}x${size.height}"

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        val bytes = bitmap.width.toLong() * bitmap.height.toLong() * 4
        map.put(key, bitmap)?.let { currentBytes -= it.width.toLong() * it.height.toLong() * 4 }
        currentBytes += bytes
        evictToBudget()
    }

    private fun evictToBudget() {
        // accessOrder = true, so iteration starts at the least recently used.
        val iterator = map.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
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
        // Sampled here rather than inside the branch below: the interesting number is how far the
        // decoded bitmap is from the draw size across *all* artwork, and the common case now is
        // precisely the one that skips the resample.
        DesktopArtworkTelemetry.recordDraw(
            sourceKey = sourceKey,
            suppliedWidth = image.width,
            suppliedHeight = image.height,
            drawWidth = drawSize.width,
            drawHeight = drawSize.height,
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
        val exact = image.width == drawSize.width && image.height == drawSize.height
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = drawSize,
            alpha = alpha,
            colorFilter = colorFilter,
            // An exact-size decode must be blitted, not re-filtered: High is a Mitchell cubic, whose
            // centre tap is only 0.89 even at 1:1, so it softened every correctly sized card. Low
            // rather than None for the ancestor-scale reason on the draw above.
            filterQuality = if (exact) FilterQuality.Low else FilterQuality.High,
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

    /** Same reduction the decoder performs; see [reduceHighQuality]. */
    private fun ImageBitmap.downscaleTo(target: IntSize): ImageBitmap {
        val source = SkiaImage.makeFromBitmap(asSkiaBitmap())
        return try {
            source.reduceHighQuality(target.width, target.height).asComposeImageBitmap()
        } finally {
            source.close()
        }
    }
}

/**
 * Holds the painter a surface last drew successfully, so a reload of the same picture is invisible.
 *
 * A plain holder rather than snapshot state on purpose: it is written from inside the state
 * transform, and a snapshot write there would invalidate the composition that is already being
 * updated by the load itself.
 */
private class RetainedArtwork {
    var painter: Painter? = null
}
