package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage

private data class RandomPlayPosterPlacement(
    val widthFraction: Float,
    val xFraction: Float,
    val yFraction: Float,
    val rotationDegrees: Float,
)

private val RandomPlayPosterPlacements = listOf(
    RandomPlayPosterPlacement(0.50f, -0.10f, 0.04f, -9f),
    RandomPlayPosterPlacement(0.48f, 0.57f, -0.03f, 8f),
    RandomPlayPosterPlacement(0.45f, -0.04f, 0.52f, -7f),
    RandomPlayPosterPlacement(0.46f, 0.59f, 0.48f, 7f),
    RandomPlayPosterPlacement(0.54f, 0.23f, 0.19f, 1f),
)

/** A personalized category card assembled from posters already present in loaded catalogs. */
@Composable
internal fun RandomPlayPosterCollage(
    title: String,
    posterUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF081614), Color(0xFF111827), Color(0xFF050505)),
                ),
            ),
    ) {
        posterUrls.take(RandomPlayPosterPlacements.size).forEachIndexed { index, posterUrl ->
            val placement = RandomPlayPosterPlacements[index]
            NuvioAsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(maxWidth * placement.widthFraction)
                    .aspectRatio(0.675f)
                    .offset(
                        x = maxWidth * placement.xFraction,
                        y = maxHeight * placement.yFraction,
                    )
                    .graphicsLayer {
                        rotationZ = placement.rotationDegrees
                        shadowElevation = 12.dp.toPx()
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.08f),
                            0.46f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.48f),
                            1f to Color.Black.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                shadow = Shadow(color = Color.Black, blurRadius = 8f),
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
