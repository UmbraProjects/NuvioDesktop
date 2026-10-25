// Chroma realignment for mpv / libplacebo (vo=gpu-next), run first in the shader chain while RTX
// Video True HDR feeds the shaders.
//
// With True HDR on, NVIDIA's video processor expands the 4:2:0 chroma and converts to RGB before
// mpv sees the frame, so mpv's cscale never runs. That chroma is softer than the luma and can sit
// slightly off it, and once Anime4K sharpens and upscales the luma it shows as a thin colour fringe
// along lines. This pass rebuilds chroma from luma (chroma-from-luma): in a 5x5 window each colour
// difference is fitted as a straight line against luma, and the fit is read back at the pixel's own
// luma, so chroma edges land where the luma edges are.
//
// The fit only replaces chroma where chroma actually follows luma (r^2 of the fit). Where it does
// not, such as two colours of equal brightness meeting, the original chroma is kept.
//
// Works on the frame as it arrives (PQ-encoded BT.2020 RGB), with BT.2020 non-constant-luminance
// weights, which is how BT.2020 YCbCr is defined on encoded values.

//!HOOK MAIN
//!BIND HOOKED
//!DESC Nuvio chroma realign

#define NUVIO_RADIUS 2
#define NUVIO_LUMA vec3(0.2627, 0.6780, 0.0593)

vec4 hook() {
    vec4 centre = HOOKED_texOff(vec2(0.0));
    float yc = dot(centre.rgb, NUVIO_LUMA);

    // Colour differences: (R - Y, B - Y). G follows from Y.
    float n = 0.0;
    float sy = 0.0;
    float syy = 0.0;
    vec2 sc = vec2(0.0);
    vec2 scc = vec2(0.0);
    vec2 syc = vec2(0.0);
    for (int dy = -NUVIO_RADIUS; dy <= NUVIO_RADIUS; dy++) {
        for (int dx = -NUVIO_RADIUS; dx <= NUVIO_RADIUS; dx++) {
            vec3 rgb = HOOKED_texOff(vec2(float(dx), float(dy))).rgb;
            float y = dot(rgb, NUVIO_LUMA);
            vec2 c = vec2(rgb.r - y, rgb.b - y);
            n += 1.0;
            sy += y;
            syy += y * y;
            sc += c;
            scc += c * c;
            syc += y * c;
        }
    }
    float my = sy / n;
    vec2 mc = sc / n;
    float vy = max(syy / n - my * my, 0.0);
    vec2 vc = max(scc / n - mc * mc, vec2(0.0));
    vec2 cov = syc / n - my * mc;

    // Flat luma: nothing to align to.
    const float eps = 1e-6;
    vec2 slope = cov / (vy + eps);
    vec2 predicted = mc + slope * (yc - my);
    vec2 fit = clamp((cov * cov) / (vy * vc + eps), 0.0, 1.0);

    vec2 original = vec2(centre.r - yc, centre.b - yc);
    vec2 c = mix(original, predicted, fit);

    float r = yc + c.x;
    float b = yc + c.y;
    float g = (yc - NUVIO_LUMA.r * r - NUVIO_LUMA.b * b) / NUVIO_LUMA.g;
    return vec4(clamp(vec3(r, g, b), 0.0, 1.0), centre.a);
}
