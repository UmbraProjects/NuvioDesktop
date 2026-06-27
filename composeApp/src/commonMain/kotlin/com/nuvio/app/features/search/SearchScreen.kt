package com.nuvio.app.features.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.isDesktop
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_search_clear
import nuvio.composeapp.generated.resources.compose_search_discover_title
import nuvio.composeapp.generated.resources.compose_search_empty_failed_message
import nuvio.composeapp.generated.resources.compose_search_empty_failed_title
import nuvio.composeapp.generated.resources.compose_search_empty_no_active_addons_message
import nuvio.composeapp.generated.resources.compose_search_empty_no_active_addons_title
import nuvio.composeapp.generated.resources.compose_search_empty_no_results_message
import nuvio.composeapp.generated.resources.compose_search_empty_no_results_title
import nuvio.composeapp.generated.resources.compose_search_empty_no_search_catalogs_message
import nuvio.composeapp.generated.resources.compose_search_empty_no_search_catalogs_title
import nuvio.composeapp.generated.resources.compose_search_placeholder
import nuvio.composeapp.generated.resources.compose_search_recent_searches
import nuvio.composeapp.generated.resources.compose_search_remove_recent_search
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    searchFocusRequestCount: Int = 0,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onNavigateToHome: (() -> Unit)? = null,
) {
    val searchBarFocusRequester = remember { FocusRequester() }
    val screenFocusRequester = remember { FocusRequester() }
    var isInRowNav by remember { mutableStateOf(false) }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    var focusedItemIndex by remember { mutableIntStateOf(0) }
    var suppressSearchActivationKey by remember(searchFocusRequestCount) {
        mutableStateOf(searchFocusRequestCount > 0)
    }
    val coroutineScope = rememberCoroutineScope()

    // Focus the outer box immediately so keyboard nav works as soon as the screen appears.
    LaunchedEffect(Unit) {
        if (isDesktop) try { screenFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(searchFocusRequestCount) {
        if (searchFocusRequestCount > 0) {
            suppressSearchActivationKey = true
            isInRowNav = false
            // Request the field immediately. The activation-key guard below prevents the
            // shortcut's S key from becoming query text while focus changes hands.
            try { screenFocusRequester.requestFocus() } catch (_: Exception) {}
            try { searchBarFocusRequester.requestFocus() } catch (_: Exception) {}
            delay(150)
            suppressSearchActivationKey = false
        }
    }

    LaunchedEffect(Unit) {
        AddonRepository.initialize()
        WatchedRepository.ensureLoaded()
        SearchHistoryRepository.ensureLoaded()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val uiState by SearchRepository.uiState.collectAsStateWithLifecycle()
    val discoverUiState by SearchRepository.discoverUiState.collectAsStateWithLifecycle()
    val homeCatalogSettingsUiState by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val recentSearches by SearchHistoryRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var lastRequestedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var observedOfflineState by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        onDispose {
            query = ""
            lastRequestedQuery = null
            SearchRepository.clear()
        }
    }

    val discoverInFocus by remember(query, listState) {
        derivedStateOf {
            query.isBlank() && listState.firstVisibleItemIndex > 0
        }
    }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect {
            listState.animateScrollToItem(0)
        }
    }

    val addonRefreshKey = remember(addonsUiState.addons) {
        addonsUiState.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    val extra = catalog.extra.joinToString(separator = "&") { property ->
                        buildString {
                            append(property.name)
                            append(':')
                            append(property.isRequired)
                            append(':')
                            append(property.options.joinToString(separator = "|"))
                        }
                    }
                    "${catalog.type}:${catalog.id}:$extra"
                })
            }
        }
    }

    LaunchedEffect(addonRefreshKey, homeCatalogSettingsUiState.hideUnreleasedContent) {
        SearchRepository.refreshDiscover(addonsUiState.addons)
    }

    LaunchedEffect(query, addonRefreshKey, homeCatalogSettingsUiState.hideUnreleasedContent) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            lastRequestedQuery = null
            SearchRepository.clear()
        } else {
            delay(350)
            lastRequestedQuery = normalizedQuery
            SearchRepository.search(
                query = normalizedQuery,
                addons = addonsUiState.addons,
            )
        }
    }

    LaunchedEffect(listState, query, discoverUiState.canLoadMore, discoverUiState.isLoading) {
        if (query.isNotBlank()) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 4
            }
            .distinctUntilChanged()
            .filter { it && discoverUiState.canLoadMore && !discoverUiState.isLoading }
            .collect {
                SearchRepository.loadMoreDiscover()
            }
    }

    LaunchedEffect(query, lastRequestedQuery, uiState.isLoading, uiState.sections) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@LaunchedEffect
        if (lastRequestedQuery != normalizedQuery) return@LaunchedEffect
        if (uiState.isLoading || uiState.sections.isEmpty()) return@LaunchedEffect
        SearchHistoryRepository.recordSearch(normalizedQuery)
    }

    LaunchedEffect(networkStatusUiState.condition, query, addonRefreshKey) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false

                val normalizedQuery = query.trim()
                if (normalizedQuery.isBlank()) {
                    SearchRepository.refreshDiscover(addonsUiState.addons)
                } else {
                    SearchRepository.search(
                        query = normalizedQuery,
                        addons = addonsUiState.addons,
                    )
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isDesktop) {
                    Modifier
                        .focusRequester(screenFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val sections = uiState.sections
                        when {
                            !isInRowNav && event.key == Key.DirectionDown && sections.isNotEmpty() -> {
                                isInRowNav = true
                                focusedRowIndex = 0
                                focusedItemIndex = 0
                                true
                            }
                            isInRowNav -> when (event.key) {
                                Key.DirectionUp -> {
                                    if (focusedRowIndex > 0) {
                                        focusedRowIndex--
                                        focusedItemIndex = 0
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(
                                                if (focusedRowIndex == 0) 0 else focusedRowIndex + 1
                                            )
                                        }
                                    } else {
                                        isInRowNav = false
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    val maxRow = (sections.size - 1).coerceAtLeast(0)
                                    focusedRowIndex = (focusedRowIndex + 1).coerceAtMost(maxRow)
                                    focusedItemIndex = 0
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(focusedRowIndex + 1)
                                    }
                                    true
                                }
                                Key.DirectionRight -> {
                                    val maxItem = (sections.getOrNull(focusedRowIndex)?.items?.size?.minus(1) ?: 0).coerceAtLeast(0)
                                    focusedItemIndex = (focusedItemIndex + 1).coerceAtMost(maxItem)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    focusedItemIndex = (focusedItemIndex - 1).coerceAtLeast(0)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    sections.getOrNull(focusedRowIndex)?.items?.getOrNull(focusedItemIndex)
                                        ?.let { onPosterClick?.invoke(it) }
                                    true
                                }
                                Key.S, Key.H -> {
                                    onNavigateToHome?.invoke()
                                    true
                                }
                                else -> false
                            }
                            else -> false
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val discoverColumns = remember(maxWidth) {
            discoverColumnCountForWidth(maxWidth)
        }
        val homeSectionPadding = remember(maxWidth) {
            homeSectionHorizontalPaddingForWidth(maxWidth.value)
        }
        val headerTitle = when {
            query.isNotBlank() -> stringResource(Res.string.compose_nav_search)
            discoverInFocus -> stringResource(Res.string.compose_search_discover_title)
            else -> stringResource(Res.string.compose_nav_search)
        }

        NuvioScreen(
            horizontalPadding = 0.dp,
            topPadding = if (topChromePadding != null) 0.dp else null,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
        stickyHeader {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.background)
                        .nuvioConsumePointerEvents(),
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NuvioScreenHeader(
                        title = headerTitle,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        topPadding = topChromePadding,
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        NuvioInputField(
                            value = query,
                            onValueChange = { value ->
                                if (suppressSearchActivationKey && isSearchActivationInsertion(query, value)) {
                                    suppressSearchActivationKey = false
                                } else {
                                    isInRowNav = false
                                    query = value
                                }
                            },
                            placeholder = stringResource(Res.string.compose_search_placeholder),
                            readOnly = suppressSearchActivationKey,
                            modifier = Modifier
                                .focusRequester(searchBarFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (suppressSearchActivationKey && event.key == Key.S) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            suppressSearchActivationKey = false
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            trailingContent = if (query.isNotBlank()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(Res.string.compose_search_clear),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }

        if (query.isBlank()) {
            if (recentSearches.isNotEmpty()) {
                item(key = "recent_searches") {
                    SearchRecentSection(
                        recentSearches = recentSearches,
                        onSearchPress = { recentQuery -> query = recentQuery },
                        onRemoveSearch = SearchHistoryRepository::removeSearch,
                    )
                }
            }
                discoverContent(
                    state = discoverUiState,
                    columns = discoverColumns,
                    networkCondition = networkStatusUiState.condition,
                    onTypeSelected = SearchRepository::selectDiscoverType,
                    onCatalogSelected = SearchRepository::selectDiscoverCatalog,
                    onGenreSelected = SearchRepository::selectDiscoverGenre,
                    onRetry = {
                        NetworkStatusRepository.requestRefresh(force = true)
                        SearchRepository.refreshDiscover(addonsUiState.addons)
                    },
                    watchedKeys = watchedUiState.watchedKeys,
                    onPosterClick = onPosterClick,
                    onPosterLongClick = onPosterLongClick,
                )
            } else {
                val normalizedQuery = query.trim()
                val isWaitingForSearch = normalizedQuery.isNotBlank() && lastRequestedQuery != normalizedQuery
                when {
                    isWaitingForSearch && uiState.sections.isEmpty() -> {
                        items(2) {
                            HomeSkeletonRow(
                                modifier = Modifier.padding(horizontal = homeSectionPadding),
                                showHeaderAccent = !homeCatalogSettingsUiState.hideCatalogUnderline,
                            )
                        }
                    }

                    uiState.isLoading && uiState.sections.isEmpty() -> {
                        items(2) {
                            HomeSkeletonRow(
                                modifier = Modifier.padding(horizontal = homeSectionPadding),
                                showHeaderAccent = !homeCatalogSettingsUiState.hideCatalogUnderline,
                            )
                        }
                    }

                    uiState.sections.isEmpty() -> {
                        item {
                            SearchEmptyStateCard(
                                reason = uiState.emptyStateReason,
                                errorMessage = uiState.errorMessage,
                                networkCondition = networkStatusUiState.condition,
                                onRetry = {
                                    if (normalizedQuery.isNotBlank()) {
                                        NetworkStatusRepository.requestRefresh(force = true)
                                        SearchRepository.search(
                                            query = normalizedQuery,
                                            addons = addonsUiState.addons,
                                        )
                                    }
                                },
                                modifier = Modifier.padding(horizontal = homeSectionPadding),
                            )
                        }
                    }

                    else -> {
                        itemsIndexed(
                            items = uiState.sections.withDuplicateSafeLazyKeys { section -> section.key },
                            key = { _, keyedSection -> keyedSection.lazyKey },
                        ) { index, keyedSection ->
                            val section = keyedSection.value
                            HomeCatalogRowSection(
                                section = section,
                                modifier = Modifier.padding(bottom = 12.dp),
                                watchedKeys = watchedUiState.watchedKeys,
                                onPosterClick = onPosterClick,
                                onPosterLongClick = onPosterLongClick,
                                focusedItemIndex = if (isInRowNav && focusedRowIndex == index) focusedItemIndex else null,
                                onHoverItem = if (isInRowNav && focusedRowIndex == index) { i -> focusedItemIndex = i } else null,
                            )
                        }
                        if (uiState.isLoading) {
                            item(key = "search_loading_more") {
                                HomeSkeletonRow(
                                    modifier = Modifier.padding(horizontal = homeSectionPadding),
                                    showHeaderAccent = !homeCatalogSettingsUiState.hideCatalogUnderline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun discoverColumnCountForWidth(screenWidth: Dp): Int =
    when {
        screenWidth >= 1400.dp -> 7
        screenWidth >= 1200.dp -> 6
        screenWidth >= 1000.dp -> 5
        screenWidth >= 840.dp -> 4
        else -> 3
    }

private fun isSearchActivationInsertion(previous: String, next: String): Boolean {
    if (previous.isBlank() && next.equals("s", ignoreCase = true)) return true
    if (next.length != previous.length + 1) return false
    return next.indices.any { index ->
        next[index].equals('s', ignoreCase = true) &&
            next.removeRange(index, index + 1) == previous
    }
}

@Composable
private fun SearchEmptyStateCard(
    reason: SearchEmptyStateReason?,
    errorMessage: String?,
    networkCondition: NetworkCondition,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (networkCondition == NetworkCondition.NoInternet || networkCondition == NetworkCondition.ServersUnreachable) {
        NuvioNetworkOfflineCard(
            condition = networkCondition,
            modifier = modifier,
            onRetry = onRetry,
        )
        return
    }

    val title: String
    val message: String

    when (reason) {
        SearchEmptyStateReason.NoActiveAddons -> {
            title = stringResource(Res.string.compose_search_empty_no_active_addons_title)
            message = stringResource(Res.string.compose_search_empty_no_active_addons_message)
        }

        SearchEmptyStateReason.NoSearchCatalogs -> {
            title = stringResource(Res.string.compose_search_empty_no_search_catalogs_title)
            message = stringResource(Res.string.compose_search_empty_no_search_catalogs_message)
        }

        SearchEmptyStateReason.RequestFailed -> {
            title = stringResource(Res.string.compose_search_empty_failed_title)
            message = errorMessage ?: stringResource(Res.string.compose_search_empty_failed_message)
        }

        SearchEmptyStateReason.NoResults, null -> {
            title = stringResource(Res.string.compose_search_empty_no_results_title)
            message = stringResource(Res.string.compose_search_empty_no_results_message)
        }
    }

    HomeEmptyStateCard(
        modifier = modifier,
        title = title,
        message = message,
    )
}

@Composable
private fun SearchRecentSection(
    recentSearches: List<String>,
    onSearchPress: (String) -> Unit,
    onRemoveSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.compose_search_recent_searches),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        recentSearches.forEach { recentQuery ->
            SearchRecentRow(
                query = recentQuery,
                onSearchPress = { onSearchPress(recentQuery) },
                onRemovePress = { onRemoveSearch(recentQuery) },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SearchRecentRow(
    query: String,
    onSearchPress: () -> Unit,
    onRemovePress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSearchPress)
            .padding(vertical = 2.dp)
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(999.dp),
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = query,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemovePress) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.compose_search_remove_recent_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
