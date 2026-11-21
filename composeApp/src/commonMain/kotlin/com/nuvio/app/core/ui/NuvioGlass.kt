package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
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
     * The deep night the settings sky starts from, mixed into [background]. What the glass reads
     * against: a panel that floats over other content (a modal) takes it as its solid base so it
     * looks like a piece of the settings page rather than a grey slab.
     *
     * Tinted by [accent], so the glass takes on the theme: a very dark version of the accent (see
     * [nightTint]). It used to be a fixed night blue, which only matched the blue themes; a red
     * theme still got blue panels.
     */
    fun night(background: Color, accent: Color): Color = lerp(background, nightTint(accent), 0.55f)

    /**
     * The accent taken down almost to black: [NightTintAmount] of it over a near-black base. For the
     * default blue that lands on the night blue this used to hard-code (#0B1230); a red accent gives
     * a deep oxblood, a green one a near-black forest.
     */
    fun nightTint(accent: Color): Color = lerp(NightTintBase, accent.copy(alpha = 1f), NightTintAmount)

    private val NightTintBase = Color(0xFF07080C)
    private const val NightTintAmount = 0.16f
}

/**
 * Subtle cursor-tracked specular sheen along the 1 dp edge of a [NuvioGlass] card.
 *
 * Reads the pointer offset inside [drawWithContent] so moving the mouse across a card only
 * invalidates the draw pass and never recomposes the card's children.
 */
internal fun Modifier.nuvioGlassHoverSheen(
    cornerRadius: Dp = NuvioGlass.Radius,
    radius: Dp = 140.dp,
    peakAlpha: Float = 0.22f,
): Modifier = composed {
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var hovered by remember { mutableStateOf(false) }
    val sheenAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "nuvio_glass_hover_sheen",
    )

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> {
                            hovered = true
                            event.changes.firstOrNull()?.position?.let { pointer = it }
                        }
                        PointerEventType.Exit -> {
                            hovered = false
                        }
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (sheenAlpha <= 0f || pointer == Offset.Unspecified) return@drawWithContent
            val strokePx = 1.dp.toPx()
            val inset = strokePx / 2f
            val cr = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
            val brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = peakAlpha * sheenAlpha),
                    Color.Transparent,
                ),
                center = pointer,
                radius = radius.toPx().coerceAtLeast(1f),
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(cr, cr),
                style = Stroke(width = strokePx),
            )
        }
}
