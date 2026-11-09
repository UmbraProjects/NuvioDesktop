package com.nuvio.app.core.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * fork:settings-arrows
 *
 * A focus ring for every control that uses the default indication (clickable, toggleable, selectable) inside the part
 * of the screen that provides it. Settings' rows are plain clickables with no focus look of their own, so moving focus
 * with the arrows, a controller or the phone remote worked but nothing showed where it was. The ring shows only while
 * the keyboard is in use (KeyboardInputMode), and the indication it wraps (the ripple, hover) still draws underneath.
 */

/** Provide as [LocalIndication]: the current indication plus a [color] ring around the control holding keyboard focus. */
@Composable
fun rememberKeyboardFocusIndication(color: Color, cornerRadius: Dp = 10.dp): IndicationNodeFactory {
    val base = LocalIndication.current as? IndicationNodeFactory
    return remember(base, color, cornerRadius) { KeyboardFocusIndication(base, color, cornerRadius) }
}

private class KeyboardFocusIndication(
    private val base: IndicationNodeFactory?,
    private val color: Color,
    private val cornerRadius: Dp,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        KeyboardFocusRingNode(interactionSource, base, color, cornerRadius)

    override fun equals(other: Any?): Boolean =
        other is KeyboardFocusIndication && other.base == base && other.color == color && other.cornerRadius == cornerRadius

    override fun hashCode(): Int = (base.hashCode() * 31 + color.hashCode()) * 31 + cornerRadius.hashCode()
}

private class KeyboardFocusRingNode(
    private val interactionSource: InteractionSource,
    base: IndicationNodeFactory?,
    private val color: Color,
    private val cornerRadius: Dp,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init {
        base?.let { delegate(it.create(interactionSource)) }
    }

    override fun onAttach() {
        coroutineScope.launch {
            var focusCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount++
                    is FocusInteraction.Unfocus -> focusCount--
                    else -> return@collect
                }
                val nowFocused = focusCount > 0
                if (nowFocused != focused) {
                    focused = nowFocused
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // Read in the draw phase, so the ring comes and goes as the keyboard or the mouse takes over.
        if (focused && KeyboardInputMode.active) {
            val stroke = 2.dp.toPx()
            val radius = cornerRadius.toPx()
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke),
            )
        }
    }
}
