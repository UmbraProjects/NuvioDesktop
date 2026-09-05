package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * The shallow gradient the house panels are filled with, in place of one flat colour.
 *
 * Two dark greys close enough together that the panel still reads as one surface — the point is
 * only that it stops reading as one flat colour. It is deliberately not a lighting effect: a lit
 * corner puts the foreground on a moving background, and text is what a panel is for.
 */

/**
 * Half the spread, in 8-bit levels: the lit end is this far above the panel colour and the shaded
 * end this far below, so the panel keeps its average brightness whatever the spread.
 *
 * Eight levels end to end. Earlier passes bracket this one — a ~5% sheen that was too faint to see
 * but banded anyway, then ~20 levels and then 12, both of which still put more distance between
 * the two ends than the panel wanted. Expressed in levels rather than as a fraction toward white
 * and black because that is the quantity that matters here: what the spread has to clear is the
 * 8-bit floor, not some proportion of the base colour.
 */
private const val BACKDROP_HALF_SPREAD = 4f

/**
 * Levels taken off the panel colour before the spread is applied, so the fill sits below the theme
 * token rather than straddling it.
 *
 * A spread centred on the base leaves the panel's average brightness exactly where it was, which
 * is tidy but reads as no change at all next to the page. Sinking the whole thing is what makes a
 * panel look like a panel, and it is also how the light end is brought down without flattening the
 * gradient: at four levels the lit corner lands back on the panel colour itself rather than above
 * it, so nothing on the panel sits on a lift.
 */
private const val BACKDROP_DARKEN = 4f

/** Which way the gradient runs across a panel. */
internal enum class NuvioBackdropDirection {
    /** Top-left to bottom-right: light end at the top-left corner, in the usual direction. */
    Diagonal,

    /** Straight down. Flatter, and the safer choice on a very wide, very short panel. */
    Vertical,
}

/**
 * The panel fill: a slightly lifted end, a slightly sunk end, dithered.
 *
 * Twelve levels across a panel puts a level boundary every fifty-odd pixels, which is squarely in
 * the range that reads as stripes — the first attempt at a gradient here was reverted for exactly
 * that. The dither is what makes a spread this narrow usable rather than merely invisible; see
 * [nuvioDitheredGradient].
 */
internal fun nuvioBackdropBrush(
    base: Color,
    direction: NuvioBackdropDirection = NuvioBackdropDirection.Diagonal,
): Brush = nuvioDitheredGradient(
    from = base.shiftedBy(BACKDROP_HALF_SPREAD - BACKDROP_DARKEN),
    to = base.shiftedBy(-BACKDROP_HALF_SPREAD - BACKDROP_DARKEN),
    direction = direction,
)

/** [levels] 8-bit levels brighter, or darker when negative. */
private fun Color.shiftedBy(levels: Float): Color = Color(
    red = (red + levels / 255f).coerceIn(0f, 1f),
    green = (green + levels / 255f).coerceIn(0f, 1f),
    blue = (blue + levels / 255f).coerceIn(0f, 1f),
    alpha = alpha,
)

/**
 * A two-stop ramp with a level of noise mixed in *before* the result is written out.
 *
 * That ordering is the whole point, and it is why this cannot be a plain [Brush.linearGradient]
 * with a noise texture drawn over it: by the time a second draw lands, the gradient has already
 * been rounded to 8 bits and the contour is baked in — adding noise on top leaves the step exactly
 * where it was and merely puts texture beside it. Dither has to perturb the value being rounded,
 * so it has to happen inside the shader that produces it.
 */
internal expect fun nuvioDitheredGradient(
    from: Color,
    to: Color,
    direction: NuvioBackdropDirection,
): Brush

/**
 * Fill a panel with [nuvioBackdropBrush] instead of a flat colour.
 *
 * For the many panels that are a Material `Surface`: pass `color = Color.Transparent` and hang
 * this off the surface's modifier with the same [shape], so the fill still clips to the corners.
 *
 * Meant for panels — dialogs, sheets, page cards. Small controls (chips, key caps, swatches) stay
 * flat: the fill scales itself down on a short element, but on a 30dp one there is nothing a
 * gradient can say.
 */
internal fun Modifier.nuvioPanelBackdrop(base: Color, shape: Shape = RectangleShape): Modifier =
    background(nuvioBackdropBrush(base), shape)
