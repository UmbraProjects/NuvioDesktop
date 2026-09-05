package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The accent gradient's colour stops, interpolated perceptually rather than by raw channel values.
 *
 * A two-stop gradient is interpolated by the GPU straight down each sRGB channel, which spends the
 * ramp unevenly when the two accents differ a lot in brightness. Measured on a real theme
 * (`#534582` violet to `#00FFFF` cyan): a straight lerp had covered **79% of the hue arc by 60% of
 * the width**, so the last 40% of every accent surface was brightening a colour that had already
 * arrived, and the violet end vanished almost immediately. Interpolating the same pair in Oklab
 * brings that to 71%, and 30% of the width to 30% of the arc instead of 45% — the change is spread
 * across the whole surface, which is what "left to right" is supposed to look like.
 *
 * Emitted as a handful of stops because neither Compose's `Brush` nor a CSS `linear-gradient` can
 * be told to interpolate in another space. The GPU still lerps in sRGB *between* the stops, but
 * over a twelfth of the width that error is far below a visible step.
 */
private const val ACCENT_GRADIENT_STOP_COUNT = 12

/**
 * [ACCENT_GRADIENT_STOP_COUNT] colours from [from] to [to], both ends included. Alpha is carried
 * through linearly; only the colour is treated perceptually.
 */
internal fun accentGradientStops(from: Color, to: Color): List<Color> {
    // Identical ends have nothing to interpolate, and asking for the ramp anyway would only spend
    // eleven redundant stops describing one colour.
    if (from == to) return listOf(from, to)
    val start = from.toOklab()
    val end = to.toOklab()
    return List(ACCENT_GRADIENT_STOP_COUNT) { index ->
        val t = index.toFloat() / (ACCENT_GRADIENT_STOP_COUNT - 1)
        Oklab(
            l = start.l + (end.l - start.l) * t,
            a = start.a + (end.a - start.a) * t,
            b = start.b + (end.b - start.b) * t,
        ).toColor(alpha = from.alpha + (to.alpha - from.alpha) * t)
    }
}

private data class Oklab(val l: Float, val a: Float, val b: Float)

private fun Color.toOklab(): Oklab {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)
    val lCone = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
    val mCone = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
    val sCone = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
    val l = cbrt(lCone)
    val m = cbrt(mCone)
    val s = cbrt(sCone)
    return Oklab(
        l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun Oklab.toColor(alpha: Float): Color {
    val lCone = (l + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val mCone = (l - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val sCone = (l - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    return Color(
        red = linearToSrgb(4.0767416621f * lCone - 3.3077115913f * mCone + 0.2309699292f * sCone),
        green = linearToSrgb(-1.2684380046f * lCone + 2.6097574011f * mCone - 0.3413193965f * sCone),
        blue = linearToSrgb(-0.0041960863f * lCone - 0.7034186147f * mCone + 1.7076147010f * sCone),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

private fun cbrt(value: Float): Float =
    if (value < 0f) -((-value).pow(1f / 3f)) else value.pow(1f / 3f)

private fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/**
 * Clamped on the way out: an Oklab point between two in-gamut colours can still land marginally
 * outside sRGB, and an unclamped channel would throw from [Color].
 */
private fun linearToSrgb(channel: Float): Float {
    val clamped = channel.coerceIn(0f, 1f)
    val encoded = if (clamped <= 0.0031308f) {
        clamped * 12.92f
    } else {
        1.055f * clamped.pow(1f / 2.4f) - 0.055f
    }
    return encoded.coerceIn(0f, 1f)
}
