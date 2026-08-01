package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
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
    return border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = edge),
                coverage to Color.Transparent,
                1f to Color.Transparent,
            ),
        ),
        shape = shape,
    ).drawWithContent {
        drawContent()
        if (sheen > 0f) {
            val height = size.height * 0.22f
            val clipPath = shape.createOutline(size, layoutDirection, this).toPath()
            clipPath(clipPath) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = sheen), Color.Transparent), endY = height),
                    size = Size(size.width, height),
                )
            }
        }
    }
}

@Composable
internal fun Modifier.nuvioPosterDepth(shape: Shape): Modifier =
    nuvioCardDepth(shape, NuvioCardDepthSurface.Posters)

private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
