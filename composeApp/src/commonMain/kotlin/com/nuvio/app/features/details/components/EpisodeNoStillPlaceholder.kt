package com.nuvio.app.features.details.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * An unaired episode with no still yet (common on a new season): a little alarm clock whose
 * minute hand ticks round in twelve steps, instead of the show's backdrop repeated down the row.
 * Lifted from the Nuvio Tracker site's `NoStill`.
 */
@Composable
internal fun EpisodeNoStillPlaceholder(
    modifier: Modifier = Modifier,
    clockSize: Dp = 44.dp,
    label: String? = "Not available yet",
    labelSize: TextUnit = 13.sp,
    contentAlignment: Alignment.Vertical = Alignment.CenterVertically,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = Color(0xFF52525B)
    val ticks by rememberInfiniteTransition(label = "noStillClock").animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "noStillHand",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF222228), Color(0xFF16161A)),
                ),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, contentAlignment),
    ) {
        Canvas(modifier = Modifier.size(clockSize)) {
            // Drawn on the tracker's 48-unit grid.
            val u = size.minDimension / 48f
            fun p(x: Float, y: Float) = Offset(x * u, y * u)
            val stroke = 2.6f * u
            val centre = p(24f, 26f)
            // Bells
            drawArc(outline, 200f, 100f, false, topLeft = p(8f, 7f), size = Size(12f * u, 12f * u), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(outline, 240f, 100f, false, topLeft = p(28f, 7f), size = Size(12f * u, 12f * u), style = Stroke(stroke, cap = StrokeCap.Round))
            // Feet
            drawLine(outline, p(15f, 41.5f), p(17.6f, 38.1f), stroke, StrokeCap.Round)
            drawLine(outline, p(33f, 41.5f), p(30.4f, 38.1f), stroke, StrokeCap.Round)
            // Face
            drawCircle(accent.copy(alpha = 0.12f), radius = 14f * u, center = centre)
            drawCircle(outline, radius = 14f * u, center = centre, style = Stroke(stroke))
            drawCircle(outline, radius = 1.6f * u, center = p(24f, 10.5f))
            // Minute hand, stepping a twelfth of a turn at a time
            rotate(degrees = floor(ticks) * 30f, pivot = centre) {
                drawLine(accent, centre, p(24f, 18.5f), stroke, StrokeCap.Round)
            }
            drawLine(accent, centre, p(29f, 29f), stroke, StrokeCap.Round)
            drawCircle(accent, radius = 1.8f * u, center = centre)
        }
        if (label != null) {
            Text(
                text = label,
                color = Color(0xFF71717A),
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
