package com.nuvio.app.features.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_category_all
import nuvio.composeapp.generated.resources.iptv_category_favourites
import nuvio.composeapp.generated.resources.iptv_category_other
import nuvio.composeapp.generated.resources.iptv_category_recent
import nuvio.composeapp.generated.resources.iptv_channel_number
import nuvio.composeapp.generated.resources.iptv_channel_picker_filter
import nuvio.composeapp.generated.resources.iptv_regions_all
import nuvio.composeapp.generated.resources.iptv_regions_toggle
import nuvio.composeapp.generated.resources.iptv_tab_empty_category
import nuvio.composeapp.generated.resources.iptv_tab_empty_favourites
import nuvio.composeapp.generated.resources.iptv_tab_empty_filter
import nuvio.composeapp.generated.resources.iptv_tab_favourite_add
import nuvio.composeapp.generated.resources.iptv_tab_favourite_remove
import nuvio.composeapp.generated.resources.iptv_tab_loading
import nuvio.composeapp.generated.resources.iptv_tab_next
import nuvio.composeapp.generated.resources.iptv_tab_open_settings
import nuvio.composeapp.generated.resources.iptv_tab_refresh
import nuvio.composeapp.generated.resources.iptv_tab_retry
import nuvio.composeapp.generated.resources.iptv_tab_show_guide
import nuvio.composeapp.generated.resources.iptv_tab_show_list
import org.jetbrains.compose.resources.stringResource

/** Sidebar entries that are not provider categories. */
private typealias IptvSection = IptvGroups

/** Whether the Live TV tab belongs in the navigation: only once a playlist exists. */
@Composable
fun rememberLiveTvTabVisible(): Boolean {
    remember { IptvRepository.ensureLoaded() }
    val settings by IptvRepository.settings.collectAsStateWithLifecycle()
    return settings.playlists.isNotEmpty()
}

/** Where the arrow keys are: the tab is driven by one virtual focus, like the Calendar. */
private enum class IptvZone { Header, Regions, Sidebar, Channels }

private enum class IptvHeaderAction { Search, Regions, Guide, Refresh, Settings }

/** One sidebar line: a playlist to switch to (only with several) or a section of the current one. */
private data class IptvSidebarItem(
    val key: String,
    val label: String,
    val count: Int? = null,
    val playlist: IptvPlaylist? = null,
    val section: String? = null,
)

/**
 * The Live TV tab: a playlist's categories down the side, its channels with what is on now and
 * next, favourites and recents. Clicking a channel plays it ([IptvPlaybackRequests]).
 *
 * Keyboard (and so the pad, whose buttons arrive as keys): the arrows move one highlight between
 * the header buttons, the region flags, the categories and the channels; Enter acts on it.
 */
@Composable
fun IptvScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onOpenSettings: () -> Unit,
) {
    remember { IptvRepository.ensureLoaded() }
    val settings by IptvRepository.settings.collectAsStateWithLifecycle()
    val playlists = settings.playlists
    if (playlists.isEmpty()) return

    var selectedPlaylistId by rememberSaveable { mutableStateOf(playlists.first().id) }
    val playlist = playlists.firstOrNull { it.id == selectedPlaylistId } ?: playlists.first()
    val state by remember(playlist.id) { IptvRepository.contentState(playlist.id) }.collectAsStateWithLifecycle()
    // Loads the disk cache at once, then downloads whatever the refresh setting says is stale.
    LaunchedEffect(playlist.id) { IptvRepository.refreshInBackground(playlist.id) }

    val guideVersion by IptvEpgRepository.version.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    var section by rememberSaveable(playlist.id) { mutableStateOf(IptvSection.ALL) }
    var filter by rememberSaveable(playlist.id) { mutableStateOf("") }
    var searchOpen by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var region by rememberSaveable(playlist.id) { mutableStateOf<String?>(null) }
    var regionsOpen by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var guideView by rememberSaveable { mutableStateOf(false) }

    val content = state.content
    val categoryRegions = remember(content) { content?.let(IptvRegions::categoryRegions).orEmpty() }
    val regions = remember(categoryRegions) { IptvRegions.regions(categoryRegions) }
    if (region != null && region !in regions) region = null

    val other = stringResource(Res.string.iptv_category_other)
    val favouritesLabel = stringResource(Res.string.iptv_category_favourites)
    val recentLabel = stringResource(Res.string.iptv_category_recent)
    val allLabel = stringResource(Res.string.iptv_category_all)
    val sidebarItems = remember(playlists, content, region, categoryRegions, other, favouritesLabel, recentLabel, allLabel) {
        buildList {
            if (playlists.size > 1) {
                playlists.forEach { add(IptvSidebarItem("playlist:${it.id}", it.name.ifBlank { it.id.take(8) }, playlist = it)) }
            }
            add(IptvSidebarItem("section:${IptvSection.FAVOURITES}", favouritesLabel, section = IptvSection.FAVOURITES))
            add(IptvSidebarItem("section:${IptvSection.RECENT}", recentLabel, section = IptvSection.RECENT))
            val allCount = content?.let { c -> if (region == null) c.channels.size else c.channels.count { categoryRegions[it.categoryId] == region } }
            add(IptvSidebarItem("section:${IptvSection.ALL}", allLabel, count = allCount, section = IptvSection.ALL))
            content?.categories
                ?.filter { region == null || categoryRegions[it.id] == region }
                ?.forEach { category ->
                    add(
                        IptvSidebarItem(
                            key = "section:${category.id}",
                            label = if (category.id == IptvPlaylistContent.OTHER_CATEGORY) other else category.name,
                            count = content.byCategory[category.id]?.size,
                            section = category.id,
                        ),
                    )
                }
        }
    }
    // A category the region filter hid is no longer something to be looking at.
    if (sidebarItems.none { it.section == section }) section = IptvSection.ALL

    val channels = remember(content, section, filter, region, categoryRegions, settings.favourites, settings.recents) {
        content?.let { channelsFor(it, section, filter, settings, region, categoryRegions) }.orEmpty()
    }

    val headerActions = buildList {
        add(IptvHeaderAction.Search)
        if (regions.isNotEmpty()) add(IptvHeaderAction.Regions)
        add(IptvHeaderAction.Guide)
        if (!state.loading) add(IptvHeaderAction.Refresh)
        add(IptvHeaderAction.Settings)
    }

    // Keyboard focus. Only drawn once a key has been pressed; the mouse puts it away again.
    var keyboardActive by remember { mutableStateOf(false) }
    var zone by remember { mutableStateOf(IptvZone.Channels) }
    var headerIndex by rememberSaveable { mutableIntStateOf(0) }
    var regionIndex by rememberSaveable { mutableIntStateOf(0) }
    var sidebarIndex by rememberSaveable(playlist.id) { mutableIntStateOf(-1) }
    var channelIndex by rememberSaveable(playlist.id, section) { mutableIntStateOf(0) }
    var starFocused by remember { mutableStateOf(false) }
    headerIndex = headerIndex.coerceIn(0, headerActions.lastIndex)
    if (sidebarIndex !in sidebarItems.indices) {
        sidebarIndex = sidebarItems.indexOfFirst { it.section == section }.coerceAtLeast(0)
    }
    channelIndex = channelIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    if (zone == IptvZone.Regions && !regionsOpen) zone = IptvZone.Header
    if (zone == IptvZone.Channels && channels.isEmpty() && keyboardActive) zone = IptvZone.Sidebar

    val screenFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var focusSearchRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // The tab owns the keys as soon as it shows; the node may not be attached on the first frame.
        repeat(10) {
            if (runCatching { screenFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(48)
        }
    }
    LaunchedEffect(focusSearchRequests) {
        if (focusSearchRequests > 0) runCatching { searchFocus.requestFocus() }
    }

    val sidebarState = rememberLazyListState()
    val channelState = rememberLazyListState()
    LaunchedEffect(keyboardActive, zone, sidebarIndex) {
        if (keyboardActive && zone == IptvZone.Sidebar) sidebarState.keepVisible(sidebarIndex)
    }
    LaunchedEffect(keyboardActive, zone, channelIndex, guideView) {
        if (keyboardActive && zone == IptvZone.Channels) channelState.keepVisible(channelIndex)
    }

    fun play(channel: IptvChannel) = IptvPlaybackRequests.play(playlist, channel, zapList = channels)

    fun closeSearch() {
        filter = ""
        searchOpen = false
        runCatching { screenFocus.requestFocus() }
    }

    fun activateHeader(action: IptvHeaderAction) {
        when (action) {
            IptvHeaderAction.Search -> {
                searchOpen = true
                focusSearchRequests++
            }
            IptvHeaderAction.Regions -> {
                regionsOpen = !regionsOpen
                if (regionsOpen) regionIndex = region?.let { regions.indexOf(it) + 1 } ?: 0
            }
            IptvHeaderAction.Guide -> guideView = !guideView
            IptvHeaderAction.Refresh -> IptvRepository.refreshInBackground(playlist.id, force = true)
            IptvHeaderAction.Settings -> onOpenSettings()
        }
    }

    fun selectRegion(code: String?) {
        region = code
        channelIndex = 0
    }

    fun activateSidebar(item: IptvSidebarItem) {
        item.playlist?.let { selectedPlaylistId = it.id }
        item.section?.let { section = it }
    }

    fun moveDown() {
        when (zone) {
            IptvZone.Header -> zone = when {
                regionsOpen -> IptvZone.Regions
                channels.isNotEmpty() -> IptvZone.Channels
                else -> IptvZone.Sidebar
            }
            IptvZone.Regions -> zone = if (channels.isNotEmpty()) IptvZone.Channels else IptvZone.Sidebar
            IptvZone.Sidebar -> sidebarIndex = (sidebarIndex + 1).coerceAtMost(sidebarItems.lastIndex)
            IptvZone.Channels -> channelIndex = (channelIndex + 1).coerceAtMost(channels.lastIndex.coerceAtLeast(0))
        }
    }

    fun moveUp() {
        val above = if (regionsOpen) IptvZone.Regions else IptvZone.Header
        when (zone) {
            IptvZone.Header -> Unit
            IptvZone.Regions -> zone = IptvZone.Header
            IptvZone.Sidebar -> if (sidebarIndex > 0) sidebarIndex-- else zone = above
            IptvZone.Channels -> if (channelIndex > 0) channelIndex-- else zone = above
        }
    }

    fun handleKey(navKey: Key): Boolean {
        when (navKey) {
            Key.DirectionDown -> moveDown()
            Key.DirectionUp -> moveUp()
            Key.PageDown, Key.PageUp -> {
                val step = if (navKey == Key.PageDown) 10 else -10
                when (zone) {
                    IptvZone.Sidebar -> sidebarIndex = (sidebarIndex + step).coerceIn(0, sidebarItems.lastIndex)
                    IptvZone.Channels -> channelIndex = (channelIndex + step).coerceIn(0, channels.lastIndex.coerceAtLeast(0))
                    else -> return false
                }
            }
            Key.DirectionLeft -> when (zone) {
                IptvZone.Header -> headerIndex = (headerIndex - 1).coerceAtLeast(0)
                IptvZone.Regions -> regionIndex = (regionIndex - 1).coerceAtLeast(0)
                IptvZone.Sidebar -> return false
                IptvZone.Channels -> if (starFocused) starFocused = false else zone = IptvZone.Sidebar
            }
            Key.DirectionRight -> when (zone) {
                IptvZone.Header -> headerIndex = (headerIndex + 1).coerceAtMost(headerActions.lastIndex)
                IptvZone.Regions -> regionIndex = (regionIndex + 1).coerceAtMost(regions.size)
                IptvZone.Sidebar -> if (channels.isNotEmpty()) zone = IptvZone.Channels
                // The guide's rows have no star; the list's star is one step right of its row.
                IptvZone.Channels -> if (!guideView) starFocused = true
            }
            Key.Enter, Key.NumPadEnter -> when (zone) {
                IptvZone.Header -> headerActions.getOrNull(headerIndex)?.let(::activateHeader)
                IptvZone.Regions -> selectRegion(if (regionIndex == 0) null else regions.getOrNull(regionIndex - 1))
                IptvZone.Sidebar -> sidebarItems.getOrNull(sidebarIndex)?.let(::activateSidebar)
                IptvZone.Channels -> channels.getOrNull(channelIndex)?.let { channel ->
                    if (starFocused && !guideView) IptvRepository.toggleFavourite(playlist.id, channel.key) else play(channel)
                }
            }
            else -> return false
        }
        if (zone != IptvZone.Channels) starFocused = false
        return true
    }

    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .focusRequester(screenFocus)
            .focusable()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                keyboardActive = false
                // A press in the filter box is the box's own; anywhere else takes the keys back.
                val at = event.changes.first().position + rootOrigin
                if (searchBounds?.contains(at) != true) runCatching { screenFocus.requestFocus() }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    // A clicked button keeps Compose focus and would act again on Enter's release.
                    return@onPreviewKeyEvent !searchFocused && event.navigationKey() in IptvNavigationKeys
                }
                if (searchFocused) {
                    // The filter box types; only the keys that leave it are taken. Raw keys, so
                    // WASD navigation does not swallow letters.
                    return@onPreviewKeyEvent when (event.key) {
                        Key.Escape -> {
                            if (filter.isEmpty()) closeSearch() else filter = ""
                            true
                        }
                        Key.DirectionDown, Key.Enter, Key.NumPadEnter -> {
                            keyboardActive = true
                            zone = if (channels.isNotEmpty()) IptvZone.Channels else IptvZone.Sidebar
                            if (filter.isEmpty()) searchOpen = false
                            runCatching { screenFocus.requestFocus() }
                            true
                        }
                        else -> false
                    }
                }
                val navKey = event.navigationKey()
                if (!keyboardActive && navKey in IptvNavigationKeys) {
                    // The first press only shows where the highlight is.
                    keyboardActive = true
                    return@onPreviewKeyEvent true
                }
                handleKey(navKey)
            },
    ) {
        IptvSidebar(
            items = sidebarItems,
            selectedPlaylist = playlist,
            selectedSection = section,
            focusedIndex = if (keyboardActive && zone == IptvZone.Sidebar) sidebarIndex else -1,
            listState = sidebarState,
            onClick = { index, item ->
                sidebarIndex = index
                activateSidebar(item)
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 8.dp, end = 32.dp, top = topChromePadding ?: 24.dp),
        ) {
            val sectionTitle = sidebarItems.firstOrNull { it.section == section }?.label ?: allLabel
            IptvHeader(
                title = sectionTitle,
                actions = headerActions,
                focusedAction = if (keyboardActive && zone == IptvZone.Header) headerActions.getOrNull(headerIndex) else null,
                loading = state.loading,
                filter = filter,
                onFilterChange = { filter = it },
                searchOpen = searchOpen || filter.isNotEmpty(),
                searchFocus = searchFocus,
                onSearchFocusChanged = { focused ->
                    // Only a real blur closes it: the field reports "not focused" as it first attaches.
                    if (searchFocused && !focused && filter.isEmpty()) searchOpen = false
                    searchFocused = focused
                },
                onSearchBounds = { searchBounds = it },
                onCloseSearch = ::closeSearch,
                selectedRegion = region,
                regionsOpen = regionsOpen,
                guideView = guideView,
                onAction = { action ->
                    headerIndex = headerActions.indexOf(action).coerceAtLeast(0)
                    activateHeader(action)
                },
            )
            AnimatedVisibility(
                visible = regionsOpen && regions.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                IptvRegionRow(
                    regions = regions,
                    selected = region,
                    focusedIndex = if (keyboardActive && zone == IptvZone.Regions) regionIndex else -1,
                    onSelect = { index, code ->
                        regionIndex = index
                        selectRegion(code)
                    },
                )
            }
            when {
                content == null && state.error != null -> IptvMessage(
                    text = iptvErrorText(state.error!!),
                    isError = true,
                    actionLabel = stringResource(Res.string.iptv_tab_retry),
                    onAction = { IptvRepository.refreshInBackground(playlist.id, force = true) },
                    secondaryLabel = stringResource(Res.string.iptv_tab_open_settings),
                    onSecondary = onOpenSettings,
                )
                content == null -> IptvLoading()
                channels.isEmpty() -> IptvMessage(
                    text = stringResource(
                        when {
                            filter.isNotBlank() -> Res.string.iptv_tab_empty_filter
                            section == IptvSection.FAVOURITES -> Res.string.iptv_tab_empty_favourites
                            else -> Res.string.iptv_tab_empty_category
                        },
                    ),
                )
                guideView -> IptvGuideGrid(
                    playlist = playlist,
                    channels = channels,
                    now = now,
                    guideVersion = guideVersion,
                    listState = channelState,
                    focusedIndex = if (keyboardActive && zone == IptvZone.Channels) channelIndex else -1,
                    onPlay = ::play,
                )
                else -> IptvChannelList(
                    playlist = playlist,
                    channels = channels,
                    favourites = settings.favourites,
                    now = now,
                    guideVersion = guideVersion,
                    listState = channelState,
                    focusedIndex = if (keyboardActive && zone == IptvZone.Channels) channelIndex else -1,
                    starFocused = starFocused,
                    onPlay = ::play,
                )
            }
        }
    }
}

private val IptvNavigationKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.Enter, Key.NumPadEnter, Key.PageUp, Key.PageDown,
)

/** Scrolls just enough to bring item [index] fully into view. */
internal suspend fun LazyListState.keepVisible(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val first = visible.first()
    val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset } ?: first
    when {
        index < first.index || (index == first.index && first.offset < info.viewportStartOffset) -> scrollToItem(index)
        index > lastFull.index -> scrollToItem((first.index + index - lastFull.index).coerceAtMost(index))
    }
}

private fun channelsFor(
    content: IptvPlaylistContent,
    section: String,
    filter: String,
    settings: IptvSettings,
    region: String?,
    categoryRegions: Map<String, String>,
): List<IptvChannel> {
    val base = IptvGroups.channels(content, section, settings)
    // Favourites and Recents are the viewer's own lists; only All is narrowed to the region (a
    // category already belongs to one).
    val regional = if (region != null && section == IptvSection.ALL) {
        base.filter { categoryRegions[it.categoryId] == region }
    } else {
        base
    }
    val needle = filter.trim()
    return if (needle.isEmpty()) regional else regional.filter { it.name.contains(needle, ignoreCase = true) }
}

@Composable
private fun IptvSidebar(
    items: List<IptvSidebarItem>,
    selectedPlaylist: IptvPlaylist,
    selectedSection: String,
    focusedIndex: Int,
    listState: LazyListState,
    onClick: (Int, IptvSidebarItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.width(280.dp).fillMaxHeight().padding(start = 24.dp, end = 8.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val isPlaylist = item.playlist != null
            // The last playlist leaves a gap before the sections, kept inside the row so indices
            // stay one per line.
            val gap = isPlaylist && items.getOrNull(index + 1)?.playlist == null
            Box(Modifier.padding(bottom = if (gap) 12.dp else 0.dp)) {
                IptvSidebarEntry(
                    label = item.label,
                    count = item.count,
                    selected = if (isPlaylist) item.playlist?.id == selectedPlaylist.id else item.section == selectedSection,
                    emphasised = isPlaylist,
                    keyboardFocused = index == focusedIndex,
                    onClick = { onClick(index, item) },
                )
            }
        }
    }
}

@Composable
private fun IptvSidebarEntry(
    label: String,
    selected: Boolean,
    keyboardFocused: Boolean,
    count: Int? = null,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    selected -> tokens.colors.accent.copy(alpha = 0.18f)
                    keyboardFocused || hovered -> tokens.colors.surfaceElevated
                    else -> tokens.colors.background
                },
            )
            .then(if (keyboardFocused) Modifier.border(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || emphasised) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun IptvHeader(
    title: String,
    actions: List<IptvHeaderAction>,
    focusedAction: IptvHeaderAction?,
    loading: Boolean,
    filter: String,
    onFilterChange: (String) -> Unit,
    searchOpen: Boolean,
    searchFocus: FocusRequester,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSearchBounds: (Rect?) -> Unit,
    onCloseSearch: () -> Unit,
    selectedRegion: String?,
    regionsOpen: Boolean,
    guideView: Boolean,
    onAction: (IptvHeaderAction) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val filterLabel = stringResource(Res.string.iptv_channel_picker_filter)
        actions.forEach { action ->
            val focused = action == focusedAction
            when (action) {
                IptvHeaderAction.Search -> if (searchOpen) {
                    NuvioTextField(
                        value = filter,
                        onValueChange = onFilterChange,
                        modifier = Modifier
                            .width(280.dp)
                            .onFocusChanged { onSearchFocusChanged(it.hasFocus) }
                            .onGloballyPositioned { onSearchBounds(it.boundsInWindow()) }
                            .then(if (focused) Modifier.border(1.5.dp, tokens.colors.borderFocus, RoundedCornerShape(12.dp)) else Modifier),
                        placeholder = filterLabel,
                        focusRequester = searchFocus,
                        leadingContent = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onCloseSearch),
                            )
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { onSearchBounds(null) }
                    IptvHeaderButton(Icons.Rounded.Search, filterLabel, focused) { onAction(action) }
                }
                IptvHeaderAction.Regions -> {
                    val label = stringResource(Res.string.iptv_regions_toggle)
                    if (selectedRegion != null) {
                        IptvHeaderButton(
                            icon = null,
                            description = label,
                            focused = focused,
                            active = regionsOpen,
                            content = { IptvFlag(selectedRegion, Modifier.size(width = 24.dp, height = 16.dp)) },
                            onClick = { onAction(action) },
                        )
                    } else {
                        IptvHeaderButton(Icons.Rounded.Flag, label, focused, active = regionsOpen) { onAction(action) }
                    }
                }
                IptvHeaderAction.Guide -> IptvHeaderButton(
                    icon = if (guideView) Icons.Rounded.ViewList else Icons.Rounded.CalendarViewWeek,
                    description = stringResource(if (guideView) Res.string.iptv_tab_show_list else Res.string.iptv_tab_show_guide),
                    focused = focused,
                ) { onAction(action) }
                IptvHeaderAction.Refresh -> IptvHeaderButton(Icons.Rounded.Refresh, stringResource(Res.string.iptv_tab_refresh), focused) {
                    onAction(action)
                }
                IptvHeaderAction.Settings -> {
                    if (loading) {
                        // Refresh steps out while it runs; the spinner stands in its place.
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = tokens.colors.accent)
                        }
                    }
                    IptvHeaderButton(Icons.Rounded.Settings, stringResource(Res.string.iptv_tab_open_settings), focused) {
                        onAction(action)
                    }
                }
            }
        }
    }
}

/** A round header button; draws [icon], or [content] when there is none. */
@Composable
private fun IptvHeaderButton(
    icon: ImageVector?,
    description: String,
    focused: Boolean,
    active: Boolean = false,
    content: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                when {
                    active -> tokens.colors.accent.copy(alpha = 0.22f)
                    focused || hovered -> tokens.colors.surfaceElevated
                    else -> Color.Transparent
                },
            )
            .then(if (focused) Modifier.border(1.5.dp, tokens.colors.borderFocus, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (active) tokens.colors.textPrimary else tokens.colors.textMuted)
        } else {
            content()
        }
    }
}

@Composable
private fun IptvFlag(region: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier.clip(RoundedCornerShape(3.dp)).background(tokens.colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        NuvioAsyncImage(
            model = IptvRegions.flagUrl(region),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** The region flags, opened from the flag button: "All regions" first, then one chip per region. */
@Composable
private fun IptvRegionRow(
    regions: List<String>,
    selected: String?,
    focusedIndex: Int,
    onSelect: (Int, String?) -> Unit,
) {
    val rowState = rememberLazyListState()
    LaunchedEffect(focusedIndex) { if (focusedIndex >= 0) rowState.keepVisible(focusedIndex) }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "region:all") {
            IptvRegionChip(
                region = null,
                label = stringResource(Res.string.iptv_regions_all),
                selected = selected == null,
                focused = focusedIndex == 0,
                onClick = { onSelect(0, null) },
            )
        }
        itemsIndexed(regions, key = { _, code -> "region:$code" }) { index, code ->
            IptvRegionChip(
                region = code,
                label = IptvRegions.label(code),
                selected = code == selected,
                focused = focusedIndex == index + 1,
                onClick = { onSelect(index + 1, code) },
            )
        }
    }
}

@Composable
private fun IptvRegionChip(
    region: String?,
    label: String,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(
                when {
                    selected -> tokens.colors.accent.copy(alpha = 0.22f)
                    focused || hovered -> tokens.colors.surfaceElevated
                    else -> tokens.colors.surface.copy(alpha = 0.55f)
                },
            )
            .then(if (focused) Modifier.border(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (region != null) IptvFlag(region, Modifier.size(width = 24.dp, height = 16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
        )
    }
}

@Composable
private fun IptvChannelList(
    playlist: IptvPlaylist,
    channels: List<IptvChannel>,
    favourites: List<String>,
    now: Long,
    guideVersion: Int,
    listState: LazyListState,
    focusedIndex: Int,
    starFocused: Boolean,
    onPlay: (IptvChannel) -> Unit,
) {
    val favouriteSet = remember(favourites) { favourites.toHashSet() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // uid is the list position: unique, unlike key (tvg-id) which providers repeat.
        itemsIndexed(channels, key = { _, channel -> "${playlist.id}:${channel.uid}" }) { index, channel ->
            val entries = remember(channel, now, guideVersion) {
                IptvEpgRepository.cached(playlist, channel, now)?.take(2).orEmpty()
            }
            IptvChannelRow(
                channel = channel,
                nowEntry = entries.firstOrNull()?.takeIf { it.startMs <= now },
                nextEntry = entries.firstOrNull { it.startMs > now },
                now = now,
                favourite = IptvRepository.channelRef(playlist.id, channel.key) in favouriteSet,
                keyboardFocused = index == focusedIndex && !starFocused,
                starKeyboardFocused = index == focusedIndex && starFocused,
                onPlay = { onPlay(channel) },
                onToggleFavourite = { IptvRepository.toggleFavourite(playlist.id, channel.key) },
            )
        }
    }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(ms: Long): String = TimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun IptvChannelRow(
    channel: IptvChannel,
    nowEntry: IptvEpgEntry?,
    nextEntry: IptvEpgEntry?,
    now: Long,
    favourite: Boolean,
    keyboardFocused: Boolean,
    starKeyboardFocused: Boolean,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val name = channel.name.ifBlank { stringResource(Res.string.iptv_channel_number, channel.num) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (keyboardFocused || starKeyboardFocused || hovered) tokens.colors.surfaceElevated else tokens.colors.surface.copy(alpha = 0.55f))
            .then(if (keyboardFocused) Modifier.border(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.num.toString(),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.colors.background.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo != null) {
                NuvioAsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(0.38f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(0.62f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (nowEntry != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatTime(nowEntry.startMs)}  ${nowEntry.title}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LinearProgressIndicator(
                    progress = { nowEntry.progress(now) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = tokens.colors.accent,
                    trackColor = tokens.colors.borderSubtle,
                    drawStopIndicator = {},
                )
            }
            if (nextEntry != null) {
                Text(
                    text = stringResource(Res.string.iptv_tab_next, formatTime(nextEntry.startMs), nextEntry.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IptvHeaderButton(
            icon = if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            description = stringResource(if (favourite) Res.string.iptv_tab_favourite_remove else Res.string.iptv_tab_favourite_add),
            focused = starKeyboardFocused,
            active = favourite,
            onClick = onToggleFavourite,
        )
    }
}

@Composable
private fun IptvLoading() {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = tokens.colors.accent)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.iptv_tab_loading), color = tokens.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IptvMessage(
    text: String,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = if (isError) tokens.colors.danger else tokens.colors.textMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (actionLabel != null || secondaryLabel != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
                secondaryLabel?.let { TextButton(onClick = onSecondary) { Text(it) } }
            }
        }
    }
}
