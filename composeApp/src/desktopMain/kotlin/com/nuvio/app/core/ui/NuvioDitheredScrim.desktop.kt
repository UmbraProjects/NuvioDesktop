package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Both ramps, the scrim colour and the dither, as one Skia runtime shader over the layer.
 *
 * `content` is the element's own rendering, premultiplied; `coord` is in the layer's pixel space,
 * so dividing by `size` gives the position along each axis. Each ramp is a piecewise-linear alpha
 * with up to [NuvioScrimRamp.MAX_STOPS] stops, padded by repeating the last one — the loop bound
 * has to be a constant for the effect to compile, and a repeated stop spans nothing. The scrim is
 * composited source-over in premultiplied form, once per ramp, and the noise (interleaved
 * gradient noise, as in NuvioSurfaceBackdrop.desktop.kt) is scaled by the result's alpha so a
 * transparent pixel stays a valid premultiplied transparent.
 */
private const val SCRIM_SKSL = """
uniform shader content;
uniform float2 size;
uniform float3 scrim;
uniform float hPos[6];
uniform float hAlpha[6];
uniform float vPos[6];
uniform float vAlpha[6];

float rampH(float t) {
    float a = hAlpha[5];
    if (t <= hPos[0]) return hAlpha[0];
    for (int i = 0; i < 5; ++i) {
        if (t >= hPos[i] && t < hPos[i + 1]) {
            float span = max(hPos[i + 1] - hPos[i], 0.0001);
            a = mix(hAlpha[i], hAlpha[i + 1], (t - hPos[i]) / span);
        }
    }
    return a;
}

float rampV(float t) {
    float a = vAlpha[5];
    if (t <= vPos[0]) return vAlpha[0];
    for (int i = 0; i < 5; ++i) {
        if (t >= vPos[i] && t < vPos[i + 1]) {
            float span = max(vPos[i + 1] - vPos[i], 0.0001);
            a = mix(vAlpha[i], vAlpha[i + 1], (t - vPos[i]) / span);
        }
    }
    return a;
}

half4 main(float2 coord) {
    float4 c = float4(content.eval(coord));
    float ha = clamp(rampH(coord.x / size.x), 0.0, 1.0);
    float va = clamp(rampV(coord.y / size.y), 0.0, 1.0);
    c = float4(scrim * ha, ha) + c * (1.0 - ha);
    c = float4(scrim * va, va) + c * (1.0 - va);
    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715))));
    c.rgb += ((n - 0.5) / 255.0) * c.a;
    return half4(c);
}
"""

private val scrimEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(SCRIM_SKSL) }

private fun NuvioScrimRamp.padded(): Pair<FloatArray, FloatArray> {
    val last = stops.last()
    val positions = FloatArray(NuvioScrimRamp.MAX_STOPS) { i -> stops.getOrNull(i)?.first ?: last.first }
    val alphas = FloatArray(NuvioScrimRamp.MAX_STOPS) { i -> stops.getOrNull(i)?.second ?: last.second }
    return positions to alphas
}

internal actual fun Modifier.nuvioDitheredScrim(
    scrim: Color,
    horizontal: NuvioScrimRamp,
    vertical: NuvioScrimRamp,
): Modifier {
    // The layer block re-runs on every placement; the filter only changes with the size.
    val cache = ScrimEffectCache(scrim, horizontal, vertical)
    return graphicsLayer {
        renderEffect = cache.effectFor(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        clip = true
    }
}

private class ScrimEffectCache(
    private val scrim: Color,
    private val horizontal: NuvioScrimRamp,
    private val vertical: NuvioScrimRamp,
) {
    private var width = -1f
    private var height = -1f
    private var effect: RenderEffect? = null

    fun effectFor(width: Float, height: Float): RenderEffect {
        effect?.let { if (this.width == width && this.height == height) return it }
        val (hPos, hAlpha) = horizontal.padded()
        val (vPos, vAlpha) = vertical.padded()
        val builder = RuntimeShaderBuilder(scrimEffect)
        builder.uniform("size", width, height)
        builder.uniform("scrim", scrim.red, scrim.green, scrim.blue)
        builder.uniform("hPos", hPos)
        builder.uniform("hAlpha", hAlpha)
        builder.uniform("vPos", vPos)
        builder.uniform("vAlpha", vAlpha)
        val built = ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
        this.width = width
        this.height = height
        effect = built
        return built
    }
}
