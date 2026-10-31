package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/*
 * Compose's border() draws a rounded shape as the outer outline filled with the inner one clipped away, and on desktop
 * that clip is not anti-aliased: a focus ring's corners came out stair-stepped. This strokes the outline instead, which
 * is drawn anti-aliased, with each corner's radius pulled in by the inset so the ring stays concentric with the shape.
 */

/** A [border] drawn as an anti-aliased stroke just inside [shape]'s edge. */
fun Modifier.smoothBorder(width: Dp, color: Color, shape: Shape): Modifier = smoothBorder(width, SolidColor(color), shape)

fun Modifier.smoothBorder(border: BorderStroke, shape: Shape): Modifier = smoothBorder(border.width, border.brush, shape)

fun Modifier.smoothBorder(width: Dp, brush: Brush, shape: Shape): Modifier = drawWithCache {
    val strokePx = width.toPx()
    // Half the stroke in from the edge, and half a pixel more, so the whole ring is inside the bounds and clear of a
    // clip on them (desktop clips are not anti-aliased either, and would cut the ring's outer edge).
    val inset = strokePx / 2f + 0.5f
    val inner = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
    val path = Path()
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> path.addRect(androidx.compose.ui.geometry.Rect(Offset(inset, inset), inner))
        is Outline.Rounded -> {
            val r = outline.roundRect
            fun CornerRadius.pulledIn() = CornerRadius((x - inset).coerceAtLeast(0f), (y - inset).coerceAtLeast(0f))
            path.addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = inset + inner.width,
                    bottom = inset + inner.height,
                    topLeftCornerRadius = r.topLeftCornerRadius.pulledIn(),
                    topRightCornerRadius = r.topRightCornerRadius.pulledIn(),
                    bottomRightCornerRadius = r.bottomRightCornerRadius.pulledIn(),
                    bottomLeftCornerRadius = r.bottomLeftCornerRadius.pulledIn(),
                ),
            )
        }
        is Outline.Generic -> {
            // An arbitrary path: scaled in about its centre by the stroke, close enough for a ring.
            path.addPath(outline.path)
            val sx = if (size.width > 0f) inner.width / size.width else 1f
            val sy = if (size.height > 0f) inner.height / size.height else 1f
            path.transform(
                androidx.compose.ui.graphics.Matrix().apply {
                    translate(inset, inset)
                    scale(sx, sy)
                },
            )
        }
    }
    val stroke = Stroke(width = strokePx)
    onDrawWithContent {
        drawContent()
        drawPath(path, brush, style = stroke)
    }
}
