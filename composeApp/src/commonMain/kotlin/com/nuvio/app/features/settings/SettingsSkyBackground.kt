package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.drawDitheredOutline
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.lerp
import kotlin.math.max

/**
 * The desktop settings backdrop: a deep night-blue wash falling off to the theme's page colour,
 * with two faint glows (one in the accent) so the glass headings and cards have something to sit
 * over. Static and built once per size in [drawWithCache]; drawn dithered so the long, shallow
 * gradients over dark values do not band.
 */
internal fun Modifier.settingsSkyBackground(
    background: Color,
    accent: Color,
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val night = NuvioGlass.night(background, accent)
    val wash = Brush.verticalGradient(
        0f to night,
        0.65f to lerp(background, night, 0.35f),
        1f to background,
    )
    val radius = max(w, h)
    val glowAccent = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
        center = Offset(w * 0.82f, h * 0.12f),
        radius = radius * 0.45f,
    )
    val glowViolet = Brush.radialGradient(
        colors = listOf(Color(0xFF6A4BC4).copy(alpha = 0.08f), Color.Transparent),
        center = Offset(w * 0.18f, h * 0.78f),
        radius = radius * 0.5f,
    )
    val bounds = Outline.Rectangle(size.toRect())
    onDrawBehind {
        drawDitheredOutline(wash, bounds)
        drawDitheredOutline(glowAccent, bounds)
        drawDitheredOutline(glowViolet, bounds)
    }
}
