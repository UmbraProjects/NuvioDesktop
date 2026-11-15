package com.nuvio.app.features.iptv

import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.features.settings.settingsSkyBackground
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
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
import kotlinx.coroutines.launch
import com.nuvio.app.core.ui.NuvioToastController
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

/** Whether the Live TV tab belongs in the navigation: [IptvSettings.tabShown]. */
@Composable
fun rememberLiveTvTabVisible(): Boolean {
    remember { IptvRepository.ensureLoaded() }
    val settings by IptvRepository.settings.collectAsStateWithLifecycle()
    return settings.tabShown
}

/** Where the arrow keys are: the tab is driven by one virtual focus, like the Calendar. */
private enum class IptvZone { Header, Regions, Sidebar, Channels }

private enum class IptvHeaderAction { Search, Regions, Guide, Refresh, Triage, Settings }

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

    // fork:live-tv-on-now - with On Now on, the tab opens on the wall instead of the channel list.
    val onNow = settings.onNow
    // fork:live-tv-lineup - with Your 40 on, the tab opens on the numbered lineup.
    val lineupOn = settings.lineupOn
    // fork:live-tv-polish - with Stage on the old wall sections are left to the stages, so the list starts on Your 40 or All.
    val home = if (lineupOn) IptvLineupRules.SECTION else if (onNow && !settings.stage) IptvWall.ON_NOW else IptvSection.ALL
    var section by rememberSaveable(playlist.id, onNow, lineupOn) { mutableStateOf(home) }
    // fork:navbar-search - shared with the app bar's Search, which on this tab filters the channels.
    var filter by IptvTopBarSearch::text
    LaunchedEffect(playlist.id) { if (IptvTopBarSearch.playlistId != playlist.id) IptvTopBarSearch.text = "" ; IptvTopBarSearch.playlistId = playlist.id }
    var searchOpen by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var region by rememberSaveable(playlist.id) { mutableStateOf<String?>(null) }
    var regionsOpen by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var guideView by rememberSaveable { mutableStateOf(false) }

    val content = state.content
    // fork:navbar-search - the channels whose names hold what is typed (those that start with it first), offered by the
    // on-screen keyboard above its keys.
    // The same list the top bar's search panel shows (SearchPredictions.kt).
    val channelWords = remember(filter, content) {
        com.nuvio.app.features.search.channelPredictions(filter, content?.channels.orEmpty().asSequence().map { it.name })
            .take(com.nuvio.app.core.ui.MAX_KEYBOARD_SUGGESTIONS)
    }
    com.nuvio.app.core.ui.PublishKeyboardSuggestions(channelWords) { picked -> filter = picked }
    val categoryRegions = remember(content) { content?.let(IptvRegions::categoryRegions).orEmpty() }
    val regions = remember(categoryRegions) { IptvRegions.regions(categoryRegions) }
    if (region != null && region !in regions) region = null

    // fork:live-tv-triage - the categories the triage kept; null until the questions have been answered.
    val triage = settings.triage[playlist.id]
    val keptCategories = remember(content, triage, categoryRegions) {
        if (content == null || triage == null) null else IptvTriageRules.keptCategories(content, triage, categoryRegions)
    }
    var triageOpen by rememberSaveable(playlist.id) { mutableStateOf(false) }
    // fork:live-tv-stage - with Stage on, Live TV opens on the stages; "All channels" there comes here, "Stages" goes back.
    var stageOpen by rememberSaveable(playlist.id) { mutableStateOf(true) }
    // fork:live-tv-brakes - the zap card's "Pick something for me" comes back to the stages.
    val stageRequested by IptvStageNav.requested.collectAsStateWithLifecycle()
    LaunchedEffect(stageRequested) { if (stageRequested != null) stageOpen = true }

    // fork:live-tv-on-now - copies of one channel folded into one row; null with the setting off.
    // fork:live-tv-stage - the stages read merged channels and the lineup too.
    val stageOn = settings.stage
    // fork:live-tv-front-door - first run: with no triage yet, Live TV opens on its questions (once a session), and
    // the front door follows them.
    LaunchedEffect(stageOn, triage == null, content != null) {
        if (stageOn && triage == null && content != null && IptvFirstRun.shouldAsk(playlist.id)) triageOpen = true
    }
    val merged = remember(content, onNow, lineupOn, stageOn, categoryRegions) {
        if ((onNow || lineupOn || stageOn) && content != null) IptvChannelMerge.merge(content.channels) { categoryRegions[it.categoryId] } else null
    }
    // Every copy's uid to the merged channel it belongs to.
    val mergedByUid = remember(merged) {
        buildMap<Int, IptvMergedChannel> { merged?.forEach { channel -> channel.sources.forEach { put(it.uid, channel) } } }
    }
    val alternates = remember(merged) { merged?.filter { it.sources.size > 1 }?.associate { it.primary.uid to it.alternates }.orEmpty() }
    val mergedPrimaries = remember(merged) { merged?.map { it.primary }.orEmpty() }
    val wallSection = onNow && section in IptvWall.SECTIONS
    // fork:live-tv-lineup - what was thrown out with "Never again", and the lineup as merged channels.
    // fork:live-tv-weed - channels the night checks found dead leave the same way as ones thrown out.
    val never = settings.never[playlist.id].orEmpty() + IptvWeedRules.dead(settings.weeds[playlist.id].orEmpty())
    val storedLineup = settings.lineup[playlist.id]
    val keyIndex = remember(merged) { merged?.let(IptvLineupRules::keyIndex).orEmpty() }
    val lineupChannels = remember(storedLineup, keyIndex, never, lineupOn, stageOn) {
        if (!(lineupOn || stageOn) || storedLineup == null) emptyList()
        else IptvLineupRules.resolve(storedLineup, keyIndex).filterNot { IptvLineupRules.isNever(it, never) }
    }
    // The first lineup is built once, from what is watched and starred and then from the triage; after that only the viewer changes it.
    LaunchedEffect(lineupOn, stageOn, merged, playlist.id, storedLineup == null) {
        if ((lineupOn || stageOn) && merged != null && storedLineup == null) {
            IptvRepository.setLineup(
                playlist.id,
                IptvLineupRules.seed(
                    merged = merged,
                    refOf = { IptvRepository.channelRef(playlist.id, it.key) },
                    plays = settings.plays.map { it.ref },
                    favourites = settings.favourites,
                    never = never,
                    keptCategories = keptCategories,
                ),
            )
        }
    }
    val wallMerged = remember(merged, never) { merged?.let { IptvLineupRules.visible(it, never) } }
    var askSkips by remember(filter) { mutableIntStateOf(0) } // fork:live-tv-evening - "Not that"
    val wall = if (onNow) rememberIptvWall(playlist, content, wallMerged, settings, now, guideVersion, keptCategories, filter, askSkips) else null
    val wallRows = remember(wall, section, filter, wallSection) { if (wallSection) wall?.rows(section, filter).orEmpty() else emptyList() }

    val other = stringResource(Res.string.iptv_category_other)
    val favouritesLabel = stringResource(Res.string.iptv_category_favourites)
    val recentLabel = stringResource(Res.string.iptv_category_recent)
    val allLabel = stringResource(Res.string.iptv_category_all)
    val soonOn = onNow && settings.departure
    val decideOn = onNow && settings.decide
    val eveningOn = onNow && settings.evening
    val sidebarItems = remember(playlists, content, region, categoryRegions, other, favouritesLabel, recentLabel, allLabel, onNow, soonOn, decideOn, eveningOn, stageOn, settings.minutes, settings.follows, lineupOn, lineupChannels, merged, wall, keptCategories) {
        buildList {
            if (playlists.size > 1) {
                playlists.forEach { add(IptvSidebarItem("playlist:${it.id}", it.name.ifBlank { it.id.take(8) }, playlist = it)) }
            }
            if (stageOn) add(IptvSidebarItem("section:$STAGE_SECTION", "Stages", section = STAGE_SECTION))
            if (lineupOn) {
                add(IptvSidebarItem("section:${IptvLineupRules.SECTION}", "Your 40", count = lineupChannels.size.takeIf { it > 0 }, section = IptvLineupRules.SECTION))
            }
            // fork:live-tv-polish - with Stage on, these are on the stages already; the classic list keeps only channels.
            if (onNow && !stageOn) {
                add(IptvSidebarItem("section:${IptvWall.ON_NOW}", "On now", section = IptvWall.ON_NOW))
                add(IptvSidebarItem("section:${IptvWall.SPORT}", "Sport", count = wall?.sport?.size?.takeIf { it > 0 }, section = IptvWall.SPORT))
                add(IptvSidebarItem("section:${IptvWall.FILMS}", "Films", count = wall?.films?.size?.takeIf { it > 0 }, section = IptvWall.FILMS))
                if (decideOn) {
                    add(IptvSidebarItem("section:${IptvDecide.THREE}", "Three things", section = IptvDecide.THREE))
                    add(IptvSidebarItem("section:${IptvDecide.MINUTES}", "I have ${settings.minutes} minutes", section = IptvDecide.MINUTES))
                    add(IptvSidebarItem("section:${IptvDecide.EARLIER}", "Earlier today", section = IptvDecide.EARLIER))
                    add(IptvSidebarItem("section:${IptvDecide.FOLLOWING}", "Following", count = settings.follows.size.takeIf { it > 0 }, section = IptvDecide.FOLLOWING))
                }
                if (eveningOn) {
                    add(IptvSidebarItem("section:${IptvRunningOrder.SECTION}", "Tonight's running order", section = IptvRunningOrder.SECTION))
                    add(IptvSidebarItem("section:${IptvAsk.SECTION}", "Just ask", section = IptvAsk.SECTION))
                }
                if (soonOn) {
                    add(IptvSidebarItem("section:${IptvWall.SOON}", "Starting soon", count = wall?.soon?.sumOf { it.programmes.size }?.takeIf { it > 0 }, section = IptvWall.SOON))
                }
            }
            add(IptvSidebarItem("section:${IptvSection.FAVOURITES}", favouritesLabel, section = IptvSection.FAVOURITES))
            add(IptvSidebarItem("section:${IptvSection.RECENT}", recentLabel, section = IptvSection.RECENT))
            fun shown(categoryId: String) =
                (keptCategories == null || categoryId in keptCategories) && (region == null || categoryRegions[categoryId] == region)
            // fork:live-tv-triage - counted without the channels left out for their language.
            val allCount = if (merged != null) {
                merged.count { shown(it.primary.categoryId) && keptCategories.keeps(it) }
            } else {
                content?.channels?.count { shown(it.categoryId) && keptCategories.keeps(it) }
            }
            add(IptvSidebarItem("section:${IptvSection.ALL}", allLabel, count = allCount, section = IptvSection.ALL))
            content?.categories
                ?.filter { (region == null || categoryRegions[it.id] == region) && (keptCategories == null || it.id in keptCategories) }
                ?.forEach { category ->
                    add(
                        IptvSidebarItem(
                            key = "section:${category.id}",
                            label = if (category.id == IptvPlaylistContent.OTHER_CATEGORY) other else category.name,
                            count = content.byCategory[category.id]?.let { list -> if (keptCategories == null) list.size else list.count { keptCategories.keeps(it) } },
                            section = category.id,
                        ),
                    )
                }
        }
    }
    // A category the region filter hid is no longer something to be looking at.
    if (sidebarItems.none { it.section == section }) section = home

    val lineupSection = lineupOn && section == IptvLineupRules.SECTION
    val channels = remember(content, section, filter, region, categoryRegions, settings.favourites, settings.recents, mergedByUid, wallSection, keptCategories, lineupSection, lineupChannels, never) {
        if (lineupSection) {
            val needle = filter.trim()
            return@remember lineupChannels.map { it.primary }.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        }
        val listed = if (wallSection) emptyList() else content?.let { channelsFor(it, section, filter, settings, region, categoryRegions, keptCategories) }.orEmpty()
        // Each copy stands for its merged channel, listed once.
        val grouped = if (mergedByUid.isEmpty()) listed else listed.map { mergedByUid[it.uid]?.primary ?: it }.distinctBy { it.uid }
        // Channels thrown out with "Never again" stay out of the lists (a search still finds them).
        if (never.isEmpty() || filter.isNotBlank()) grouped else grouped.filterNot { it.key in never }
    }
    // What the arrows can move into right of the sidebar: channels, or the wall's tiles.
    val hasContent = if (wallSection) wallRows.isNotEmpty() else channels.isNotEmpty()

    val headerActions = buildList {
        add(IptvHeaderAction.Search)
        if (regions.isNotEmpty()) add(IptvHeaderAction.Regions)
        add(IptvHeaderAction.Guide)
        if (!state.loading) add(IptvHeaderAction.Refresh)
        if (content != null) add(IptvHeaderAction.Triage)
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
    // fork:live-tv-lineup - the "..." beside the star, and the menu it opens.
    var moreFocused by remember { mutableStateOf(false) }
    var menuChannel by remember { mutableStateOf<IptvMergedChannel?>(null) }
    var menuIndex by remember { mutableIntStateOf(0) }
    // fork:live-tv-on-now - the wall's highlight (a row of tiles and a tile in it), and the open programme page.
    var wallRow by rememberSaveable(playlist.id, section) { mutableIntStateOf(0) }
    var wallCol by rememberSaveable(playlist.id, section) { mutableIntStateOf(0) }
    wallRow = wallRow.coerceIn(0, (wallRows.size - 1).coerceAtLeast(0))
    wallCol = wallCol.coerceIn(0, ((wallRows.getOrNull(wallRow)?.items?.size ?: 1) - 1).coerceAtLeast(0))
    var detail by remember(playlist.id, onNow) { mutableStateOf<IptvWallDetail?>(null) }
    var detailIndex by remember { mutableIntStateOf(0) }
    val detailActions = remember(detail, settings.reminders, soonOn, settings.follows, decideOn, settings.onDemand) {
        detail?.let {
            iptvDetailActions(
                playlist, it, System.currentTimeMillis(), reminders = settings.reminders.takeIf { soonOn }, follows = settings.follows.takeIf { decideOn },
                onDemand = settings.onDemand,
            )
        }.orEmpty()
    }
    detailIndex = detailIndex.coerceIn(0, (detailActions.size - 1).coerceAtLeast(0))
    headerIndex = headerIndex.coerceIn(0, headerActions.lastIndex)
    if (sidebarIndex !in sidebarItems.indices) {
        sidebarIndex = sidebarItems.indexOfFirst { it.section == section }.coerceAtLeast(0)
    }
    channelIndex = channelIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    if (zone == IptvZone.Regions && !regionsOpen) zone = IptvZone.Header
    if (zone == IptvZone.Channels && !hasContent && keyboardActive) zone = IptvZone.Sidebar

    val screenFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var focusSearchRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(triageOpen) {
        // The tab owns the keys as soon as it shows (and again when the triage closes); the node may not be attached
        // on the first frame.
        if (triageOpen) return@LaunchedEffect
        repeat(10) {
            if (screenFocus.requestFocusLogged()) return@LaunchedEffect
            delay(48)
        }
    }
    LaunchedEffect(focusSearchRequests) {
        if (focusSearchRequests > 0) searchFocus.requestFocusLogged()
    }

    val sidebarState = rememberLazyListState()
    val channelState = rememberLazyListState()
    val wallState = rememberLazyListState()
    LaunchedEffect(keyboardActive, zone, wallRow, wallSection) {
        if (keyboardActive && zone == IptvZone.Channels && wallSection) wallState.keepVisible(wallRow)
    }
    LaunchedEffect(keyboardActive, zone, sidebarIndex) {
        if (keyboardActive && zone == IptvZone.Sidebar) sidebarState.keepVisible(sidebarIndex)
    }
    LaunchedEffect(keyboardActive, zone, channelIndex, guideView) {
        if (keyboardActive && zone == IptvZone.Channels) channelState.keepVisible(channelIndex)
    }

    // Back from the player, the highlight is on the channel last watched (clicked here or zapped
    // to in the player), and stays there across restarts: the head of this playlist's recents.
    val lastPlayedKey = remember(settings.recents, playlist.id) {
        val prefix = IptvRepository.channelRef(playlist.id, "")
        settings.recents.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }
    var restoredKey by rememberSaveable(playlist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(lastPlayedKey, channels) {
        if (lastPlayedKey == null || lastPlayedKey == restoredKey) return@LaunchedEffect
        val lastKey = content?.byKey?.get(lastPlayedKey)?.let { mergedByUid[it.uid]?.primary?.key } ?: lastPlayedKey
        val at = channels.indexOfFirst { it.key == lastKey }
        if (at < 0) return@LaunchedEffect
        restoredKey = lastPlayedKey
        channelIndex = at
        zone = IptvZone.Channels
        starFocused = false
        if (channelState.layoutInfo.visibleItemsInfo.none { it.index == at }) {
            channelState.scrollToItem((at - 2).coerceAtLeast(0))
        }
    }

    fun play(channel: IptvChannel): Boolean =
        IptvPlaybackRequests.play(playlist, channel, zapList = if (wallSection) mergedPrimaries else channels, alternates = alternates)

    // fork:live-tv-on-demand - a channel picked in the list (or Your 40, or the guide) whose programme is well under way
    // opens that programme's page first: From the start, on demand, or Watch live.
    val onDemandScope = rememberCoroutineScope()
    fun playOrOffer(channel: IptvChannel) {
        val t = System.currentTimeMillis()
        val entry = if (settings.onDemand) IptvEpgRepository.guideEntries(playlist, channel).firstOrNull { it.startMs <= t && it.endMs > t } else null
        if (entry != null && IptvOnDemandRules.worth(entry, t)) {
            detail = IptvWallDetail(mergedByUid[channel.uid] ?: IptvMergedChannel(listOf(channel)), entry, entry.kind == IptvProgrammeKind.FILM, null)
            detailIndex = 0
        } else {
            play(channel)
        }
    }

    fun isFavourite(channel: IptvChannel): Boolean {
        val copies = mergedByUid[channel.uid]?.sources ?: listOf(channel)
        return copies.any { IptvRepository.channelRef(playlist.id, it.key) in settings.favourites }
    }

    fun toggleFavourite(channel: IptvChannel) {
        val whole = mergedByUid[channel.uid]
        if (whole != null) IptvRepository.toggleFavourite(playlist.id, whole) else IptvRepository.toggleFavourite(playlist.id, channel.key)
    }

    // fork:live-tv-on-now - a film, or anything that can be started over, opens its page; the rest just plays.
    fun openOrPlay(channel: IptvMergedChannel, entry: IptvEpgEntry?, film: Boolean, match: com.nuvio.app.features.livescores.LiveMatch?) {
        val startOver = entry != null && IptvCatchup.available(playlist, channel, entry, System.currentTimeMillis())
        // fork:live-tv-departure - a programme that has not begun opens its page, which offers the reminder.
        val notBegun = soonOn && entry != null && entry.startMs > System.currentTimeMillis() + 60_000L
        // fork:live-tv-on-demand - something well under way opens its page too, which offers it from the start.
        val demand = settings.onDemand && entry != null && IptvOnDemandRules.worth(entry, System.currentTimeMillis())
        if (film || startOver || notBegun || demand) {
            detail = IptvWallDetail(channel, entry, film, match)
            detailIndex = 0
        } else {
            play(channel.primary)
        }
    }

    fun setReminder(channel: IptvMergedChannel, entry: IptvEpgEntry, autoSwitch: Boolean) {
        IptvRepository.addReminder(
            IptvReminder(
                playlistId = playlist.id,
                channelKey = channel.primary.key,
                channelName = channel.primary.name.ifBlank { "Channel ${channel.primary.num}" },
                title = entry.title,
                startMs = entry.startMs,
                endMs = entry.endMs,
                autoSwitch = autoSwitch,
            ),
        )
    }

    fun activateWall(item: IptvWallItem) {
        when (item) {
            is IptvWallItem.Match -> item.programme?.let { openOrPlay(it.channel, it.entry, film = false, match = item.match) }
            is IptvWallItem.Programme -> openOrPlay(item.programme.channel, item.programme.entry, item.film, match = null)
            is IptvWallItem.Channel -> play(item.channel.primary)
            is IptvWallItem.Action -> when {
                item.id.startsWith("minutes:") -> item.id.removePrefix("minutes:").toIntOrNull()?.let(IptvRepository::setMinutes)
                item.id == "reshuffle" -> IptvRepository.reshuffleThree(IptvDecide.nightOf(System.currentTimeMillis()))
                // fork:live-tv-evening - approving the running order sets an auto-switch reminder for every slot.
                item.id == "order:approve" -> wallRows.flatMap { it.items }.filterIsInstance<IptvWallItem.Programme>().forEach {
                    setReminder(it.programme.channel, it.programme.entry, autoSwitch = true)
                }
                item.id == "order:cancel" -> wallRows.flatMap { it.items }.filterIsInstance<IptvWallItem.Programme>().forEach {
                    IptvRepository.removeReminder(IptvRunningOrder.reminderId(playlist.id, it.programme))
                }
                item.id == "ask:not" -> askSkips++
                else -> Unit
            }
            is IptvWallItem.Note -> Unit
        }
    }

    fun runDetailAction(action: IptvDetailAction) {
        val open = detail ?: return
        when (action) {
            is IptvDetailAction.StartOver -> IptvPlaybackRequests.playCatchup(playlist, open.channel, action.entry, mergedPrimaries, alternates)
            is IptvDetailAction.Earlier -> IptvPlaybackRequests.playCatchup(playlist, open.channel, action.entry, mergedPrimaries, alternates)
            IptvDetailAction.WatchLive -> play(open.channel.primary)
            is IptvDetailAction.Remind -> setReminder(open.channel, action.entry, autoSwitch = false)
            is IptvDetailAction.Switch -> setReminder(open.channel, action.entry, autoSwitch = true)
            is IptvDetailAction.CancelReminder -> IptvRepository.removeReminder(action.reminder.id)
            is IptvDetailAction.OnDemand -> onDemandScope.launch {
                val target = IptvOnDemand.find(action.entry)
                if (target != null) IptvOnDemand.play(target) else NuvioToastController.show(message = "${action.entry.title} isn't on TMDB, so it can only be joined live", durationMillis = 3500L)
            }
            is IptvDetailAction.Follow -> IptvRepository.follow(action.title)
            is IptvDetailAction.Unfollow -> IptvRepository.unfollow(action.title)
        }
    }

    fun lineupPosition(channel: IptvMergedChannel): Int = IptvLineupRules.position(storedLineup.orEmpty(), channel)
    val menuActions = remember(menuChannel, storedLineup) {
        menuChannel?.let { IptvLineupAction.forChannel(IptvLineupRules.position(storedLineup.orEmpty(), it), storedLineup.orEmpty().size) }.orEmpty()
    }
    menuIndex = menuIndex.coerceIn(0, (menuActions.size - 1).coerceAtLeast(0))

    fun openMenu(channel: IptvChannel) {
        val whole = mergedByUid[channel.uid] ?: IptvMergedChannel(listOf(channel))
        menuChannel = whole
        menuIndex = 0
    }

    fun runMenuAction(action: IptvLineupAction) {
        val whole = menuChannel ?: return
        val current = storedLineup.orEmpty()
        when (action) {
            IptvLineupAction.MoveUp, IptvLineupAction.MoveDown -> {
                val moved = IptvLineupRules.move(current, whole, if (action == IptvLineupAction.MoveUp) -1 else 1)
                IptvRepository.setLineup(playlist.id, moved)
                IptvLineupRules.position(moved, whole).takeIf { it >= 0 && lineupSection }?.let { channelIndex = it }
            }
            IptvLineupAction.Add -> IptvLineupRules.add(current, whole)?.let { IptvRepository.setLineup(playlist.id, it) }
            IptvLineupAction.Remove -> IptvRepository.setLineup(playlist.id, IptvLineupRules.remove(current, whole))
            IptvLineupAction.Never -> IptvRepository.neverAgain(playlist.id, whole)
        }
        menuChannel = null
    }

    fun closeSearch() {
        filter = ""
        searchOpen = false
        screenFocus.requestFocusLogged()
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
            IptvHeaderAction.Triage -> triageOpen = true
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
        detail = null // picking a section leaves an open programme page
    }

    fun moveDown() {
        when (zone) {
            IptvZone.Header -> zone = when {
                regionsOpen -> IptvZone.Regions
                hasContent -> IptvZone.Channels
                else -> IptvZone.Sidebar
            }
            IptvZone.Regions -> zone = if (hasContent) IptvZone.Channels else IptvZone.Sidebar
            IptvZone.Sidebar -> sidebarIndex = (sidebarIndex + 1).coerceAtMost(sidebarItems.lastIndex)
            IptvZone.Channels -> if (wallSection) {
                wallRow = (wallRow + 1).coerceAtMost(wallRows.lastIndex.coerceAtLeast(0))
            } else {
                channelIndex = (channelIndex + 1).coerceAtMost(channels.lastIndex.coerceAtLeast(0))
            }
        }
    }

    fun moveUp() {
        val above = if (regionsOpen) IptvZone.Regions else IptvZone.Header
        when (zone) {
            IptvZone.Header -> Unit
            IptvZone.Regions -> zone = IptvZone.Header
            IptvZone.Sidebar -> if (sidebarIndex > 0) sidebarIndex-- else zone = above
            IptvZone.Channels -> if (wallSection) {
                if (wallRow > 0) wallRow-- else zone = above
            } else {
                if (channelIndex > 0) channelIndex-- else zone = above
            }
        }
    }

    fun handleKey(navKey: Key): Boolean {
        if (menuChannel != null) {
            when (navKey) {
                Key.DirectionDown -> menuIndex = (menuIndex + 1).coerceAtMost((menuActions.size - 1).coerceAtLeast(0))
                Key.DirectionUp -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                Key.Enter, Key.NumPadEnter -> menuActions.getOrNull(menuIndex)?.let(::runMenuAction)
                Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown -> Unit
                else -> return false
            }
            return true
        }
        if (detail != null) {
            // The programme page: its actions are one list; Back (key-up, see below) closes it.
            when (navKey) {
                Key.DirectionDown -> detailIndex = (detailIndex + 1).coerceAtMost((detailActions.size - 1).coerceAtLeast(0))
                Key.DirectionUp -> detailIndex = (detailIndex - 1).coerceAtLeast(0)
                Key.Enter, Key.NumPadEnter -> detailActions.getOrNull(detailIndex)?.let(::runDetailAction)
                Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown -> Unit
                else -> return false
            }
            return true
        }
        when (navKey) {
            Key.DirectionDown -> moveDown()
            Key.DirectionUp -> moveUp()
            Key.PageDown, Key.PageUp -> {
                val step = if (navKey == Key.PageDown) 10 else -10
                when (zone) {
                    IptvZone.Sidebar -> sidebarIndex = (sidebarIndex + step).coerceIn(0, sidebarItems.lastIndex)
                    IptvZone.Channels -> if (wallSection) {
                        wallRow = (wallRow + step / 5).coerceIn(0, wallRows.lastIndex.coerceAtLeast(0))
                    } else {
                        channelIndex = (channelIndex + step).coerceIn(0, channels.lastIndex.coerceAtLeast(0))
                    }
                    else -> return false
                }
            }
            Key.DirectionLeft -> when (zone) {
                IptvZone.Header -> headerIndex = (headerIndex - 1).coerceAtLeast(0)
                IptvZone.Regions -> regionIndex = (regionIndex - 1).coerceAtLeast(0)
                IptvZone.Sidebar -> return false
                IptvZone.Channels -> when {
                    wallSection -> if (wallCol > 0) wallCol-- else zone = IptvZone.Sidebar
                    moreFocused -> { moreFocused = false; starFocused = true }
                    starFocused -> starFocused = false
                    else -> zone = IptvZone.Sidebar
                }
            }
            Key.DirectionRight -> when (zone) {
                IptvZone.Header -> headerIndex = (headerIndex + 1).coerceAtMost(headerActions.lastIndex)
                IptvZone.Regions -> regionIndex = (regionIndex + 1).coerceAtMost(regions.size)
                IptvZone.Sidebar -> if (hasContent) zone = IptvZone.Channels
                // The guide's rows have no star; the list's star is one step right of its row.
                IptvZone.Channels -> if (wallSection) {
                    wallCol = (wallCol + 1).coerceAtMost(((wallRows.getOrNull(wallRow)?.items?.size ?: 1) - 1).coerceAtLeast(0))
                } else if (!guideView) {
                    if (starFocused && lineupOn) { starFocused = false; moreFocused = true } else if (!moreFocused) starFocused = true
                }
            }
            Key.Enter, Key.NumPadEnter -> when (zone) {
                IptvZone.Header -> headerActions.getOrNull(headerIndex)?.let(::activateHeader)
                IptvZone.Regions -> selectRegion(if (regionIndex == 0) null else regions.getOrNull(regionIndex - 1))
                IptvZone.Sidebar -> sidebarItems.getOrNull(sidebarIndex)?.let(::activateSidebar)
                IptvZone.Channels -> if (wallSection) {
                    wallRows.getOrNull(wallRow)?.items?.getOrNull(wallCol)?.let(::activateWall)
                } else {
                    channels.getOrNull(channelIndex)?.let { channel ->
                        when {
                            moreFocused && !guideView -> openMenu(channel)
                            starFocused && !guideView -> toggleFavourite(channel)
                            else -> playOrOffer(channel)
                        }
                    }
                }
            }
            else -> return false
        }
        if (zone != IptvZone.Channels) { starFocused = false; moreFocused = false }
        return true
    }

    // fork:live-tv-stage - picking "Stages" in the sidebar goes back to them.
    LaunchedEffect(section) {
        if (section == STAGE_SECTION) {
            stageOpen = true
            section = home
        }
    }

    // fork:live-tv-triage - the questions take the whole tab while they are open.
    if (triageOpen && content != null) {
        IptvTriageScreen(
            playlist = playlist,
            content = content,
            categoryRegions = categoryRegions,
            settings = settings,
            modifier = modifier,
            topChromePadding = topChromePadding,
            onClose = { triageOpen = false },
        )
        return
    }

    // fork:live-tv-stage
    if (stageOn && stageOpen && content != null && merged != null) {
        IptvStageScreen(
            playlist = playlist,
            content = content,
            merged = wallMerged ?: merged,
            lineup = lineupChannels,
            settings = settings,
            alternates = alternates,
            guideVersion = guideVersion,
            modifier = modifier,
            topChromePadding = topChromePadding,
            onAllChannels = { stageOpen = false },
            onTriage = { triageOpen = true },
            keptCategories = keptCategories,
        )
        return
    }

    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxSize()
            // The settings sky and glass, so Live TV reads as part of the same app as Settings.
            .settingsSkyBackground(tokens.colors.background, tokens.colors.accent)
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Live TV") { screenFocus.requestFocusLogged() }
            .focusRequester(screenFocus)
            .focusable()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                keyboardActive = false
                // A press in the filter box is the box's own; anywhere else takes the keys back.
                val at = event.changes.first().position + rootOrigin
                if (searchBounds?.contains(at) != true) screenFocus.requestFocusLogged()
            }
            .onPreviewKeyEvent { event ->
                if ((detail != null || menuChannel != null) && !searchFocused && event.key in IptvBackKeys) {
                    // Back acts as the key comes up, as it does across the app; its key-down is taken so nothing else acts on it.
                    if (event.type == KeyEventType.KeyUp) { if (menuChannel != null) menuChannel = null else detail = null }
                    return@onPreviewKeyEvent true
                }
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
                            zone = if (hasContent) IptvZone.Channels else IptvZone.Sidebar
                            if (filter.isEmpty()) searchOpen = false
                            screenFocus.requestFocusLogged()
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
                triageActive = triage != null,
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
                menuChannel != null -> IptvLineupMenu(
                    channelName = menuChannel!!.primary.name,
                    note = menuChannel!!.let { whole ->
                        val at = lineupPosition(whole)
                        if (at >= 0) "Number ${at + 1} in Your 40" else "Not in Your 40"
                    },
                    actions = menuActions,
                    focused = if (keyboardActive) menuIndex else -1,
                    onAction = { action ->
                        menuIndex = menuActions.indexOf(action).coerceAtLeast(0)
                        runMenuAction(action)
                    },
                    onBack = { menuChannel = null },
                )
                detail != null -> IptvProgrammeDetail(
                    playlist = playlist,
                    detail = detail!!,
                    actions = detailActions,
                    now = now,
                    focusedAction = if (keyboardActive) detailIndex else -1,
                    onAction = { action ->
                        detailIndex = detailActions.indexOf(action).coerceAtLeast(0)
                        runDetailAction(action)
                    },
                    onBack = { detail = null },
                )
                wallSection && wallRows.isEmpty() -> IptvMessage(
                    text = when {
                        filter.isNotBlank() -> stringResource(Res.string.iptv_tab_empty_filter)
                        wall == null -> "Reading the guide..."
                        section == IptvWall.SPORT -> "No sport in the guide right now. Follow teams or competitions under " +
                            "Settings > Live TV > Live scores to see their matches here."
                        section == IptvDecide.EARLIER -> "Nothing from earlier today is kept for these channels. This needs an Xtream account " +
                            "whose provider keeps catch-up, and the lineup's (or your usual) channels."
                        section == IptvDecide.FOLLOWING -> "Nothing followed yet. Open a programme and choose Follow: it is found on whichever channel has it."
                        section == IptvWall.SOON -> "Nothing starts in the next hour" +
                            (if (settings.lineupOn) " on the channels of Your 40." else ", or this playlist has no programme guide.")
                        section == IptvWall.FILMS -> "No films on right now, or this playlist's guide does not say what is a film."
                        else -> "Nothing to show yet. This screen needs a programme guide; your channels appear here once you have watched some."
                    },
                )
                wallSection -> IptvWallContent(
                    playlist = playlist,
                    rows = wallRows,
                    now = now,
                    listState = wallState,
                    focusRow = if (keyboardActive && zone == IptvZone.Channels) wallRow else -1,
                    focusCol = wallCol,
                    onActivate = { row, col, item ->
                        wallRow = row
                        wallCol = col
                        activateWall(item)
                    },
                )
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
                    onPlay = { playOrOffer(it) },
                )
                else -> IptvChannelList(
                    playlist = playlist,
                    channels = channels,
                    isFavourite = { isFavourite(it) },
                    sourcesLabel = { channel -> mergedByUid[channel.uid]?.sourcesLabel() },
                    now = now,
                    guideVersion = guideVersion,
                    listState = channelState,
                    focusedIndex = if (keyboardActive && zone == IptvZone.Channels) channelIndex else -1,
                    starFocused = starFocused,
                    moreFocused = moreFocused,
                    showMore = lineupOn,
                    numberOf = if (lineupSection) ({ index: Int -> index + 1 }) else null,
                    onMore = { openMenu(it) },
                    onPlay = { playOrOffer(it) },
                    onToggleFavourite = { toggleFavourite(it) },
                )
            }
        }
    }
}

/** Keys that close the programme page. */
/** fork:live-tv-stage - the sidebar item that goes back to the stages. */
private const val STAGE_SECTION = "__stage__"

private val IptvBackKeys = setOf(Key.Escape, Key.Back, Key.Backspace)

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
    /** fork:live-tv-triage - what the triage kept; null = everything. */
    keptCategories: Set<String>? = null,
): List<IptvChannel> {
    val needle = filter.trim()
    val all = IptvGroups.channels(content, section, settings)
    // All channels shows what the triage kept, but a search there still reaches every channel.
    // fork:live-tv-triage - a kept category opens without the channels its triage left out for their language.
    val base = when {
        keptCategories == null || needle.isNotEmpty() -> all
        section == IptvSection.ALL -> all.filter { keptCategories.keeps(it) }
        section == IptvSection.FAVOURITES || section == IptvSection.RECENT -> all
        keptCategories is IptvKept -> all.filter { it.key !in keptCategories.dropped }
        else -> all
    }
    // Favourites and Recents are the viewer's own lists; only All is narrowed to the region (a
    // category already belongs to one).
    val regional = if (region != null && section == IptvSection.ALL) {
        base.filter { categoryRegions[it.categoryId] == region }
    } else {
        base
    }
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
                    keyboardFocused || hovered -> IptvGlassHover
                    else -> Color.Transparent
                },
            )
            .then(if (keyboardFocused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
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
    /** fork:live-tv-triage - lights the Triage button while a triage is narrowing the tab. */
    triageActive: Boolean,
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
                            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, RoundedCornerShape(12.dp)) else Modifier),
                        placeholder = filterLabel,
                        focusRequester = searchFocus,
                        leadingContent = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                        },
                        trailingContent = {
                            com.nuvio.app.core.ui.VoiceSearchButton(
                                owner = "iptv-filter",
                                focusField = { searchFocus.requestFocusLogged() },
                                tint = tokens.colors.textMuted,
                                size = 26.dp,
                                iconSize = 18.dp,
                            )
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onCloseSearch),
                            )
                        },
                    )
                    com.nuvio.app.core.ui.VoiceSearchCommands(owner = "iptv-filter", text = filter, onTextChange = onFilterChange)
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
                IptvHeaderAction.Triage -> IptvHeaderButton(
                    icon = Icons.Rounded.Tune,
                    description = "Triage: choose what Live TV shows",
                    focused = focused,
                    active = triageActive,
                ) { onAction(action) }
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
internal fun IptvHeaderButton(
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
                    focused || hovered -> IptvGlassHover
                    else -> Color.Transparent
                },
            )
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, CircleShape) else Modifier)
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
internal fun IptvFlag(region: String, modifier: Modifier = Modifier) {
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
            .iptvGlass(shape, selected = selected, raised = focused || hovered, selectedTint = tokens.colors.accent.copy(alpha = 0.22f))
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
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
    isFavourite: (IptvChannel) -> Boolean,
    /** fork:live-tv-on-now - "FHD · 4 sources" for a merged channel; null otherwise. */
    sourcesLabel: (IptvChannel) -> String?,
    now: Long,
    guideVersion: Int,
    listState: LazyListState,
    focusedIndex: Int,
    starFocused: Boolean,
    moreFocused: Boolean,
    showMore: Boolean,
    /** fork:live-tv-lineup - the number shown for the row at an index (the lineup's place); null shows the provider's. */
    numberOf: ((Int) -> Int)?,
    onMore: (IptvChannel) -> Unit,
    onPlay: (IptvChannel) -> Unit,
    onToggleFavourite: (IptvChannel) -> Unit,
) {
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
                favourite = isFavourite(channel),
                sourcesLabel = sourcesLabel(channel),
                keyboardFocused = index == focusedIndex && !starFocused && !moreFocused,
                starKeyboardFocused = index == focusedIndex && starFocused,
                number = numberOf?.invoke(index) ?: channel.num,
                showMore = showMore,
                moreKeyboardFocused = index == focusedIndex && moreFocused,
                onMore = { onMore(channel) },
                onPlay = { onPlay(channel) },
                onToggleFavourite = { onToggleFavourite(channel) },
            )
        }
    }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun formatTime(ms: Long): String = TimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun IptvChannelRow(
    channel: IptvChannel,
    nowEntry: IptvEpgEntry?,
    nextEntry: IptvEpgEntry?,
    now: Long,
    favourite: Boolean,
    sourcesLabel: String?,
    keyboardFocused: Boolean,
    starKeyboardFocused: Boolean,
    number: Int,
    showMore: Boolean,
    moreKeyboardFocused: Boolean,
    onMore: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    val name = channel.name.ifBlank { stringResource(Res.string.iptv_channel_number, channel.num) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iptvGlass(shape, raised = keyboardFocused || starKeyboardFocused || hovered)
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .then(if (keyboardFocused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
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
            if (sourcesLabel != null) {
                Text(
                    text = sourcesLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        if (showMore) {
            Spacer(Modifier.width(4.dp))
            IptvHeaderButton(
                icon = Icons.Rounded.MoreHoriz,
                description = "Your 40: move, take out, never again",
                focused = moreKeyboardFocused,
                active = false,
                onClick = onMore,
            )
        }
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

/** A hovered or focused row: the glass, a step lighter. */
internal val IptvGlassHover = Color.White.copy(alpha = 0.09f)

/**
 * The settings glass for a Live TV tile or row: the frosted fill and edge, a lighter fill while
 * [raised] (hovered or focused), and [selectedTint] instead while [selected].
 */
internal fun Modifier.iptvGlass(
    shape: Shape,
    raised: Boolean = false,
    selected: Boolean = false,
    selectedTint: Color = Color.Transparent,
): Modifier = this
    .ditheredBackground(NuvioGlass.Fill, shape)
    .background(
        when {
            selected -> selectedTint
            raised -> IptvGlassHover
            else -> Color.Transparent
        },
        shape,
    )
    .border(1.dp, NuvioGlass.Edge, shape)

/**
 * fork:live-tv-polish - a menu or picker over a stage: a solid dark card (not see-through glass, so nothing behind it
 * shows through the words) with the glass edge and a deep shadow.
 */
internal fun Modifier.iptvDialog(shape: Shape): Modifier = this
    .shadow(32.dp, shape, clip = false)
    .background(IptvDialogFill, shape)
    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)

private val IptvDialogFill = Color(0xFF17171C)

/** fork:live-tv-polish - what is behind a menu: dimmed well down, so the menu is the only thing to read. */
internal val IptvDialogScrim = Color.Black.copy(alpha = 0.72f)

/** fork:navbar-search - the Live TV channel filter, typed in the tab's own box or the app bar's Search on this tab. */
object IptvTopBarSearch {
    var text by mutableStateOf("")
    internal var playlistId: String? = null
}
