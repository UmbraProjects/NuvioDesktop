package com.nuvio.app.features.catalog

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
import com.nuvio.app.core.ui.PosterLabelWidthFraction
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.NuvioShelfItemSlot
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.PosterShape
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
    title: String,
    subtitle: String,
    target: CatalogTarget,
    onBack: () -> Unit,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by CatalogRepository.uiState.collectAsStateWithLifecycle()
    val homeCatalogSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val posterCardStyle = rememberPosterCardStyleUiState()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val initialScrollPosition = remember(
        target,
        homeCatalogSettingsUiState.hideUnreleasedContent,
    ) {
        CatalogRepository.scrollPosition(
            target = target,
        )
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScrollPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialScrollPosition.firstVisibleItemScrollOffset,
    )
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var observedOfflineState by remember { mutableStateOf(false) }

    val adaptiveHeroEnabled = homeCatalogSettingsUiState.adaptiveHeroEnabled && isDesktop
    val tvFocusRequester = remember { FocusRequester() }
    val tvCoroutineScope = rememberCoroutineScope()
    val mouseActivity = rememberMouseActivityState()
    var focusedItemIndex by remember(target) { mutableIntStateOf(0) }

    LaunchedEffect(uiState.items.size) {
        if (uiState.items.isEmpty()) {
            focusedItemIndex = 0
        } else if (focusedItemIndex > uiState.items.size - 1) {
            focusedItemIndex = uiState.items.size - 1
        }
    }

    LaunchedEffect(adaptiveHeroEnabled) {
        if (adaptiveHeroEnabled) {
            tvFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(target, homeCatalogSettingsUiState.hideUnreleasedContent) {
        CatalogRepository.load(
            target = target,
        )
    }

    LaunchedEffect(gridState, target, homeCatalogSettingsUiState.hideUnreleasedContent) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                CatalogRepository.saveScrollPosition(
                    target = target,
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
                CatalogRepository.loadMore()
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
                CatalogRepository.load(
                    target = target,
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

        LaunchedEffect(focusedItemIndex, uiState.items.size) {
            if (uiState.items.isEmpty() || focusedItemIndex !in uiState.items.indices) return@LaunchedEffect
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (adaptiveHeroEnabled) {
                        Modifier
                            .focusRequester(tvFocusRequester)
                            .focusable()
                            .onPointerEvent(PointerEventType.Move) { event ->
                                mouseActivity.onMouseMoved(event.changes.first().position)
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                if (uiState.items.isEmpty()) return@onPreviewKeyEvent false
                                val lastIndex = uiState.items.size - 1
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
                                        focusedItemIndex = (focusedItemIndex - columns).coerceAtLeast(0)
                                        true
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        uiState.items.getOrNull(focusedItemIndex)?.let { onPosterClick?.invoke(it) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    } else {
                        Modifier
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
                if (uiState.items.isEmpty() && uiState.isLoading) {
                    items(columns * 3) {
                        CatalogSkeletonTile(cornerRadiusDp = posterCardStyle.cornerRadiusDp)
                    }
                } else if (uiState.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatalogEmptyState(
                            errorMessage = uiState.errorMessage,
                            networkCondition = networkStatusUiState.condition,
                            onRetry = {
                                NetworkStatusRepository.requestRefresh(force = true)
                                CatalogRepository.load(
                                    target = target,
                                    force = true,
                                )
                            },
                        )
                    }
                } else {
                    itemsIndexed(
                        items = uiState.items.withDuplicateSafeLazyKeys { item -> item.stableKey() },
                        key = { _, keyedItem -> keyedItem.lazyKey },
                    ) { index, keyedItem ->
                        val item = keyedItem.value
                        NuvioShelfItemSlot(focused = adaptiveHeroEnabled && index == focusedItemIndex) {
                            CatalogPosterTile(
                                item = item,
                                cornerRadiusDp = posterCardStyle.cornerRadiusDp,
                                hideLabels = posterCardStyle.hideLabelsEnabled,
                                isWatched = WatchingState.isPosterWatched(
                                    watchedKeys = watchedUiState.watchedKeys,
                                    item = item,
                                ),
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

            CatalogHeader(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun CatalogHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .padding(top = 52.dp, bottom = 12.dp),
    ) {
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            iconSize = 24.dp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogPosterTile(
    item: MetaPreview,
    cornerRadiusDp: Int,
    hideLabels: Boolean,
    isWatched: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(item.posterShape.catalogAspectRatio())
                .clip(RoundedCornerShape(cornerRadiusDp.dp))
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
            NuvioPosterWatchedOverlay(isWatched = isWatched)
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
