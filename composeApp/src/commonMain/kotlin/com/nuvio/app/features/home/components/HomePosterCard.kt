package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.PosterRatingBadgeScale
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.randomPlayCategoryOrNull
import kotlin.math.roundToInt

@Composable
fun HomePosterCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    useLandscapeBackdropMode: Boolean = false,
    basePosterWidthDpOverride: Int? = null,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val isLandscapeMode = useLandscapeBackdropMode || posterCardStyle.catalogLandscapeModeEnabled
    val titleOverlay = landscapeCardTitleOverlay(
        isLandscapeMode = isLandscapeMode,
        useTextTitle = posterCardStyle.landscapeTextTitlesEnabled,
        hideLabels = posterCardStyle.hideLabelsEnabled,
        title = item.name,
        logoUrl = item.logo,
        artIncludesTitle = isLandscapeMode && !item.landscapePoster.isNullOrBlank(),
    )
    val artwork = posterCardArtwork(
        isLandscapeMode = isLandscapeMode,
        posterUrl = item.poster,
        posterFallbackUrl = item.posterFallback,
        backdropUrl = item.banner,
        landscapePosterUrl = item.landscapePoster,
    )
    val isRandomPlayCard = item.randomPlayCategoryOrNull() != null
    val ratingBadgeText = if (isLandscapeMode && !isRandomPlayCard) {
        posterRatingBadgeText(
            rawRating = item.imdbRating,
            scale = posterCardStyle.landscapeRatingBadgeScale,
        )
    } else {
        null
    }

    // Plan §5/§18: an AI row's per-item reason is the whole reason that row exists, and until now
    // it was visible only inside the settings editor. The shelf renders a bare list of MetaPreview
    // and has no card-level slot to print it in, so it goes on hover — where a sentence has room to
    // be a sentence, and where it costs nothing on the rows that carry no reason at all. Wrapping
    // the artwork rather than the label is deliberate: the label already owns the truncated-title
    // tooltip, and a card with labels hidden would otherwise have nowhere to show this.
    // The hover preview sits outside the tooltip so a card can carry both: the preview is the
    // desktop affordance for the card as a whole, the tooltip is the AI row's per-item reason.
    HomePosterHoverPreview(
        item = item,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
    ) { hoverModifier ->
        NuvioPosterHoverTooltip(title = item.recommendationReason.orEmpty()) {
            NuvioPosterCard(
                title = item.name,
                imageUrl = artwork.imageUrl.takeUnless { isRandomPlayCard },
                fallbackImageUrl = artwork.fallbackImageUrl,
                modifier = modifier.then(hoverModifier),
                shape = if (isLandscapeMode) NuvioPosterShape.Landscape else item.posterShape.toNuvioPosterShape(),
                basePosterWidthDpOverride = basePosterWidthDpOverride,
                detailLine = if (isLandscapeMode || posterCardStyle.hideLabelsEnabled) null else item.releaseInfo?.let { formatReleaseDateForDisplay(it) },
                showTitleBelow = !posterCardStyle.hideLabelsEnabled,
                bottomLeftLogoUrl = titleOverlay.logoUrl,
                bottomLeftText = titleOverlay.text,
                ratingBadgeText = ratingBadgeText,
                artworkContent = if (isRandomPlayCard) {
                    {
                        RandomPlayPosterCollage(
                            title = item.name,
                            posterUrls = item.posterCollage,
                        )
                    }
                } else {
                    null
                },
                isWatched = isWatched,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
    }
}

/**
 * Formats a catalog row's rating for the corner badge, or returns null when there is nothing worth
 * showing.
 *
 * The rating rides along with the catalog page — Cinemeta-style addons put `imdbRating` in every
 * meta, the synced library caches `imdb_rating`, and the TMDB-backed rows carry TMDB's vote average
 * in the same field — so a badge never costs a request and never appears late. Rows that supply no
 * rating (local library, filename-resolved cloud library, most Continue Watching entries) simply
 * get no badge.
 *
 * Both scales round the same tenth, so a title reads as either 8.3 or 83 and never disagrees with
 * itself. Anything outside 0–10 is rejected rather than clamped: a value off that scale means the
 * field holds something this badge cannot honestly label.
 */
internal fun posterRatingBadgeText(
    rawRating: String?,
    scale: PosterRatingBadgeScale,
): String? {
    if (scale == PosterRatingBadgeScale.Off) return null
    val value = rawRating?.trim()?.toDoubleOrNull() ?: return null
    if (value.isNaN() || value <= 0.0 || value > 10.0) return null
    val tenths = (value * 10).roundToInt()
    if (tenths <= 0) return null
    return when (scale) {
        // 0-100 needs no separator, and a perfect score is the only three-digit result.
        PosterRatingBadgeScale.OutOfHundred -> tenths.toString()
        // A perfect 10 drops its decimal; the badge's fixed value width keeps the row aligned.
        else -> if (tenths >= 100) "10" else "${tenths / 10}.${tenths % 10}"
    }
}

internal data class PosterCardArtwork(
    val imageUrl: String?,
    val fallbackImageUrl: String?,
)

/**
 * Landscape cards must not fall back to a custom portrait-poster provider. Library mapping keeps
 * the original poster in [posterFallbackUrl] whenever [posterUrl] was replaced by such a provider,
 * so prefer that original after the real backdrop. Portrait cards retain the configured provider.
 *
 * [landscapePosterUrl] wins over the backdrop when an addon supplied one: it is art cut for this
 * shape rather than a still cropped into it. It stays a preference and not a requirement — a title
 * the addon has no landscape art for degrades to the backdrop instead of leaving a gap.
 */
internal fun posterCardArtwork(
    isLandscapeMode: Boolean,
    posterUrl: String?,
    posterFallbackUrl: String?,
    backdropUrl: String?,
    landscapePosterUrl: String? = null,
): PosterCardArtwork {
    if (!isLandscapeMode) {
        return PosterCardArtwork(
            imageUrl = posterUrl,
            fallbackImageUrl = posterFallbackUrl,
        )
    }

    if (!landscapePosterUrl.isNullOrBlank()) {
        return PosterCardArtwork(
            imageUrl = landscapePosterUrl,
            fallbackImageUrl = backdropUrl?.takeIf { it.isNotBlank() }
                ?: posterFallbackUrl
                ?: posterUrl,
        )
    }

    return if (!backdropUrl.isNullOrBlank()) {
        PosterCardArtwork(
            imageUrl = backdropUrl,
            fallbackImageUrl = posterFallbackUrl ?: posterUrl,
        )
    } else {
        PosterCardArtwork(
            imageUrl = posterFallbackUrl ?: posterUrl,
            fallbackImageUrl = null,
        )
    }
}

internal data class LandscapeCardTitleOverlay(
    val logoUrl: String? = null,
    val text: String? = null,
)

/**
 * [artIncludesTitle] outranks every preference below it: an addon-supplied landscape poster has the
 * title composited into the art, so drawing a logo — or the text title the user asked for — would
 * print the name of the title twice over itself. The label below the card is left alone; it sits
 * outside the art and keeps a landscape row aligned with its portrait neighbours.
 */
internal fun landscapeCardTitleOverlay(
    isLandscapeMode: Boolean,
    useTextTitle: Boolean,
    hideLabels: Boolean,
    title: String,
    logoUrl: String?,
    artIncludesTitle: Boolean = false,
): LandscapeCardTitleOverlay {
    if (!isLandscapeMode) return LandscapeCardTitleOverlay()
    if (artIncludesTitle) return LandscapeCardTitleOverlay()
    if (useTextTitle) return LandscapeCardTitleOverlay(text = title)
    if (!logoUrl.isNullOrBlank()) return LandscapeCardTitleOverlay(logoUrl = logoUrl)
    return LandscapeCardTitleOverlay(text = title.takeUnless { hideLabels })
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
