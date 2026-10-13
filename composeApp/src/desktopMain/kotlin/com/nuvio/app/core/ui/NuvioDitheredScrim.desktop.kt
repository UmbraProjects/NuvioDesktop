package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
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
 *
 * `hdrFix` (see [DesktopHdrUiCompensation]) re-encodes the result as `sRGB_encode(x^2.2)` before
 * the dither, so Windows' sRGB decode in HDR mode shows what gamma 2.2 would have. The correction
 * moves the scrim colour too, and the page beyond the hero is not corrected, so the scrim's own
 * share of each pixel is shifted back by `scrim - f(scrim)`: a fully scrimmed pixel stays exactly
 * the scrim colour and meets the page without a seam. A black scrim maps to itself either way.
 */
private const val SCRIM_SKSL = """
uniform shader content;
uniform float2 size;
uniform float3 scrim;
uniform float hPos[6];
uniform float hAlpha[6];
uniform float vPos[6];
uniform float vAlpha[6];
uniform float hdrFix;

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

float3 srgbEncode(float3 l) {
    l = max(l, float3(0.0));
    float3 lo = l * 12.92;
    float3 hi = 1.055 * pow(l, float3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(float3(0.0031308), l));
}

float3 hdrCorrect(float3 x) {
    return srgbEncode(pow(max(x, float3(0.0)), float3(2.2)));
}

half4 main(float2 coord) {
    float4 c = float4(content.eval(coord));
    float ha = clamp(rampH(coord.x / size.x), 0.0, 1.0);
    float va = clamp(rampV(coord.y / size.y), 0.0, 1.0);
    float contentShare = c.a * (1.0 - ha) * (1.0 - va);
    c = float4(scrim * ha, ha) + c * (1.0 - ha);
    c = float4(scrim * va, va) + c * (1.0 - va);
    if (hdrFix > 0.5 && c.a > 0.0) {
        float scrimShare = clamp(1.0 - contentShare / c.a, 0.0, 1.0);
        float3 y = hdrCorrect(c.rgb / c.a) + (scrim - hdrCorrect(scrim)) * scrimShare;
        c.rgb = clamp(y, 0.0, 1.0) * c.a;
    }
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
    // The layer block re-runs on every placement; the filter only changes with the size and HDR.
    val cache = ScrimEffectCache(scrim, horizontal, vertical)
    return graphicsLayer {
        // A snapshot read: an HDR switch re-runs this block and redraws the layer, nothing more.
        val hdrFix = DesktopHdrUiCompensation.active
        renderEffect = cache.effectFor(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f), hdrFix)
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
    private var hdrFix = false
    private var effect: RenderEffect? = null

    fun effectFor(width: Float, height: Float, hdrFix: Boolean): RenderEffect {
        effect?.let { if (this.width == width && this.height == height && this.hdrFix == hdrFix) return it }
        val (hPos, hAlpha) = horizontal.padded()
        val (vPos, vAlpha) = vertical.padded()
        val builder = RuntimeShaderBuilder(scrimEffect)
        builder.uniform("size", width, height)
        builder.uniform("scrim", scrim.red, scrim.green, scrim.blue)
        builder.uniform("hPos", hPos)
        builder.uniform("hAlpha", hAlpha)
        builder.uniform("vPos", vPos)
        builder.uniform("vAlpha", vAlpha)
        builder.uniform("hdrFix", if (hdrFix) 1f else 0f)
        val built = ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
        this.width = width
        this.height = height
        this.hdrFix = hdrFix
        effect = built
        return built
    }
}

/**
 * [nuvioHdrFadeCorrection]'s shader: the scrim's `sRGB_encode(x^2.2)` correction, per channel.
 * Over a black `base` it is exactly that. Over any other base it eases in: identity at and below
 * `base`, so the background meets the uncorrected page unchanged, reaching full correction by four
 * times its level. (Anchoring the curve itself on `base` was tried and measured: it cancels the
 * correction near `base` and even lifts it.) Dithered, because the correction squeezes the darkest
 * levels together and would otherwise band them.
 */
private const val HDR_FADE_SKSL = """
uniform shader content;
uniform float3 base;

float3 srgbEncode(float3 l) {
    l = max(l, float3(0.0));
    float3 lo = l * 12.92;
    float3 hi = 1.055 * pow(l, float3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(float3(0.0031308), l));
}

float3 hdrCorrect(float3 x) {
    return srgbEncode(pow(max(x, float3(0.0)), float3(2.2)));
}

half4 main(float2 coord) {
    float4 c = float4(content.eval(coord));
    if (c.a <= 0.0) return half4(c);
    float3 x = clamp(c.rgb / c.a, 0.0, 1.0);
    float3 fx = hdrCorrect(x);
    float3 w = float3(
        base.r > 0.0 ? smoothstep(base.r, base.r * 4.0, x.r) : 1.0,
        base.g > 0.0 ? smoothstep(base.g, base.g * 4.0, x.g) : 1.0,
        base.b > 0.0 ? smoothstep(base.b, base.b * 4.0, x.b) : 1.0
    );
    float3 y = mix(x, fx, w);
    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715))));
    y += (n - 0.5) / 255.0;
    return half4(float4(clamp(y, 0.0, 1.0) * c.a, c.a));
}
"""

private val hdrFadeEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(HDR_FADE_SKSL) }

internal actual fun Modifier.nuvioHdrFadeCorrection(background: Color): Modifier =
    graphicsLayer {
        // A snapshot read: an HDR switch re-runs this block only. SDR gets no effect at all.
        renderEffect = if (DesktopHdrUiCompensation.active) hdrFadeEffectFor(background) else null
        clip = true
    }

/** One filter per background colour; the hero recomposes far more often than its colour changes. */
private val hdrFadeEffects = HashMap<Color, RenderEffect>()

private fun hdrFadeEffectFor(background: Color): RenderEffect = hdrFadeEffects.getOrPut(background) {
    if (hdrFadeEffects.size >= 16) hdrFadeEffects.clear()
    val builder = RuntimeShaderBuilder(hdrFadeEffect)
    builder.uniform("base", background.red, background.green, background.blue)
    ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
}

/**
 * [nuvioDitheredVerticalGradient]'s shader. Premultiplied stops, padded by repeating the last as
 * the scrim ramps are. The noise is ±half a level of output, so it is not scaled by alpha (over
 * black the output *is* the premultiplied colour), only clamped to keep the colour premultiplied.
 */
private const val VERTICAL_GRADIENT_SKSL = """
uniform float height;
uniform float pos[6];
uniform float r[6];
uniform float g[6];
uniform float b[6];
uniform float a[6];

half4 main(float2 coord) {
    float t = coord.y / height;
    float4 c = float4(r[0], g[0], b[0], a[0]);
    if (t > pos[0]) {
        c = float4(r[5], g[5], b[5], a[5]);
        for (int i = 0; i < 5; ++i) {
            if (t >= pos[i] && t < pos[i + 1]) {
                float span = max(pos[i + 1] - pos[i], 0.0001);
                c = mix(float4(r[i], g[i], b[i], a[i]), float4(r[i + 1], g[i + 1], b[i + 1], a[i + 1]),
                    (t - pos[i]) / span);
            }
        }
    }
    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715))));
    c.rgb = clamp(c.rgb + (n - 0.5) / 255.0, 0.0, c.a);
    return half4(c);
}
"""

private val verticalGradientEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(VERTICAL_GRADIENT_SKSL) }

internal actual fun nuvioDitheredVerticalGradient(colorStops: Array<Pair<Float, Color>>): Brush =
    DitheredVerticalGradient(colorStops.toList())

private class DitheredVerticalGradient(private val stops: List<Pair<Float, Color>>) : ShaderBrush() {
    init {
        require(stops.isNotEmpty() && stops.size <= NuvioScrimRamp.MAX_STOPS) {
            "dithered gradients take 1..${NuvioScrimRamp.MAX_STOPS} stops"
        }
    }

    override fun createShader(size: Size): Shader {
        val n = NuvioScrimRamp.MAX_STOPS
        val padded = List(n) { i -> stops.getOrElse(i) { stops.last() } }
        val builder = RuntimeShaderBuilder(verticalGradientEffect)
        builder.uniform("height", size.height.coerceAtLeast(1f))
        builder.uniform("pos", FloatArray(n) { padded[it].first })
        builder.uniform("r", FloatArray(n) { padded[it].second.run { red * alpha } })
        builder.uniform("g", FloatArray(n) { padded[it].second.run { green * alpha } })
        builder.uniform("b", FloatArray(n) { padded[it].second.run { blue * alpha } })
        builder.uniform("a", FloatArray(n) { padded[it].second.alpha })
        return builder.makeShader().asComposeShader()
    }

    override fun equals(other: Any?): Boolean = other is DitheredVerticalGradient && other.stops == stops

    override fun hashCode(): Int = stops.hashCode()

    override fun toString(): String = "DitheredVerticalGradient($stops)"
}
