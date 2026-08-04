package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.randomPlayCategoryOrNull

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
    )
    val artwork = posterCardArtwork(
        isLandscapeMode = isLandscapeMode,
        posterUrl = item.poster,
        posterFallbackUrl = item.posterFallback,
        backdropUrl = item.banner,
    )
    val isRandomPlayCard = item.randomPlayCategoryOrNull() != null

    NuvioPosterCard(
        title = item.name,
        imageUrl = artwork.imageUrl.takeUnless { isRandomPlayCard },
        fallbackImageUrl = artwork.fallbackImageUrl,
        modifier = modifier,
        shape = if (isLandscapeMode) NuvioPosterShape.Landscape else item.posterShape.toNuvioPosterShape(),
        basePosterWidthDpOverride = basePosterWidthDpOverride,
        detailLine = if (isLandscapeMode || posterCardStyle.hideLabelsEnabled) null else item.releaseInfo?.let { formatReleaseDateForDisplay(it) },
        showTitleBelow = !posterCardStyle.hideLabelsEnabled,
        bottomLeftLogoUrl = titleOverlay.logoUrl,
        bottomLeftText = titleOverlay.text,
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

internal data class PosterCardArtwork(
    val imageUrl: String?,
    val fallbackImageUrl: String?,
)

/**
 * Landscape cards must not fall back to a custom portrait-poster provider. Library mapping keeps
 * the original poster in [posterFallbackUrl] whenever [posterUrl] was replaced by such a provider,
 * so prefer that original after the real backdrop. Portrait cards retain the configured provider.
 */
internal fun posterCardArtwork(
    isLandscapeMode: Boolean,
    posterUrl: String?,
    posterFallbackUrl: String?,
    backdropUrl: String?,
): PosterCardArtwork {
    if (!isLandscapeMode) {
        return PosterCardArtwork(
            imageUrl = posterUrl,
            fallbackImageUrl = posterFallbackUrl,
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

internal fun landscapeCardTitleOverlay(
    isLandscapeMode: Boolean,
    useTextTitle: Boolean,
    hideLabels: Boolean,
    title: String,
    logoUrl: String?,
): LandscapeCardTitleOverlay {
    if (!isLandscapeMode) return LandscapeCardTitleOverlay()
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
