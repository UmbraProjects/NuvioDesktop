package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.lerp
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The two ends, their axis and the dither, as one Skia shader.
 *
 * `coord` arrives in the local space of whatever is being painted, so projecting it onto `axis`
 * gives the panel's own position along the gradient however large the panel is.
 *
 * The noise is interleaved gradient noise (Jimenez): one cheap `fract` chain that is well spread
 * spatially and has no visible tiling, unlike a repeated texture. Scaled to ±half a level and
 * added before the 8-bit write, it scatters each quantisation boundary across the pixels either
 * side of it, which is what turns a hard contour into a gradual change of mix. At a twelve-level
 * spread that is not polish — it is the only reason the spread can be this narrow.
 *
 * The two ends are mixed straight rather than perceptually: they sit close enough together that
 * the Oklab midpoint and the sRGB one differ by well under a level.
 */
private const val BACKDROP_SKSL = """
uniform float3 from;
uniform float3 to;
uniform float2 axis;
uniform float axisLengthSquared;

half4 main(float2 coord) {
    float t = clamp(dot(coord, axis) / axisLengthSquared, 0.0, 1.0);
    float3 c = mix(from, to, t);
    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715))));
    c += (n - 0.5) / 255.0;
    return half4(half3(c), 1.0);
}
"""

/** Compiled once: the SkSL is constant, and compiling it per panel would be pure waste. */
private val backdropEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(BACKDROP_SKSL) }

/** Panel size, in pixels along the gradient axis, at which the fill reaches full strength. */
private const val FULL_STRENGTH_EXTENT = 520f

/** Floor on the fill, so a short panel is still not quite flat. */
private const val MIN_STRENGTH = 0.35f

internal actual fun nuvioDitheredGradient(
    from: Color,
    to: Color,
    direction: NuvioBackdropDirection,
): Brush = DitheredGradient(from, to, direction)

private class DitheredGradient(
    private val from: Color,
    private val to: Color,
    private val direction: NuvioBackdropDirection,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        // Both axes start at the top-left, so a panel does not flip its gradient when it changes
        // shape.
        val axisX = if (direction == NuvioBackdropDirection.Diagonal) size.width else 0f
        val axisY = size.height
        val lengthSquared = (axisX * axisX + axisY * axisY).coerceAtLeast(1f)

        // The spread is chosen for a panel the size of a dialog. Run unchanged across something a
        // fifth of that it becomes a steep wash — and a column of short cards each covering the
        // full range reads as stripes, every card's dark end against the next card's light one.
        // Shrinking toward the midpoint keeps the same direction at a strength the element can
        // carry.
        val strength = (maxOf(axisX, axisY) / FULL_STRENGTH_EXTENT).coerceIn(MIN_STRENGTH, 1f)
        val mid = lerp(from, to, 0.5f)
        val start = lerp(mid, from, strength)
        val end = lerp(mid, to, strength)

        val builder = RuntimeShaderBuilder(backdropEffect)
        builder.uniform("from", start.red, start.green, start.blue)
        builder.uniform("to", end.red, end.green, end.blue)
        builder.uniform("axis", axisX, axisY)
        builder.uniform("axisLengthSquared", lengthSquared)
        return builder.makeShader().asComposeShader()
    }

    override fun equals(other: Any?): Boolean =
        other is DitheredGradient &&
            other.from == from &&
            other.to == to &&
            other.direction == direction

    override fun hashCode(): Int = (31 * from.hashCode() + to.hashCode()) * 31 + direction.hashCode()

    override fun toString(): String = "DitheredGradient(from=$from, to=$to, direction=$direction)"
}
