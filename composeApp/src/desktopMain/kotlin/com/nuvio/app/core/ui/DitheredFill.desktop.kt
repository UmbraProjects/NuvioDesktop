package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

internal actual fun DrawScope.drawDitheredOutline(brush: Brush, outline: Outline) {
    // Anti-aliased explicitly: a bare Paint draws the rounded corners stair-stepped.
    val paint = Paint().apply { isAntiAlias = true }
    brush.applyTo(size, paint, 1f)
    paint.asFrameworkPaint().isDither = true
    drawIntoCanvas { it.drawOutline(outline, paint) }
}
