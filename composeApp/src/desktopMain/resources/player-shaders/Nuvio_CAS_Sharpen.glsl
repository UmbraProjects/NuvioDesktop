// Contrast Adaptive Sharpening (non-scaling path) for mpv / libplacebo (vo=gpu-next).
//
// mpv's own --sharpen is "Only for --vo=gpu" and does nothing on gpu-next, so the player's
// Sharpen menu runs this instead. It hooks LUMA, which libplacebo runs before any MAIN-stage
// shader (Anime4K etc.) regardless of chain order, so it can be appended to any chain.
// Strength comes from --glsl-shader-opts=nuvio_cas_sharpness=<0..1>.
//
// Algorithm: AMD FidelityFX CAS (ffx_cas.h).
// Copyright (c) 2020 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without
// restriction, including without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or
// substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

//!PARAM nuvio_cas_sharpness
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.5

//!HOOK LUMA
//!BIND HOOKED
//!DESC Nuvio CAS sharpen

vec4 hook() {
    // 3x3 neighbourhood:  a b c / d e f / g h i
    float a = HOOKED_texOff(vec2(-1.0, -1.0)).x;
    float b = HOOKED_texOff(vec2( 0.0, -1.0)).x;
    float c = HOOKED_texOff(vec2( 1.0, -1.0)).x;
    float d = HOOKED_texOff(vec2(-1.0,  0.0)).x;
    vec4  centre = HOOKED_texOff(vec2(0.0, 0.0));
    float e = centre.x;
    float f = HOOKED_texOff(vec2( 1.0,  0.0)).x;
    float g = HOOKED_texOff(vec2(-1.0,  1.0)).x;
    float h = HOOKED_texOff(vec2( 0.0,  1.0)).x;
    float i = HOOKED_texOff(vec2( 1.0,  1.0)).x;

    // Soft min/max: the cross plus the full 3x3, so the range is doubled (hence 2.0 below).
    float mnCross = min(min(min(d, e), min(f, b)), h);
    float mxCross = max(max(max(d, e), max(f, b)), h);
    float mn = mnCross + min(mnCross, min(min(a, c), min(g, i)));
    float mx = mxCross + max(mxCross, max(max(a, c), max(g, i)));

    // Less sharpening where the local contrast is already high, which is what keeps CAS from
    // haloing the way a plain unsharp mask does.
    float amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, 1e-5), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, nuvio_cas_sharpness);
    float w = amp * peak;

    centre.x = clamp((w * (b + d + f + h) + e) / (1.0 + 4.0 * w), 0.0, 1.0);
    return centre;
}
