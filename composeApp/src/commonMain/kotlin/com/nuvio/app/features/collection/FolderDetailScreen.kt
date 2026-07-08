package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.HeroAmbientBackdrop
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.MouseActivityState
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.canOpenCatalog
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.immersiveCatalogPosterBaseWidthDp
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeHeroTrailerManualTrigger
import com.nuvio.app.features.home.components.HomeTvKey
import com.nuvio.app.features.home.components.HomeTvKeyboardBridge
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_folder_empty_items
import nuvio.composeapp.generated.resources.collections_folder_not_found
import nuvio.composeapp.generated.resources.collections_tab_all
import org.jetbrains.compose.resources.stringResource

private val FolderCoverHeight = 176.dp
private val FolderAdaptiveHeroHeightFallback = 440.dp
private const val FolderAdaptiveHeroItemLimit = 8
private const val FolderCatalogPreviewLimit = 18

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FolderDetailScreen(
    onBack: () -> Unit,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onCastClick: (HeroCastMember) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val uiState by FolderDetailRepository.uiState.collectAsState()
    val homeSettings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsState()
    val folder = uiState.folder
    val collectionSections = remember(uiState.tabs) {
        FolderDetailRepository.getCatalogSectionsForRows()
    }
    val showImmersiveCollection = isDesktop &&
        homeSettings.heroEnabled &&
        homeSettings.tvModeEnabled &&
        collectionSections.isNotEmpty()
    val showAdaptiveCollection = isDesktop &&
        homeSettings.heroEnabled &&
        homeSettings.adaptiveHeroEnabled &&
        !homeSettings.tvModeEnabled &&
        collectionSections.isNotEmpty()
    // While Adaptive Hero or TV mode is the user's actual setting, an empty collectionSections
    // is only ever a momentary loading gap before the real per-item hero (above) takes over —
    // not a genuine "Default mode" view. Falling through to the plain cover-image banner below
    // for that gap flashes the folder's title logo (stretched full-bleed via ContentScale.Crop,
    // since it's a wordmark image, not a backdrop) full-screen for a frame. Suppress the banner
    // in that case; real Default mode (hero/adaptive/TV all off) is unaffected.
    val suppressFallbackCoverBanner = homeSettings.heroEnabled &&
        (homeSettings.adaptiveHeroEnabled || homeSettings.tvModeEnabled)
    val coverImageUrl = if (suppressFallbackCoverBanner) {
        null
    } else {
        folder?.coverImageUrl?.takeIf { it.isNotBlank() }
    }

    if (showImmersiveCollection) {
        ImmersiveCollectionContent(
            sections = collectionSections,
            watchedKeys = watchedUiState.watchedKeys,
            onBack = onBack,
            onCatalogClick = onCatalogClick,
            onCastClick = onCastClick,
            onPosterClick = onPosterClick,
        )
        return
    }

    if (showAdaptiveCollection) {
        AdaptiveCollectionContent(
            sections = collectionSections,
            watchedKeys = watchedUiState.watchedKeys,
            onBack = onBack,
            onCatalogClick = onCatalogClick,
            onCastClick = onCastClick,
            onPosterClick = onPosterClick,
        )
        return
    }

    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxHeroHeightPx = with(density) { FolderCoverHeight.toPx() }
    var heroHeightPx by remember(coverImageUrl, maxHeroHeightPx) {
        mutableFloatStateOf(if (coverImageUrl != null) maxHeroHeightPx else 0f)
    }

    val heroScrollConnection = remember(coverImageUrl, maxHeroHeightPx) {
        object : NestedScrollConnection {
            fun consumeHeroDelta(deltaY: Float): Float {
                if (coverImageUrl == null || deltaY == 0f) return 0f
                val previousHeight = heroHeightPx
                val nextHeight = (previousHeight + deltaY).coerceIn(0f, maxHeroHeightPx)
                heroHeightPx = nextHeight
                return nextHeight - previousHeight
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                return Offset(x = 0f, y = consumeHeroDelta(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                return Offset(x = 0f, y = consumeHeroDelta(available.y))
            }
        }
    }

    val heroHeight = with(density) { heroHeightPx.toDp() }
    val heroCollapseFraction = if (coverImageUrl == null || maxHeroHeightPx == 0f) {
        1f
    } else {
        1f - (heroHeightPx / maxHeroHeightPx)
    }
    val contentModifier = if (coverImageUrl != null) {
        Modifier.nestedScroll(heroScrollConnection)
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (coverImageUrl != null && heroHeight > 0.dp) {
            FolderCoverImage(
                imageUrl = coverImageUrl,
                title = folder?.title.orEmpty(),
                modifier = Modifier.height(heroHeight),
            )
        }

        NuvioScreenHeader(
            title = folder?.title ?: uiState.collectionTitle,
            modifier = Modifier.padding(horizontal = 16.dp),
            includeStatusBarPadding = coverImageUrl == null,
            topPadding = if (coverImageUrl != null) {
                statusBarTop * heroCollapseFraction
            } else {
                null
            },
            onBack = onBack,
        )

        if (folder == null && !uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.collections_folder_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (uiState.viewMode) {
            FolderViewMode.TABBED_GRID -> TabbedGridContent(
                uiState = uiState,
                watchedKeys = watchedUiState.watchedKeys,
                modifier = Modifier.weight(1f).then(contentModifier),
                onTabSelected = { FolderDetailRepository.selectTab(it) },
                onPosterClick = onPosterClick,
            )
            FolderViewMode.ROWS -> RowsContent(
                uiState = uiState,
                watchedKeys = watchedUiState.watchedKeys,
                modifier = Modifier.weight(1f).then(contentModifier),
                onCatalogClick = onCatalogClick,
                onPosterClick = onPosterClick,
            )
            FolderViewMode.FOLLOW_LAYOUT -> RowsContent(
                uiState = uiState,
                watchedKeys = watchedUiState.watchedKeys,
                modifier = Modifier.weight(1f).then(contentModifier),
                onCatalogClick = onCatalogClick,
                onPosterClick = onPosterClick,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImmersiveCollectionContent(
    sections: List<HomeCatalogSection>,
    watchedKeys: Set<String>,
    onBack: () -> Unit,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onCastClick: (HeroCastMember) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var activeRowIndex by remember { mutableIntStateOf(0) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var wheelLocked by remember { mutableStateOf(false) }
    var backButtonHovered by remember { mutableStateOf(false) }
    var activeHeroBackdrop by remember { mutableStateOf<String?>(null) }
    var activeHeroAccent by remember { mutableStateOf<Color?>(null) }
    val homeSettings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val ambientBackgroundEnabled = homeSettings.heroAmbientBackgroundEnabled
    val heroTrailerShowing by HomeHeroTrailerManualTrigger.active.collectAsStateWithLifecycle()
    val backButtonAlpha by animateFloatAsState(
        targetValue = if (backButtonHovered) 1f else 0f,
        label = "collection_back_button_alpha",
    )

    LaunchedEffect(sections) {
        activeRowIndex = activeRowIndex.coerceIn(0, sections.lastIndex.coerceAtLeast(0))
        activeItemIndex = activeItemIndex.coerceIn(
            0,
            (sections.getOrNull(activeRowIndex)?.items?.size?.minus(1) ?: 0).coerceAtLeast(0),
        )
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val activeSection = sections.getOrNull(activeRowIndex) ?: return
    val activeEntries = activeSection.items.take(FolderCatalogPreviewLimit)
    val activeRowEntries = if (activeSection.paginates) {
        activeSection.items
    } else {
        activeEntries
    }
    val metadataPrefetchItems = activeEntries +
        sections.getOrNull(activeRowIndex + 1)
            ?.items
            ?.take(FolderCatalogPreviewLimit)
            .orEmpty()
    // Look up against activeRowEntries, not the hero-preview-capped activeEntries: paginating
    // sections render/hover their full item list in the row, so a hover past the 18th tile
    // must still resolve to a real item instead of going null and freezing the hero on
    // whatever the pager last showed.
    val focusedItem = activeRowEntries.getOrNull(activeItemIndex)

    // Mirrors HomeScreen's handleHomeTvKey: a shared handler so the same navigation works
    // whether Compose still owns keyboard focus or the native hero-trailer surface has
    // grabbed it (in which case keys arrive via HomeTvKeyboardBridge instead).
    fun handleTvKey(key: HomeTvKey): Boolean = when (key) {
        HomeTvKey.Down -> {
            activeRowIndex = (activeRowIndex + 1).coerceAtMost(sections.lastIndex)
            activeItemIndex = activeItemIndex.coerceIn(
                0,
                (sections[activeRowIndex].items.size - 1).coerceAtLeast(0),
            )
            true
        }
        HomeTvKey.Up -> {
            activeRowIndex = (activeRowIndex - 1).coerceAtLeast(0)
            activeItemIndex = activeItemIndex.coerceIn(
                0,
                (sections[activeRowIndex].items.size - 1).coerceAtLeast(0),
            )
            true
        }
        HomeTvKey.Right -> {
            activeItemIndex = (activeItemIndex + 1).coerceAtMost((activeRowEntries.size - 1).coerceAtLeast(0))
            true
        }
        HomeTvKey.Left -> {
            activeItemIndex = (activeItemIndex - 1).coerceAtLeast(0)
            true
        }
        HomeTvKey.Select -> {
            focusedItem?.let(onPosterClick)
            true
        }
        HomeTvKey.ToggleTrailer -> {
            HomeHeroTrailerManualTrigger.trigger()
            true
        }
        HomeTvKey.Dismiss -> {
            if (heroTrailerShowing) {
                HomeHeroTrailerManualTrigger.trigger()
                true
            } else {
                false
            }
        }
        HomeTvKey.ToggleMute, HomeTvKey.VolumeDown, HomeTvKey.VolumeUp,
        HomeTvKey.TogglePeoplePanel, HomeTvKey.Search, HomeTvKey.Library -> false
    }
    val latestTvKeyHandler = rememberUpdatedState<(HomeTvKey) -> Boolean>(::handleTvKey)
    LaunchedEffect(Unit) {
        HomeTvKeyboardBridge.keys.collect { key -> latestTvKeyHandler.value(key) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (ambientBackgroundEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val direction = change.scrollDelta.y.compareTo(0f)
                if (direction != 0) {
                    change.consume()
                    if (!wheelLocked) {
                        wheelLocked = true
                        activeRowIndex = (activeRowIndex + direction).coerceIn(0, sections.lastIndex)
                        activeItemIndex = activeItemIndex.coerceIn(
                            0,
                            (sections[activeRowIndex].items.size - 1).coerceAtLeast(0),
                        )
                        coroutineScope.launch {
                            delay(220)
                            wheelLocked = false
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.navigationKey()) {
                    Key.Backspace -> {
                        onBack()
                        true
                    }
                    Key.DirectionDown -> handleTvKey(HomeTvKey.Down)
                    Key.DirectionUp -> handleTvKey(HomeTvKey.Up)
                    Key.DirectionRight -> handleTvKey(HomeTvKey.Right)
                    Key.DirectionLeft -> handleTvKey(HomeTvKey.Left)
                    Key.Enter, Key.NumPadEnter -> handleTvKey(HomeTvKey.Select)
                    Key.T -> handleTvKey(HomeTvKey.ToggleTrailer)
                    Key.Escape -> handleTvKey(HomeTvKey.Dismiss)
                    else -> false
                }
            },
    ) {
        if (ambientBackgroundEnabled) {
            HeroAmbientBackdrop(
                backdrop = activeHeroBackdrop,
                accent = activeHeroAccent,
                onAccentChanged = { activeHeroAccent = it },
                label = "collection_hero_ambient_background",
            )
        }

        val shelfHeight = (maxHeight * 0.43f).coerceIn(300.dp, 440.dp)
        // Fill-the-shelf poster sizing, matching HomeScreen's TV Mode shelf. Without the
        // override the row below fell back to the saved poster width preference, which
        // account sync can overwrite with a mobile-sized value.
        val shelfPosterBaseWidthDp = remember(maxWidth, shelfHeight) {
            immersiveCatalogPosterBaseWidthDp(
                maxWidthDp = maxWidth.value,
                shelfHeightDp = shelfHeight.value,
                sectionPaddingDp = homeSectionHorizontalPaddingForWidth(maxWidth.value).value,
                hideLabels = true,
            )
        }
        HomeHeroSection(
            items = activeEntries.take(FolderAdaptiveHeroItemLimit),
            focusedItem = focusedItem,
            metadataPrefetchItems = metadataPrefetchItems,
            heightOverride = maxHeight,
            roundedBottomCorners = false,
            immersiveMode = true,
            heroInfoLines = homeSettings.heroInfoLines,
            heroInfoPriority = homeSettings.heroInfoPriority,
            heroBadgePlacement = homeSettings.heroBadgePlacement,
            heroReleaseStatusUnavailableOnly = homeSettings.heroReleaseStatusUnavailableOnly,
            immersiveContentBottomPadding = shelfHeight - 20.dp,
            onActiveItemChanged = { item ->
                activeHeroBackdrop = item.banner ?: item.poster
            },
            onCastClick = onCastClick,
            onItemClick = onPosterClick,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(shelfHeight)
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.30f to MaterialTheme.colorScheme.background.copy(alpha = 0.28f),
                            0.62f to MaterialTheme.colorScheme.background.copy(alpha = if (ambientBackgroundEnabled) 0.74f else 0.88f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = if (ambientBackgroundEnabled) 0.82f else 1f),
                        ),
                    ),
                )
                .padding(top = 68.dp, bottom = 12.dp),
        ) {
            HomeCatalogRowSection(
                section = activeSection,
                entries = activeRowEntries,
                watchedKeys = watchedKeys,
                basePosterWidthDpOverride = shelfPosterBaseWidthDp,
                focusedItemIndex = activeItemIndex,
                onHoverItem = { itemIndex -> activeItemIndex = itemIndex },
                onViewAllClick = if (
                    !activeSection.paginates &&
                    activeSection.canOpenCatalog(FolderCatalogPreviewLimit)
                ) {
                    { onCatalogClick(activeSection) }
                } else {
                    null
                },
                onLoadMore = if (activeSection.paginates) {
                    { FolderDetailRepository.loadMoreCatalogRow(activeSection) }
                } else {
                    null
                },
                isLoadingMore = activeSection.isLoadingMore,
                onPosterClick = onPosterClick,
            )
        }

        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 20.dp)
                .size(40.dp)
                .onPointerEvent(PointerEventType.Enter) { backButtonHovered = true }
                .onPointerEvent(PointerEventType.Exit) { backButtonHovered = false },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f * backButtonAlpha),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = backButtonAlpha),
            iconSize = 24.dp,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AdaptiveCollectionContent(
    sections: List<HomeCatalogSection>,
    watchedKeys: Set<String>,
    onBack: () -> Unit,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onCastClick: (HeroCastMember) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var activeRowIndex by remember { mutableIntStateOf(0) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    // The mouse cursor stays at whatever screen position it was at on Home when the user
    // clicked into this collection. Since this screen always mounts scrolled to the top,
    // Compose Desktop's hit-testing fires a synthetic hover "Enter" for whichever row now
    // sits under that stationary cursor - which, the further down Home was scrolled, the
    // deeper into this list it lands - snapping the hero/active row there and making the
    // screen look like it didn't open at the start. Arm ignoreNextMouseMove synchronously
    // (mirrors HomeScreen's native-surface-disposal guard) so that first synthetic event is
    // swallowed; a genuine mouse move afterwards re-activates hover normally.
    val mouseActivity = remember { MouseActivityState().apply { onKeyboardNavigation(ignoreNextMouseMove = true) } }
    var backButtonHovered by remember { mutableStateOf(false) }
    var activeHeroBackdrop by remember { mutableStateOf<String?>(null) }
    var activeHeroAccent by remember { mutableStateOf<Color?>(null) }
    val homeSettings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val ambientBackgroundEnabled = homeSettings.heroAmbientBackgroundEnabled
    val heroTrailerShowing by HomeHeroTrailerManualTrigger.active.collectAsStateWithLifecycle()
    val backButtonAlpha by animateFloatAsState(
        targetValue = if (backButtonHovered) 1f else 0f,
        label = "tv_collection_back_button_alpha",
    )

    LaunchedEffect(sections) {
        activeRowIndex = activeRowIndex.coerceIn(0, sections.lastIndex.coerceAtLeast(0))
        activeItemIndex = activeItemIndex.coerceIn(
            0,
            (sections.getOrNull(activeRowIndex)?.items?.size?.minus(1) ?: 0).coerceAtLeast(0),
        )
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val activeSection = sections.getOrNull(activeRowIndex) ?: return
    val activeEntries = activeSection.items.take(FolderCatalogPreviewLimit)
    val activeRowEntries = if (activeSection.paginates) {
        activeSection.items
    } else {
        activeEntries
    }
    val metadataPrefetchItems = activeEntries +
        sections.getOrNull(activeRowIndex + 1)?.items?.take(FolderCatalogPreviewLimit).orEmpty()
    // Look up against activeRowEntries, not the hero-preview-capped activeEntries: paginating
    // sections render/hover their full item list in the row, so a hover past the 18th tile
    // must still resolve to a real item instead of going null and freezing the hero on
    // whatever the pager last showed.
    val focusedItem = activeRowEntries.getOrNull(activeItemIndex)

    // Mirrors HomeScreen's handleHomeTvKey: a shared handler so the same navigation works
    // whether Compose still owns keyboard focus or the native hero-trailer surface has
    // grabbed it (in which case keys arrive via HomeTvKeyboardBridge instead).
    fun handleTvKey(key: HomeTvKey): Boolean = when (key) {
        HomeTvKey.Down -> {
            activeRowIndex = (activeRowIndex + 1).coerceAtMost(sections.lastIndex)
            activeItemIndex = activeItemIndex.coerceIn(
                0,
                (sections[activeRowIndex].items.size - 1).coerceAtLeast(0),
            )
            coroutineScope.launch { lazyListState.animateScrollToItem(activeRowIndex) }
            true
        }
        HomeTvKey.Up -> {
            activeRowIndex = (activeRowIndex - 1).coerceAtLeast(0)
            activeItemIndex = activeItemIndex.coerceIn(
                0,
                (sections[activeRowIndex].items.size - 1).coerceAtLeast(0),
            )
            coroutineScope.launch { lazyListState.animateScrollToItem(activeRowIndex) }
            true
        }
        HomeTvKey.Right -> {
            activeItemIndex = (activeItemIndex + 1).coerceAtMost((activeRowEntries.size - 1).coerceAtLeast(0))
            true
        }
        HomeTvKey.Left -> {
            activeItemIndex = (activeItemIndex - 1).coerceAtLeast(0)
            true
        }
        HomeTvKey.Select -> {
            focusedItem?.let(onPosterClick)
            true
        }
        HomeTvKey.ToggleTrailer -> {
            HomeHeroTrailerManualTrigger.trigger()
            true
        }
        HomeTvKey.Dismiss -> {
            if (heroTrailerShowing) {
                HomeHeroTrailerManualTrigger.trigger()
                true
            } else {
                false
            }
        }
        HomeTvKey.ToggleMute, HomeTvKey.VolumeDown, HomeTvKey.VolumeUp,
        HomeTvKey.TogglePeoplePanel, HomeTvKey.Search, HomeTvKey.Library -> false
    }
    val latestTvKeyHandler = rememberUpdatedState<(HomeTvKey) -> Boolean>(::handleTvKey)
    LaunchedEffect(Unit) {
        HomeTvKeyboardBridge.keys.collect { key -> latestTvKeyHandler.value(key) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (ambientBackgroundEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                mouseActivity.onMouseMoved(event.changes.first().position)
            }
            // Enter fires before Move when the cursor first crosses into a child;
            // use Initial pass so isMouseActive is set before child Enter handlers run.
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                mouseActivity.onMouseMoved(event.changes.first().position)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.navigationKey()) {
                    Key.Backspace -> { onBack(); true }
                    Key.DirectionDown -> handleTvKey(HomeTvKey.Down)
                    Key.DirectionUp -> handleTvKey(HomeTvKey.Up)
                    Key.DirectionRight -> handleTvKey(HomeTvKey.Right)
                    Key.DirectionLeft -> handleTvKey(HomeTvKey.Left)
                    Key.Enter, Key.NumPadEnter -> handleTvKey(HomeTvKey.Select)
                    Key.T -> handleTvKey(HomeTvKey.ToggleTrailer)
                    Key.Escape -> handleTvKey(HomeTvKey.Dismiss)
                    else -> false
                }
            },
    ) {
        if (ambientBackgroundEnabled) {
            HeroAmbientBackdrop(
                backdrop = activeHeroBackdrop,
                accent = activeHeroAccent,
                onAccentChanged = { activeHeroAccent = it },
                label = "collection_hero_ambient_background",
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box {
                HomeHeroSection(
                    items = activeEntries.take(FolderAdaptiveHeroItemLimit),
                    focusedItem = focusedItem,
                    metadataPrefetchItems = metadataPrefetchItems,
                    viewportHeight = FolderAdaptiveHeroHeightFallback,
                    roundedBottomCorners = false,
                    adaptiveHeroMode = true,
                    heroInfoLines = homeSettings.heroInfoLines,
                    heroInfoPriority = homeSettings.heroInfoPriority,
                    heroBadgePlacement = homeSettings.heroBadgePlacement,
                    heroReleaseStatusUnavailableOnly = homeSettings.heroReleaseStatusUnavailableOnly,
                    onActiveItemChanged = { item ->
                        activeHeroBackdrop = item.banner ?: item.poster
                    },
                    onCastClick = onCastClick,
                    onItemClick = onPosterClick,
                )
                NuvioBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 20.dp, top = 20.dp)
                        .size(40.dp)
                        .onPointerEvent(PointerEventType.Enter) { backButtonHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { backButtonHovered = false },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f * backButtonAlpha),
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = backButtonAlpha),
                    iconSize = 24.dp,
                )
            }

            LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
                sections.forEachIndexed { rowIndex, section ->
                    val previewEntries = section.items.take(FolderCatalogPreviewLimit)
                    val entries = if (section.paginates) {
                        section.items
                    } else {
                        previewEntries
                    }
                    item(key = section.key) {
                        HomeCatalogRowSection(
                            section = section,
                            entries = entries,
                            watchedKeys = watchedKeys,
                            focusedItemIndex = if (rowIndex == activeRowIndex) activeItemIndex else null,
                            onHoverItem = { itemIndex ->
                                if (mouseActivity.isMouseActive) {
                                    activeRowIndex = rowIndex
                                    activeItemIndex = itemIndex
                                }
                            },
                            onViewAllClick = if (
                                !section.paginates &&
                                section.canOpenCatalog(FolderCatalogPreviewLimit)
                            ) {
                                { onCatalogClick(section) }
                            } else {
                                null
                            },
                            onLoadMore = if (section.paginates) {
                                { FolderDetailRepository.loadMoreCatalogRow(section) }
                            } else {
                                null
                            },
                            isLoadingMore = section.isLoadingMore,
                            onPosterClick = onPosterClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCoverImage(
    imageUrl: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = title,
        modifier = modifier
            .fillMaxWidth()
            .height(FolderCoverHeight),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun TabbedGridContent(
    uiState: FolderDetailUiState,
    watchedKeys: Set<String>,
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, uiState.selectedTabIndex, uiState.selectedTabCanLoadMore, uiState.selectedTabIsLoadingMore) {
        snapshotFlow { gridState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 6
            }
            .distinctUntilChanged()
            .filter { it && uiState.selectedTabCanLoadMore && !uiState.selectedTabIsLoadingMore }
            .collect {
                FolderDetailRepository.loadMoreSelectedTab()
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.tabs.size > 1) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                ScrollableTabRow(
                    selectedTabIndex = uiState.selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    divider = {},
                ) {
                    uiState.tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == uiState.selectedTabIndex,
                            onClick = { onTabSelected(index) },
                            text = {
                                Text(
                                    text = if (tab.isAllTab) {
                                        stringResource(Res.string.collections_tab_all)
                                    } else {
                                        tab.label
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val selectedTab = uiState.tabs.getOrNull(uiState.selectedTabIndex)
        if (selectedTab == null) return

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = remember(maxWidth) { folderDetailGridColumnsForWidth(maxWidth) }

            when {
                selectedTab.isLoading && selectedTab.items.isEmpty() -> LoadingIndicator()
                selectedTab.error != null && selectedTab.items.isEmpty() -> ErrorMessage(selectedTab.error)
                selectedTab.items.isEmpty() -> EmptyMessage()
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = nuvioSafeBottomPadding(18.dp),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            items = selectedTab.items.withDuplicateSafeLazyKeys { item -> item.stableKey() },
                            key = { item -> item.lazyKey },
                        ) { keyedItem ->
                            val item = keyedItem.value
                            NuvioPosterCard(
                                title = item.name,
                                imageUrl = item.poster,
                                shape = NuvioPosterShape.Poster,
                                detailLine = item.releaseInfo,
                                isWatched = WatchingState.isPosterWatched(
                                    watchedKeys = watchedKeys,
                                    item = item,
                                ),
                                onClick = { onPosterClick(item) },
                            )
                        }

                        if (uiState.selectedTabIsLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PaginationLoadingFooter()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowsContent(
    uiState: FolderDetailUiState,
    watchedKeys: Set<String>,
    modifier: Modifier = Modifier,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val sections = FolderDetailRepository.getCatalogSectionsForRows()

    if (uiState.isLoading && sections.isEmpty()) {
        LoadingIndicator()
        return
    }

    if (sections.isEmpty() && !uiState.isLoading) {
        EmptyMessage()
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = nuvioSafeBottomPadding(18.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = sections.withDuplicateSafeLazyKeys { it.key },
            key = { it.lazyKey },
        ) { keyedSection ->
            val section = keyedSection.value
            val entries = if (section.paginates) {
                section.items
            } else {
                section.items.take(FolderCatalogPreviewLimit)
            }
            HomeCatalogRowSection(
                section = section,
                entries = entries,
                onViewAllClick = if (
                    !section.paginates &&
                    section.canOpenCatalog(FolderCatalogPreviewLimit)
                ) {
                    { onCatalogClick(section) }
                } else {
                    null
                },
                onLoadMore = if (section.paginates) {
                    { FolderDetailRepository.loadMoreCatalogRow(section) }
                } else {
                    null
                },
                isLoadingMore = section.isLoadingMore,
                watchedKeys = watchedKeys,
                onPosterClick = { onPosterClick(it) },
            )
        }
    }
}

@Composable
private fun PaginationLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

private fun folderDetailGridColumnsForWidth(screenWidth: Dp): Int =
    when {
        screenWidth >= 1400.dp -> 7
        screenWidth >= 1200.dp -> 6
        screenWidth >= 1000.dp -> 5
        screenWidth >= 840.dp -> 4
        else -> 3
    }

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun ErrorMessage(error: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyMessage() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.collections_folder_empty_items),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
