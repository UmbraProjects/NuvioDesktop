package com.nuvio.app.features.preroll

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** The resolved colours of a [TriviaTheme]: always usable, whatever the model sent. */
internal data class TriviaPalette(
    val primary: Color,
    val secondary: Color,
    /** Primary lifted towards white so small text in it stays legible on a dark scrim. */
    val accent: Color,
    val atmosphere: TriviaAtmosphere,
)

internal fun TriviaTheme?.toPalette(): TriviaPalette {
    val base = this ?: fallbackTheme(emptyList())
    val primary = Color(hexToArgb(base.primary) ?: 0xFFD4A94AL)
    val secondary = Color(hexToArgb(base.secondary) ?: 0xFF3C5A8AL)
    return TriviaPalette(
        primary = primary,
        secondary = secondary,
        accent = lerp(primary, Color.White, 0.3f),
        atmosphere = base.atmosphere,
    )
}

/**
 * Everything behind the pre-show's text: fanart with a slow push-in, graded towards the film's
 * palette, with drifting atmosphere on top and a scrim that keeps text readable.
 *
 * [imageIndex] picks which backdrop shows (wrapping), so each slide gets a fresh picture and the
 * pre-show works its way through the title's extra fanart. With no images at all the same palette
 * and atmosphere draw over a graded dark field, which is still a long way from flat grey.
 */
@Composable
internal fun TriviaBackdrop(
    backdrops: List<String>,
    imageIndex: Int,
    palette: TriviaPalette,
    modifier: Modifier = Modifier,
) {
    val base = remember(palette) {
        Brush.verticalGradient(
            listOf(
                lerp(Color.Black, palette.primary, 0.28f),
                lerp(Color.Black, palette.secondary, 0.22f),
                Color(0xFF05060A),
            ),
        )
    }
    Box(modifier = modifier.background(base).clipToBounds()) {
        if (backdrops.isNotEmpty()) {
            val url = backdrops[((imageIndex % backdrops.size) + backdrops.size) % backdrops.size]
            Crossfade(targetState = url, animationSpec = tween(900), label = "triviaBackdropImage") { shown ->
                val zoom = remember(shown) { Animatable(1.02f) }
                LaunchedEffect(shown) { zoom.animateTo(1.13f, tween(16_000, easing = LinearEasing)) }
                NuvioAsyncImage(
                    model = shown,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                    },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // Colour grade: the film's own colours washed over the photograph.
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(
                        palette.primary.copy(alpha = 0.34f),
                        Color.Transparent,
                        palette.secondary.copy(alpha = 0.26f),
                    ),
                ),
            ),
        )
        TriviaAtmosphereLayer(palette = palette, modifier = Modifier.fillMaxSize())
        // Legibility: dark where the text sits (bottom and left), open where the picture breathes.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.9f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.6f),
                    0.65f to Color.Transparent,
                ),
            ),
        )
    }
}

private class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val phase: Float,
    val useSecondary: Boolean,
)

private fun particleCount(a: TriviaAtmosphere): Int = when (a) {
    TriviaAtmosphere.Embers -> 70
    TriviaAtmosphere.Sparks -> 50
    TriviaAtmosphere.Snow -> 110
    TriviaAtmosphere.Rain -> 140
    TriviaAtmosphere.Dust -> 60
    TriviaAtmosphere.Stars -> 120
    TriviaAtmosphere.Fog -> 6
    TriviaAtmosphere.Neon -> 8
    TriviaAtmosphere.Bokeh -> 22
    TriviaAtmosphere.None -> 0
}

@Composable
private fun TriviaAtmosphereLayer(palette: TriviaPalette, modifier: Modifier = Modifier) {
    val atmosphere = palette.atmosphere
    if (atmosphere == TriviaAtmosphere.None) return
    val particles = remember(atmosphere) {
        val random = Random(atmosphere.ordinal * 7919)
        List(particleCount(atmosphere)) {
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                size = 0.3f + random.nextFloat() * 0.7f,
                speed = 0.4f + random.nextFloat() * 0.6f,
                phase = random.nextFloat() * (2 * PI.toFloat()),
                useSecondary = random.nextFloat() < 0.35f,
            )
        }
    }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(atmosphere) {
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                clock.longValue = now - start
            }
        }
    }
    Canvas(modifier) {
        val t = clock.longValue / 1_000_000_000f
        particles.forEach { drawParticle(atmosphere, it, t, palette) }
    }
}

private fun frac(v: Float): Float = v - floor(v)

private fun DrawScope.drawParticle(a: TriviaAtmosphere, p: Particle, t: Float, palette: TriviaPalette) {
    val w = size.width
    val h = size.height
    val tint = if (p.useSecondary) palette.secondary else palette.primary
    val unit = size.minDimension / 1000f
    when (a) {
        TriviaAtmosphere.Embers, TriviaAtmosphere.Sparks -> {
            val fast = if (a == TriviaAtmosphere.Sparks) 0.09f else 0.04f
            val rise = frac(p.y - t * fast * p.speed)
            val sway = sin(t * 0.9f * p.speed + p.phase) * 0.025f
            val fade = sin(rise * PI.toFloat()).coerceIn(0f, 1f)
            val color = lerp(tint, Color(0xFFFFE2A8), 0.45f)
            val r = (3f + 6f * p.size) * unit * 2f
            val c = Offset((p.x + sway) * w, rise * h)
            drawCircle(color.copy(alpha = 0.18f * fade), radius = r * 3f, center = c)
            drawCircle(color.copy(alpha = 0.85f * fade), radius = r * 0.55f, center = c)
        }

        TriviaAtmosphere.Snow -> {
            val fall = frac(p.y + t * 0.035f * p.speed)
            val sway = sin(t * 0.6f * p.speed + p.phase) * 0.03f
            drawCircle(
                Color.White.copy(alpha = 0.3f + 0.5f * p.size),
                radius = (1.5f + 4.5f * p.size) * unit * 2f,
                center = Offset((p.x + sway) * w, fall * h),
            )
        }

        TriviaAtmosphere.Rain -> {
            val fall = frac(p.y + t * 0.9f * p.speed)
            val x = frac(p.x - fall * 0.08f) * w
            val len = (18f + 40f * p.size) * unit * 2f
            val from = Offset(x, fall * h)
            drawLine(
                Color.White.copy(alpha = 0.12f + 0.22f * p.size),
                start = from,
                end = Offset(from.x - len * 0.18f, from.y - len),
                strokeWidth = 1.5f * unit * 2f,
            )
        }

        TriviaAtmosphere.Dust -> {
            val drift = frac(p.x + t * 0.012f * p.speed)
            val bob = sin(t * 0.4f * p.speed + p.phase) * 0.03f
            val twinkle = 0.5f + 0.5f * sin(t * 0.8f + p.phase)
            drawCircle(
                lerp(tint, Color.White, 0.6f).copy(alpha = 0.10f + 0.28f * twinkle * p.size),
                radius = (1.5f + 3.5f * p.size) * unit * 2f,
                center = Offset(drift * w, (p.y + bob) * h),
            )
        }

        TriviaAtmosphere.Stars -> {
            val twinkle = 0.5f + 0.5f * sin(t * (0.6f + p.speed) + p.phase)
            drawCircle(
                Color.White.copy(alpha = 0.15f + 0.7f * twinkle * p.size),
                radius = (1f + 2.6f * p.size) * unit * 2f,
                center = Offset(p.x * w, p.y * h),
            )
        }

        TriviaAtmosphere.Fog -> {
            val drift = frac(p.x + t * 0.008f * p.speed)
            val r = (0.35f + 0.4f * p.size) * w
            val c = Offset(drift * w * 1.4f - 0.2f * w, (0.45f + 0.5f * p.y) * h)
            drawCircle(
                Brush.radialGradient(
                    listOf(lerp(tint, Color.White, 0.25f).copy(alpha = 0.16f), Color.Transparent),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
        }

        TriviaAtmosphere.Neon -> {
            val pulse = 0.6f + 0.4f * sin(t * 0.7f * p.speed + p.phase)
            val c = Offset(frac(p.x + t * 0.01f * p.speed) * w, p.y * h)
            val r = (0.18f + 0.25f * p.size) * w
            drawCircle(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.30f * pulse), Color.Transparent),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
        }

        TriviaAtmosphere.Bokeh -> {
            val rise = frac(p.y - t * 0.015f * p.speed)
            val pulse = 0.7f + 0.3f * sin(t * 0.5f + p.phase)
            val r = (14f + 46f * p.size) * unit * 2f
            val c = Offset(p.x * w, rise * h)
            drawCircle(lerp(tint, Color.White, 0.35f).copy(alpha = 0.12f * pulse), radius = r, center = c)
            drawCircle(
                lerp(tint, Color.White, 0.5f).copy(alpha = 0.22f * pulse),
                radius = r,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * unit * 2f),
            )
        }

        TriviaAtmosphere.None -> Unit
    }
}
