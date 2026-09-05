package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    entries: List<MetaPreview> = section.items,
    watchedKeys: Set<String> = emptySet(),
    sectionPadding: Dp? = null,
    basePosterWidthDpOverride: Int? = null,
    focusedItemIndex: Int? = null,
    rowState: LazyListState? = null,
    onHoverItem: ((Int) -> Unit)? = null,
    isKeyboardNavigation: Boolean = false,
    onViewAllClick: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    // Row shuffle: null on rows that cannot re-deal, and on every surface that doesn't offer it.
    onShuffleClick: (() -> Unit)? = null,
    isShuffling: Boolean = false,
    shuffleGeneration: Int = 0,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    // 1-based position of this row among the home content rows, appended to the header when the
    // "Number catalog rows" setting is on. Null on every surface that isn't the home row list.
    rowNumber: Int? = null,
    // TV Mode's row-jump dots, rendered on the header line next to the title. Null everywhere else.
    headerTrailingContent: (@Composable () -> Unit)? = null,
    // Discover's clickable catalog/genre segments replace the header title, and its picker panel
    // draws over the faded posters. Null on every ordinary catalog row.
    titleContent: (@Composable () -> Unit)? = null,
    bodyAlpha: Float = 1f,
    bodyOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val effectiveRowState = rowState ?: rememberLazyListState()
    if (sectionPadding != null) {
        HomeCatalogRowSectionContent(
            section = section,
            entries = entries,
            watchedKeys = watchedKeys,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            basePosterWidthDpOverride = basePosterWidthDpOverride,
            focusedItemIndex = focusedItemIndex,
            rowState = effectiveRowState,
            onHoverItem = onHoverItem,
            isKeyboardNavigation = isKeyboardNavigation,
            onViewAllClick = onViewAllClick,
            onLoadMore = onLoadMore,
            isLoadingMore = isLoadingMore,
            onShuffleClick = onShuffleClick,
            isShuffling = isShuffling,
            shuffleGeneration = shuffleGeneration,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
            rowNumber = rowNumber,
            headerTrailingContent = headerTrailingContent,
            titleContent = titleContent,
            bodyAlpha = bodyAlpha,
            bodyOverlay = bodyOverlay,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCatalogRowSectionContent(
                section = section,
                entries = entries,
                watchedKeys = watchedKeys,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                basePosterWidthDpOverride = basePosterWidthDpOverride,
                focusedItemIndex = focusedItemIndex,
                rowState = effectiveRowState,
                onHoverItem = onHoverItem,
                onViewAllClick = onViewAllClick,
                onLoadMore = onLoadMore,
                isLoadingMore = isLoadingMore,
                onShuffleClick = onShuffleClick,
                isShuffling = isShuffling,
                shuffleGeneration = shuffleGeneration,
                isKeyboardNavigation = isKeyboardNavigation,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
                rowNumber = rowNumber,
                headerTrailingContent = headerTrailingContent,
                titleContent = titleContent,
                bodyAlpha = bodyAlpha,
                bodyOverlay = bodyOverlay,
            )
        }
    }
}

@Composable
private fun HomeCatalogRowSectionContent(
    section: HomeCatalogSection,
    entries: List<MetaPreview>,
    watchedKeys: Set<String>,
    modifier: Modifier,
    sectionPadding: Dp,
    basePosterWidthDpOverride: Int?,
    focusedItemIndex: Int?,
    rowState: LazyListState,
    onHoverItem: ((Int) -> Unit)?,
    onViewAllClick: (() -> Unit)?,
    onLoadMore: (() -> Unit)?,
    isLoadingMore: Boolean,
    onShuffleClick: (() -> Unit)?,
    isShuffling: Boolean,
    shuffleGeneration: Int,
    isKeyboardNavigation: Boolean,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    rowNumber: Int?,
    headerTrailingContent: (@Composable () -> Unit)?,
    titleContent: (@Composable () -> Unit)?,
    bodyAlpha: Float,
    bodyOverlay: (@Composable BoxScope.() -> Unit)?,
) {
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val homeCatalogSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    val headerTitle = if (rowNumber != null && homeCatalogSettings.catalogRowNumbersEnabled) {
        "${section.title} • $rowNumber"
    } else {
        section.title
    }

    NuvioShelfSection(
        title = headerTitle,
        entries = entries,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        showHeaderAccent = !homeCatalogSettings.hideCatalogUnderline,
        focusedItemIndex = focusedItemIndex,
        onHoverItem = onHoverItem,
        onViewAllClick = onViewAllClick,
        onLoadMore = onLoadMore,
        isLoadingMore = isLoadingMore,
        onShuffleClick = onShuffleClick,
        isShuffling = isShuffling,
        shuffleGeneration = shuffleGeneration,
        isKeyboardNavigation = isKeyboardNavigation,
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        headerTrailingContent = headerTrailingContent,
        titleContent = titleContent,
        bodyAlpha = bodyAlpha,
        bodyOverlay = bodyOverlay,
        key = { item -> item.stableKey() },
        rowState = rowState,
    ) { item ->
        HomePosterCard(
            item = item,
            useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
            basePosterWidthDpOverride = basePosterWidthDpOverride,
            isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item,
            ),
            onClick = onPosterClick?.let { { it(item) } },
            onLongClick = onPosterLongClick?.let { { it(item) } },
        )
    }
}
