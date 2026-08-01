package com.nuvio.app.features.qualicache

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.quality_badge_4k_bluray
import nuvio.composeapp.generated.resources.quality_badge_4k_web
import nuvio.composeapp.generated.resources.quality_badge_atmos
import nuvio.composeapp.generated.resources.quality_badge_dts
import nuvio.composeapp.generated.resources.quality_badge_dv
import nuvio.composeapp.generated.resources.quality_badge_hdr
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The artwork for each [QualityHighlight].
 *
 * Every viewBox is cropped to its own ink (see `six-badge-set/` beside the sources), which is what
 * lets all six be drawn at one height with no per-badge fudging: box centre is ink centre, so a
 * plain `Row` centres them against each other correctly. Don't re-add artboard padding to a source
 * file — it comes straight back as a badge floating above the line.
 */
internal object QualityBadgeArt {

    /** Nominal height when the badges are set into the year/runtime line. */
    val INLINE_HEIGHT: Dp = 18.dp

    /**
     * Drops the badges onto the adjacent text's optical centre rather than the row's.
     *
     * A `Row` centres line boxes, and a font's ascent exceeds its descent, so text with no
     * descenders — `2026 • 38m` — inks slightly *above* the centre of its own box by about half the
     * descent. Negative therefore lifts the badges to match. Expressed against the row's nominal
     * height rather than each badge's, because it corrects for the text and must shift every badge
     * equally.
     *
     * Measured inside `NuvioTheme` against `labelLarge` as the theme defines it — JetBrains Sans,
     * with a lineHeight shorter than the font size. A harness using Compose's default font gives a
     * different (and wrong) answer; re-measure in the theme if the footer's type ever changes.
     */
    const val TEXT_ALIGN_FRACTION: Float = -0.0085f

    /**
     * Opacity the art is drawn at: the same as the hero synopsis, and deliberately not the slightly
     * dimmer value on the year/runtime line beside it. Matching the body copy is what keeps the
     * badges reading as part of the block rather than as a brighter strip laid over it.
     *
     * Alpha only, never a colour filter — every badge carries meaning in its colour now (the yellow
     * disc pill against the white stream one, black lettering inside both), so tinting would erase
     * the distinction the art exists to draw.
     */
    const val ALPHA: Float = 0.82f

    /**
     * Aspect ratios are of the cropped viewBox, printed by `_prepare.py`.
     *
     * All six are pills built to one 13.221-unit design height, so drawing them at a common height
     * makes them exactly as tall as each other — only the width differs, and only because the 4K
     * marks carry two characters where the rest carry a word. Nothing here is scaled per badge;
     * if a future badge needs to be, it is a sign the art, not the code, is off the grid.
     */
    private val artByHighlight: Map<QualityHighlight, QualityBadgeArtwork> = mapOf(
        QualityHighlight.FourKBluRay to
            QualityBadgeArtwork(Res.drawable.quality_badge_4k_bluray, 1.6162f),
        QualityHighlight.FourKWeb to
            QualityBadgeArtwork(Res.drawable.quality_badge_4k_web, 1.6162f),
        QualityHighlight.DolbyVision to
            QualityBadgeArtwork(Res.drawable.quality_badge_dv, 3.5966f),
        QualityHighlight.Hdr to
            QualityBadgeArtwork(Res.drawable.quality_badge_hdr, 3.5966f),
        QualityHighlight.DolbyAtmos to
            QualityBadgeArtwork(Res.drawable.quality_badge_atmos, 3.5966f),
        QualityHighlight.Dts to
            QualityBadgeArtwork(Res.drawable.quality_badge_dts, 3.5966f),
    )

    fun artworkFor(highlight: QualityHighlight): QualityBadgeArtwork = artByHighlight.getValue(highlight)
}

internal data class QualityBadgeArtwork(
    val resource: DrawableResource,
    /** Width divided by height of the cropped viewBox, used to pin the width — see [QualityBadgeImage]. */
    val aspectRatio: Float,
)

/**
 * Draws one badge at [nominalHeight].
 *
 * The width is pinned from the known aspect ratio rather than left to the painter's intrinsic size:
 * these rows sit above the ratings and the synopsis, so a width that settles a frame after layout
 * shoves both down.
 *
 * The art is all SVG, so `painterResource` lets Skia rasterise it at the size it is actually drawn —
 * sharp at every size and DPI, with no filter quality to get wrong. Were a bitmap ever added here it
 * would need the `ImageBitmap` overload and an explicit `FilterQuality.High`, because
 * `Image(painter = …)` has no filter-quality parameter and silently uses `Low`, which bilinear-samples
 * thin lettering into mush at these sizes.
 */
@Composable
internal fun QualityBadgeImage(
    highlight: QualityHighlight,
    nominalHeight: Dp,
    alignWithText: Boolean = false,
) {
    val artwork = QualityBadgeArt.artworkFor(highlight)
    val modifier = Modifier
        .height(nominalHeight)
        .width(nominalHeight * artwork.aspectRatio)
        .offset(
            y = if (alignWithText) nominalHeight * QualityBadgeArt.TEXT_ALIGN_FRACTION else 0.dp,
        )

    Image(
        painter = painterResource(artwork.resource),
        contentDescription = highlight.label,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = QualityBadgeArt.ALPHA,
    )
}
