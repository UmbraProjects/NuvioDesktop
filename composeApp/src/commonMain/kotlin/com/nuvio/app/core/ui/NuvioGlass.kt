package com.nuvio.app.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The frosted glass the desktop settings are drawn in: a faint white fill over whatever sits
 * behind, a one-pixel light edge, and 10dp corners. Shared so modals, menus and other screens can
 * use the same look as the settings cards.
 *
 * The fill is a gradient across dark values, so draw it with [ditheredBackground] rather than
 * `background`, or it bands.
 */
object NuvioGlass {
    val Radius = 10.dp
    val Shape = RoundedCornerShape(Radius)

    // Even across the width: a left-to-right fade read as the glass thinning out toward one side.
    // Only a slight top-to-bottom falloff, which is symmetric.
    val Fill: Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.045f),
    )

    // One solid tone so the gap lines between row cards match the outline exactly.
    val EdgeColor = Color.White.copy(alpha = 0.10f)
    val Edge: Brush = SolidColor(EdgeColor)

    /**
     * The deep night blue the settings sky starts from, mixed into [background]. What the glass
     * reads against: a panel that floats over other content (a modal) takes it as its solid base so
     * it looks like a piece of the settings page rather than a grey slab.
     */
    fun night(background: Color): Color = lerp(background, Color(0xFF0B1230), 0.55f)
}
