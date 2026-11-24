package com.nuvio.app.core.ui

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import com.nuvio.app.features.home.extractHeroAccentColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Blurring the ambient backdrop at full window resolution (2K/4K displays) every time the
// active item changes — and, unless perfectly layer-cached, on every nearby recomposition
// (accent-color tween, Crossfade) — is the "extremely inefficient" cost here: a 72dp Gaussian
// blur's work scales with pixel count, so a 4K frame costs ~8x what a 1080p one does for a
// visual result the blur itself immediately destroys the detail of anyway. Instead we lay the
// source image out at 1/6th size, blur it there (radius scaled down to match), then let a
// graphicsLayer scale stretch the *already-blurred* result back up — ~36x fewer pixels touched
// by the blur pass with no visible difference, since nothing sharper than a few px survives a
// 72dp blur regardless of source resolution.
//
// THE DOWNSAMPLE IS NOT ENOUGH, measured 2026-09-07. Pricing each component in the running app
// (Ctrl+Shift+W, see AmbientWashVariant) against an Adaptive control at 2.51 ms:
//
//     full 6.66 ms | noBlur 3.34 | noCrossfade 4.10 | noColorFilter 5.51 | noTint 6.78
//
// so of a 4.15 ms wash the blur is 3.32 ms (80%) and the Crossfade 2.56 ms (62%) — they overlap,
// because the Crossfade produces the blurred layer TWICE while a transition runs, and in this mode
// the hero follows focus so a transition is live most of the time. The tint measures free. The
// blur is also where the stutter lives: removing it takes P95 from 21.0 ms to 7.9 ms.
//
// The "unless perfectly layer-cached" caveat above was therefore the whole problem: it was not
// cached, and the blur re-ran per frame per crossfade branch. FIXED by moving the blur into the
// image request (see rememberAmbientBackdropModel), where it runs once per URL on a decode thread.
// Re-priced the same way afterwards, against an identical 2.51 ms control:
//
//     wash 4.15 -> 1.52 ms (-63%) | blur 3.32 -> 0.26 | crossfade 2.56 -> 0.91
//     colorFilter 1.15 -> 0.61 | tint -0.12 -> 0.57 | P95 21.0 -> 11.5 ms
//
// Downsampling harder was the wrong instinct throughout: the cost was never the pixel count, it was
// that a fixed picture was being recomputed every frame.
private const val AmbientBlurRadiusDp = 72
private const val AmbientBlurDownsampleFactor = 6
private const val AmbientPreloadTimeoutMs = 1_500L
private const val AmbientWashMinFadeMs = 120
private const val AmbientWashMaxFadeMs = 650
private const val MaxAmbientWashLayers = 4

@Stable
private class AmbientWashLayer(
    val id: Long,
    val backdrop: String?,
    initialAlpha: Float = 0f,
    initialResolved: Boolean = backdrop == null,
    initialAccent: Color? = null,
) {
    val alpha = Animatable(initialAlpha)
    var resolved by mutableStateOf(initialResolved)
    var extractedAccent by mutableStateOf(initialAccent)
}

private class AmbientWashLayerIds {
    private var nextId = 1L
    fun next(): Long = nextId++
}

/**
 * Shared accent cache across all [HeroAmbientBackdrop] instances (Home main wash, Adaptive hero
 * underlay, Collections) so an already-decoded backdrop switches synchronously on the same frame
 * as the hero instead of bouncing through a hidden preload pass.
 */
private val HeroAmbientAccentCache = mutableStateMapOf<String, Color>()

/**
 * The wash backdrops of the cards either side of the focused one, nearest first, published by
 * Home's focus prefetch beside [com.nuvio.app.features.home.components.HeroBackdropPrefetcher].
 *
 * The hero's neighbours were already decoded ahead, but the wash draws its own pre-blurred copy
 * of a different URL, so it still had to decode and blur on every move and arrived several frames
 * after the hero (most visible in Adaptive, where the hero follows focus). A wash given
 * `prefetchNeighbours` decodes these invisibly, which also caches their accent, so a move onto one
 * takes the same-frame path below.
 */
internal object HeroAmbientWashPrefetch {
    val upcoming = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
}

/** Hidden wash decodes at a time; each is a full decode plus blur, competing with the hero's own. */
private const val AmbientWashPrefetchCount = 2

/**
 * Shared full-bleed ambient background: a crossfading, blurred, tinted wash of [backdrop] used
 * behind both Home's hero and a Collection's hero. [accent] drives the tint gradient; callers
 * are expected to derive it from the loaded image (see [extractHeroAccentColor]) and feed it
 * back through [onAccentChanged].
 */
@Composable
internal fun HeroAmbientBackdrop(
    backdrop: String?,
    accent: Color?,
    onAccentChanged: (Color?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "hero_ambient_background",
    /**
     * Keep the wash at its row-top strength to the bottom instead of dimming toward background.
     * On for every mode (Adaptive Ambient, TV ambient, Collections); false restores the dip.
     */
    holdBottom: Boolean = true,
    /**
     * Decode [HeroAmbientWashPrefetch.upcoming] invisibly. One wash per screen is enough: the
     * Adaptive underlay is laid out at the same size as the screen's wash, so it shares the
     * decoded bitmaps through Coil's memory cache and the accents through [HeroAmbientAccentCache].
     */
    prefetchNeighbours: Boolean = false,
) {
    val ambientBackdropColorFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(1.7f) },
        )
    }
    val currentOnAccentChanged by rememberUpdatedState(onAccentChanged)
    val normalizedBackdrop = backdrop?.takeIf { it.isNotBlank() }

    // The wash keeps time with the hero's own backdrop crossfade (Settings -> Home), image and tint
    // alike. A floor keeps a cut from flashing the whole background.
    val homeSettings by com.nuvio.app.features.home.HomeCatalogSettingsRepository.uiState
        .collectAsState()
    val washFadeMs = homeSettings.heroBackdropCrossfadeMillis.coerceIn(AmbientWashMinFadeMs, AmbientWashMaxFadeMs)
    val currentWashFadeMs by rememberUpdatedState(washFadeMs)
    // Always Full outside the diagnostic build; see AmbientWashProbe.
    val variant = AmbientWashProbe.effective()
    val currentVariant by rememberUpdatedState(variant)
    val background = MaterialTheme.colorScheme.background

    // Stacked per-layer fades (matching HeroBackdropCrossfade):
    // - Not Compose's `Crossfade`, whose shared Transition clock truncates interrupted fades (so
    //   A -> B -> C while B is still fading snaps instead of smoothly fading) and dips brightness
    //   mid-fade when both translucent layers sit over the background.
    // - Each incoming layer waits for its own CheapBlurredBackdropImage to resolve before animating
    //   alpha 0 -> 1 over the current opaque wash, so an evicted Coil bitmap or a second Adaptive
    //   underlay instance never fades in a blank frame and pops.
    // - Each layer carries its own extracted accent, so the tint and blurred backdrop fade in
    //   together with zero per-frame animateColorAsState recompositions.
    val nextLayerId = remember { AmbientWashLayerIds() }
    val layers = remember {
        mutableStateListOf(
            AmbientWashLayer(
                id = 0L,
                backdrop = normalizedBackdrop,
                initialAlpha = 1f,
                initialResolved = normalizedBackdrop == null,
                initialAccent = normalizedBackdrop?.let { HeroAmbientAccentCache[it] },
            ),
        )
    }

    LaunchedEffect(normalizedBackdrop) {
        val top = layers.lastOrNull()
        when {
            top == null -> layers.add(
                AmbientWashLayer(
                    id = nextLayerId.next(),
                    backdrop = normalizedBackdrop,
                    initialAlpha = 1f,
                    initialResolved = normalizedBackdrop == null,
                    initialAccent = normalizedBackdrop?.let { HeroAmbientAccentCache[it] },
                ),
            )
            top.backdrop != normalizedBackdrop -> layers.add(
                AmbientWashLayer(
                    id = nextLayerId.next(),
                    backdrop = normalizedBackdrop,
                    initialAlpha = 0f,
                    initialResolved = normalizedBackdrop == null,
                    initialAccent = normalizedBackdrop?.let { HeroAmbientAccentCache[it] },
                ),
            )
            else -> return@LaunchedEffect
        }
        // Drop intermediate backdrops the user already moved past before their blurred bitmap arrived.
        for (index in layers.lastIndex - 1 downTo 1) {
            if (!layers[index].resolved) layers.removeAt(index)
        }
        while (layers.size > MaxAmbientWashLayers) {
            layers[1].alpha.snapTo(1f)
            layers.removeAt(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (prefetchNeighbours) {
            val activeBackdrops = layers.mapNotNullTo(HashSet()) { it.backdrop }
            val upcoming by HeroAmbientWashPrefetch.upcoming.collectAsState()
            upcoming.asSequence()
                .filter { it.isNotBlank() && it !in activeBackdrops }
                .filterNot { HeroAmbientAccentCache.containsKey(it) }
                .take(AmbientWashPrefetchCount)
                .forEach { url ->
                    key(url) {
                        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0f }) {
                            CheapBlurredBackdropImage(
                                imageUrl = url,
                                colorFilter = if (variant == AmbientWashVariant.NoColorFilter) null else ambientBackdropColorFilter,
                                blurred = variant != AmbientWashVariant.NoBlur,
                                onSuccess = { state ->
                                    if (HeroAmbientAccentCache.size > 128) HeroAmbientAccentCache.clear()
                                    HeroAmbientAccentCache[url] =
                                        extractHeroAccentColor(state.result.image) ?: Color.Unspecified
                                },
                            )
                        }
                    }
                }
        }

        layers.forEach { layer ->
            key(layer.id) {
                LaunchedEffect(layer.id) {
                    if (layer.alpha.value < 1f) {
                        if (!layer.resolved) {
                            withTimeoutOrNull(AmbientPreloadTimeoutMs) {
                                snapshotFlow { layer.resolved }.first { it }
                            }
                        }
                        // Only the newest layer speaks for the accent: one overtaken while it waited would set the colour of
                        // a backdrop that is already on its way out.
                        val isTop = layers.lastOrNull()?.id == layer.id
                        if (isTop && layer.backdrop == null) {
                            currentOnAccentChanged(null)
                        } else if (isTop && layer.resolved) {
                            currentOnAccentChanged(layer.extractedAccent?.takeIf { it != Color.Unspecified })
                        }
                        if (currentVariant == AmbientWashVariant.NoCrossfade) {
                            layer.alpha.snapTo(1f)
                        } else {
                            UiMotion.markBusy(currentWashFadeMs.toLong())
                            layer.alpha.animateTo(1f, tween(durationMillis = currentWashFadeMs))
                        }
                    } else if (layer.backdrop != null) {
                        if (!layer.resolved) {
                            snapshotFlow { layer.resolved }.first { it }
                        }
                        // The first layer waits without a timeout: a newer backdrop may have resolved and set its accent in
                        // the meantime, which this older one must not overwrite as it fades out.
                        if (layers.lastOrNull()?.id == layer.id) {
                            currentOnAccentChanged(layer.extractedAccent?.takeIf { it != Color.Unspecified })
                        }
                    }
                    // Fully opaque on top: drop every wash layer covered beneath this one.
                    while (layers.isNotEmpty() && layers.first().id != layer.id) {
                        layers.removeAt(0)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = layer.alpha.value }
                        .background(background),
                ) {
                    if (layer.backdrop != null) {
                        CheapBlurredBackdropImage(
                            imageUrl = layer.backdrop,
                            colorFilter = if (variant == AmbientWashVariant.NoColorFilter) {
                                null
                            } else {
                                ambientBackdropColorFilter
                            },
                            blurred = variant != AmbientWashVariant.NoBlur,
                            onSuccess = { state ->
                                val extracted = extractHeroAccentColor(state.result.image) ?: Color.Unspecified
                                if (HeroAmbientAccentCache.size > 128) HeroAmbientAccentCache.clear()
                                HeroAmbientAccentCache[layer.backdrop] = extracted
                                layer.extractedAccent = extracted
                                layer.resolved = true
                            },
                            onError = {
                                layer.resolved = true
                            },
                        )
                    }
                    if (variant != AmbientWashVariant.NoTint) {
                        val layerAccent = when (val extracted = layer.extractedAccent) {
                            null -> accent ?: background
                            Color.Unspecified -> background
                            else -> extracted
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = ambientWashStops(
                                            accent = layerAccent,
                                            background = background,
                                            holdBottom = holdBottom,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
        // Lights out: while a hero trailer is on screen the wash goes the way the details page's
        // surround does (Settings > Details > trailer background). TV Mode's ambient wash otherwise
        // stays lit around the video, a coloured cloud around a black-edged picture.
        val trailerOnScreen by com.nuvio.app.features.home.components.HomeHeroTrailerManualTrigger.onScreen
            .collectAsState()
        val metaSettings by com.nuvio.app.features.details.MetaScreenSettingsRepository.uiState.collectAsState()
        val lightsOutColor = when (metaSettings.heroTrailerBackgroundMode) {
            com.nuvio.app.features.details.MetaHeroTrailerBackgroundMode.Black -> Color.Black
            com.nuvio.app.features.details.MetaHeroTrailerBackgroundMode.Theme -> background
            com.nuvio.app.features.details.MetaHeroTrailerBackgroundMode.Backdrop -> null
        }
        // In at once, ahead of the trailer: HomeHeroTrailerSurface reports ready, waits for this black to be on
        // screen, then reveals the video, so a fade in here only showed as the page darkening after it (2026-11-24).
        // Out slower, as the artwork comes back when the trailer ends.
        val lightsOutTarget = trailerOnScreen && lightsOutColor != null
        val lightsOut by animateFloatAsState(
            targetValue = if (lightsOutTarget) 1f else 0f,
            animationSpec = if (lightsOutTarget) snap() else tween(durationMillis = AmbientLightsOutOutMs),
            label = "${label}_lights_out",
        )
        if (lightsOut > 0f && lightsOutColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = lightsOut }
                    .background(lightsOutColor),
            )
        }
    }
}

private const val AmbientLightsOutOutMs = 400

/**
 * The accent gradient and the flat dim above it, folded into a single gradient.
 *
 * These were two stacked full-screen `Box`es: a three-stop accent gradient, then a constant
 * `background * 0.38` over the whole window. Each is a full-screen alpha blend, and on a 4K display
 * that is 8.3 million blended pixels per layer per frame. Measurement (2026-09-07) put Adaptive
 * Ambient at 5.71 ms of frame CPU against 2.74 ms for Adaptive — the two modes differ only by this
 * wash — so roughly 3 ms a frame is being spent here, more than a third of a 120 Hz budget.
 *
 * Folding is exact at each stop. Compositing colour `Ca` at alpha `a`, then `Cb` at alpha `b`, over
 * the same base gives
 *
 * ```
 * base*(1-a)*(1-b) + Ca*a*(1-b) + Cb*b
 * ```
 *
 * which one layer reproduces with `t = 1 - (1-a)(1-b)` and `Ct = (Ca*a*(1-b) + Cb*b) / t`. Between
 * stops the two differ slightly, because the original interpolates before compositing and this
 * interpolates after; `HeroAmbientWashRenderTest` measures that difference rather than assuming it
 * is small.
 */
internal fun ambientWashStops(
    accent: Color,
    background: Color,
    holdBottom: Boolean = false,
): Array<Pair<Float, Color>> {
    val dim = 0.38f
    fun fold(layerColor: Color, layerAlpha: Float): Color {
        val kept = layerAlpha * (1f - dim)
        val alpha = 1f - (1f - layerAlpha) * (1f - dim)
        // Weight of the accent against the background in the merged colour. Both contributions are
        // divided by the merged alpha, which is what un-premultiplies the result.
        val accentWeight = kept / alpha
        // Mixed component-wise on the encoded sRGB values, which is what alpha compositing itself
        // operates on. `Color.lerp` cannot be used here: it interpolates through Oklab, and doing
        // so put the folded colour 6/255 off the two-layer original at a stop where the algebra is
        // exact — measured, not assumed.
        return Color(
            red = background.red + (layerColor.red - background.red) * accentWeight,
            green = background.green + (layerColor.green - background.green) * accentWeight,
            blue = background.blue + (layerColor.blue - background.blue) * accentWeight,
            alpha = alpha,
        )
    }
    // The accent holds most of the way down and the closing dim is light, so the wash carries
    // well into the rows rather than being spent just under the hero.
    return arrayOf(
        0f to fold(accent, 0.46f),
        0.68f to fold(accent, 0.34f),
        1f to if (holdBottom) fold(accent, 0.34f) else fold(background, 0.55f),
    )
}

@Composable
private fun CheapBlurredBackdropImage(
    imageUrl: String,
    colorFilter: ColorFilter?,
    blurred: Boolean,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit,
    onError: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val downsampledWidth = maxWidth / AmbientBlurDownsampleFactor
        val downsampledHeight = maxHeight / AmbientBlurDownsampleFactor
        Box(
            modifier = Modifier
                .size(downsampledWidth, downsampledHeight)
                .graphicsLayer {
                    scaleX = AmbientBlurDownsampleFactor.toFloat()
                    scaleY = AmbientBlurDownsampleFactor.toFloat()
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                // Applied inside the scale layer, so it blurs the small source bitmap — the
                // outer graphicsLayer then stretches the already-blurred result, it isn't
                // re-blurring at full size.
,
        ) {
            NuvioAsyncImage(
                // Pre-blurred by the image pipeline rather than by a per-frame render effect; the
                // request carries the radius, so a variant with the blur off is a different model.
                model = if (blurred) {
                    rememberAmbientBackdropModel(
                        imageUrl = imageUrl,
                        blurRadius = (AmbientBlurRadiusDp / AmbientBlurDownsampleFactor).dp,
                    )
                } else {
                    imageUrl
                },
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.58f
                        scaleX = 1.18f
                        scaleY = 1.18f
                    },
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                onSuccess = onSuccess,
                onError = { onError() },
            )
        }
    }
}
