package com.nuvio.app.features.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small, platform-independent flag artwork for the discovery language badge.
 *
 * Emoji flags are not consistently available on desktop, but reducing every flag to equal-sized
 * colour bands makes Japan, Korea, the Nordic flags, and several others look like different
 * countries. These designs retain the defining geometry at the badge's intentionally small size.
 */
@Composable
internal fun HeroDiscoveryFlagIcon(
    languageCode: String,
    modifier: Modifier = Modifier,
) {
    val design = languageCode.heroDiscoveryFlagDesign()
    if (design == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "\uD83C\uDF10",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 20.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val flagWidth = size.width * 0.78f
        val flagHeight = size.height * 0.56f
        val origin = Offset((size.width - flagWidth) / 2f, (size.height - flagHeight) / 2f)
        val flagSize = Size(flagWidth, flagHeight)
        drawHeroDiscoveryFlag(design, origin, flagSize)
        drawRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = origin,
            size = Size(flagWidth, 1.2f),
        )
    }
}

internal enum class HeroDiscoveryFlagDesign {
    SPAIN,
    FRANCE,
    GERMANY,
    ITALY,
    PORTUGAL,
    JAPAN,
    SOUTH_KOREA,
    CHINA,
    DENMARK,
    SWEDEN,
    NORWAY,
    FINLAND,
    NETHERLANDS,
    POLAND,
    RUSSIA,
    TURKEY,
    SAUDI_ARABIA,
    INDIA,
    IRAN,
    ROMANIA,
    HUNGARY,
    CZECHIA,
    ISRAEL,
    GREECE,
}

internal fun String.heroDiscoveryFlagDesign(): HeroDiscoveryFlagDesign? =
    when (trim().lowercase()) {
        "es", "spa" -> HeroDiscoveryFlagDesign.SPAIN
        "fr", "fre", "fra" -> HeroDiscoveryFlagDesign.FRANCE
        "de", "ger", "deu" -> HeroDiscoveryFlagDesign.GERMANY
        "it", "ita" -> HeroDiscoveryFlagDesign.ITALY
        "pt", "por" -> HeroDiscoveryFlagDesign.PORTUGAL
        "ja", "jpn" -> HeroDiscoveryFlagDesign.JAPAN
        "ko", "kor" -> HeroDiscoveryFlagDesign.SOUTH_KOREA
        "zh", "zho", "chi" -> HeroDiscoveryFlagDesign.CHINA
        "da", "dan" -> HeroDiscoveryFlagDesign.DENMARK
        "sv", "swe" -> HeroDiscoveryFlagDesign.SWEDEN
        "no", "nor" -> HeroDiscoveryFlagDesign.NORWAY
        "fi", "fin" -> HeroDiscoveryFlagDesign.FINLAND
        "nl", "dut", "nld" -> HeroDiscoveryFlagDesign.NETHERLANDS
        "pl", "pol" -> HeroDiscoveryFlagDesign.POLAND
        "ru", "rus" -> HeroDiscoveryFlagDesign.RUSSIA
        "tr", "tur" -> HeroDiscoveryFlagDesign.TURKEY
        "ar", "ara" -> HeroDiscoveryFlagDesign.SAUDI_ARABIA
        "hi", "hin" -> HeroDiscoveryFlagDesign.INDIA
        "fa", "per", "fas" -> HeroDiscoveryFlagDesign.IRAN
        "ro", "rum", "ron" -> HeroDiscoveryFlagDesign.ROMANIA
        "hu", "hun" -> HeroDiscoveryFlagDesign.HUNGARY
        "cs", "cze", "ces" -> HeroDiscoveryFlagDesign.CZECHIA
        "he", "heb" -> HeroDiscoveryFlagDesign.ISRAEL
        "el", "gre", "ell" -> HeroDiscoveryFlagDesign.GREECE
        else -> null
    }

private fun DrawScope.drawHeroDiscoveryFlag(
    design: HeroDiscoveryFlagDesign,
    origin: Offset,
    flagSize: Size,
) {
    when (design) {
        HeroDiscoveryFlagDesign.SPAIN -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color(0xFFAA151B) to 1f, Color(0xFFF1BF00) to 2f, Color(0xFFAA151B) to 1f),
        )
        HeroDiscoveryFlagDesign.FRANCE -> drawVerticalStripes(
            origin,
            flagSize,
            listOf(Color(0xFF0055A4), Color.White, Color(0xFFEF4135)),
        )
        HeroDiscoveryFlagDesign.GERMANY -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color.Black, Color(0xFFDD0000), Color(0xFFFFCE00)).equalWeights(),
        )
        HeroDiscoveryFlagDesign.ITALY -> drawVerticalStripes(
            origin,
            flagSize,
            listOf(Color(0xFF009246), Color.White, Color(0xFFCE2B37)),
        )
        HeroDiscoveryFlagDesign.PORTUGAL -> drawPortugal(origin, flagSize)
        HeroDiscoveryFlagDesign.JAPAN -> drawJapan(origin, flagSize)
        HeroDiscoveryFlagDesign.SOUTH_KOREA -> drawSouthKorea(origin, flagSize)
        HeroDiscoveryFlagDesign.CHINA -> drawChina(origin, flagSize)
        HeroDiscoveryFlagDesign.DENMARK -> drawNordicCross(
            origin,
            flagSize,
            Color(0xFFC8102E),
            Color.White,
            verticalCenter = 0.37f,
            crossWidth = 0.13f,
        )
        HeroDiscoveryFlagDesign.SWEDEN -> drawNordicCross(
            origin,
            flagSize,
            Color(0xFF006AA7),
            Color(0xFFFECC02),
            verticalCenter = 0.38f,
            crossWidth = 0.13f,
        )
        HeroDiscoveryFlagDesign.NORWAY -> drawNorway(origin, flagSize)
        HeroDiscoveryFlagDesign.FINLAND -> drawNordicCross(
            origin,
            flagSize,
            Color.White,
            Color(0xFF003580),
            verticalCenter = 0.39f,
            crossWidth = 0.16f,
        )
        HeroDiscoveryFlagDesign.NETHERLANDS -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color(0xFFAE1C28), Color.White, Color(0xFF21468B)).equalWeights(),
        )
        HeroDiscoveryFlagDesign.POLAND -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color.White, Color(0xFFDC143C)).equalWeights(),
        )
        HeroDiscoveryFlagDesign.RUSSIA -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color.White, Color(0xFF0039A6), Color(0xFFD52B1E)).equalWeights(),
        )
        HeroDiscoveryFlagDesign.TURKEY -> drawTurkey(origin, flagSize)
        HeroDiscoveryFlagDesign.SAUDI_ARABIA -> drawSaudiArabia(origin, flagSize)
        HeroDiscoveryFlagDesign.INDIA -> drawIndia(origin, flagSize)
        HeroDiscoveryFlagDesign.IRAN -> drawIran(origin, flagSize)
        HeroDiscoveryFlagDesign.ROMANIA -> drawVerticalStripes(
            origin,
            flagSize,
            listOf(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126)),
        )
        HeroDiscoveryFlagDesign.HUNGARY -> drawHorizontalStripes(
            origin,
            flagSize,
            listOf(Color(0xFFCE2939), Color.White, Color(0xFF477050)).equalWeights(),
        )
        HeroDiscoveryFlagDesign.CZECHIA -> drawCzechia(origin, flagSize)
        HeroDiscoveryFlagDesign.ISRAEL -> drawIsrael(origin, flagSize)
        HeroDiscoveryFlagDesign.GREECE -> drawGreece(origin, flagSize)
    }
}

private fun List<Color>.equalWeights(): List<Pair<Color, Float>> = map { it to 1f }

private fun DrawScope.drawHorizontalStripes(
    origin: Offset,
    flagSize: Size,
    stripes: List<Pair<Color, Float>>,
) {
    val totalWeight = stripes.sumOf { it.second.toDouble() }.toFloat()
    var top = origin.y
    stripes.forEachIndexed { index, (color, weight) ->
        val bottom = if (index == stripes.lastIndex) origin.y + flagSize.height else top + flagSize.height * weight / totalWeight
        drawRect(color, Offset(origin.x, top), Size(flagSize.width, bottom - top + 0.5f))
        top = bottom
    }
}

private fun DrawScope.drawVerticalStripes(origin: Offset, flagSize: Size, colors: List<Color>) {
    val stripeWidth = flagSize.width / colors.size
    colors.forEachIndexed { index, color ->
        drawRect(color, Offset(origin.x + stripeWidth * index, origin.y), Size(stripeWidth + 0.5f, flagSize.height))
    }
}

private fun DrawScope.drawPortugal(origin: Offset, flagSize: Size) {
    val greenWidth = flagSize.width * 0.4f
    drawRect(Color(0xFF046A38), origin, Size(greenWidth, flagSize.height))
    drawRect(Color(0xFFDA291C), Offset(origin.x + greenWidth, origin.y), Size(flagSize.width - greenWidth, flagSize.height))
    val center = Offset(origin.x + greenWidth, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.22f
    drawCircle(Color(0xFFFFC72C), radius, center)
    drawCircle(Color.White, radius * 0.62f, center)
    drawRect(Color(0xFFDA291C), Offset(center.x - radius * 0.34f, center.y - radius * 0.45f), Size(radius * 0.68f, radius * 0.9f))
}

private fun DrawScope.drawJapan(origin: Offset, flagSize: Size) {
    drawRect(Color.White, origin, flagSize)
    drawCircle(
        color = Color(0xFFBC002D),
        radius = flagSize.height * 0.30f,
        center = Offset(origin.x + flagSize.width / 2f, origin.y + flagSize.height / 2f),
    )
}

private fun DrawScope.drawSouthKorea(origin: Offset, flagSize: Size) {
    drawRect(Color.White, origin, flagSize)
    val center = Offset(origin.x + flagSize.width / 2f, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.22f
    drawCircle(Color(0xFFCD2E3A), radius, center)
    val blueHalf = Path().apply {
        moveTo(center.x - radius, center.y)
        cubicTo(center.x - radius * 0.55f, center.y + radius, center.x + radius * 0.55f, center.y + radius, center.x + radius, center.y)
        cubicTo(center.x + radius * 0.55f, center.y - radius * 0.48f, center.x, center.y - radius * 0.48f, center.x, center.y)
        cubicTo(center.x, center.y + radius * 0.48f, center.x - radius * 0.55f, center.y + radius * 0.48f, center.x - radius, center.y)
        close()
    }
    drawPath(blueHalf, Color(0xFF0047A0))
    drawKoreanTrigram(
        Offset(origin.x + flagSize.width * 0.22f, origin.y + flagSize.height * 0.24f),
        -34f,
        flagSize.height,
        solidRows = listOf(true, true, true),
    )
    drawKoreanTrigram(
        Offset(origin.x + flagSize.width * 0.78f, origin.y + flagSize.height * 0.24f),
        34f,
        flagSize.height,
        solidRows = listOf(false, true, false),
    )
    drawKoreanTrigram(
        Offset(origin.x + flagSize.width * 0.22f, origin.y + flagSize.height * 0.76f),
        34f,
        flagSize.height,
        solidRows = listOf(true, false, true),
    )
    drawKoreanTrigram(
        Offset(origin.x + flagSize.width * 0.78f, origin.y + flagSize.height * 0.76f),
        -34f,
        flagSize.height,
        solidRows = listOf(false, false, false),
    )
}

private fun DrawScope.drawKoreanTrigram(
    center: Offset,
    angle: Float,
    flagHeight: Float,
    solidRows: List<Boolean>,
) {
    rotate(angle, center) {
        val halfLength = flagHeight * 0.105f
        val spacing = flagHeight * 0.055f
        val strokeWidth = flagHeight * 0.022f
        for (row in -1..1) {
            val y = center.y + row * spacing
            if (solidRows[row + 1]) {
                drawLine(Color.Black, Offset(center.x - halfLength, y), Offset(center.x + halfLength, y), strokeWidth)
            } else {
                val gap = flagHeight * 0.025f
                drawLine(Color.Black, Offset(center.x - halfLength, y), Offset(center.x - gap, y), strokeWidth)
                drawLine(Color.Black, Offset(center.x + gap, y), Offset(center.x + halfLength, y), strokeWidth)
            }
        }
    }
}

private fun DrawScope.drawChina(origin: Offset, flagSize: Size) {
    drawRect(Color(0xFFDE2910), origin, flagSize)
    val yellow = Color(0xFFFFDE00)
    drawStar(Offset(origin.x + flagSize.width * 0.20f, origin.y + flagSize.height * 0.28f), flagSize.height * 0.13f, yellow)
    listOf(0.40f to 0.13f, 0.48f to 0.27f, 0.48f to 0.45f, 0.40f to 0.57f).forEach { (x, y) ->
        drawStar(Offset(origin.x + flagSize.width * x, origin.y + flagSize.height * y), flagSize.height * 0.045f, yellow)
    }
}

private fun DrawScope.drawNordicCross(
    origin: Offset,
    flagSize: Size,
    background: Color,
    cross: Color,
    verticalCenter: Float,
    crossWidth: Float,
) {
    drawRect(background, origin, flagSize)
    val thickness = flagSize.height * crossWidth
    val crossX = origin.x + flagSize.width * verticalCenter
    val crossY = origin.y + flagSize.height / 2f
    drawRect(cross, Offset(crossX - thickness / 2f, origin.y), Size(thickness, flagSize.height))
    drawRect(cross, Offset(origin.x, crossY - thickness / 2f), Size(flagSize.width, thickness))
}

private fun DrawScope.drawNorway(origin: Offset, flagSize: Size) {
    drawNordicCross(origin, flagSize, Color(0xFFBA0C2F), Color.White, 0.39f, 0.22f)
    drawNordicCross(origin, flagSize, Color.Transparent, Color(0xFF00205B), 0.39f, 0.11f)
}

private fun DrawScope.drawTurkey(origin: Offset, flagSize: Size) {
    val red = Color(0xFFE30A17)
    drawRect(red, origin, flagSize)
    val center = Offset(origin.x + flagSize.width * 0.40f, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.27f
    drawCircle(Color.White, radius, center)
    drawCircle(red, radius * 0.79f, Offset(center.x + radius * 0.31f, center.y))
    drawStar(Offset(origin.x + flagSize.width * 0.60f, center.y), flagSize.height * 0.105f, Color.White, -18f)
}

private fun DrawScope.drawSaudiArabia(origin: Offset, flagSize: Size) {
    drawRect(Color(0xFF006C35), origin, flagSize)
    val white = Color.White
    val centerY = origin.y + flagSize.height * 0.43f
    val stroke = flagSize.height * 0.035f
    // A compact calligraphic suggestion plus the defining sword; literal script is illegible here.
    drawLine(white, Offset(origin.x + flagSize.width * 0.25f, centerY), Offset(origin.x + flagSize.width * 0.75f, centerY), stroke)
    for (index in 0..4) {
        val x = origin.x + flagSize.width * (0.30f + index * 0.10f)
        drawLine(white, Offset(x, centerY - flagSize.height * 0.12f), Offset(x - flagSize.width * 0.025f, centerY), stroke)
    }
    val swordY = origin.y + flagSize.height * 0.69f
    drawLine(white, Offset(origin.x + flagSize.width * 0.28f, swordY), Offset(origin.x + flagSize.width * 0.73f, swordY), stroke * 0.75f)
}

private fun DrawScope.drawIndia(origin: Offset, flagSize: Size) {
    drawHorizontalStripes(
        origin,
        flagSize,
        listOf(Color(0xFFFF9933), Color.White, Color(0xFF138808)).equalWeights(),
    )
    val center = Offset(origin.x + flagSize.width / 2f, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.115f
    val navy = Color(0xFF000080)
    drawCircle(navy, radius, center, style = Stroke(width = flagSize.height * 0.018f))
    repeat(12) { index ->
        val angle = (index * 30.0 - 90.0) * PI / 180.0
        drawLine(
            navy,
            center,
            Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius),
            flagSize.height * 0.010f,
        )
    }
}

private fun DrawScope.drawIran(origin: Offset, flagSize: Size) {
    drawHorizontalStripes(
        origin,
        flagSize,
        listOf(Color(0xFF239F40), Color.White, Color(0xFFDA0000)).equalWeights(),
    )
    val center = Offset(origin.x + flagSize.width / 2f, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.09f
    drawCircle(Color(0xFFDA0000), radius, center, style = Stroke(flagSize.height * 0.025f))
    drawLine(Color(0xFFDA0000), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), flagSize.height * 0.02f)
}

private fun DrawScope.drawCzechia(origin: Offset, flagSize: Size) {
    drawHorizontalStripes(origin, flagSize, listOf(Color.White, Color(0xFFD7141A)).equalWeights())
    val triangle = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + flagSize.width * 0.45f, origin.y + flagSize.height / 2f)
        lineTo(origin.x, origin.y + flagSize.height)
        close()
    }
    drawPath(triangle, Color(0xFF11457E))
}

private fun DrawScope.drawIsrael(origin: Offset, flagSize: Size) {
    drawRect(Color.White, origin, flagSize)
    val blue = Color(0xFF0038B8)
    val stripeHeight = flagSize.height * 0.10f
    drawRect(blue, Offset(origin.x, origin.y + flagSize.height * 0.16f), Size(flagSize.width, stripeHeight))
    drawRect(blue, Offset(origin.x, origin.y + flagSize.height * 0.74f), Size(flagSize.width, stripeHeight))
    val center = Offset(origin.x + flagSize.width / 2f, origin.y + flagSize.height / 2f)
    val radius = flagSize.height * 0.18f
    drawTriangleOutline(center, radius, blue, pointingUp = true)
    drawTriangleOutline(center, radius, blue, pointingUp = false)
}

private fun DrawScope.drawTriangleOutline(center: Offset, radius: Float, color: Color, pointingUp: Boolean) {
    val direction = if (pointingUp) -1f else 1f
    val path = Path().apply {
        moveTo(center.x, center.y + direction * radius)
        lineTo(center.x - radius * 0.87f, center.y - direction * radius * 0.5f)
        lineTo(center.x + radius * 0.87f, center.y - direction * radius * 0.5f)
        close()
    }
    drawPath(path, color, style = Stroke(width = radius * 0.14f))
}

private fun DrawScope.drawGreece(origin: Offset, flagSize: Size) {
    val blue = Color(0xFF0D5EAF)
    val stripeHeight = flagSize.height / 9f
    repeat(9) { index ->
        drawRect(
            if (index % 2 == 0) blue else Color.White,
            Offset(origin.x, origin.y + index * stripeHeight),
            Size(flagSize.width, stripeHeight + 0.5f),
        )
    }
    val cantonSize = stripeHeight * 5f
    drawRect(blue, origin, Size(cantonSize, cantonSize))
    drawRect(Color.White, Offset(origin.x + stripeHeight * 2f, origin.y), Size(stripeHeight, cantonSize))
    drawRect(Color.White, Offset(origin.x, origin.y + stripeHeight * 2f), Size(cantonSize, stripeHeight))
}

private fun DrawScope.drawStar(
    center: Offset,
    outerRadius: Float,
    color: Color,
    rotationDegrees: Float = -90f,
) {
    val path = Path()
    repeat(10) { index ->
        val radius = if (index % 2 == 0) outerRadius else outerRadius * 0.38f
        val angle = (rotationDegrees + index * 36.0) * PI / 180.0
        val point = Offset(
            center.x + cos(angle).toFloat() * radius,
            center.y + sin(angle).toFloat() * radius,
        )
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color)
}
