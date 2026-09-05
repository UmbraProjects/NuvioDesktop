package com.nuvio.app.features.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryItemType
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.CloudLibraryFileEntry
import com.nuvio.app.features.cloud.cloudLibraryWindowLabel
import com.nuvio.app.features.cloud.formatCloudLibraryBytes
import com.nuvio.app.features.cloud.playableFileEntries
import com.nuvio.app.isDesktop
import com.nuvio.app.features.debrid.DebridCloudLibraryWindow
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.Refresh

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onPosterClick: ((LibraryItem) -> Unit)? = null,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    onSectionViewAllClick: ((LibrarySection) -> Unit)? = null,
    onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    onConnectCloudClick: (() -> Unit)? = null,
    onNavigateToHome: (() -> Unit)? = null,
    onCalendarClick: (() -> Unit)? = null,
) {
    val uiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val cloudUiState by CloudLibraryRepository.uiState.collectAsStateWithLifecycle()
    val cloudSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val displaySettings by remember {
        LibraryDisplaySettingsRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeCatalogSettingsUiState by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    var observedOfflineState by remember { mutableStateOf(false) }
    var sourceModeName by rememberSaveable { mutableStateOf(LibraryViewMode.Saved.name) }
    val sourceMode = remember(sourceModeName) {
        runCatching { LibraryViewMode.valueOf(sourceModeName) }.getOrDefault(LibraryViewMode.Saved)
    }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedType = remember(selectedTypeName) {
        selectedTypeName?.let { runCatching { CloudLibraryItemType.valueOf(it) }.getOrNull() }
    }
    var selectedCloudItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val screenFocusRequester = remember { FocusRequester() }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    var focusedItemIndex by remember { mutableIntStateOf(0) }
    val isTraktSource = uiState.sourceMode == LibrarySourceMode.TRAKT
    val isRemoteSource = uiState.sourceMode != LibrarySourceMode.LOCAL
    val sortedSections = remember(uiState.sections, displaySettings.sortOption, uiState.sourceMode) {
        sortLibrarySections(uiState.sections, displaySettings.sortOption, uiState.sourceMode)
    }
    val gridEntries = remember(sortedSections, displaySettings.sortOption, uiState.sourceMode) {
        libraryGridEntries(sortedSections, displaySettings.sortOption, uiState.sourceMode)
    }

    LaunchedEffect(Unit) {
        if (isDesktop) try { screenFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(uiState.sections.size) {
        if (uiState.sections.isNotEmpty()) {
            focusedRowIndex = focusedRowIndex.coerceAtMost(uiState.sections.lastIndex)
        }
    }
    val retryLibraryLoad: () -> Unit = {
        NetworkStatusRepository.requestRefresh(force = true)
        coroutineScope.launch {
            LibraryRepository.pullFromServer(ProfileRepository.activeProfileId)
        }
    }

    LaunchedEffect(networkStatusUiState.condition, isRemoteSource) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false
                if (isRemoteSource) {
                    coroutineScope.launch {
                        LibraryRepository.pullFromServer(ProfileRepository.activeProfileId)
                    }
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(
        sourceMode,
        cloudSettings.cloudLibraryEnabled,
        cloudSettings.cloudLibraryWindow,
        cloudSettings.providerApiKeys,
    ) {
        if (sourceMode == LibraryViewMode.Cloud) {
            CloudLibraryRepository.ensureLoaded()
            selectedCloudItemKey = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val gridColumns = remember(maxWidth) {
        when {
            maxWidth >= 1400.dp -> 7
            maxWidth >= 1200.dp -> 6
            maxWidth >= 1000.dp -> 5
            maxWidth >= 840.dp -> 4
            else -> 3
        }
    }
    NuvioScreen(
        modifier = Modifier.fillMaxSize().then(
            if (isDesktop) {
                Modifier
                    .focusRequester(screenFocusRequester)
                    .focusable()
                    .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                        try { screenFocusRequester.requestFocus() } catch (_: Exception) {}
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val sections = sortedSections
                        when (event.navigationKey()) {
                            Key.L, Key.H -> { onNavigateToHome?.invoke(); true }
                            Key.DirectionDown -> {
                                val maxRow = if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                                    ((gridEntries.size - 1).coerceAtLeast(0) / gridColumns)
                                } else {
                                    (sections.size - 1).coerceAtLeast(0)
                                }
                                focusedRowIndex = (focusedRowIndex + 1).coerceAtMost(maxRow)
                                if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                                    val rowSize = (gridEntries.size - focusedRowIndex * gridColumns).coerceIn(0, gridColumns)
                                    focusedItemIndex = focusedItemIndex.coerceAtMost((rowSize - 1).coerceAtLeast(0))
                                } else {
                                    focusedItemIndex = 0
                                }
                                coroutineScope.launch {
                                    listState.animateScrollToItem((focusedRowIndex + 1).coerceAtLeast(0))
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                focusedRowIndex = (focusedRowIndex - 1).coerceAtLeast(0)
                                if (displaySettings.layoutMode != LibraryLayoutMode.GRID) focusedItemIndex = 0
                                coroutineScope.launch {
                                    listState.animateScrollToItem(if (focusedRowIndex == 0) 0 else focusedRowIndex + 1)
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                val maxItem = if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                                    val remaining = gridEntries.size - focusedRowIndex * gridColumns
                                    (remaining.coerceAtMost(gridColumns) - 1).coerceAtLeast(0)
                                } else {
                                    (sections.getOrNull(focusedRowIndex)?.items?.take(LIBRARY_SECTION_PREVIEW_LIMIT)?.size?.minus(1) ?: 0).coerceAtLeast(0)
                                }
                                focusedItemIndex = (focusedItemIndex + 1).coerceAtMost(maxItem)
                                true
                            }
                            Key.DirectionLeft -> {
                                focusedItemIndex = (focusedItemIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                val selectedItem = if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                                    gridEntries.getOrNull(focusedRowIndex * gridColumns + focusedItemIndex)?.item
                                } else {
                                    sections.getOrNull(focusedRowIndex)?.items?.take(LIBRARY_SECTION_PREVIEW_LIMIT)?.getOrNull(focusedItemIndex)
                                }
                                selectedItem
                                    ?.let { onPosterClick?.invoke(it) }
                                true
                            }
                            else -> false
                        }
                    }
            } else {
                Modifier
            },
        ),
        horizontalPadding = 0.dp,
        topPadding = if (topChromePadding != null) 0.dp else null,
        listState = listState,
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
                        title = if (sourceMode == LibraryViewMode.Cloud) {
                            stringResource(Res.string.library_title)
                        } else if (isTraktSource) {
                            stringResource(Res.string.library_trakt_title)
                        } else {
                            stringResource(Res.string.library_title)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        topPadding = topChromePadding,
                        titleTrailing = {
                            // Trakt calendar pairs with the (Trakt) library title — sits just to its
                            // right, vertically centered on the text line (a plain clickable Icon
                            // rather than a 48dp IconButton, which would sit off-line).
                            if (onCalendarClick != null) {
                                Icon(
                                    imageVector = Icons.Rounded.DateRange,
                                    contentDescription = stringResource(Res.string.calendar_title),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onCalendarClick)
                                        .padding(6.dp)
                                        .size(28.dp),
                                )
                            }
                        },
                    )
                    LibrarySourceSwitch(
                        selectedMode = sourceMode,
                        onModeSelected = { mode ->
                            sourceModeName = mode.name
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        if (sourceMode == LibraryViewMode.Cloud) {
            cloudLibraryContent(
                uiState = cloudUiState,
                selectedProviderId = selectedProviderId,
                selectedType = selectedType,
                selectedWindow = cloudSettings.cloudLibraryWindow,
                selectedCloudItemKey = selectedCloudItemKey,
                onWindowSelected = { window ->
                    DebridSettingsRepository.setCloudLibraryWindow(window)
                    selectedCloudItemKey = null
                },
                onProviderSelected = {
                    selectedProviderId = it
                    selectedTypeName = null
                    selectedCloudItemKey = null
                },
                onTypeSelected = {
                    selectedTypeName = it?.name
                    selectedCloudItemKey = null
                },
                onItemSelected = { item ->
                    val playableFiles = item.playableFiles
                    when {
                        playableFiles.size == 1 -> onCloudFilePlay?.invoke(item, playableFiles.first())
                        playableFiles.size > 1 -> selectedCloudItemKey = item.stableKey
                    }
                },
                onFileSelected = { item, file -> onCloudFilePlay?.invoke(item, file) },
                onBackToItems = { selectedCloudItemKey = null },
                onRefresh = { CloudLibraryRepository.refresh() },
                onConnectCloudClick = onConnectCloudClick,
            )
        } else {
            when {
                !uiState.isLoaded || (uiState.isLoading && uiState.sections.isEmpty()) -> {
                    if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                        libraryGridSkeletonItems(gridColumns)
                    } else {
                        items(3) {
                            HomeSkeletonRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                showHeaderAccent = !homeCatalogSettingsUiState.hideCatalogUnderline,
                            )
                        }
                    }
                }

                !uiState.errorMessage.isNullOrBlank() && uiState.sections.isEmpty() -> {
                    item {
                        if (networkStatusUiState.isOfflineLike) {
                            NuvioNetworkOfflineCard(
                                condition = networkStatusUiState.condition,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onRetry = retryLibraryLoad,
                            )
                        } else {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = if (isTraktSource) {
                                    stringResource(Res.string.library_trakt_load_failed)
                                } else {
                                    stringResource(Res.string.library_load_failed)
                                },
                                message = uiState.errorMessage.orEmpty(),
                                actionLabel = stringResource(Res.string.action_retry),
                                onActionClick = retryLibraryLoad,
                            )
                        }
                    }
                }

                uiState.sections.isEmpty() -> {
                    item {
                        if (networkStatusUiState.isOfflineLike && isRemoteSource) {
                            NuvioNetworkOfflineCard(
                                condition = networkStatusUiState.condition,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onRetry = retryLibraryLoad,
                            )
                        } else {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = if (isTraktSource) {
                                    stringResource(Res.string.library_trakt_empty_title)
                                } else {
                                    stringResource(Res.string.library_empty_title)
                                },
                                message = if (isTraktSource) {
                                    stringResource(Res.string.library_trakt_empty_message)
                                } else {
                                    stringResource(Res.string.library_empty_message)
                                },
                            )
                        }
                    }
                }

                else -> {
                    if (displaySettings.layoutMode == LibraryLayoutMode.GRID) {
                        libraryGridContent(
                            entries = gridEntries,
                            columns = gridColumns,
                            watchedKeys = watchedUiState.watchedKeys,
                            focusedRowIndex = focusedRowIndex,
                            focusedItemIndex = focusedItemIndex,
                            onPosterClick = onPosterClick,
                            onPosterLongClick = onPosterLongClick,
                        )
                    } else {
                        librarySections(
                            sections = sortedSections,
                            watchedKeys = watchedUiState.watchedKeys,
                            showHeaderAccent = !homeCatalogSettingsUiState.hideCatalogUnderline,
                            focusedRowIndex = focusedRowIndex,
                            focusedItemIndex = focusedItemIndex,
                            onHoverItem = if (isDesktop) { rowIdx, itemIdx ->
                                focusedRowIndex = rowIdx
                                focusedItemIndex = itemIdx
                            } else null,
                            onPosterClick = onPosterClick,
                            onSectionViewAllClick = onSectionViewAllClick,
                            onPosterLongClick = onPosterLongClick,
                        )
                    }
                }
            }
        }
    }
    }
}

private fun LazyListScope.libraryGridContent(
    entries: List<LibraryGridEntry>,
    columns: Int,
    watchedKeys: Set<String>,
    focusedRowIndex: Int,
    focusedItemIndex: Int,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
) {
    itemsIndexed(entries.chunked(columns), key = { _, row -> "library-grid:${row.first().item.type}:${row.first().item.id}" }) { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            row.forEachIndexed { itemIndex, entry ->
                val focused = isDesktop && rowIndex == focusedRowIndex && itemIndex == focusedItemIndex
                HomePosterCard(
                    item = entry.item.toMetaPreview(),
                    modifier = Modifier.weight(1f).then(
                        if (focused) Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(14.dp),
                        ) else Modifier,
                    ),
                    isWatched = WatchingState.isPosterWatched(watchedKeys, entry.item.toMetaPreview()),
                    onClick = onPosterClick?.let { { it(entry.item) } },
                    onLongClick = onPosterLongClick?.let { { it(entry.item, entry.section) } },
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun LazyListScope.libraryGridSkeletonItems(columns: Int) {
    items(2) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(columns) {
                Box(
                    Modifier.weight(1f).height(210.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

@Composable
fun LibraryNavigationContextMenu(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        content(Modifier.secondaryClick { expanded = true })
        LibraryDisplayDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false })
    }
}

// The library sort menu is styled after the floating search bar's discover picker, so the two
// navbar menus read as one system: same translucent surface, hairline border and row metrics.
internal val LibraryNavMenuWidth = 320.dp
internal val LibraryNavMenuBorderColor = Color.White.copy(alpha = 0.13f)
private val LibraryNavMenuRowSelectedColor = Color.White.copy(alpha = 0.1f)

@Composable
internal fun libraryNavMenuSurfaceColor(): Color {
    val tokens = MaterialTheme.nuvio
    return tokens.colors.surface.copy(alpha = tokens.opacity.strong)
}

private val LibrarySortEntries: List<Pair<LibrarySortOption, String>> = listOf(
    LibrarySortOption.DEFAULT to "Default order",
    LibrarySortOption.ADDED_DESC to "Recently added",
    LibrarySortOption.ADDED_ASC to "Oldest added",
    LibrarySortOption.TITLE_ASC to "Title A–Z",
    LibrarySortOption.TITLE_DESC to "Title Z–A",
)

/**
 * Standalone popup form, used where the menu has no bar to hang off (bottom navigation bar,
 * desktop hover sidebar). The floating top bar attaches [LibrarySortMenuPanel] under the pill
 * instead.
 */
@Composable
fun LibraryDisplayDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = libraryNavMenuSurfaceColor(),
        tonalElevation = 0.dp,
        shadowElevation = MaterialTheme.nuvio.elevation.overlay,
        border = BorderStroke(0.5.dp, LibraryNavMenuBorderColor),
        modifier = Modifier.width(240.dp),
    ) {
        LibrarySortMenuItems(onDismissRequest = onDismissRequest)
    }
}

/**
 * The menu body without a container, for hosts that supply their own surface — the floating top
 * bar hangs this off the bottom of the nav pill exactly like the discover picker.
 */
@Composable
fun LibrarySortMenuPanel(
    shape: Shape,
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit = {},
) {
    Surface(
        color = libraryNavMenuSurfaceColor(),
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.5.dp, LibraryNavMenuBorderColor),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            LibrarySortMenuItems(onDismissRequest = onDismissRequest)
        }
    }
}

@Composable
private fun LibrarySortMenuItems(onDismissRequest: () -> Unit) {
    val settings by remember {
        LibraryDisplaySettingsRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    Text(
        text = stringResource(Res.string.library_sort),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
    )
    LibrarySortEntries.forEach { (option, label) ->
        LibraryMenuItem(label = label, selected = settings.sortOption == option) {
            LibraryDisplaySettingsRepository.setSortOption(option)
            onDismissRequest()
        }
    }
}

@Composable
private fun LibraryMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) LibraryNavMenuRowSelectedColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Kept in the layout even when unselected so every label starts on the same column.
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) Color.White else Color.Transparent,
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LazyListScope.cloudLibraryContent(
    uiState: CloudLibraryUiState,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    selectedWindow: DebridCloudLibraryWindow,
    selectedCloudItemKey: String?,
    onWindowSelected: (DebridCloudLibraryWindow) -> Unit,
    onProviderSelected: (String?) -> Unit,
    onTypeSelected: (CloudLibraryItemType?) -> Unit,
    onItemSelected: (CloudLibraryItem) -> Unit,
    onFileSelected: (CloudLibraryItem, CloudLibraryFile) -> Unit,
    onBackToItems: () -> Unit,
    onRefresh: () -> Unit,
    onConnectCloudClick: (() -> Unit)?,
) {
    when {
        !uiState.isLoaded -> {
            cloudLibrarySkeletonItems()
        }

        !uiState.isEnabled -> {
            item {
                HomeEmptyStateCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = stringResource(Res.string.cloud_library_disabled_title),
                    message = stringResource(Res.string.cloud_library_disabled_message),
                    actionLabel = stringResource(Res.string.cloud_library_disabled_action),
                    onActionClick = onConnectCloudClick,
                )
            }
        }

        !uiState.hasConnectedProvider -> {
            item {
                HomeEmptyStateCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = stringResource(Res.string.cloud_library_connect_title),
                    message = stringResource(Res.string.cloud_library_connect_message),
                    actionLabel = stringResource(Res.string.cloud_library_connect_action),
                    onActionClick = onConnectCloudClick,
                )
            }
        }

        else -> {
            val providerItems = uiState.items
                .filter { item -> selectedProviderId == null || item.providerId == selectedProviderId }
            val availableTypes = providerItems
                .map { item -> item.type }
                .distinct()
                .sortedBy { type -> type.ordinal }
            val effectiveSelectedType = selectedType?.takeIf { type -> type in availableTypes }
            val filteredItems = providerItems
                .filter { item -> effectiveSelectedType == null || item.type == effectiveSelectedType }
            val selectedItem = filteredItems.firstOrNull { it.stableKey == selectedCloudItemKey }

            if (selectedItem != null) {
                item {
                    CloudLibraryFilePicker(
                        item = selectedItem,
                        onBack = onBackToItems,
                        onFileSelected = { file -> onFileSelected(selectedItem, file) },
                    )
                }
            } else {
                item {
                    CloudLibraryToolbar(
                        uiState = uiState,
                        selectedProviderId = selectedProviderId,
                        selectedType = effectiveSelectedType,
                        selectedWindow = selectedWindow,
                        availableTypes = availableTypes,
                        onWindowSelected = onWindowSelected,
                        onProviderSelected = onProviderSelected,
                        onTypeSelected = onTypeSelected,
                        onRefresh = onRefresh,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                uiState.providers
                    .filter { providerState -> selectedProviderId == null || providerState.providerId == selectedProviderId }
                    .filter { providerState -> !providerState.errorMessage.isNullOrBlank() && providerState.items.isEmpty() }
                    .forEach { providerState ->
                        item(key = "cloud-error-${providerState.providerId}") {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = stringResource(Res.string.cloud_library_load_failed, providerState.providerName),
                                message = providerState.errorMessage.orEmpty(),
                                actionLabel = stringResource(Res.string.action_retry),
                                onActionClick = onRefresh,
                            )
                        }
                    }

                if (uiState.isRefreshing && filteredItems.isEmpty()) {
                    cloudLibrarySkeletonItems()
                } else if (filteredItems.isEmpty()) {
                    item {
                        HomeEmptyStateCard(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            title = stringResource(Res.string.cloud_library_empty_title),
                            message = stringResource(Res.string.cloud_library_empty_message),
                            actionLabel = stringResource(Res.string.action_retry),
                            onActionClick = onRefresh,
                        )
                    }
                } else {
                    items(
                        items = filteredItems,
                        key = { item -> item.stableKey },
                    ) { item ->
                        CloudLibraryRow(
                            item = item,
                            onClick = { onItemSelected(item) },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.cloudLibrarySkeletonItems() {
    item(key = "cloud-library-skeleton-toolbar") {
        CloudLibrarySkeletonToolbar(
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    items(3) {
        CloudLibrarySkeletonRow()
    }
}

@Composable
private fun LibrarySourceSwitch(
    selectedMode: LibraryViewMode,
    onModeSelected: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryChip(
            label = stringResource(Res.string.library_source_saved),
            selected = selectedMode == LibraryViewMode.Saved,
            onClick = { onModeSelected(LibraryViewMode.Saved) },
        )
        LibraryChip(
            label = stringResource(Res.string.library_source_cloud),
            selected = selectedMode == LibraryViewMode.Cloud,
            onClick = { onModeSelected(LibraryViewMode.Cloud) },
        )
    }
}

@Composable
private fun CloudLibraryToolbar(
    uiState: CloudLibraryUiState,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    selectedWindow: DebridCloudLibraryWindow,
    availableTypes: List<CloudLibraryItemType>,
    onWindowSelected: (DebridCloudLibraryWindow) -> Unit,
    onProviderSelected: (String?) -> Unit,
    onTypeSelected: (CloudLibraryItemType?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerOptions = buildList {
        add(NuvioDropdownOption(key = "", label = stringResource(Res.string.cloud_library_provider_all)))
        addAll(
            uiState.providers.map { provider ->
                NuvioDropdownOption(
                    key = provider.providerId,
                    label = provider.providerName,
                )
            },
        )
    }
    val typeOptions = buildList {
        add(NuvioDropdownOption(key = "", label = stringResource(Res.string.cloud_library_type_all)))
        addAll(
            availableTypes.map { type ->
                NuvioDropdownOption(
                    key = type.name,
                    label = cloudLibraryTypeLabel(type),
                )
            },
        )
    }
    val selectedProviderName = uiState.providers
        .firstOrNull { provider -> provider.providerId == selectedProviderId }
        ?.providerName
        ?: stringResource(Res.string.cloud_library_provider_all)
    val selectedTypeLabel = selectedType?.let { type -> cloudLibraryTypeLabel(type) }
        ?: stringResource(Res.string.cloud_library_type_all)
    val windowOptions = DebridCloudLibraryWindow.entries.map { window ->
        NuvioDropdownOption(key = window.name, label = cloudLibraryWindowLabel(window))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NuvioDropdownChip(
                    title = stringResource(Res.string.cloud_library_select_provider),
                    label = selectedProviderName,
                    selectedKey = selectedProviderId.orEmpty(),
                    options = providerOptions,
                    enabled = providerOptions.size > 1,
                    onSelected = { option ->
                        onProviderSelected(option.key.ifBlank { null })
                    },
                )
                NuvioDropdownChip(
                    title = stringResource(Res.string.cloud_library_select_window),
                    label = cloudLibraryWindowLabel(selectedWindow),
                    selectedKey = selectedWindow.name,
                    options = windowOptions,
                    onSelected = { option ->
                        runCatching { DebridCloudLibraryWindow.valueOf(option.key) }
                            .getOrNull()
                            ?.let(onWindowSelected)
                    },
                )
                NuvioDropdownChip(
                    title = stringResource(Res.string.cloud_library_select_type),
                    label = selectedTypeLabel,
                    selectedKey = selectedType?.name.orEmpty(),
                    options = typeOptions,
                    enabled = typeOptions.size > 1,
                    onSelected = { option ->
                        val type = option.key
                            .takeIf { it.isNotBlank() }
                            ?.let(CloudLibraryItemType::valueOf)
                        onTypeSelected(type)
                    },
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.cloud_library_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryChip(
    label: String,
    selected: Boolean,
    loading: Boolean = false,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.45f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = colorScheme.primary,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    error -> colorScheme.error
                    selected -> colorScheme.onPrimaryContainer
                    else -> colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CloudLibraryRow(
    item: CloudLibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableCount = item.playableFiles.size
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = playableCount > 0, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cloudLibrarySubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cloudLibraryStatusLine(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (playableCount > 0) {
                    IconButton(onClick = onClick) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.action_play),
                        )
                    }
                }
            }
            item.progressFraction?.takeIf { it in 0f..0.999f }?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloudLibraryFilePicker(
    item: CloudLibraryItem,
    onBack: () -> Unit,
    onFileSelected: (CloudLibraryFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.action_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(Res.string.cloud_library_file_picker_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val entries = item.playableFileEntries()
            if (entries.isEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.cloud_library_no_files_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.cloud_library_no_files_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                entries.forEach { entry ->
                    CloudLibraryFileRow(
                        entry = entry,
                        onClick = { onFileSelected(entry.file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudLibraryFileRow(
    entry: CloudLibraryFileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = entry.file
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp),
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    // Episode-marked files lead with their coordinates: twenty release names from
                    // one season pack are otherwise indistinguishable at a glance.
                    text = entry.episodeLabel?.let { label -> "$label • ${file.name}" } ?: file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = file.sizeBytes?.let { size -> formatCloudLibraryBytes(size) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.cloud_library_play_file),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun cloudLibrarySubtitle(item: CloudLibraryItem): String {
    val fileLine = when (val playableCount = item.playableFiles.size) {
        0 -> stringResource(Res.string.cloud_library_no_playable_files)
        1 -> item.playableFiles.first().name
        else -> stringResource(Res.string.cloud_library_playable_file_count, playableCount)
    }
    return listOf(item.providerName, cloudLibraryTypeLabel(item.type), fileLine).joinToString(" • ")
}

@Composable
private fun cloudLibraryStatusLine(item: CloudLibraryItem): String {
    val fallback = if (item.playableFiles.isEmpty()) {
        stringResource(Res.string.cloud_library_no_playable_files)
    } else {
        stringResource(Res.string.cloud_library_status_ready)
    }
    return listOfNotNull(
        item.status?.toDisplayStatus(),
        item.sizeBytes?.let(::formatCloudLibraryBytes),
        item.progressFraction?.let { "${(it * 100f).toInt()}%" },
        cloudLibraryAddedLabel(item.addedAtEpochMs),
    ).joinToString(" • ").ifBlank { fallback }
}

/** How old the download is, so a date-ordered list reads as one. Null when the provider gave no date. */
@Composable
private fun cloudLibraryAddedLabel(addedAtEpochMs: Long?): String? {
    val addedAt = addedAtEpochMs ?: return null
    val days = ((LibraryClock.nowEpochMs() - addedAt) / MILLIS_PER_DAY).toInt()
    return if (days <= 0) {
        stringResource(Res.string.cloud_library_added_today)
    } else {
        stringResource(Res.string.cloud_library_added_days_ago, days)
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

@Composable
private fun cloudLibraryTypeLabel(type: CloudLibraryItemType): String =
    when (type) {
        CloudLibraryItemType.Torrent -> stringResource(Res.string.cloud_library_type_torrents)
        CloudLibraryItemType.Usenet -> stringResource(Res.string.cloud_library_type_usenet)
        CloudLibraryItemType.WebDownload -> stringResource(Res.string.cloud_library_type_web)
        CloudLibraryItemType.File -> stringResource(Res.string.cloud_library_type_files)
    }

private fun String.toDisplayStatus(): String =
    replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.titlecase() }

@Composable
private fun CloudLibrarySkeletonToolbar(
    modifier: Modifier = Modifier,
) {
    val brush = rememberCloudLibrarySkeletonBrush()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CloudSkeletonBlock(brush = brush, width = 112.dp, height = 36.dp, cornerRadius = 12.dp)
            CloudSkeletonBlock(brush = brush, width = 92.dp, height = 36.dp, cornerRadius = 12.dp)
        }
    }
}

@Composable
private fun CloudLibrarySkeletonRow(
    modifier: Modifier = Modifier,
) {
    val brush = rememberCloudLibrarySkeletonBrush()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CloudSkeletonBlock(
                        brush = brush,
                        modifier = Modifier.fillMaxWidth(0.74f),
                        height = 18.dp,
                        cornerRadius = 6.dp,
                    )
                    CloudSkeletonBlock(
                        brush = brush,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        height = 14.dp,
                        cornerRadius = 6.dp,
                    )
                    CloudSkeletonBlock(
                        brush = brush,
                        modifier = Modifier.fillMaxWidth(0.52f),
                        height = 12.dp,
                        cornerRadius = 6.dp,
                    )
                }
                CloudSkeletonBlock(brush = brush, width = 48.dp, height = 48.dp, cornerRadius = 24.dp)
            }
        }
    }
}

@Composable
private fun rememberCloudLibrarySkeletonBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
    )
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )
}

@Composable
private fun CloudSkeletonBlock(
    brush: Brush,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp,
    cornerRadius: Dp,
) {
    val sizeModifier = if (width != null) {
        modifier.size(width = width, height = height)
    } else {
        modifier.height(height)
    }
    Box(
        modifier = sizeModifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush),
    )
}

private enum class LibraryViewMode {
    Saved,
    Cloud,
}

private fun LazyListScope.librarySections(
    sections: List<LibrarySection>,
    watchedKeys: Set<String>,
    showHeaderAccent: Boolean,
    focusedRowIndex: Int,
    focusedItemIndex: Int,
    onHoverItem: ((rowIndex: Int, itemIndex: Int) -> Unit)?,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onSectionViewAllClick: ((LibrarySection) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
) {
    itemsIndexed(
        items = sections,
        key = { _, section -> section.type },
    ) { index, section ->
        val previewItems = section.items.take(LIBRARY_SECTION_PREVIEW_LIMIT)
        NuvioShelfSection(
            title = section.displayTitle,
            entries = previewItems,
            headerHorizontalPadding = 16.dp,
            rowContentPadding = PaddingValues(horizontal = 16.dp),
            showHeaderAccent = showHeaderAccent,
            onViewAllClick = if (section.items.size > LIBRARY_SECTION_PREVIEW_LIMIT) {
                onSectionViewAllClick?.let { { it(section) } }
            } else {
                null
            },
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { item -> "${item.type}:${item.id}" },
            focusedItemIndex = if (focusedRowIndex == index) focusedItemIndex else null,
            onHoverItem = onHoverItem?.let { callback -> { itemIdx -> callback(index, itemIdx) } },
        ) { item ->
            val posterItem = item.toMetaPreview()
            HomePosterCard(
                item = posterItem,
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = posterItem,
                ),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = onPosterLongClick?.let { { it(item, section) } },
            )
        }
    }
}

private const val LIBRARY_SECTION_PREVIEW_LIMIT = 18
