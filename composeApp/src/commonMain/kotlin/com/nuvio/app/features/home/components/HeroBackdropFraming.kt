package com.nuvio.app.features.home.components

import coil3.Image
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What automatic framing learned about one backdrop, independent of the hero it is shown in.
 *
 * The crop itself is not stored: the visible slice depends on the hero's current aspect (window
 * size, the Hero height slider), so [heroBackdropFramingBias] picks it on demand from this.
 *
 * [subjectMass] and [edgeCut] are per-row profiles, top to bottom, over the whole image height.
 * Both weight the left of the frame down, because that side sits under the hero's fade and its
 * logo/metadata column.
 */
internal class HeroBackdropFramingProfile(
    /** Width over height of the analysed image. */
    val imageAspect: Float,
    /** Share of the subject in each row; sums to 1. */
    val subjectMass: FloatArray,
    /** How much subject a horizontal crop edge at each row would slice through, 0..1. */
    val edgeCut: FloatArray,
    val faces: List<HeroBackdropFace>,
)

/** A detected face, as fractions of the image's width and height. */
internal class HeroBackdropFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Saliency + face analysis of hero backdrops, cached by artwork url.
 *
 * Desktop-only work (ONNX Runtime); see the actual for the models and the cache.
 */
internal expect object HeroBackdropFramer {
    /** An already-known profile, from memory only. Safe to call during composition. */
    fun peek(key: String): HeroBackdropFramingProfile?

    /**
     * The profile for [key]: memory, then disk, then — when [image] is given — a fresh analysis
     * of it. Null when the backdrop cannot be analysed (no image yet, or the models failed to
     * load); callers fall back to the manual bias.
     */
    suspend fun profile(key: String, image: Image?): HeroBackdropFramingProfile?
}

/**
 * The vertical [androidx.compose.ui.BiasAlignment] bias that frames [profile] in a hero of
 * [heroAspect] (width over height), cropped with `ContentScale.Crop`.
 *
 * Scores every window position and keeps the best. In order of weight:
 * - subject inside the window, minus subject cut off above it (heads) and, at half weight,
 *   below it (legs);
 * - a window edge slicing through a subject, much worse at the top edge (hats, helmets, heads)
 *   than at the bottom;
 * - each face kept whole with a little headroom; a face sliced by the edge scores worse than
 *   one left out entirely, and up to a face-height of clear space above it is wanted for the
 *   hat or hair the saliency map tends to miss — capped, so close-ups don't chase empty sky;
 * - a mild pull toward [priorBias], so a backdrop with nothing to say stays where the manual
 *   setting would have put it.
 *
 * Tuned on a 40-backdrop bench; the weights are deliberately few. It fits as many people as it
 * can and cannot know which one matters most, so an ensemble shot may lose a lone figure at the
 * top in favour of a row of faces below.
 */
internal fun heroBackdropFramingBias(
    profile: HeroBackdropFramingProfile,
    heroAspect: Float,
    priorBias: Float,
): Float {
    if (heroAspect <= 0f || profile.imageAspect <= 0f) return priorBias
    val visible = profile.imageAspect / heroAspect
    // Nothing is cropped vertically, so there is no position to choose.
    if (visible >= 1f) return priorBias
    val slack = 1f - visible
    val rows = profile.subjectMass.size
    if (rows == 0) return priorBias
    val cumulative = FloatArray(rows + 1)
    for (row in 0 until rows) cumulative[row + 1] = cumulative[row] + profile.subjectMass[row]
    val total = cumulative[rows]

    val priorTop = (1f + priorBias) / 2f * slack
    var bestTop = priorTop
    var bestScore = Float.NEGATIVE_INFINITY
    for (step in 0..FramingSteps) {
        val top = slack * step / FramingSteps
        val bottom = top + visible
        val topRow = (top * rows).roundToInt().coerceIn(0, rows)
        val bottomRow = (bottom * rows).roundToInt().coerceIn(0, rows)
        val above = cumulative[topRow]
        val inside = cumulative[bottomRow] - cumulative[topRow]
        val below = total - cumulative[bottomRow]
        var score = inside - above - 0.5f * below
        if (topRow > 0) score -= TopEdgeCutWeight * profile.edgeCut[topRow.coerceAtMost(rows - 1)]
        if (bottomRow < rows) score -= BottomEdgeCutWeight * profile.edgeCut[bottomRow]
        for (face in profile.faces) {
            val faceHeight = face.bottom - face.top
            if (faceHeight <= 0f) continue
            val centerX = (face.left + face.right) / 2f
            // Faces under the fade, and tiny background faces, count for less.
            val weight = (if (centerX < 0.3f) 0.4f else 1f) * min(1f, faceHeight / 0.08f)
            val headTop = face.top - 0.35f * faceHeight
            val headBottom = face.bottom + 0.1f * faceHeight
            val shown = (min(headBottom, bottom) - maxOf(headTop, top)).coerceAtLeast(0f) /
                (headBottom - headTop)
            score += FaceWeight * weight * (shown - 2f * min(shown, 1f - shown))
            val room = min(faceHeight, FaceHeadroomCap)
            val crown = face.top - room
            if (top > crown) score -= HeadroomWeight * weight * min(1f, (top - crown) / room)
        }
        score -= PriorPull * abs(top - priorTop)
        if (score > bestScore) {
            bestScore = score
            bestTop = top
        }
    }
    return (2f * bestTop / slack - 1f).coerceIn(-1f, 1f)
}

private const val FramingSteps = 100
private const val TopEdgeCutWeight = 0.6f
private const val BottomEdgeCutWeight = 0.1f
private const val FaceWeight = 0.6f
private const val HeadroomWeight = 0.5f
private const val FaceHeadroomCap = 0.07f
private const val PriorPull = 0.15f
