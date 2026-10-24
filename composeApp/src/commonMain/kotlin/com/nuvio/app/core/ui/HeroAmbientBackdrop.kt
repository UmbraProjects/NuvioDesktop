package com.nuvio.app.core.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
) {
    val ambientBackdropColorFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(1.7f) },
        )
    }
    val ambientAccent by animateColorAsState(
        targetValue = accent ?: MaterialTheme.colorScheme.background,
        animationSpec = tween(durationMillis = 650),
        label = "${label}_accent",
    )
    // Always Full outside the diagnostic build; see AmbientWashProbe.
    val variant = AmbientWashProbe.effective()
    val background = MaterialTheme.colorScheme.background
    val washContent: @Composable (String?) -> Unit = { activeBackdrop ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!activeBackdrop.isNullOrBlank()) {
                CheapBlurredBackdropImage(
                    imageUrl = activeBackdrop,
                    colorFilter = if (variant == AmbientWashVariant.NoColorFilter) {
                        null
                    } else {
                        ambientBackdropColorFilter
                    },
                    blurred = variant != AmbientWashVariant.NoBlur,
                    onSuccess = { state ->
                        if (backdrop == activeBackdrop) {
                            onAccentChanged(extractHeroAccentColor(state.result.image))
                        }
                    },
                )
            }
            if (variant != AmbientWashVariant.NoTint) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = ambientWashStops(
                                    accent = ambientAccent,
                                    background = background,
                                ),
                            ),
                        ),
                )
            }
        }
    }
    // The tint stays INSIDE the Crossfade. It depends only on `ambientAccent`, never on which
    // backdrop is showing, so hoisting it out looks like an obvious saving — every hero change
    // draws it twice, once into each crossfade layer. Measured (2026-09-07), hoisting it cost
    // 0.39 ms a frame rather than saving anything: wash cost went 2.87 -> 3.26 ms against a stable
    // Adaptive control, giving back the whole gradient-fold win. Do not "fix" this again without a
    // measurement.
    if (variant == AmbientWashVariant.NoCrossfade) {
        Box(modifier = modifier.fillMaxSize()) { washContent(backdrop) }
    } else {
        Crossfade(
            targetState = backdrop,
            animationSpec = tween(durationMillis = 650),
            label = label,
            modifier = modifier.fillMaxSize(),
        ) { activeBackdrop -> washContent(activeBackdrop) }
    }
}

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
    return arrayOf(
        0f to fold(accent, 0.46f),
        0.42f to fold(accent, 0.32f),
        1f to fold(background, 0.72f),
    )
}

@Composable
private fun CheapBlurredBackdropImage(
    imageUrl: String,
    colorFilter: ColorFilter?,
    blurred: Boolean,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit,
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
            )
        }
    }
}
