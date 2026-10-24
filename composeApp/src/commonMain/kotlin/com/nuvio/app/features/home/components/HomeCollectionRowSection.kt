package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.nuvioArtworkRequestSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalNuvioShelfItemHighlighted
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvioPosterHighlight
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.PosterShape

@Composable
fun HomeCollectionRowSection(
    collection: Collection,
    modifier: Modifier = Modifier,
    sectionPadding: Dp? = null,
    basePosterWidthDpOverride: Int? = null,
    animateGifs: Boolean = true,
    focusedItemIndex: Int? = null,
    rowState: LazyListState? = null,
    onHoverItem: ((Int) -> Unit)? = null,
    isKeyboardNavigation: Boolean = false,
    rowNumber: Int? = null,
    // TV Mode's row-jump dots, rendered on the header line next to the title. Null everywhere else.
    headerTrailingContent: (@Composable () -> Unit)? = null,
    bodyModifier: Modifier = Modifier,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
) {
    if (collection.folders.isEmpty()) return
    val effectiveRowState = rowState ?: rememberLazyListState()
    PrefetchCollectionArtwork(collection, animateGifs)

    if (sectionPadding != null) {
        HomeCollectionRowSectionContent(
            collection = collection,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            basePosterWidthDpOverride = basePosterWidthDpOverride,
            animateGifs = animateGifs,
            focusedItemIndex = focusedItemIndex,
            rowState = effectiveRowState,
            onHoverItem = onHoverItem,
            isKeyboardNavigation = isKeyboardNavigation,
            rowNumber = rowNumber,
            headerTrailingContent = headerTrailingContent,
            bodyModifier = bodyModifier,
            onFolderClick = onFolderClick,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCollectionRowSectionContent(
                collection = collection,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                basePosterWidthDpOverride = basePosterWidthDpOverride,
                animateGifs = animateGifs,
                focusedItemIndex = focusedItemIndex,
                rowState = effectiveRowState,
                onHoverItem = onHoverItem,
                isKeyboardNavigation = isKeyboardNavigation,
                rowNumber = rowNumber,
                headerTrailingContent = headerTrailingContent,
                bodyModifier = bodyModifier,
                onFolderClick = onFolderClick,
            )
        }
    }
}

@Composable
private fun HomeCollectionRowSectionContent(
    collection: Collection,
    modifier: Modifier,
    sectionPadding: Dp,
    basePosterWidthDpOverride: Int?,
    animateGifs: Boolean,
    focusedItemIndex: Int?,
    rowState: LazyListState,
    onHoverItem: ((Int) -> Unit)?,
    isKeyboardNavigation: Boolean,
    rowNumber: Int?,
    headerTrailingContent: (@Composable () -> Unit)?,
    bodyModifier: Modifier,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)?,
) {
    val homeCatalogSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    // Same "Trending • 3" suffix catalog rows get; a collection occupies a row slot too.
    val headerTitle = if (rowNumber != null && homeCatalogSettings.catalogRowNumbersEnabled) {
        "${collection.title} • $rowNumber"
    } else {
        collection.title
    }

    NuvioShelfSection(
        title = headerTitle,
        entries = collection.folders,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        showHeaderAccent = !homeCatalogSettings.hideCatalogUnderline,
        focusedItemIndex = focusedItemIndex,
        onHoverItem = onHoverItem,
        isKeyboardNavigation = isKeyboardNavigation,
        headerTrailingContent = headerTrailingContent,
        bodyModifier = bodyModifier,
        key = { folder -> "collection_${collection.id}_folder_${folder.id}" },
        rowState = rowState,
    ) { folder ->
        CollectionFolderCard(
            folder = folder,
            basePosterWidthDpOverride = basePosterWidthDpOverride,
            animateGifs = animateGifs,
            animateOnlyWhenHighlighted = homeCatalogSettings.collectionGifsOnFocusOnly,
            hideTitleBelow = homeCatalogSettings.tvModeEnabled,
            onClick = onFolderClick?.let { { it(collection.id, folder.id) } },
        )
    }
}

@Composable
private fun CollectionFolderCard(
    folder: CollectionFolder,
    modifier: Modifier = Modifier,
    basePosterWidthDpOverride: Int? = null,
    animateGifs: Boolean = true,
    animateOnlyWhenHighlighted: Boolean = false,
    hideTitleBelow: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val basePosterWidthDp = basePosterWidthDpOverride ?: posterCardStyle.widthDp
    val isLandscapeMode = posterCardStyle.catalogLandscapeModeEnabled
    val shape = if (isLandscapeMode) PosterShape.Landscape else folder.posterShape
    val cardWidth: Dp
    val aspectRatio: Float

    when (shape) {
        PosterShape.Poster -> {
            cardWidth = basePosterWidthDp.dp
            aspectRatio = 0.675f
        }
        PosterShape.Landscape -> {
            cardWidth = landscapePosterWidth(basePosterWidthDp)
            aspectRatio = PosterLandscapeAspectRatio
        }
        PosterShape.Square -> {
            cardWidth = basePosterWidthDp.dp
            aspectRatio = 1f
        }
    }

    Column(
        modifier = modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shapeCorner = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)
        val imageUrl = collectionFolderCardImageUrl(folder, animateGifs)
        // The shelf's highlight (mouse or keyboard) is the focus signal; see nuvioPosterHighlight.
        val playAnimation = !animateOnlyWhenHighlighted || LocalNuvioShelfItemHighlighted.current
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .nuvioPosterHighlight(posterCardStyle.cornerRadiusDp.dp),
            shape = shapeCorner,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 2.dp,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !imageUrl.isNullOrBlank() -> {
                        CollectionCardRemoteImage(
                            imageUrl = imageUrl,
                            contentDescription = folder.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            animateIfPossible = animateGifs && isAnimatedCollectionFolderImage(folder, imageUrl),
                            playAnimation = playAnimation,
                            // The still behind the animation. When the folder has no separate GIF
                            // this is the same URL as [imageUrl] and the card skips the extra load.
                            staticImageUrl = firstNonBlank(folder.coverImageUrl),
                        )
                    }
                    !folder.coverEmoji.isNullOrBlank() -> {
                        Text(
                            text = folder.coverEmoji,
                            fontSize = 36.sp,
                        )
                    }
                    else -> {
                        Text(
                            text = folder.title.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (onClick != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .posterCardClickable(onClick = onClick, onLongClick = null),
                    )
                }
            }
        }

        // TV rows never show below-card labels; the hero needs the reclaimed vertical space.
        if (!folder.hideTitle && !hideTitleBelow) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Which artwork a folder card loads.
 *
 * [animateGifs] belongs here, not only at the decode: this used to prefer the GIF whenever the
 * folder had one, so a surface that animates nothing (Search, Library, Discover) still downloaded
 * and decoded multi-megabyte animations to display them as if they were stills. Choosing the cover
 * instead avoids the transfer entirely.
 *
 * The GIF stays as a last resort so a folder whose only artwork is animated still shows something -
 * the card asks the decoder for a still in that case rather than animating anyway.
 */
/**
 * Warms the compressed bytes for a collection row's artwork.
 *
 * A collection row holds far more folders than fit on screen — commonly forty against eight
 * visible — and nothing was prefetching them, so scrolling right hit the network for every card
 * that came into view. The card draws its `surface` colour until the image arrives, which is the
 * grey people describe as a flicker: not the animation swapping, just an image that is not there
 * yet. The equivalent prefetch already existed for Library rows and simply never reached here.
 *
 * Disk only, deliberately: no card has been laid out when this runs, so the size it will ask for is
 * unknown, and artwork bitmaps are cached per requested size — decoding now would warm an entry
 * nothing can reach. Warming the bytes removes the network round trip, which is the part measured
 * in hundreds of milliseconds; the decode that remains is a few.
 *
 * Animated folders warm their cover as well as the animation, because the cover is what the card
 * shows first while the animation's frames are still being decoded.
 */
@Composable
private fun PrefetchCollectionArtwork(collection: Collection, animateGifs: Boolean) {
    val platformContext = LocalPlatformContext.current
    val imageLoader = SingletonImageLoader.get(platformContext)
    LaunchedEffect(collection.folders, animateGifs) {
        collection.folders
            .take(COLLECTION_ARTWORK_PREFETCH_LIMIT)
            .flatMap { folder ->
                listOfNotNull(
                    collectionFolderCardImageUrl(folder, animateGifs),
                    folder.coverImageUrl?.takeIf { it.isNotBlank() },
                )
            }
            .distinct()
            .forEach { url ->
                imageLoader.enqueue(
                    ImageRequest.Builder(platformContext)
                        .data(url)
                        .nuvioArtworkRequestSize()
                        .build(),
                )
            }
    }
}

/** Enough to cover a long row without turning one screen into a hundred queued requests. */
private const val COLLECTION_ARTWORK_PREFETCH_LIMIT = 40

internal fun collectionFolderCardImageUrl(folder: CollectionFolder, animateGifs: Boolean): String? {
    return if (folder.mobileFocusGifEnabled && animateGifs) {
        firstNonBlank(folder.focusGifUrl, folder.coverImageUrl)
    } else {
        firstNonBlank(folder.coverImageUrl, folder.focusGifUrl.takeIf { folder.mobileFocusGifEnabled })
    }
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun isAnimatedCollectionFolderImage(
    folder: CollectionFolder,
    imageUrl: String,
): Boolean {
    val gifUrl = firstNonBlank(folder.focusGifUrl) ?: return false
    return folder.mobileFocusGifEnabled && imageUrl == gifUrl
}
