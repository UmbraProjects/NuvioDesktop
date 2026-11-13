package com.nuvio.app.features.youtube

import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Refresh
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.CursorMenuPositionProvider
import com.nuvio.app.core.ui.NuvioGlassMenuSurface
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.layout.Spacer
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.settings.settingsSkyBackground
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.ContextMenuInvocation
import com.nuvio.app.core.ui.KeyboardInputMode
import com.nuvio.app.core.ui.NuvioContextMenu
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.TabBackHandler
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.rememberHoldToSelectState
import com.nuvio.app.core.ui.secondaryClick
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * fork:youtube-mode
 *
 * The YouTube tab: the signed-in account's home feed and subscriptions as a grid. Picking a video
 * resolves it through the music resolver (restricted-client retry plus a stream probe) and hands it
 * to the ordinary player. First version: no channels, search, Shorts, live or watch-history sync.
 */

/** Whether the YouTube tab is in the navigation. */
@Composable
fun rememberYouTubeTabVisible(): Boolean {
    val enabled by remember { YouTubeFeedRepository.tabEnabled }.collectAsStateWithLifecycle()
    return enabled == true
}

@Composable
fun YouTubeTabScreen(
    onPlay: (PlayerLaunch) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    /** Opens a playlist's next video once the player gave up on it; off in the mini player's copy of the tab. */
    opensQueueNext: Boolean = true,
) {
    val auth by remember {
        YouTubeMusicAuth.ensureLoaded()
        YouTubeMusicAuth.state
    }.collectAsStateWithLifecycle()
    // A feed's name, or CHANNELS_VIEW / PLAYLISTS_VIEW / QUEUE_VIEW for the lists of subscribed channels,
    // of playlists and the queue (not YouTube feeds).
    var selectedName by rememberSaveable { mutableStateOf(YouTubeFeedKind.Home.name) }
    val showingChannelList = selectedName == CHANNELS_VIEW
    val showingPlaylists = selectedName == PLAYLISTS_VIEW
    val showingQueue = selectedName == QUEUE_VIEW
    // Under a list, the effects keep the Subscriptions feed loaded meanwhile.
    val kind = if (showingChannelList || showingPlaylists || showingQueue) YouTubeFeedKind.Subscriptions else YouTubeFeedKind.valueOf(selectedName)
    val watchQueue by YouTubeWatchQueue.videos.collectAsStateWithLifecycle()
    val queuedIds = remember(watchQueue) { watchQueue.mapTo(HashSet()) { it.videoId } }
    val openChannel by YouTubeFeedRepository.openChannel.collectAsStateWithLifecycle()
    val openPlaylist by YouTubeFeedRepository.openPlaylist.collectAsStateWithLifecycle()
    // The pages a channel or playlist page was opened from, newest last: Back (the arrow, Esc, a
    // pad's B) steps through them inside the tab instead of leaving it.
    var backStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Where each page on the back stack was left (by its depth): Back scrolls there and focuses the
    // card or tile the next page was opened from, which has left the screen by then.
    val returnPoints = remember { HashMap<Int, YouTubeReturnPoint>() }
    var pendingReturn by remember { mutableStateOf<YouTubeReturnPoint?>(null) }
    // fork:youtube-mini-player - the tab picks up where the mini player's copy of it was left when
    // the mini player closes: same page, same pages behind it, same scroll (YouTubeTabCarryOver).
    // Otherwise where the tab was last left this session (Subscriptions, a channel, scrolled down it), so
    // coming back from a video does not always start on Home.
    val carried = remember { (if (opensQueueNext) YouTubeTabCarryOver.take() else null) ?: YouTubeTabLastPlace.place }
    remember(carried) {
        if (carried != null) {
            selectedName = carried.selectedName
            backStack = carried.backStack
        }
    }
    // Read in push(), which comes before the grid in this function.
    var mainGrid: androidx.compose.foundation.lazy.grid.LazyGridState? = null
    fun push(next: String, from: String? = null) {
        if (selectedName == next) return
        backStack = (backStack + selectedName).takeLast(MAX_BACK_STEPS)
        returnPoints[backStack.lastIndex] = YouTubeReturnPoint(
            page = selectedName,
            itemKey = from,
            index = mainGrid?.firstVisibleItemIndex ?: 0,
            offset = mainGrid?.firstVisibleItemScrollOffset ?: 0,
        )
        selectedName = next
    }
    fun goBack() {
        val page = backStack.lastOrNull() ?: YouTubeFeedKind.Home.name
        pendingReturn = returnPoints.remove(backStack.lastIndex)?.takeIf { it.page == page }
        selectedName = page
        backStack = backStack.dropLast(1)
    }
    fun showChannel(channelId: String, name: String, avatarUrl: String?, from: String?) {
        YouTubeFeedRepository.openChannel(channelId, name, avatarUrl)
        push(YouTubeFeedKind.Channel.name, from)
    }
    fun showPlaylist(playlist: YouTubePlaylist) {
        YouTubeFeedRepository.openPlaylist(playlist)
        push(YouTubeFeedKind.Playlist.name, from = playlist.playlistId)
    }
    // fork:youtube-mini-player - a channel asked for from the watch page (its name): opened here, as
    // if picked in the tab, the video going on in the corner.
    val channelRequest by YouTubeWatchLayout.channelRequest.collectAsStateWithLifecycle()
    LaunchedEffect(channelRequest) {
        val request = channelRequest ?: return@LaunchedEffect
        YouTubeWatchLayout.consumeChannelRequest(request)
        showChannel(request.channelId, request.name, request.avatarUrl, from = null)
    }
    // TabBackHandler, not PlatformBackHandler (which does nothing on desktop): the app asks it before
    // walking its tab history, as the Music tab's pages do.
    TabBackHandler(enabled = backStack.isNotEmpty()) { goBack() }
    val queue by YouTubePlayQueue.state.collectAsStateWithLifecycle()
    val queueNext by YouTubePlayQueue.next.collectAsStateWithLifecycle()
    val feeds by YouTubeFeedRepository.feeds.collectAsStateWithLifecycle()
    val feed = feeds.getValue(kind)
    val channel by YouTubeFeedRepository.channel.collectAsStateWithLifecycle()
    val searchQuery by YouTubeFeedRepository.searchQuery.collectAsStateWithLifecycle()
    val deArrow by remember { YouTubeFeedRepository.deArrow }.collectAsStateWithLifecycle()
    val hideWatched by remember { YouTubeFeedRepository.hideWatched }.collectAsStateWithLifecycle()
    val hideStreams by remember { YouTubeFeedRepository.hideStreams }.collectAsStateWithLifecycle()
    val autoplay by remember { YouTubeFeedRepository.autoplay }.collectAsStateWithLifecycle()
    // Not on History, which is all watched by definition (a stream watched is history like any other).
    val filtered = kind != YouTubeFeedKind.History
    // The queue is shown as it is: the person put each one there.
    // A channel's Live tab is nothing but streams, so "Hide streams" leaves it alone.
    val dropStreams = hideStreams &&
        !(kind == YouTubeFeedKind.Channel && openChannel?.tab == YouTubeChannelTab.Live)
    val videos = if (showingQueue) {
        watchQueue
    } else if (filtered && (hideWatched || dropStreams)) {
        feed.videos.filterNot { (hideWatched && isYouTubeVideoWatched(it)) || (dropStreams && it.wasLive) }
    } else {
        feed.videos
    }
    // A page that was all watched leaves nothing on screen to scroll to the end of, so ask for the next one here.
    val allHidden = !showingQueue && videos.isEmpty() && feed.videos.isNotEmpty()
    LaunchedEffect(allHidden, feed.continuation, feed.loading) {
        if (allHidden && feed.continuation != null && !feed.loading && feed.error == null) YouTubeFeedRepository.loadMore(kind)
    }
    // The box's text lives with the top bar's box, which shows it while the bar hosts the search.
    val searchBox = YouTubeTopBarSearch
    var focusResultsPending by remember { mutableStateOf(false) }
    fun submitSearch() {
        if (searchBox.text.isBlank()) return
        focusResultsPending = true
        YouTubeSearchHistory.record(searchBox.text)
        YouTubeFeedRepository.search(searchBox.text)
        // Back from the results returns to where the search was typed.
        if (selectedName != YouTubeFeedKind.Search.name) {
            backStack = (backStack + selectedName).takeLast(MAX_BACK_STEPS)
            selectedName = YouTubeFeedKind.Search.name
        }
    }

    // Pages fetched under one session are not the other session's feed.
    var lastSignedIn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(auth.signedIn) {
        if (lastSignedIn != null && lastSignedIn != auth.signedIn) YouTubeFeedRepository.clear()
        lastSignedIn = auth.signedIn
    }
    // Also once the repository has dropped the lookup for a new sign-in (resolved goes back to false).
    LaunchedEffect(auth.signedIn, channel.resolved) {
        if (auth.signedIn && !channel.resolved) YouTubeFeedRepository.loadChannels()
    }
    // The feed waits for the channel: loading it first would show the wrong channel's feed and then swap.
    LaunchedEffect(kind, auth.signedIn, channel.resolved, channel.pageId, channel.needsChoice) {
        if (auth.signedIn && channel.resolved && !channel.needsChoice) {
            YouTubeFeedRepository.load(kind)
            // Home and Subscriptions are a click apart: the other one is fetched alongside, so switching is instant.
            when (kind) {
                YouTubeFeedKind.Home -> YouTubeFeedRepository.load(YouTubeFeedKind.Subscriptions)
                YouTubeFeedKind.Subscriptions -> YouTubeFeedRepository.load(YouTubeFeedKind.Home)
                else -> Unit
            }
        }
    }

    val scope = rememberCoroutineScope()
    var openingId by remember { mutableStateOf<String?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    // fork:youtube-unplayable - the age-gated video the message is about, for its "Open on youtube.com".
    var openErrorVideoId by remember { mutableStateOf<String?>(null) }
    // What a like or a save did, as the app's toast: a line at the top of the grid went unnoticed.
    fun confirm(title: String, message: String) =
        com.nuvio.app.core.ui.NuvioToastController.show(message = message, title = title)
    fun like(video: YouTubeVideo, liked: Boolean) {
        scope.launch {
            val ok = YouTubeFeedRepository.setLiked(video.videoId, liked)
            when {
                !ok -> confirm("Like", "YouTube didn't take that. Try again shortly.")
                liked -> confirm("Liked", youTubeDisplayTitle(video))
                else -> confirm("Like removed", youTubeDisplayTitle(video))
            }
        }
    }
    var savingVideo by remember { mutableStateOf<YouTubeVideo?>(null) }
    var commentsVideo by remember { mutableStateOf<YouTubeVideo?>(null) }
    fun saveTo(playlistId: String, name: String, video: YouTubeVideo, add: Boolean) {
        scope.launch {
            val ok = YouTubeFeedRepository.saveToPlaylist(playlistId, video, add)
            when {
                !ok -> confirm(name, "YouTube didn't take that. Try again shortly.")
                add -> confirm("Saved to $name", youTubeDisplayTitle(video))
                else -> confirm("Removed from $name", youTubeDisplayTitle(video))
            }
        }
    }
    val watchLater by YouTubeFeedRepository.watchLater.collectAsStateWithLifecycle()
    val watchedDateMode by remember { YouTubeFeedRepository.watchedDateMode }.collectAsStateWithLifecycle()
    val dateFormat by remember { YouTubeFeedRepository.dateFormat }.collectAsStateWithLifecycle()
    val watchHistory by remember { YouTubeWatchHistory.entries }.collectAsStateWithLifecycle()
    val showWatchedDates = when (watchedDateMode) {
        YouTubeWatchedDateMode.Everywhere -> true
        YouTubeWatchedDateMode.HistoryOnly -> kind == YouTubeFeedKind.History && !showingQueue && !showingChannelList && !showingPlaylists
        YouTubeWatchedDateMode.Off -> false
    }
    LaunchedEffect(auth.signedIn, channel.pageId) {
        if (auth.signedIn) YouTubeFeedRepository.loadWatchLater()
    }
    fun toggleWatchLater(video: YouTubeVideo) =
        saveTo(WATCH_LATER_PLAYLIST_ID, "Watch later", video, add = video.videoId !in watchLater)
    fun markWatched(video: YouTubeVideo) {
        scope.launch {
            if (YouTubeFeedRepository.markWatched(video)) {
                confirm("Marked as watched", youTubeDisplayTitle(video))
            } else {
                confirm("Mark as watched", "YouTube didn't take that. Try again shortly.")
            }
        }
    }
    fun dontRecommendChannel(video: YouTubeVideo) {
        scope.launch {
            val name = video.channel ?: "this channel"
            if (YouTubeFeedRepository.dontRecommendChannel(video)) {
                confirm("Channel hidden", "YouTube won't recommend $name any more.")
            } else {
                confirm("Don't recommend channel", "YouTube didn't take that. Try again shortly.")
            }
        }
    }
    fun play(video: YouTubeVideo) {
        // Anything opened outside the playlist being played through ends it.
        YouTubePlayQueue.onOpened(video.videoId)
        openJob?.cancel()
        openError = null
        openErrorVideoId = null
        openingId = video.videoId
        openJob = scope.launch {
            val launch = youTubePlayerLaunch(video)
            if (openingId != video.videoId) return@launch
            openingId = null
            if (launch == null) {
                // What YouTube said about it, when it said anything: an age gate is not throttling.
                openError = YouTubeUnplayable.openErrorMessage(
                    title = youTubeDisplayTitle(video),
                    refusal = YouTubeUnplayable.refusal(video.videoId),
                    paused = com.nuvio.app.features.music.MusicRateLimit.isBlocked(),
                )
                openErrorVideoId = video.videoId.takeIf { YouTubeUnplayable.refusal(it)?.ageGated == true }
                return@launch
            }
            onPlay(launch)
        }
    }

    // The playlist being played through: its previous video ended, so open the next.
    LaunchedEffect(queueNext) {
        if (opensQueueNext && queueNext != null) YouTubePlayQueue.takeNext()?.let(::play)
    }
    fun playFromPlaylist(index: Int) {
        val playlist = openPlaylist ?: return
        val video = videos.getOrNull(index) ?: return
        YouTubePlayQueue.start(playlist.title, videos, index)
        play(video)
    }

    fun playFromQueue(index: Int) {
        val video = watchQueue.getOrNull(index) ?: return
        YouTubePlayQueue.start(WATCH_QUEUE_NAME, watchQueue, index, watchQueue = true)
        play(video)
    }
    fun toggleQueued(video: YouTubeVideo) {
        if (YouTubeWatchQueue.contains(video.videoId)) {
            YouTubeWatchQueue.remove(video.videoId)
            confirm("Removed from queue", youTubeDisplayTitle(video))
        } else {
            YouTubeWatchQueue.add(video)
            confirm("Added to queue", youTubeDisplayTitle(video))
        }
    }

    val gridState = rememberLazyGridState()
    mainGrid = gridState
    val columnFocus = rememberGridColumnFocus(gridState)
    // In the queue the cards can be dragged into a new order (the grid holds the queue alone there).
    val queueReorder = rememberReorderableLazyGridState(gridState) { from, to ->
        YouTubeWatchQueue.move(from.key as? String ?: return@rememberReorderableLazyGridState, to.index)
    }
    // The page and search the grid was last scrolled for. Kept across the tab leaving the screen (a
    // player opened over it, the mini player's copy between videos), so coming back is not taken for
    // a change of page and the grid stays where it was rather than jumping to the top.
    var scrolledFor by rememberSaveable { mutableStateOf<String?>(null) }
    var carriedApplied by remember { mutableStateOf(false) }
    LaunchedEffect(selectedName, searchQuery) {
        val key = "$selectedName|$searchQuery"
        if (carried != null && !carriedApplied) {
            carriedApplied = true
            scrolledFor = key
            gridState.scrollToItem(carried.index, carried.offset)
            return@LaunchedEffect
        }
        if (scrolledFor == key) return@LaunchedEffect
        val first = scrolledFor == null
        scrolledFor = key
        if (first) return@LaunchedEffect
        // Back to a feed: where it was left. Anywhere else starts at the top.
        val back = pendingReturn?.takeIf { it.page == selectedName && !it.ownGrid }
        gridState.scrollToItem(back?.index ?: 0, back?.offset ?: 0)
    }
    // The mini player's copy hands its place over as it goes (to the next video, or for good); either copy
    // leaves it for the next time the tab is shown this session.
    val latestSelected by rememberUpdatedState(selectedName)
    val latestBackStack by rememberUpdatedState(backStack)
    androidx.compose.runtime.DisposableEffect(gridState) {
        onDispose {
            val place = YouTubeTabPlace(latestSelected, latestBackStack, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            if (!opensQueueNext) YouTubeTabCarryOver.put(place)
            YouTubeTabLastPlace.place = place
        }
    }
    // Shown again after a while (switched to, the tab reopened, the player closed): a feed that
    // changes by itself is fetched again, the old page staying up until the new one replaces it.
    // Not while scrolled down it, where the new page would pull the list out from under the reader.
    LaunchedEffect(kind, auth.signedIn, channel.resolved) {
        if (auth.signedIn && channel.resolved && YouTubeFeedRepository.isStale(kind) && gridState.firstVisibleItemIndex < 6) {
            YouTubeFeedRepository.load(kind, force = true)
        }
    }
    LaunchedEffect(gridState, kind, showingQueue) {
        // The queue has no next page.
        if (showingQueue) return@LaunchedEffect
        // Keyed on the item count too: the end is in sight before the first page is even back (an
        // empty grid), when there is no next page to ask for yet; a short first page (Nov 4: Home
        // answered 12) left it in sight, and on the end-only key nothing asked again until the
        // feed was left and come back to. Each page arriving with the end still in sight asks for
        // the one after.
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= total - 6) to total
        }.distinctUntilChanged().collect { (nearEnd, _) ->
            if (nearEnd) YouTubeFeedRepository.loadMore(kind)
        }
    }

    // Keyboard and pad: a page of videos arriving with nothing in the tab focused hands focus to its
    // first card, so the arrows have somewhere to start (a mouse user never sees it: the ring is
    // keyboard-only).
    val firstCardFocus = searchBox.resultsFocus
    val searchFocus = remember { FocusRequester() }
    val playlistsFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var tabHasFocus by remember { mutableStateOf(false) }
    val firstVideoId = videos.firstOrNull()?.videoId
    // The card a page was opened from, when Back returns to it.
    val returnFocus = remember { FocusRequester() }
    val returnKey = pendingReturn?.takeIf { it.page == selectedName }?.itemKey
    LaunchedEffect(pendingReturn, selectedName, firstVideoId) {
        val back = pendingReturn ?: return@LaunchedEffect
        if (back.page != selectedName || back.ownGrid) return@LaunchedEffect
        if (firstVideoId == null && feed.loading) return@LaunchedEffect // the page is still coming
        kotlinx.coroutines.delay(50L) // the grid's first frame
        val index = videos.indexOfFirst { it.videoId == back.itemKey }
        if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.key == back.itemKey }) gridState.scrollToItem(index)
        if (index < 0 || !returnFocus.requestFocusLogged()) {
            if (firstVideoId != null) firstCardFocus.requestFocusLogged()
        }
        pendingReturn = null
    }
    LaunchedEffect(KeyboardInputMode.active, selectedName, firstVideoId) {
        if (KeyboardInputMode.active && !tabHasFocus && firstVideoId != null && pendingReturn == null) {
            kotlinx.coroutines.delay(50L) // the grid's first frame
            firstCardFocus.requestFocusLogged()
        }
    }
    // The bar's box: Enter searches, its clear button leaves the results. Requests made before this
    // screen existed are not replayed.
    // fork:voice-control - a search said while elsewhere, run once the tab is here.
    LaunchedEffect(searchBox.pendingSearch) {
        val query = searchBox.pendingSearch ?: return@LaunchedEffect
        searchBox.pendingSearch = null
        searchBox.text = query
        submitSearch()
    }
    var handledSubmits by remember { mutableStateOf(searchBox.submits) }
    LaunchedEffect(searchBox.submits) {
        if (searchBox.submits != handledSubmits) submitSearch()
        handledSubmits = searchBox.submits
    }
    var handledClears by remember { mutableStateOf(searchBox.clears) }
    LaunchedEffect(searchBox.clears) {
        if (searchBox.clears != handledClears && kind == YouTubeFeedKind.Search) goBack()
        handledClears = searchBox.clears
    }
    // Enter in the search box: once the results are in, focus moves to the first of them.
    LaunchedEffect(focusResultsPending, firstVideoId, feed.loading) {
        if (focusResultsPending && kind == YouTubeFeedKind.Search && firstVideoId != null && !feed.loading) {
            kotlinx.coroutines.delay(50L)
            firstCardFocus.requestFocusLogged()
            focusResultsPending = false
        }
    }

    val sky by remember { YouTubeFeedRepository.skyBackground }.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.nuvio
    val barHosted = searchBox.barHosted
    Column(
        modifier = modifier
            .fillMaxSize()
            // Settings > YouTube account > Gradient background: the Settings screen's sky.
            .then(if (sky) Modifier.settingsSkyBackground(tokens.colors.background, tokens.colors.accent) else Modifier)
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one returns focus to the control
            // last used in here. Focus had ended up on a control outside the tab, where every arrow was lost
            // (2026-11-13, 70 keys until a shortcut moved it).
            .keyLayer("YouTube")
            .onFocusChanged { tabHasFocus = it.hasFocus }
            .arrowKeyFocusNavigation()
            // With the search on the top bar, the page starts right under the bar.
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = if (barHosted) ((topChromePadding ?: 24.dp) - BAR_HOSTED_TRIM).coerceAtLeast(8.dp) else topChromePadding ?: 24.dp,
            ),
    ) {
        // A window too narrow for every pill's name shows them as icons alone (named on hover) rather
        // than cutting the row off: what the names need is measured against the room there is.
        val headerLabels = buildList {
            YouTubeFeedKind.entries.filter { it.inHeader }.forEach { add(it.label) }
            add("Channels")
            add("Playlists")
            if (watchQueue.isNotEmpty() || showingQueue) add("Queue · ${watchQueue.size}")
            if (auth.signedIn) addAll(listOf("Hide watched", "Hide streams", "Autoplay", "Refresh"))
        }
        val headerText = androidx.compose.ui.text.rememberTextMeasurer()
        val headerStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val labelledWidth = remember(headerLabels, headerStyle, density) {
            with(density) {
                headerLabels.sumOf { label ->
                    SECTION_TAB_CHROME.toPx().toDouble() + headerText.measure(label, headerStyle, maxLines = 1).size.width
                }.toFloat().toDp()
            }
        } + 4.dp * (headerLabels.size - 1) +
            (if (auth.signedIn && !barHosted) 352.dp else 0.dp) +
            (if (auth.signedIn && channel.resolved) 42.dp else 0.dp) +
            16.dp
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalCompactSectionTabs provides (labelledWidth > maxWidth)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A section button starts it afresh: nothing to go back to inside it. Pressed again, it reloads.
            fun section(name: String) {
                backStack = emptyList()
                selectedName = name
            }
            // The sections as named icons; the one in use is lit.
            YouTubeFeedKind.entries.filter { it.inHeader }.forEach { entry ->
                val current = entry == kind && !showingChannelList && !showingPlaylists
                YouTubeSectionTab(entry.headerIcon, entry.label, on = current) {
                    if (current) YouTubeFeedRepository.load(entry) else section(entry.name)
                }
            }
            YouTubeSectionTab(Icons.Rounded.Groups, "Channels", on = showingChannelList) {
                if (showingChannelList) YouTubeFeedRepository.loadSubscribedChannels() else section(CHANNELS_VIEW)
            }
            // Right goes on to the search box: the geometric search passed it over.
            Box(if (auth.signedIn && !barHosted) Modifier.focusRequester(playlistsFocus).focusProperties { right = searchFocus } else Modifier) {
                YouTubeSectionTab(Icons.Rounded.VideoLibrary, "Playlists", on = showingPlaylists) {
                    if (showingPlaylists) YouTubeFeedRepository.loadPlaylists() else section(PLAYLISTS_VIEW)
                }
            }
            // Only once something is queued (or while it is shown): an empty queue needs no button.
            if (watchQueue.isNotEmpty() || showingQueue) {
                YouTubeSectionTab(Icons.Rounded.Queue, "Queue · ${watchQueue.size}", on = showingQueue) {
                    if (!showingQueue) section(QUEUE_VIEW)
                }
            }
            // Without the top bar (the sidebar layout) the search box stays in the tab.
            if (auth.signedIn && !barHosted) {
                Spacer(Modifier.width(12.dp))
                com.nuvio.app.core.ui.VoiceSearchCommands(
                    owner = "youtube-tab",
                    text = searchBox.text,
                    onTextChange = { searchBox.text = it },
                    onSubmit = { query ->
                        searchBox.text = query
                        submitSearch()
                    },
                )
                NuvioTextField(
                    value = searchBox.text,
                    onValueChange = { searchBox.text = it },
                    focusRequester = searchFocus,
                    modifier = Modifier
                        .width(340.dp)
                        .focusProperties { left = playlistsFocus }
                        // The field takes the arrows for its cursor, which left a keyboard or pad
                        // stuck in it: Down drops to the videos, Up has nowhere to go.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (firstVideoId == null || !firstCardFocus.requestFocusLogged()) {
                                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                                    }
                                    true
                                }
                                Key.DirectionUp -> true
                                else -> false
                            }
                        },
                    // A pill, like the top bar's search.
                    shape = CircleShape,
                    placeholder = "Search YouTube",
                    imeAction = ImeAction.Search,
                    onImeAction = ::submitSearch,
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = if (kind == YouTubeFeedKind.Search) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            // Clear of the pill's rounded end.
                            modifier = Modifier.padding(start = 8.dp).size(18.dp).clip(CircleShape).clickable(onClick = ::submitSearch),
                        )
                    },
                    trailingContent = {
                        if (searchBox.text.isNotEmpty() || kind == YouTubeFeedKind.Search) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).clip(CircleShape).clickable {
                                    searchBox.text = ""
                                    if (kind == YouTubeFeedKind.Search) goBack()
                                },
                            )
                        }
                        // Clear of the pill's rounded end, as the search icon is at the other.
                        com.nuvio.app.core.ui.VoiceSearchButton(
                            owner = "youtube-tab",
                            voiceScope = com.nuvio.app.features.voice.VoiceSearchScope.YouTube,
                            focusField = { searchFocus.requestFocusLogged() },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                            size = 28.dp,
                            iconSize = 18.dp,
                        )
                    },
                )
            }
            Box(Modifier.weight(1f))
            if (auth.signedIn) {
                // Named like the sections; lit while the filter is on.
                YouTubeSectionTab(Icons.Rounded.VisibilityOff, "Hide watched", on = hideWatched) {
                    YouTubeFeedRepository.setHideWatched(!hideWatched)
                }
                YouTubeSectionTab(Icons.Rounded.Sensors, "Hide streams", on = hideStreams) {
                    YouTubeFeedRepository.setHideStreams(!hideStreams)
                }
                YouTubeSectionTab(Icons.Rounded.SkipNext, "Autoplay", on = autoplay) {
                    YouTubeFeedRepository.setAutoplay(!autoplay)
                }
                YouTubeSectionTab(Icons.Rounded.Refresh, "Refresh", on = false) {
                    if (!feed.loading) YouTubeFeedRepository.load(kind, force = true)
                }
                if (channel.resolved) {
                    Spacer(Modifier.width(6.dp))
                    ChannelPicker(channel, onOpenSettings = onOpenSettings)
                }
            }
        }
        }
        }

        openError?.let { message ->
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false),
                )
                openErrorVideoId?.let { id ->
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    YouTubePill(
                        label = "Open on youtube.com",
                        selected = false,
                        onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/watch?v=$id") } },
                    )
                }
            }
        }
        queue?.takeUnless { it.oneOff }?.let { playing ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Playing \"${playing.name}\" · ${playing.index + 1} of ${playing.videos.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                YouTubePill(label = "Stop", selected = false, onClick = YouTubePlayQueue::stop)
            }
        }
        if (kind == YouTubeFeedKind.Channel) {
            openChannel?.let { open ->
                ChannelHeader(
                    channel = open,
                    onBack = ::goBack,
                    onSubscribe = YouTubeFeedRepository::setSubscribed,
                )
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    YouTubeChannelTab.entries.forEach { tab ->
                        YouTubePill(
                            label = tab.label,
                            selected = open.tab == tab,
                            onClick = { YouTubeFeedRepository.selectChannelTab(tab) },
                        )
                    }
                }
            }
        }
        if (kind == YouTubeFeedKind.Playlist) {
            openPlaylist?.let { playlist ->
                PlaylistHeader(
                    playlist = playlist,
                    canPlay = videos.isNotEmpty(),
                    onBack = ::goBack,
                    onPlayAll = { playFromPlaylist(0) },
                )
            }
        }
        commentsVideo?.let { video ->
            CommentsDialog(
                video = video,
                title = YouTubeDeArrow.cached(video.videoId)?.title?.takeIf { deArrow } ?: video.title,
                onDismiss = { commentsVideo = null },
            )
        }
        savingVideo?.let { video ->
            SaveToPlaylistDialog(
                video = video,
                onDismiss = { savingVideo = null },
                onToggle = { option, add -> saveTo(option.playlistId, option.title, video, add) },
            )
        }

        when {
            showingQueue && watchQueue.isEmpty() -> FeedMessage(
                text = "The queue is empty. Add videos from a video's menu (Add to queue); it lasts until Nuvio closes.",
                action = "Back to Home",
                onAction = {
                    backStack = emptyList()
                    selectedName = YouTubeFeedKind.Home.name
                },
            )
            showingChannelList && auth.signedIn -> SubscribedChannels(
                onOpen = { showChannel(it.channelId, it.name, it.avatarUrl, from = it.channelId) },
                returnTo = pendingReturn?.takeIf { it.page == CHANNELS_VIEW }?.itemKey,
                onReturned = { pendingReturn = null },
            )
            showingPlaylists && auth.signedIn -> PlaylistLibrary(
                onOpen = ::showPlaylist,
                returnTo = pendingReturn?.takeIf { it.page == PLAYLISTS_VIEW }?.itemKey,
                onReturned = { pendingReturn = null },
            )
            kind == YouTubeFeedKind.Channel && openChannel?.tab == YouTubeChannelTab.Playlists && auth.signedIn ->
                ChannelPlaylists(onOpen = ::showPlaylist)
            !auth.signedIn && !showingQueue -> FeedMessage(
                text = "Sign in to your YouTube account to see your home feed and subscriptions.",
                action = "Open YouTube account settings",
                onAction = onOpenSettings,
            )
            kind == YouTubeFeedKind.Search && searchQuery.isBlank() -> FeedMessage(
                text = "Type in the search box above and press Enter to search YouTube.",
                action = "Back to Home",
                onAction = { selectedName = YouTubeFeedKind.Home.name },
            )
            channel.needsChoice && !showingQueue -> FeedMessage(
                text = "This Google account has several YouTube channels. Pick the one to watch as from the picture at the top right.",
                action = "Reload channels",
                onAction = { YouTubeFeedRepository.loadChannels(force = true) },
            )
            videos.isEmpty() && (feed.loading || !feed.loaded || (allHidden && feed.continuation != null && feed.error == null)) -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                contentAlignment = Alignment.TopCenter,
            ) { CircularProgressIndicator() }
            videos.isEmpty() && feed.error != null -> FeedMessage(
                text = feed.error,
                action = "Try again",
                onAction = { YouTubeFeedRepository.load(kind, force = true) },
            )
            allHidden -> FeedMessage(
                text = if (hideWatched) "You've watched everything here." else "Everything here is a past live stream.",
                action = if (hideWatched && hideStreams) "Show everything" else if (hideWatched) "Show watched videos" else "Show live streams",
                onAction = {
                    YouTubeFeedRepository.setHideWatched(false)
                    YouTubeFeedRepository.setHideStreams(false)
                },
            )
            videos.isEmpty() -> FeedMessage(
                text = when (kind) {
                    YouTubeFeedKind.Subscriptions -> "No recent uploads from your subscriptions."
                    YouTubeFeedKind.Search -> "No videos found for \"$searchQuery\"."
                    YouTubeFeedKind.History ->
                        "Nothing in this account's watch history. YouTube keeps none while watch history is paused."
                    YouTubeFeedKind.Channel ->
                        if (openChannel?.tab == YouTubeChannelTab.Live) "This channel has no past live streams." else "This channel has no videos."
                    YouTubeFeedKind.Playlist -> "This playlist is empty."
                    YouTubeFeedKind.Home ->
                        "YouTube has no recommendations for this account yet. Watch history turned off gives an empty home feed."
                },
                action = "Refresh",
                onAction = { YouTubeFeedRepository.load(kind, force = true) },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().gridColumnKeys(columnFocus) { videos.size },
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(videos, key = { _, video -> video.videoId }) { index, video ->
                    val inPlaylist = openPlaylist?.takeIf { kind == YouTubeFeedKind.Playlist }
                    QueueReorderable(showingQueue, queueReorder, video.videoId) { dragModifier ->
                    GridColumnItem(columnFocus, index) {
                        YouTubeVideoCard(
                            modifier = dragModifier,
                            video = video,
                            opening = openingId == video.videoId,
                            deArrow = deArrow,
                            // Inside a playlist a video plays on through the rest of it, as on the website.
                            // And inside the queue, through the rest of the queue.
                            onClick = {
                                when {
                                    showingQueue -> playFromQueue(index)
                                    inPlaylist != null -> playFromPlaylist(index)
                                    else -> play(video)
                                }
                            },
                            queued = video.videoId in queuedIds,
                            onToggleQueued = { toggleQueued(video) },
                            onLike = { liked -> like(video, liked) },
                            inWatchLater = video.videoId in watchLater,
                            watchedOn = if (showWatchedDates) {
                                watchHistory[video.videoId]?.watchedOn?.let { formatYouTubeWatchDate(it, dateFormat) }
                            } else {
                                null
                            },
                            onToggleWatchLater = { toggleWatchLater(video) },
                            onMarkWatched = { markWatched(video) },
                            onDontRecommendChannel = video.dontRecommendToken?.let { { dontRecommendChannel(video) } },
                            onSaveToPlaylist = { savingVideo = video },
                            onComments = { commentsVideo = video },
                            // Liked videos is YouTube's own list: a like taken back leaves it, not a removal.
                            onRemoveFromPlaylist = inPlaylist?.takeIf { it.playlistId != LIKED_PLAYLIST_ID }?.let { playlist ->
                                { saveTo(playlist.playlistId, playlist.title, video, add = false) }
                            },
                            // Not from the channel's own page, which is where it would go.
                            onOpenChannel = video.channelId?.takeIf { kind != YouTubeFeedKind.Channel }?.let { id ->
                                { showChannel(id, video.channel ?: "Channel", null, from = video.videoId) }
                            },
                            focusRequester = when {
                                video.videoId == returnKey -> returnFocus
                                index == 0 -> firstCardFocus
                                else -> null
                            },
                        )
                    }
                    }
                }
                if (!showingQueue && (feed.continuation != null || feed.error != null)) {
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                feed.loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                feed.error != null -> FilledTonalButton(onClick = { YouTubeFeedRepository.loadMore(kind) }) {
                                    Text("Couldn't load more. Try again")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which of the account's channels the feeds act as; the website's "Switch account". */
@Composable
private fun ChannelPicker(state: YouTubeChannelState, onOpenSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var avatarBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box {
        val current = state.current
        val label = when {
            state.channels.isEmpty() -> "Channel: account default"
            state.needsChoice || current == null -> "Choose a channel"
            else -> "Watching as ${current.name}"
        }
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsKeyboardFocusedAsState()
        // Ringed in the accent while a channel still has to be picked, as the feed asks.
        val ring = when {
            focused -> MaterialTheme.colorScheme.onSurface
            state.needsChoice -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        }
        com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = if (open) "" else label) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .onGloballyPositioned { avatarBounds = it.boundsInWindow() }
                    .clip(CircleShape)
                    .border(2.dp, ring, CircleShape)
                    .clickable(interactionSource = interaction, indication = null) { open = true },
                contentAlignment = Alignment.Center,
            ) {
                val photo = current?.photoUrl.takeUnless { state.needsChoice }
                if (photo != null) {
                    NuvioAsyncImage(
                        model = photo,
                        contentDescription = label,
                        modifier = Modifier.size(30.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Rounded.AccountCircle, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(30.dp))
                }
            }
        }
        val anchor = avatarBounds
        if (open && anchor != null) {
            // Under the picture, lined up with its right edge (the menu flips left off the window's edge).
            Popup(
                popupPositionProvider = remember(anchor) {
                    CursorMenuPositionProvider(IntOffset(anchor.right.roundToInt(), anchor.bottom.roundToInt() + 6))
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                com.nuvio.app.core.ui.KeyRouteLayer("youtube menu") // the focus router leaves an open menu alone
                ProfileMenu(
                    state = state,
                    onPick = { identity ->
                        open = false
                        if (identity.pageId != state.pageId || state.needsChoice) {
                            YouTubeFeedRepository.selectChannel(identity.pageId)
                        }
                    },
                    onReload = {
                        open = false
                        YouTubeFeedRepository.loadChannels(force = true)
                    },
                    onOpenSettings = {
                        open = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

/** The profile picture's menu: the account's channels to watch as, then a way to the YouTube settings. In the app's menu glass. */
@Composable
private fun ProfileMenu(
    state: YouTubeChannelState,
    onPick: (YouTubeChannelIdentity) -> Unit,
    onReload: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val first = remember { FocusRequester() }
    // Opened from the keyboard (or a pad), the arrows walk the menu straight away.
    LaunchedEffect(Unit) {
        if (KeyboardInputMode.active) {
            kotlinx.coroutines.delay(16)
            first.requestFocusLogged()
        }
    }
    NuvioGlassMenuSurface(modifier = Modifier.widthIn(min = 260.dp, max = 360.dp)) {
        Column(
            modifier = Modifier
                .focusRequester(first)
                .arrowKeyFocusNavigation()
                .padding(6.dp),
        ) {
            Text(
                text = "WATCHING AS",
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textMuted,
            )
            if (state.channels.isEmpty()) {
                ProfileMenuRow(onClick = onReload) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Couldn't read this account's channels. Reload", modifier = Modifier.weight(1f))
                }
            }
            state.channels.forEach { identity ->
                val current = identity.pageId == state.pageId && !state.needsChoice
                ProfileMenuRow(onClick = { onPick(identity) }) {
                    NuvioAsyncImage(
                        model = identity.photoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(identity.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = when {
                                !identity.hasChannel -> "No channel"
                                identity.handle != null -> identity.handle
                                identity.pageId == null -> "Account profile"
                                else -> "Brand channel"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current) Icon(Icons.Rounded.Check, contentDescription = "Current", tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(com.nuvio.app.core.ui.NuvioGlass.EdgeColor),
            )
            ProfileMenuRow(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("YouTube settings", modifier = Modifier.weight(1f))
            }
        }
    }
}

/** One row of [ProfileMenu], lit like the app's other menus under the pointer or the keyboard. */
@Composable
private fun ProfileMenuRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsKeyboardFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (hovered || focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.5.dp, if (focused) tokens.colors.borderFocus else Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White.copy(alpha = 0.9f),
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
        ) {
            content()
        }
    }
}

@Composable
private fun FeedMessage(text: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        YouTubePill(label = action, selected = true, onClick = onAction)
    }
}

/**
 * A pill button for the tab's header and pages: filled when [selected], outlined otherwise, with a
 * ring while the keyboard or a pad has it focused (Material's own focus tint is too faint to follow
 * across a row of them).
 */
@Composable
private fun YouTubePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsKeyboardFocusedAsState()
    // The button's own border, so the ring is the pill's outline: a border modifier traced the
    // button's taller touch area instead, a ring standing off the pill.
    val ring = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface).takeIf { focused }
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        leadingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp)) }
        Text(label)
    }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = modifier, border = ring, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = modifier,
            border = ring ?: androidx.compose.material3.ButtonDefaults.outlinedButtonBorder(enabled),
            content = content,
        )
    }
}

/** An icon button with the same keyboard-focus ring as [YouTubePill]. */
@Composable
private fun YouTubeIconButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsKeyboardFocusedAsState()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.border(2.dp, if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
        content = content,
    )
}

/** The tabs' top padding leaves room under the bar for a header; with the search on the bar most of it is spare. */
private val BAR_HOSTED_TRIM = 36.dp

/** How many pages Back can step through inside the tab. */
private const val MAX_BACK_STEPS = 12

/** [YouTubeTabScreen]'s selection for the subscribed-channels list, beside the feed kinds' names. */
private const val CHANNELS_VIEW = "Channels"

/**
 * Where a page on the tab's back stack was left: its scroll position and the card or tile ([itemKey]: a
 * video, channel or playlist id) the next page was opened from. The channel list and the playlist library
 * scroll themselves ([ownGrid]); the feeds share the tab's grid.
 */
private data class YouTubeReturnPoint(val page: String, val itemKey: String?, val index: Int, val offset: Int) {
    val ownGrid: Boolean get() = page == CHANNELS_VIEW || page == PLAYLISTS_VIEW
}

/**
 * For a grid Back has returned to: scrolls to the tile at [index] ([key]'s, -1 when it is gone) and
 * focuses it through the returned requester, once, then calls [onDone].
 */
@Composable
private fun rememberReturnFocus(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    key: String?,
    index: Int,
    onDone: () -> Unit,
): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        if (index >= 0) {
            gridState.scrollToItem(index)
            kotlinx.coroutines.delay(50L) // the tile's first frame
            requester.requestFocusLogged()
        }
        onDone()
    }
    return requester
}

/** [YouTubeTabScreen]'s selection for the playlist library. */
private const val PLAYLISTS_VIEW = "Playlists"

/** [YouTubeTabScreen]'s selection for the queue ([YouTubeWatchQueue]). */
private const val QUEUE_VIEW = "Queue"

/** The open playlist's name, a way back, and Play all. */
@Composable
private fun PlaylistHeader(playlist: YouTubePlaylist, canPlay: Boolean, onBack: () -> Unit, onPlayAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        YouTubeIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            playlist.details?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        YouTubePill(label = "Play all", selected = true, onClick = onPlayAll, enabled = canPlay, leadingIcon = Icons.Rounded.PlayArrow)
    }
}

/** The account's playlists, Watch Later and Liked videos first; picking one opens it. */
@Composable
private fun PlaylistLibrary(onOpen: (YouTubePlaylist) -> Unit, returnTo: String?, onReturned: () -> Unit) {
    val state by YouTubeFeedRepository.playlists.collectAsStateWithLifecycle()
    LaunchedEffect(state.loaded) { YouTubeFeedRepository.loadPlaylists() }
    when {
        state.playlists.isEmpty() && (state.loading || !state.loaded) -> Box(
            modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
            contentAlignment = Alignment.TopCenter,
        ) { CircularProgressIndicator() }
        state.playlists.isEmpty() -> FeedMessage(
            text = state.error ?: "This account has no playlists.",
            action = "Refresh",
            onAction = { YouTubeFeedRepository.loadPlaylists(force = true) },
        )
        else -> {
            val gridState = rememberLazyGridState()
            val columnFocus = rememberGridColumnFocus(gridState)
            val returnFocus = rememberReturnFocus(gridState, returnTo, state.playlists.indexOfFirst { it.playlistId == returnTo }, onReturned)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().gridColumnKeys(columnFocus) { state.playlists.size },
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(state.playlists, key = { _, it -> it.playlistId }) { index, playlist ->
                    GridColumnItem(columnFocus, index) {
                        PlaylistTile(
                            playlist = playlist,
                            onClick = { onOpen(playlist) },
                            modifier = if (playlist.playlistId == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** The open channel's Playlists tab: its playlists, a page at a time as the grid is scrolled; picking one opens it. */
@Composable
private fun ChannelPlaylists(onOpen: (YouTubePlaylist) -> Unit) {
    val state by YouTubeFeedRepository.channelPlaylists.collectAsStateWithLifecycle()
    LaunchedEffect(state.loaded) { if (!state.loaded) YouTubeFeedRepository.loadChannelPlaylists() }
    when {
        state.playlists.isEmpty() && (state.loading || !state.loaded) -> Box(
            modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
            contentAlignment = Alignment.TopCenter,
        ) { CircularProgressIndicator() }
        state.playlists.isEmpty() -> FeedMessage(
            text = state.error ?: "This channel has no playlists.",
            action = "Refresh",
            onAction = { YouTubeFeedRepository.loadChannelPlaylists(force = true) },
        )
        else -> {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
                snapshotFlow {
                    val total = gridState.layoutInfo.totalItemsCount
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    (last >= total - 8) to total
                }.distinctUntilChanged().collect { (nearEnd, _) ->
                    if (nearEnd) YouTubeFeedRepository.loadChannelPlaylists()
                }
            }
            val columnFocus = rememberGridColumnFocus(gridState)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().gridColumnKeys(columnFocus) { state.playlists.size },
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(state.playlists, key = { _, it -> it.playlistId }) { index, playlist ->
                    GridColumnItem(columnFocus, index) {
                        PlaylistTile(playlist = playlist, onClick = { onOpen(playlist) })
                    }
                }
                if (state.loading && state.continuation != null) {
                    item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: YouTubePlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, if (focused || hovered) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (playlist.thumbnailUrl != null) {
                NuvioAsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = playlist.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = if (playlist.playlistId == LIKED_PLAYLIST_ID) Icons.Rounded.ThumbUp else Icons.Rounded.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        playlist.details?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [video]'s top comments, read-only, a page at a time as the list is scrolled. */
@Composable
private fun CommentsDialog(video: YouTubeVideo, title: String, onDismiss: () -> Unit) {
    var comments by remember(video.videoId) { mutableStateOf<List<YouTubeComment>>(emptyList()) }
    var continuation by remember(video.videoId) { mutableStateOf<String?>(null) }
    var loading by remember(video.videoId) { mutableStateOf(true) }
    var failed by remember(video.videoId) { mutableStateOf(false) }
    // How many pages have been asked for; the first has no token, so it is told apart by this.
    var requested by remember(video.videoId) { mutableStateOf(1) }
    LaunchedEffect(video.videoId, requested) {
        loading = true
        val page = YouTubeFeedRepository.comments(video.videoId, continuation.takeIf { requested > 1 })
        if (page == null) {
            failed = true
        } else {
            failed = false
            val seen = comments.mapTo(HashSet()) { it.id }
            comments = comments + page.comments.filter { it.id !in seen }
            continuation = page.continuation
        }
        loading = false
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 4
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && !loading && !failed && continuation != null) requested++
        }
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Comments",
        subtitle = title,
        maxWidth = 720.dp,
        actions = { FilledTonalButton(onClick = onDismiss) { Text("Close") } },
    ) {
        when {
            comments.isEmpty() && loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            comments.isEmpty() && failed -> Text(
                "YouTube didn't send the comments. Try again shortly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            comments.isEmpty() -> Text(
                "No comments, or comments are turned off for this video.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                lazyItems(comments, key = { it.id }) { comment -> CommentRow(comment) }
                if (loading || (failed && continuation != null)) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                FilledTonalButton(onClick = { failed = false; requested++ }) { Text("Couldn't load more. Try again") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: YouTubeComment) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioAsyncImage(
            model = comment.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = listOfNotNull(
                    "Pinned".takeIf { comment.pinned },
                    comment.author.takeIf { it.isNotBlank() },
                    comment.published,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(comment.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            // Likes only: the replies are not loaded, so their count would point at nothing to read.
            val counts = listOfNotNull(comment.likes?.let { "$it likes" })
            if (counts.isNotEmpty()) {
                Text(
                    text = counts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Save to playlist": the account's playlists with a tick on those already holding [video]; a click adds or removes. */
@Composable
private fun SaveToPlaylistDialog(
    video: YouTubeVideo,
    onDismiss: () -> Unit,
    onToggle: (YouTubePlaylistOption, Boolean) -> Unit,
) {
    var options by remember(video.videoId) { mutableStateOf<List<YouTubePlaylistOption>?>(null) }
    var failed by remember(video.videoId) { mutableStateOf(false) }
    LaunchedEffect(video.videoId) {
        val found = YouTubeFeedRepository.playlistOptions(video.videoId)
        if (found == null) failed = true else options = found
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Save to playlist",
        subtitle = video.title,
        maxWidth = 480.dp,
        actions = { FilledTonalButton(onClick = onDismiss) { Text("Done") } },
    ) {
        val current = options
        when {
            failed -> Text(
                "YouTube didn't list your playlists. Try again shortly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            current == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.isEmpty() -> Text(
                "This account has no playlists to save to.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> current.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val add = !option.containsVideo
                            onToggle(option, add)
                            options = current.map { if (it.playlistId == option.playlistId) it.copy(containsVideo = add) else it }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (option.containsVideo) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (option.containsVideo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(option.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The open channel's name and avatar, a way back, and its Subscribe button. */
@Composable
private fun ChannelHeader(channel: YouTubeOpenChannel, onBack: () -> Unit, onSubscribe: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        YouTubeIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
        }
        if (channel.avatarUrl != null) {
            NuvioAsyncImage(
                model = channel.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "1.2M subscribers · 345 videos", once the page's header has said.
            val counts = listOfNotNull(channel.stats?.subscribers, channel.stats?.videos).joinToString(" · ")
            if (counts.isNotEmpty()) {
                Text(
                    text = counts,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (channel.subscribed) {
            // Not known until the page answers; no button rather than a guess.
            null -> Unit
            true -> YouTubePill(label = "Subscribed", selected = false, onClick = { onSubscribe(false) }, enabled = !channel.changing)
            false -> YouTubePill(label = "Subscribe", selected = true, onClick = { onSubscribe(true) }, enabled = !channel.changing)
        }
    }
}

/** The channels the account subscribes to, as a grid of avatars; picking one opens its page. */
@Composable
private fun SubscribedChannels(onOpen: (YouTubeChannel) -> Unit, returnTo: String?, onReturned: () -> Unit) {
    val state by YouTubeFeedRepository.subscribedChannels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { YouTubeFeedRepository.loadSubscribedChannels() }
    when {
        state.channels.isEmpty() && (state.loading || !state.loaded) -> Box(
            modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
            contentAlignment = Alignment.TopCenter,
        ) { CircularProgressIndicator() }
        state.channels.isEmpty() -> FeedMessage(
            text = state.error ?: "This account doesn't subscribe to any channels.",
            action = "Refresh",
            onAction = { YouTubeFeedRepository.loadSubscribedChannels(force = true) },
        )
        else -> {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
                // Keyed on the count as well, as the feeds' grid is: a short page asks for the next.
                snapshotFlow {
                    val total = gridState.layoutInfo.totalItemsCount
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    (last >= total - 8) to total
                }.distinctUntilChanged().collect { (nearEnd, _) ->
                    if (nearEnd) YouTubeFeedRepository.loadMoreSubscribedChannels()
                }
            }
            val columnFocus = rememberGridColumnFocus(gridState)
            val returnFocus = rememberReturnFocus(gridState, returnTo, state.channels.indexOfFirst { it.channelId == returnTo }, onReturned)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().gridColumnKeys(columnFocus) { state.channels.size },
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(state.channels, key = { _, it -> it.channelId }) { index, channel ->
                    GridColumnItem(columnFocus, index) {
                        ChannelTile(
                            channel = channel,
                            onClick = { onOpen(channel) },
                            modifier = if (channel.channelId == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(channel: YouTubeChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioAsyncImage(
            model = channel.avatarUrl,
            contentDescription = channel.name,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, if (focused || hovered) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        channel.details?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YouTubeVideoCard(
    video: YouTubeVideo,
    opening: Boolean,
    deArrow: Boolean,
    onClick: () -> Unit,
    /** Likes the video (true) or takes the like back (false). */
    onLike: (Boolean) -> Unit,
    /** Whether it is in the account's Watch later, as far as [YouTubeFeedRepository.watchLater] knows. */
    inWatchLater: Boolean,
    /** The day it was last watched, written out, when the cards show it (Settings > Watched date). */
    watchedOn: String?,
    onToggleWatchLater: () -> Unit,
    onMarkWatched: () -> Unit,
    /** Null where YouTube's own menu has no "Don't recommend channel" (anything but recommendations). */
    onDontRecommendChannel: (() -> Unit)?,
    onSaveToPlaylist: () -> Unit,
    onComments: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onOpenChannel: (() -> Unit)?,
    /** Whether it is in [YouTubeWatchQueue]; the menu offers to add it or take it out. */
    queued: Boolean = false,
    onToggleQueued: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    // The card's menu, open at this window position (the ⋮ button, the cursor, or beside the card).
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var cardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var moreBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    fun beside(): IntOffset? = cardBounds?.let { IntOffset(it.left.roundToInt() + 24, it.top.roundToInt() + 24) }
    val selectHold = rememberHoldToSelectState()
    // DeArrow's title and thumbnail once looked up (cards ask as they come into view); YouTube's meanwhile.
    val branding by produceState(YouTubeDeArrow.cached(video.videoId), video.videoId, deArrow) {
        if (deArrow) value = YouTubeDeArrow.branding(video.videoId)
    }
    val title = branding?.title?.takeIf { deArrow } ?: video.title
    // A frame DeArrow's thumbnail service could not cut falls back to YouTube's thumbnail.
    var brandedThumbnailFailed by remember(video.videoId) { mutableStateOf(false) }
    val thumbnail = branding?.thumbnailUrl?.takeIf { deArrow && !brandedThumbnailFailed } ?: video.thumbnailUrl
    val interaction = remember { MutableInteractionSource() }
    // A focus ring only while the keyboard or a pad is in use: a click focuses what it lands on too.
    val focused by interaction.collectIsKeyboardFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = focused || hovered
    val shape = RoundedCornerShape(12.dp)
    // Whether this account likes the video: asked when the menu opens, so its first row can offer to
    // take a like back. Until YouTube answers it reads "Like", and turns into "Remove like" in place.
    val likeStatus by YouTubeFeedRepository.likeStatus.collectAsStateWithLifecycle()
    val liked = likeStatus[video.videoId]
    // The thumbnail's buttons opened out ask the same, for the like button.
    var actionsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuAt != null || actionsOpen, video.videoId) {
        if ((menuAt != null || actionsOpen) && liked == null) YouTubeFeedRepository.loadLikeStatus(video.videoId)
    }
    val watched = isYouTubeVideoWatched(video)
    // A card under the cursor (or the keyboard's focus) for a moment is likely the next one opened:
    // its streams are looked up now, so the click starts it at once (YouTubeStreamsAhead).
    LaunchedEffect(highlight, video.videoId) {
        if (highlight) {
            kotlinx.coroutines.delay(STREAMS_AHEAD_DWELL_MS)
            YouTubeStreamsAhead.warm(video.videoId)
        }
    }
    menuAt?.let { position ->
        NuvioContextMenu(
            positionProvider = remember(position) { com.nuvio.app.core.ui.CursorMenuPositionProvider(position) },
            // Live: the like row's label settles once YouTube has said; the rows themselves never change.
            liveActions = true,
            actions = buildList {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Rounded.ThumbUp,
                        label = if (liked == true) "Remove like" else "Like",
                        onSelected = { onLike(liked != true) },
                    ),
                )
                add(
                    PosterZoomOverlayAction(
                        icon = if (queued) Icons.Rounded.PlaylistAddCheck else Icons.Rounded.QueuePlayNext,
                        label = if (queued) "Remove from queue" else "Add to queue",
                        onSelected = onToggleQueued,
                    ),
                )
                add(
                    PosterZoomOverlayAction(
                        icon = if (inWatchLater) Icons.Rounded.Done else Icons.Rounded.WatchLater,
                        label = if (inWatchLater) "Remove from Watch later" else "Save to Watch later",
                        onSelected = onToggleWatchLater,
                    ),
                )
                if (!watched) add(PosterZoomOverlayAction(icon = Icons.Rounded.TaskAlt, label = "Mark as watched", onSelected = onMarkWatched))
                add(PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Save to playlist…", onSelected = onSaveToPlaylist))
                add(PosterZoomOverlayAction(icon = Icons.AutoMirrored.Rounded.Comment, label = "Comments", onSelected = onComments, group = 1))
                onOpenChannel?.let {
                    add(PosterZoomOverlayAction(icon = Icons.Rounded.AccountCircle, label = "Go to channel", onSelected = it, group = 1))
                }
                onDontRecommendChannel?.let {
                    add(PosterZoomOverlayAction(icon = Icons.Rounded.Block, label = "Don't recommend channel", onSelected = it, group = 1))
                }
                onRemoveFromPlaylist?.let {
                    add(
                        PosterZoomOverlayAction(
                            icon = Icons.Rounded.Delete,
                            label = "Remove from this playlist",
                            isDestructive = true,
                            onSelected = it,
                            group = 2,
                        ),
                    )
                }
            },
            onDismiss = { menuAt = null },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardBounds = it.boundsInWindow() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Select plays; holding it (Enter, or a pad's A) opens the menu beside the card, as
            // right-click does under the cursor.
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> selectHold.handle(
                        event,
                        onSelect = onClick,
                        onHold = {
                            beside()?.let { ContextMenuInvocation.recordKeyboardInvocation(it) }
                            menuAt = ContextMenuInvocation.consume() ?: beside()
                        },
                    )
                    Key.Menu -> {
                        if (event.type == KeyEventType.KeyDown) {
                            beside()?.let { ContextMenuInvocation.recordKeyboardInvocation(it) }
                            menuAt = ContextMenuInvocation.consume() ?: beside()
                        }
                        true
                    }
                    else -> false
                }
            }
            .secondaryClick { menuAt = ContextMenuInvocation.consume() ?: beside() }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 2.dp,
                    color = if (highlight) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = shape,
                ),
        ) {
            NuvioAsyncImage(
                model = thumbnail,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { if (thumbnail != video.thumbnailUrl) brandedThumbnailFailed = true },
            )
            video.duration?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            video.watchedPercent?.let { percent ->
                // YouTube's red "watched" bar along the thumbnail's bottom edge.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percent / 100f)
                            .fillMaxHeight()
                            .background(Color(0xFFFF0033)),
                    )
                }
            }
            watchedOn?.let { day ->
                WatchedDateBadge(day, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            YouTubeCardActions(
                // Always there while it is in Watch later (the green says so); otherwise on the card's hover or focus.
                shown = highlight || inWatchLater,
                inWatchLater = inWatchLater,
                watched = watched,
                liked = liked,
                queued = queued,
                onToggleWatchLater = onToggleWatchLater,
                onMarkWatched = onMarkWatched,
                onToggleQueued = onToggleQueued,
                onLike = onLike,
                onSaveToPlaylist = onSaveToPlaylist,
                onOpenChanged = { actionsOpen = it },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            if (opening) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Color.White) }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // For the mouse; the keyboard and a pad open the same menu by holding select, so the
            // button is left out of focus and arrowing across the grid lands on cards only.
            IconButton(
                onClick = {
                    ContextMenuInvocation.consume()
                    menuAt = moreBounds?.let { IntOffset(it.left.roundToInt(), it.bottom.roundToInt()) } ?: beside()
                },
                modifier = Modifier
                    .size(28.dp)
                    .onGloballyPositioned { moreBounds = it.boundsInWindow() }
                    .focusProperties { canFocus = false },
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // The channel's name opens it, as on the website (the mouse's way; the menu has it for the keyboard).
        video.channel?.let { name ->
            val channelInteraction = remember { MutableInteractionSource() }
            val channelHovered by channelInteraction.collectIsHoveredAsState()
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = if (channelHovered && onOpenChannel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onOpenChannel != null) {
                    Modifier
                        .hoverable(channelInteraction)
                        .focusProperties { canFocus = false }
                        .clickable(interactionSource = channelInteraction, indication = null, onClick = onOpenChannel)
                } else {
                    Modifier
                },
            )
        }
        // Likes go between the views and the age; the feeds leave them out, so each card asks (YouTubeLikeCounts).
        val likes by produceState(YouTubeLikeCounts.cached(video.videoId), video.videoId) {
            if (value == null) value = YouTubeLikeCounts.likes(video.videoId)
        }
        youTubeDetailsWithLikes(video.details, likes)?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How long a card is under the cursor or focused before its streams are looked up ahead of a click. */
private const val STREAMS_AHEAD_DWELL_MS = 350L

/** The green a thumbnail button takes while what it stands for is on (in Watch later, queued, liked). */
private val CardActionOn = Color(0xFF2FA866)

/**
 * The thumbnail's top-right buttons, for the mouse: Watch later, green while the video is in it, and
 * to its left, while the pointer is over them, mark watched, queue, like and save to a playlist. The
 * keyboard and a pad have the same in the card's menu, so none of them takes focus.
 */
@Composable
private fun YouTubeCardActions(
    shown: Boolean,
    inWatchLater: Boolean,
    watched: Boolean,
    liked: Boolean?,
    queued: Boolean,
    onToggleWatchLater: () -> Unit,
    onMarkWatched: () -> Unit,
    onToggleQueued: () -> Unit,
    onLike: (Boolean) -> Unit,
    onSaveToPlaylist: () -> Unit,
    onOpenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onOpenChanged(hovered) }
    if (!shown && !hovered) return
    // A row opening out to the left of Watch later: a thumbnail is wider than it is tall.
    Row(
        modifier = modifier.hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = hovered,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(expandFrom = Alignment.End),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.End),
        ) {
            // Nearest Watch later first, so the order reads outward from it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CardGlassButton(Icons.Rounded.PlaylistAdd, "Save to playlist…", on = false, onClick = onSaveToPlaylist)
                CardGlassButton(
                    icon = Icons.Rounded.ThumbUp,
                    label = if (liked == true) "Remove like" else "Like",
                    on = liked == true,
                    onClick = { onLike(liked != true) },
                )
                CardGlassButton(
                    icon = if (queued) Icons.Rounded.PlaylistAddCheck else Icons.Rounded.QueuePlayNext,
                    label = if (queued) "Remove from queue" else "Add to queue",
                    on = queued,
                    onClick = onToggleQueued,
                )
                if (!watched) CardGlassButton(Icons.Rounded.TaskAlt, "Mark as watched", on = false, onClick = onMarkWatched)
            }
        }
        CardGlassButton(
            icon = if (inWatchLater) Icons.Rounded.Done else Icons.Rounded.WatchLater,
            label = if (inWatchLater) "Remove from Watch later" else "Save to Watch later",
            on = inWatchLater,
            size = 34.dp,
            onClick = onToggleWatchLater,
        )
    }
}

/** The day a video was watched, top left of its thumbnail, in the same dark glass as the buttons. */
@Composable
private fun WatchedDateBadge(day: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = day,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.64f))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.18f),
                    1f to Color.White.copy(alpha = 0.03f),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.30f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A round frosted button over a thumbnail: dark glass with a light edge, [CardActionOn] green while [on]. */
@Composable
private fun CardGlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 30.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = androidx.compose.foundation.shape.CircleShape
    com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = label) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    when {
                        // Solid enough to read on a bright or busy thumbnail.
                        on -> CardActionOn.copy(alpha = if (hovered) 1f else 0.92f)
                        hovered -> Color.Black.copy(alpha = 0.80f)
                        else -> Color.Black.copy(alpha = 0.64f)
                    },
                )
                // The glass's sheen: lighter at the top, as if lit from above.
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.18f),
                        1f to Color.White.copy(alpha = 0.03f),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = if (hovered) 0.45f else 0.30f), shape)
                .hoverable(interaction)
                .focusProperties { canFocus = false }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * A section (or filter, or Refresh) in the tab's header: its icon and name, white at rest and lit in the accent glass while
 * it is the one shown, like the settings bar's pages; the keyboard ring follows the pill.
 */
@Composable
private fun YouTubeSectionTab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    if (LocalCompactSectionTabs.current) {
        com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = label) { YouTubeSectionTabPill(icon, label, on, showLabel = false, onClick) }
    } else {
        YouTubeSectionTabPill(icon, label, on, showLabel = true, onClick)
    }
}

/** Whether the header's pills show their icons alone: the window is too narrow for their names. */
private val LocalCompactSectionTabs = androidx.compose.runtime.compositionLocalOf { false }

/** A pill's width beside its name's: 12dp padding each side, the 20dp icon and the 8dp gap. */
private val SECTION_TAB_CHROME = 52.dp

@Composable
private fun YouTubeSectionTabPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(percent = 50)
    val tint = when {
        on -> tokens.colors.accent
        hovered -> Color.White
        else -> Color.White.copy(alpha = 0.7f)
    }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(
                when {
                    on -> tokens.colors.accent.copy(alpha = 0.18f)
                    hovered -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .musicKeyFocusRing(shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (showLabel) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = if (showLabel) null else label, tint = tint, modifier = Modifier.size(20.dp))
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * A queue card that can be dragged to a new place while [enabled] (the queue is shown); elsewhere the
 * card as it is. [content] gets the drag handle's modifier, for the whole card.
 */
@Composable
private fun androidx.compose.foundation.lazy.grid.LazyGridItemScope.QueueReorderable(
    enabled: Boolean,
    state: sh.calvin.reorderable.ReorderableLazyGridState,
    key: String,
    content: @Composable (Modifier) -> Unit,
) {
    if (!enabled) {
        content(Modifier)
        return
    }
    ReorderableItem(state, key = key) { isDragging ->
        val lift by androidx.compose.animation.core.animateFloatAsState(if (isDragging) 1.03f else 1f)
        content(
            Modifier
                .draggableHandle()
                .graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                },
        )
    }
}

/** A section's icon in the tab's header. */
private val YouTubeFeedKind.headerIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        YouTubeFeedKind.Home -> Icons.Rounded.Home
        YouTubeFeedKind.Subscriptions -> Icons.Rounded.Subscriptions
        YouTubeFeedKind.History -> Icons.Rounded.History
        YouTubeFeedKind.Search -> Icons.Rounded.Search
        YouTubeFeedKind.Channel -> Icons.Rounded.AccountCircle
        YouTubeFeedKind.Playlist -> Icons.Rounded.PlaylistPlay
    }

/** fork:youtube-mini-player - where the tab was: its page, the pages behind it, and the grid's scroll. */
internal data class YouTubeTabPlace(val selectedName: String, val backStack: List<String>, val index: Int, val offset: Int)

/**
 * fork:youtube-mini-player - the mini player's copy of the tab leaves its place here as it goes; the
 * tab itself takes it the next time it comes on screen (the player closed), once.
 */
/**
 * Where the tab was last left this session (not saved across restarts): it opens there again, after a
 * video as much as after another tab, rather than always on Home. A sign-in change forgets it.
 */
internal object YouTubeTabLastPlace {
    @kotlin.concurrent.Volatile
    var place: YouTubeTabPlace? = null
}

internal object YouTubeTabCarryOver {
    private var place: YouTubeTabPlace? = null
    private var putAtMs = 0L

    fun put(value: YouTubeTabPlace) {
        place = value
        putAtMs = com.nuvio.app.features.streams.epochMs()
    }

    /** Only straight after the mini player went: the tab opened later (from elsewhere) starts as it was. */
    fun take(): YouTubeTabPlace? {
        val value = place?.takeIf { com.nuvio.app.features.streams.epochMs() - putAtMs < CARRY_OVER_MS }
        place = null
        return value
    }

    private const val CARRY_OVER_MS = 10_000L
}
