package com.nuvio.app.features.catalog

import com.nuvio.app.core.ui.keyLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.rememberPosterWatchlistMembership
import com.nuvio.app.core.ui.PosterLabelWidthFraction
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.NuvioShelfItemSlot
import com.nuvio.app.core.ui.nuvioPosterHighlight
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberHoldToSelectState
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomePosterHoverPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun CatalogScreen(
    entryKey: String,
    title: String,
    subtitle: String,
    target: CatalogTarget,
    onBack: () -> Unit,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Discover's TMDB browser (row 1) opened out: the page shows the browser itself, live, with its full filter bar,
    // and pages on through it, rather than the copy of what row 1 had loaded.
    val discoverBrowser = target is CatalogTarget.DiscoverRow &&
        target.rowKey == com.nuvio.app.features.home.DISCOVER_BROWSER_ROW_KEY
    val catalogState by CatalogRepository.uiState.collectAsStateWithLifecycle()
    val discoverState by com.nuvio.app.features.search.SearchRepository.discoverUiState.collectAsStateWithLifecycle()
    val uiState = if (discoverBrowser) {
        CatalogUiState(
            items = discoverState.items,
            isLoading = discoverState.isLoading,
            nextSkip = discoverState.nextSkip,
            errorMessage = discoverState.errorMessage,
        )
    } else {
        catalogState
    }
    fun loadMore() {
        if (discoverBrowser) com.nuvio.app.features.search.SearchRepository.loadMoreDiscover() else CatalogRepository.loadMore()
    }
    // An addon catalog's own genre list goes to the addon: picking one reloads the catalog with it.
    val addonGenres = remember(target) { (target as? CatalogTarget.Addon)?.let(::addonCatalogGenres) }
    var addonGenre by remember(target) { mutableStateOf((target as? CatalogTarget.Addon)?.genre) }
    val activeTarget = remember(target, addonGenre) {
        if (target is CatalogTarget.Addon) target.copy(genre = addonGenre) else target
    }
    var localFilters by remember(target) { mutableStateOf(CatalogLocalFilters()) }
    val homeCatalogSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val posterCardStyle = rememberPosterCardStyleUiState()
    val watchlist = rememberPosterWatchlistMembership()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val shownItems = remember(uiState.items, localFilters, watchedUiState.watchedKeys) {
        applyCatalogFilters(uiState.items, localFilters) { item ->
            WatchingState.isPosterWatched(watchedKeys = watchedUiState.watchedKeys, item = item)
        }
    }
    val typeOptions = remember(uiState.items) { uiState.items.map { it.type.lowercase() }.distinct() }
    val initialScrollPosition = remember(
        activeTarget,
        homeCatalogSettingsUiState.hideUnreleasedContent,
        homeCatalogSettingsUiState.hideWatchedContent,
    ) {
        CatalogRepository.scrollPosition(
            entryKey = entryKey,
            target = activeTarget,
        )
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScrollPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialScrollPosition.firstVisibleItemScrollOffset,
    )
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var observedOfflineState by remember { mutableStateOf(false) }

    // The page drives the grid from the keyboard on every desktop layout, not only with the TV-style hero on: without
    // it the arrows reached nothing but the back button (2026-11-15). The TV layout keeps its focus ring up even
    // under the mouse; elsewhere it shows once a key is pressed.
    val adaptiveHeroEnabled = homeCatalogSettingsUiState.adaptiveHeroEnabled && isDesktop
    val keyboardGrid = isDesktop
    var filterBarHasFocus by remember { mutableStateOf(false) }
    // The back button is reached with Left from the first filter (the bar alone stops there).
    val backFocus = remember { FocusRequester() }
    var backHasFocus by remember { mutableStateOf(false) }
    val tvFocusRequester = remember { FocusRequester() }
    val filterBarFocus = remember { FocusRequester() }
    val tvCoroutineScope = rememberCoroutineScope()
    val mouseActivity = rememberMouseActivityState()
    var focusedItemIndex by remember(target) { mutableIntStateOf(0) }

    LaunchedEffect(shownItems.size) {
        if (shownItems.isEmpty()) {
            focusedItemIndex = 0
        } else if (focusedItemIndex > shownItems.size - 1) {
            focusedItemIndex = shownItems.size - 1
        }
    }
    // A new genre or filter starts the page over at the top.
    LaunchedEffect(activeTarget, localFilters) {
        if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0)
        focusedItemIndex = 0
    }

    LaunchedEffect(keyboardGrid) {
        if (keyboardGrid) {
            // Asked again over a few frames: the first request can land before the page is attached.
            repeat(6) {
                if (tvFocusRequester.requestFocusLogged()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }

    LaunchedEffect(activeTarget, homeCatalogSettingsUiState.hideUnreleasedContent, homeCatalogSettingsUiState.hideWatchedContent) {
        if (discoverBrowser) return@LaunchedEffect
        CatalogRepository.load(
            target = activeTarget,
        )
    }

    LaunchedEffect(gridState, activeTarget, homeCatalogSettingsUiState.hideUnreleasedContent, homeCatalogSettingsUiState.hideWatchedContent) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                CatalogRepository.saveScrollPosition(
                    entryKey = entryKey,
                    target = activeTarget,
                    firstVisibleItemIndex = index,
                    firstVisibleItemScrollOffset = offset,
                )
            }
    }

    LaunchedEffect(gridState, uiState.canLoadMore, uiState.isLoading) {
        snapshotFlow { gridState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 6
            }
            .distinctUntilChanged()
            .filter { it && uiState.canLoadMore && !uiState.isLoading }
            .collect {
                loadMore()
            }
    }

    LaunchedEffect(networkStatusUiState.condition, target) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false
                if (!discoverBrowser) CatalogRepository.load(
                    target = activeTarget,
                    force = true,
                )
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val columns = remember(maxWidth) { catalogGridColumnsForWidth(maxWidth) }

        // Keyed on the focused index only. Keying on the item count too made every page that
        // arrived while the user was wheel-scrolling re-check item 0 (the untouched keyboard
        // focus), find it off-screen, and snap the grid back to the top.
        LaunchedEffect(focusedItemIndex) {
            if (shownItems.isEmpty() || focusedItemIndex !in shownItems.indices) return@LaunchedEffect
            if (mouseActivity.isMouseActive) return@LaunchedEffect
            val layoutInfo = gridState.layoutInfo
            val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
                item.index == focusedItemIndex &&
                    item.offset.y >= layoutInfo.viewportStartOffset &&
                    item.offset.y + item.size.height <= layoutInfo.viewportEndOffset
            }
            if (!isFullyVisible) {
                gridState.animateScrollToItem(focusedItemIndex)
            }
        }

        val selectHold = rememberHoldToSelectState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (keyboardGrid) {
                        Modifier
                            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
                            .keyLayer("Catalog") { tvFocusRequester.requestFocusLogged() }
                            .focusRequester(tvFocusRequester)
                            .focusable()
                            .onPointerEvent(PointerEventType.Move) { event ->
                                mouseActivity.onMouseMoved(event.changes.first().position)
                            }
                            .onPreviewKeyEvent { event ->
                                // The filter bar (and its menus' chips) walk themselves; the grid leaves those keys alone.
                                if (filterBarHasFocus || backHasFocus) return@onPreviewKeyEvent false
                                val selectKey = event.navigationKey()
                                if (
                                    (selectKey == Key.Enter || selectKey == Key.NumPadEnter) &&
                                    shownItems.isNotEmpty()
                                ) {
                                    val item = shownItems.getOrNull(focusedItemIndex)
                                    return@onPreviewKeyEvent selectHold.handle(
                                        event = event,
                                        onSelect = { item?.let { onPosterClick?.invoke(it) } },
                                        onHold = item?.let { focused ->
                                            onPosterLongClick?.let { longPress -> { longPress(focused) } }
                                        },
                                    )
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                if (shownItems.isEmpty()) {
                                    // Nothing passes the filters: Up still reaches them.
                                    if (event.navigationKey() == Key.DirectionUp) return@onPreviewKeyEvent filterBarFocus.requestFocusLogged()
                                    return@onPreviewKeyEvent false
                                }
                                val lastIndex = shownItems.size - 1
                                when (event.navigationKey()) {
                                    Key.Backspace -> {
                                        onBack()
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        mouseActivity.onKeyboardNavigation()
                                        focusedItemIndex = (focusedItemIndex + 1).coerceAtMost(lastIndex)
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        mouseActivity.onKeyboardNavigation()
                                        focusedItemIndex = (focusedItemIndex - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        mouseActivity.onKeyboardNavigation()
                                        focusedItemIndex = (focusedItemIndex + columns).coerceAtMost(lastIndex)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        mouseActivity.onKeyboardNavigation()
                                        // Up from the top row goes to the filters in the top bar.
                                        if (focusedItemIndex < columns) return@onPreviewKeyEvent filterBarFocus.requestFocusLogged()
                                        focusedItemIndex = (focusedItemIndex - columns).coerceAtLeast(0)
                                        true
                                    }
                                    else -> false
                                }
                            }
                    } else {
                        // Posters take focus themselves here; the fallback returns it to the last one (KeyLayers).
                        Modifier.keyLayer("Catalog")
                    },
                ),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = with(androidx.compose.ui.platform.LocalDensity.current) { headerHeightPx.toDp() } + 12.dp,
                    end = 16.dp,
                    bottom = nuvioSafeBottomPadding(28.dp),
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (shownItems.isEmpty() && uiState.isLoading) {
                    items(columns * 3) {
                        CatalogSkeletonTile(cornerRadiusDp = posterCardStyle.cornerRadiusDp)
                    }
                } else if (uiState.items.isNotEmpty() && shownItems.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Nothing loaded so far matches these filters",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (uiState.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatalogEmptyState(
                            errorMessage = uiState.errorMessage,
                            networkCondition = networkStatusUiState.condition,
                            onRetry = {
                                NetworkStatusRepository.requestRefresh(force = true)
                                if (!discoverBrowser) CatalogRepository.load(
                                    target = activeTarget,
                                    force = true,
                                )
                            },
                        )
                    }
                } else {
                    itemsIndexed(
                        items = shownItems.withDuplicateSafeLazyKeys { item -> item.stableKey() },
                        key = { _, keyedItem -> keyedItem.lazyKey },
                    ) { index, keyedItem ->
                        val item = keyedItem.value
                        NuvioShelfItemSlot(
                            focused = keyboardGrid && index == focusedItemIndex && !filterBarHasFocus &&
                                (adaptiveHeroEnabled || !mouseActivity.isMouseActive),
                        ) {
                            CatalogPosterTile(
                                item = item,
                                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
                                hideLabels = posterCardStyle.hideLabelsEnabled,
                                isWatched = WatchingState.isPosterWatched(
                                    watchedKeys = watchedUiState.watchedKeys,
                                    item = item,
                                ),
                                isInWatchlist = watchlist.contains(item),
                                onClick = onPosterClick?.let { { it(item) } },
                                onLongClick = onPosterLongClick?.let { { it(item) } },
                            )
                        }
                    }
                    if (uiState.isLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CatalogLoadingFooter()
                        }
                    }
                }
            }

            val allLabel = stringResource(Res.string.discover_all_filters)
            CatalogTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { headerHeightPx = it.height },
                backModifier = Modifier
                    .focusRequester(backFocus)
                    .onFocusChanged { backHasFocus = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.navigationKey()) {
                            Key.DirectionRight -> filterBarFocus.requestFocusLogged()
                            Key.DirectionDown -> keyboardGrid && tvFocusRequester.requestFocusLogged()
                            Key.DirectionLeft, Key.DirectionUp -> true
                            else -> false
                        }
                    },
            ) {
                // The bar's filters take focus from the grid's top row (Up) and give it back (Down).
                val stripFocus = remember {
                    com.nuvio.app.features.discover.BrowseFilterStripFocus(requester = filterBarFocus, onFocusChanged = { filterBarHasFocus = it })
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.nuvio.app.features.discover.LocalBrowseFilterStripFocus provides stripFocus,
                ) {
                    // Compose on desktop moves focus with Tab only, so the bar walks itself with Left/Right, as
                    // Discover's does (HomeScreen); Down or Backspace returns to the posters.
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    val stripModifier = Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.navigationKey()) {
                            Key.DirectionLeft -> {
                                // At the first filter the bar refuses to go on; Left then reaches the back button.
                                if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) backFocus.requestFocusLogged()
                                true
                            }
                            Key.DirectionRight -> {
                                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                                true
                            }
                            Key.DirectionDown, Key.Backspace -> keyboardGrid && tvFocusRequester.requestFocusLogged()
                            Key.DirectionUp -> true
                            else -> false
                        }
                    }
                    if (discoverBrowser) {
                        com.nuvio.app.features.discover.DiscoverRowHeader(
                            state = discoverState,
                            allFiltersLabel = allLabel,
                            onMenuOpenChange = {},
                            modifier = stripModifier,
                        )
                    } else {
                        CatalogFilterMenus(
                            genres = addonGenres,
                            genre = addonGenre,
                            onGenre = { addonGenre = it },
                            typeOptions = typeOptions,
                            filters = localFilters,
                            onFilters = { localFilters = it },
                            allLabel = allLabel,
                            modifier = stripModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPosterTile(
    item: MetaPreview,
    cornerRadiusDp: Int,
    hideLabels: Boolean,
    isWatched: Boolean,
    isInWatchlist: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val tileShape = RoundedCornerShape(cornerRadiusDp.dp)
    HomePosterHoverPreview(
        item = item,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
    ) { hoverModifier ->
        Column(
            modifier = hoverModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(item.posterShape.catalogAspectRatio())
                    // Ahead of the clip on purpose — see nuvioPosterHighlight.
                    .nuvioPosterHighlight(cornerRadiusDp.dp, artworkUrl = item.poster)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .posterCardClickable(onClick = onClick, onLongClick = onLongClick),
            ) {
                if (item.poster != null) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                NuvioPosterWatchedOverlay(isWatched = isWatched, isInWatchlist = isInWatchlist)
            }
            if (!hideLabels) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(PosterLabelWidthFraction)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NuvioPosterHoverTooltip(title = item.name) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val detail = item.releaseInfo?.let { formatReleaseDateForDisplay(it) }
                if (detail != null) {
                    Text(
                        text = detail,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogSkeletonTile(cornerRadiusDp: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun CatalogEmptyState(
    errorMessage: String?,
    networkCondition: NetworkCondition,
    onRetry: (() -> Unit)? = null,
) {
    if (networkCondition == NetworkCondition.NoInternet || networkCondition == NetworkCondition.ServersUnreachable) {
        NuvioNetworkOfflineCard(
            condition = networkCondition,
            onRetry = onRetry,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.catalog_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = errorMessage ?: stringResource(Res.string.catalog_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}

private fun PosterShape.catalogAspectRatio(): Float =
    when (this) {
        PosterShape.Poster -> 0.68f
        PosterShape.Square -> 1f
        // The same 16:9 the home shelves use — a narrower ratio here would crop away a third of the
        // width of art the addon cut for this shape, and disagree with the row the grid opened from.
        PosterShape.Landscape -> PosterLandscapeAspectRatio
    }

private fun catalogGridColumnsForWidth(screenWidth: Dp): Int =
    when {
        screenWidth >= 1400.dp -> 7
        screenWidth >= 1200.dp -> 6
        screenWidth >= 1000.dp -> 5
        screenWidth >= 840.dp -> 4
        else -> 3
    }
