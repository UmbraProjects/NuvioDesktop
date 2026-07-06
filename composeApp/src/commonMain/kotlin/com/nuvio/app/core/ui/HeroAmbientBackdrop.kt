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
    Crossfade(
        targetState = backdrop,
        animationSpec = tween(durationMillis = 650),
        label = label,
        modifier = modifier.fillMaxSize(),
    ) { activeBackdrop ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!activeBackdrop.isNullOrBlank()) {
                CheapBlurredBackdropImage(
                    imageUrl = activeBackdrop,
                    colorFilter = ambientBackdropColorFilter,
                    onSuccess = { state ->
                        if (backdrop == activeBackdrop) {
                            onAccentChanged(extractHeroAccentColor(state.result.image))
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to ambientAccent.copy(alpha = 0.46f),
                                0.42f to ambientAccent.copy(alpha = 0.32f),
                                1f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.38f)),
            )
        }
    }
}

@Composable
private fun CheapBlurredBackdropImage(
    imageUrl: String,
    colorFilter: ColorFilter,
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
                .blur((AmbientBlurRadiusDp / AmbientBlurDownsampleFactor).dp),
        ) {
            NuvioAsyncImage(
                model = imageUrl,
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
