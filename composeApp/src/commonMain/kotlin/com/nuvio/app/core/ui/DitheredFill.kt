package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Fills [outline] with [brush] with dithering on. The settings sky and glass fills are long, shallow
 * gradients across dark values, which band visibly in 8-bit without it; `background(brush)` gives
 * no way to ask for dither.
 */
internal expect fun DrawScope.drawDitheredOutline(brush: Brush, outline: Outline)

/** [Modifier.background] for a gradient, dithered; see [drawDitheredOutline]. */
internal fun Modifier.ditheredBackground(brush: Brush, shape: Shape = RectangleShape): Modifier =
    drawBehind { drawDitheredOutline(brush, shape.createOutline(size, layoutDirection, this)) }
