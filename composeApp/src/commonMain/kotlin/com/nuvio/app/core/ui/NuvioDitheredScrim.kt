package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A scrim ramp: alpha of the scrim colour at each fraction of the axis, in ascending position
 * order. Between stops the alpha is linear, exactly as a `Brush.linearGradient` of the same
 * colour at those alphas would be.
 */
internal data class NuvioScrimRamp(val stops: List<Pair<Float, Float>>) {
    init {
        require(stops.isNotEmpty() && stops.size <= MAX_STOPS) { "scrim ramps take 1..$MAX_STOPS stops" }
    }

    companion object {
        const val MAX_STOPS = 6

        /** No darkening along this axis. */
        val None = NuvioScrimRamp(listOf(0f to 0f, 1f to 0f))
    }
}

/**
 * Darkens this element's own content with a horizontal and a vertical scrim, the two composited
 * over the content in floating point and rounded to 8 bits once, with a level of noise mixed in
 * before the write.
 *
 * The obvious way to draw a scrim is a translucent gradient over the artwork, and over most
 * pictures that is fine. Over a dark one it is not: an alpha ramp over a backdrop sitting at ten
 * levels above black has only ten output levels to move through across the whole width, so every
 * one of them lands as a visible stripe a hundred-odd pixels wide. No dither on the gradient
 * itself can help — the step is in the *product* of the ramp and the picture, which a separate
 * gradient draw never sees. So this is a render effect rather than a brush: the content is the
 * shader's input, the ramps are applied to it, and the dither perturbs the value that is actually
 * about to be rounded. See [nuvioDitheredGradient] for the same argument at panel scale.
 *
 * [scrim] is the colour the ramps fade toward; a ramp alpha of 1 replaces the content with it.
 * The horizontal ramp runs left→right and is applied first, the vertical ramp top→bottom over the
 * result, matching two stacked gradient boxes in that order.
 */
internal expect fun Modifier.nuvioDitheredScrim(
    scrim: Color,
    horizontal: NuvioScrimRamp,
    vertical: NuvioScrimRamp,
): Modifier
