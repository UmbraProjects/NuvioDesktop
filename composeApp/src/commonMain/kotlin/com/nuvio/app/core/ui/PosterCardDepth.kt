package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class NuvioCardDepthSurface {
    Posters,
    ContinueWatching,
    Episodes,
    Cast,
    Trailers,
}

private fun PosterCardStyleUiState.depthEnabledFor(surface: NuvioCardDepthSurface): Boolean = when (surface) {
    NuvioCardDepthSurface.Posters -> depthPosters
    NuvioCardDepthSurface.ContinueWatching -> depthContinueWatching
    NuvioCardDepthSurface.Episodes -> depthEpisodes
    NuvioCardDepthSurface.Cast -> depthCast
    NuvioCardDepthSurface.Trailers -> depthTrailers
}

private const val DepthEdgeFeatherAlphaScale = 0.28f

/**
 * Draws the subtle top-lit edge and sheen on cards.
 *
 * Apply **ahead of** `Modifier.clip(shape)` (like [nuvioPosterHighlight]) so Compose Desktop's
 * un-antialiased clip cannot slice the outer half-pixel off the rounded corners.
 *
 * Both layers are drawn as anti-aliased paths (no `Modifier.border` and no `clipPath`):
 * - The sheen fills the rounded outline directly with a clamped vertical gradient, so the corner
 *   arcs get full Skia anti-aliasing instead of a hard `clipPath` stair-step.
 * - The edge strokes the concentric outline with a soft 2 dp feather underneath the 1 dp core,
 *   preventing the 45-degree corner necking that makes 1 px strokes look jagged at 1080p.
 */
@Composable
fun Modifier.nuvioCardDepth(
    shape: Shape,
    surface: NuvioCardDepthSurface = NuvioCardDepthSurface.Posters,
): Modifier {
    val state = rememberPosterCardStyleUiState()
    if (!state.depthEnabled || !state.depthEnabledFor(surface)) return this
    val edge = state.depthEdgeStrength.coerceIn(0, 100) / 100f
    val sheen = state.depthSheenStrength.coerceIn(0, 100) / 100f
    val coverage = state.depthEdgeCoverage.coerceIn(0, 100) / 100f
    if (edge <= 0f && sheen <= 0f) return this

    return drawWithCache {
        val coreWidth = 1.dp.toPx()
        val strokeInset = coreWidth / 2f
        val outline = shape.createOutline(size, layoutDirection, this)
        val strokePath = outline.toInsetPath(size, strokeInset)
        val fillPath = if (sheen > 0f) outline.toInsetPath(size, 0.5f) else null

        val coreStroke = Stroke(width = coreWidth)
        val featherStroke = Stroke(width = coreWidth * 2f)
        val edgeBrush = if (edge > 0f) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = edge),
                    coverage to Color.Transparent,
                    1f to Color.Transparent,
                ),
            )
        } else {
            null
        }
        val featherBrush = if (edge > 0f) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = edge * DepthEdgeFeatherAlphaScale),
                    coverage to Color.Transparent,
                    1f to Color.Transparent,
                ),
            )
        } else {
            null
        }
        val sheenBrush = if (sheen > 0f) {
            val sheenHeight = (size.height * 0.22f).coerceAtLeast(1f)
            Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = sheen), Color.Transparent),
                startY = 0f,
                endY = sheenHeight,
            )
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            if (fillPath != null && sheenBrush != null) {
                drawPath(path = fillPath, brush = sheenBrush)
            }
            if (featherBrush != null) {
                drawPath(path = strokePath, brush = featherBrush, style = featherStroke)
            }
            if (edgeBrush != null) {
                drawPath(path = strokePath, brush = edgeBrush, style = coreStroke)
            }
        }
    }
}

@Composable
internal fun Modifier.nuvioPosterDepth(shape: Shape): Modifier =
    nuvioCardDepth(shape, NuvioCardDepthSurface.Posters)

private fun Outline.toInsetPath(size: Size, inset: Float): Path {
    val innerW = (size.width - inset * 2f).coerceAtLeast(0f)
    val innerH = (size.height - inset * 2f).coerceAtLeast(0f)
    return Path().apply {
        when (this@toInsetPath) {
            is Outline.Rectangle -> addRect(Rect(Offset(inset, inset), Size(innerW, innerH)))
            is Outline.Rounded -> {
                val r = roundRect
                fun CornerRadius.pulledIn() = CornerRadius((x - inset).coerceAtLeast(0f), (y - inset).coerceAtLeast(0f))
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = inset + innerW,
                        bottom = inset + innerH,
                        topLeftCornerRadius = r.topLeftCornerRadius.pulledIn(),
                        topRightCornerRadius = r.topRightCornerRadius.pulledIn(),
                        bottomRightCornerRadius = r.bottomRightCornerRadius.pulledIn(),
                        bottomLeftCornerRadius = r.bottomLeftCornerRadius.pulledIn(),
                    ),
                )
            }
            is Outline.Generic -> {
                addPath(path)
                val sx = if (size.width > 0f) innerW / size.width else 1f
                val sy = if (size.height > 0f) innerH / size.height else 1f
                transform(
                    Matrix().apply {
                        translate(inset, inset)
                        scale(sx, sy)
                    },
                )
            }
        }
    }
}
