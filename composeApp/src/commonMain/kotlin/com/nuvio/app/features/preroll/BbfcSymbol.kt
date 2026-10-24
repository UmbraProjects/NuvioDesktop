package com.nuvio.app.features.preroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * The BBFC classification symbols as shown on BBFC certification stickers: a green triangle for U, a
 * yellow triangle for PG, and circles for 12/12A, 15 and 18. Each carries the repeating "BBFC" watermark
 * and the "BBFC (c)1985" line, with the certificate in heavy outlined lettering.
 *
 * Drawn from shapes rather than bundled as an image so they stay sharp at any size on any display.
 * Returns nothing for certificates that are not BBFC ones (MPAA letters are handled by the caller).
 */
@Composable
fun BbfcSymbol(certificate: String, size: Dp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val spec = bbfcSpec(certificate) ?: return
    Canvas(modifier.size(size)) {
        when (spec.shape) {
            BbfcShape.Triangle -> drawTriangleSymbol(measurer, spec)
            BbfcShape.Circle -> drawCircleSymbol(measurer, spec)
        }
    }
}

/** True when [certificate] has a BBFC symbol. */
fun hasBbfcSymbol(certificate: String): Boolean = bbfcSpec(certificate) != null

private enum class BbfcShape { Triangle, Circle }

private data class BbfcSpec(
    val shape: BbfcShape,
    val label: String,
    val fill: Color,
    val pattern: Color,
    val glyphFill: Color,
    val doubleRing: Boolean = false,
)

private val BbfcGreen = Color(0xFF2E9A44)
private val BbfcYellow = Color(0xFFF2C12E)
private val BbfcRed = Color(0xFFD2232A)
private val BbfcDarkRed = Color(0xFFB5191F)
private val Ink = Color(0xFF111111)

private fun bbfcSpec(certificate: String): BbfcSpec? = when (certificate.uppercase()) {
    "U" -> BbfcSpec(BbfcShape.Triangle, "U", BbfcGreen, Ink, Color.White)
    "PG" -> BbfcSpec(BbfcShape.Triangle, "PG", BbfcYellow, Ink, Color.White)
    "12", "12A" -> BbfcSpec(BbfcShape.Circle, certificate.uppercase(), Color.White, BbfcRed, BbfcRed)
    "15" -> BbfcSpec(BbfcShape.Circle, "15", Color.White, BbfcRed, BbfcRed)
    "18" -> BbfcSpec(BbfcShape.Circle, "18", BbfcDarkRed, Ink, Color.White, doubleRing = true)
    else -> null
}

private fun DrawScope.drawWatermark(measurer: TextMeasurer, color: Color, unit: Float, bounds: Size, withCopyright: Boolean) {
    val style = TextStyle(color = color, fontSize = (unit * 0.072f).toSp(), fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
    val word = measurer.measure("BBFC", style)
    val gap = word.size.width * 0.35f
    val stepX = word.size.width + gap
    val stepY = word.size.height * 0.98f
    var row = 0
    var y = -stepY
    while (y < bounds.height + stepY) {
        val offset = if (row % 2 == 0) 0f else stepX / 2f
        var x = -stepX + offset
        while (x < bounds.width + stepX) {
            drawText(word, topLeft = Offset(x, y))
            x += stepX
        }
        y += stepY
        row++
    }
    if (withCopyright) {
        val line = measurer.measure("BBFC\u00A91985", style)
        drawText(line, topLeft = Offset((bounds.width - line.size.width) / 2f, bounds.height * 0.86f))
    }
}

private fun DrawScope.drawGlyph(measurer: TextMeasurer, label: String, fill: Color, centre: Offset, height: Float) {
    val px = height.toSp()
    val base = TextStyle(fontSize = px, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black)
    val stroke = measurer.measure(label, base.copy(color = Ink, drawStyle = Stroke(width = height * 0.09f)))
    val body = measurer.measure(label, base.copy(color = fill))
    // The BBFC lettering is a heavy, condensed face; squeeze the default sans to match.
    val squeeze = 0.78f
    scale(scaleX = squeeze, scaleY = 1f, pivot = centre) {
        val topLeft = Offset(centre.x - body.size.width / 2f, centre.y - body.size.height / 2f)
        drawText(stroke, topLeft = topLeft)
        drawText(body, topLeft = topLeft)
    }
}

private fun DrawScope.drawTriangleSymbol(measurer: TextMeasurer, spec: BbfcSpec) {
    val s = size.minDimension
    fun tri(inset: Float): Path {
        val top = s * (0.02f + inset * 1.1f)
        val bottom = s * (0.98f - inset * 0.6f)
        val half = (0.5f - inset * 1.0f) * s
        return Path().apply {
            moveTo(s / 2f, top)
            lineTo(s / 2f + half, bottom)
            lineTo(s / 2f - half, bottom)
            close()
        }
    }
    drawPath(tri(0f), Color.White)
    drawPath(tri(0f), Ink, style = Stroke(s * 0.012f))
    val inner = tri(0.035f)
    drawPath(inner, Ink, style = Stroke(s * 0.012f))
    val field = tri(0.05f)
    drawPath(field, spec.fill)
    clipPath(field) { drawWatermark(measurer, spec.pattern, s, Size(s, s), withCopyright = true) }
    drawPath(field, Ink, style = Stroke(s * 0.012f))
    drawGlyph(measurer, spec.label, spec.glyphFill, Offset(s / 2f, s * 0.60f), s * 0.38f)
}

private fun DrawScope.drawCircleSymbol(measurer: TextMeasurer, spec: BbfcSpec) {
    val s = size.minDimension
    val c = Offset(s / 2f, s / 2f)
    val line = s * 0.012f
    // Outer white ring edged in black (two rings on the 18).
    drawCircle(Color.White, radius = s * 0.49f, center = c)
    drawCircle(Ink, radius = s * 0.49f, center = c, style = Stroke(line))
    if (spec.doubleRing) drawCircle(Ink, radius = s * 0.455f, center = c, style = Stroke(line))
    if (spec.fill == Color.White) {
        // 12 / 12A / 15: red band around a white field.
        drawCircle(BbfcRed, radius = s * 0.43f, center = c)
        drawCircle(Ink, radius = s * 0.43f, center = c, style = Stroke(line))
        drawCircle(Ink, radius = s * 0.35f, center = c, style = Stroke(line * 1.6f))
        drawCircle(Color.White, radius = s * 0.345f, center = c)
    } else {
        drawCircle(Ink, radius = s * 0.43f, center = c, style = Stroke(line * 1.6f))
        drawCircle(spec.fill, radius = s * 0.425f, center = c)
    }
    val fieldR = if (spec.fill == Color.White) s * 0.345f else s * 0.425f
    val field = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, fieldR)) }
    clipPath(field) {
        drawWatermark(measurer, spec.pattern, s, Size(s, s), withCopyright = true)
    }
    val label = spec.label
    val height = if (label.length > 2) s * 0.36f else s * 0.44f
    drawGlyph(measurer, label, spec.glyphFill, c, height)
}
