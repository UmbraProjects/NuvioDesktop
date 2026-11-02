package com.nuvio.app.features.youtube

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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ThumbUp
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    val auth by remember {
        YouTubeMusicAuth.ensureLoaded()
        YouTubeMusicAuth.state
    }.collectAsStateWithLifecycle()
    // A feed's name, or CHANNELS_VIEW / PLAYLISTS_VIEW for the lists of subscribed channels and of
    // playlists (not video feeds).
    var selectedName by rememberSaveable { mutableStateOf(YouTubeFeedKind.Home.name) }
    val showingChannelList = selectedName == CHANNELS_VIEW
    val showingPlaylists = selectedName == PLAYLISTS_VIEW
    // Under a list, the effects keep the Subscriptions feed loaded meanwhile.
    val kind = if (showingChannelList || showingPlaylists) YouTubeFeedKind.Subscriptions else YouTubeFeedKind.valueOf(selectedName)
    val openChannel by YouTubeFeedRepository.openChannel.collectAsStateWithLifecycle()
    val openPlaylist by YouTubeFeedRepository.openPlaylist.collectAsStateWithLifecycle()
    // The pages a channel or playlist page was opened from, newest last: Back (the arrow, Esc, a
    // pad's B) steps through them inside the tab instead of leaving it.
    var backStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun push(next: String) {
        if (selectedName != next) backStack = (backStack + selectedName).takeLast(MAX_BACK_STEPS)
        selectedName = next
    }
    fun goBack() {
        selectedName = backStack.lastOrNull() ?: YouTubeFeedKind.Home.name
        backStack = backStack.dropLast(1)
    }
    fun showChannel(channelId: String, name: String, avatarUrl: String?) {
        YouTubeFeedRepository.openChannel(channelId, name, avatarUrl)
        push(YouTubeFeedKind.Channel.name)
    }
    fun showPlaylist(playlist: YouTubePlaylist) {
        YouTubeFeedRepository.openPlaylist(playlist)
        push(YouTubeFeedKind.Playlist.name)
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
    // Not on History, which is all watched by definition.
    val videos = if (hideWatched && kind != YouTubeFeedKind.History) feed.videos.filterNot(::isYouTubeVideoWatched) else feed.videos
    // A page that was all watched leaves nothing on screen to scroll to the end of, so ask for the next one here.
    val allHidden = videos.isEmpty() && feed.videos.isNotEmpty()
    LaunchedEffect(allHidden, feed.continuation, feed.loading) {
        if (allHidden && feed.continuation != null && !feed.loading && feed.error == null) YouTubeFeedRepository.loadMore(kind)
    }
    var searchText by rememberSaveable { mutableStateOf(YouTubeFeedRepository.searchQuery.value) }
    var focusResultsPending by remember { mutableStateOf(false) }
    fun submitSearch() {
        if (searchText.isBlank()) return
        focusResultsPending = true
        YouTubeFeedRepository.search(searchText)
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
        if (auth.signedIn && channel.resolved && !channel.needsChoice) YouTubeFeedRepository.load(kind)
    }

    val scope = rememberCoroutineScope()
    var openingId by remember { mutableStateOf<String?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
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
    fun play(video: YouTubeVideo) {
        // Anything opened outside the playlist being played through ends it.
        YouTubePlayQueue.onOpened(video.videoId)
        openJob?.cancel()
        openError = null
        openingId = video.videoId
        openJob = scope.launch {
            val launch = youTubePlayerLaunch(video)
            if (openingId != video.videoId) return@launch
            openingId = null
            if (launch == null) {
                openError = "\"${youTubeDisplayTitle(video)}\" couldn't be opened. YouTube may be limiting requests; try again shortly."
                return@launch
            }
            onPlay(launch)
        }
    }

    // The playlist being played through: its previous video ended, so open the next.
    LaunchedEffect(queueNext) {
        if (queueNext != null) YouTubePlayQueue.takeNext()?.let(::play)
    }
    fun playFromPlaylist(index: Int) {
        val playlist = openPlaylist ?: return
        val video = videos.getOrNull(index) ?: return
        YouTubePlayQueue.start(playlist.title, videos, index)
        play(video)
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(kind, searchQuery) { gridState.scrollToItem(0) }
    // Shown again after a while (switched to, the tab reopened, the player closed): a feed that
    // changes by itself is fetched again, the old page staying up until the new one replaces it.
    // Not while scrolled down it, where the new page would pull the list out from under the reader.
    LaunchedEffect(kind, auth.signedIn, channel.resolved) {
        if (auth.signedIn && channel.resolved && YouTubeFeedRepository.isStale(kind) && gridState.firstVisibleItemIndex < 6) {
            YouTubeFeedRepository.load(kind, force = true)
        }
    }
    LaunchedEffect(gridState, kind) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= gridState.layoutInfo.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) YouTubeFeedRepository.loadMore(kind)
        }
    }

    // Keyboard and pad: a page of videos arriving with nothing in the tab focused hands focus to its
    // first card, so the arrows have somewhere to start (a mouse user never sees it: the ring is
    // keyboard-only).
    val firstCardFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val playlistsFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var tabHasFocus by remember { mutableStateOf(false) }
    val firstVideoId = videos.firstOrNull()?.videoId
    LaunchedEffect(KeyboardInputMode.active, selectedName, firstVideoId) {
        if (KeyboardInputMode.active && !tabHasFocus && firstVideoId != null) {
            kotlinx.coroutines.delay(50L) // the grid's first frame
            runCatching { firstCardFocus.requestFocus() }
        }
    }
    // Enter in the search box: once the results are in, focus moves to the first of them.
    LaunchedEffect(focusResultsPending, firstVideoId, feed.loading) {
        if (focusResultsPending && kind == YouTubeFeedKind.Search && firstVideoId != null && !feed.loading) {
            kotlinx.coroutines.delay(50L)
            runCatching { firstCardFocus.requestFocus() }
            focusResultsPending = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { tabHasFocus = it.hasFocus }
            .arrowKeyFocusNavigation()
            .padding(start = 32.dp, end = 32.dp, top = topChromePadding ?: 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "YouTube",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 12.dp),
            )
            // A header button starts a section afresh: nothing to go back to inside it.
            fun section(name: String) {
                backStack = emptyList()
                selectedName = name
            }
            YouTubeFeedKind.entries.filter { it.inHeader }.forEach { entry ->
                val current = entry == kind && !showingChannelList && !showingPlaylists
                YouTubePill(
                    label = entry.label,
                    selected = current,
                    onClick = { if (current) YouTubeFeedRepository.load(entry) else section(entry.name) },
                )
            }
            YouTubePill(
                label = "Channels",
                selected = showingChannelList,
                onClick = { if (showingChannelList) YouTubeFeedRepository.loadSubscribedChannels() else section(CHANNELS_VIEW) },
            )
            YouTubePill(
                label = "Playlists",
                selected = showingPlaylists,
                onClick = { if (showingPlaylists) YouTubeFeedRepository.loadPlaylists() else section(PLAYLISTS_VIEW) },
                // Right goes on to the search box: the geometric search passed it over.
                modifier = if (auth.signedIn) {
                    Modifier.focusRequester(playlistsFocus).focusProperties { right = searchFocus }
                } else {
                    Modifier
                },
            )
            if (auth.signedIn) {
                NuvioTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
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
                                    if (firstVideoId == null || runCatching { firstCardFocus.requestFocus() }.isFailure) {
                                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                                    }
                                    true
                                }
                                Key.DirectionUp -> true
                                else -> false
                            }
                        },
                    // A pill, like the buttons it sits among.
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
                    trailingContent = if (searchText.isNotEmpty() || kind == YouTubeFeedKind.Search) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp).size(18.dp).clip(CircleShape).clickable {
                                    searchText = ""
                                    if (kind == YouTubeFeedKind.Search) goBack()
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            Box(Modifier.weight(1f))
            if (auth.signedIn && channel.resolved) {
                ChannelPicker(channel)
            }
            if (auth.signedIn) {
                YouTubePill(
                    label = "Hide watched",
                    selected = hideWatched,
                    onClick = { YouTubeFeedRepository.setHideWatched(!hideWatched) },
                    leadingIcon = Icons.Rounded.Check.takeIf { hideWatched },
                )
                YouTubeIconButton(
                    onClick = { YouTubeFeedRepository.load(kind, force = true) },
                    enabled = !feed.loading,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        openError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
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
            showingChannelList && auth.signedIn -> SubscribedChannels(
                onOpen = { showChannel(it.channelId, it.name, it.avatarUrl) },
            )
            showingPlaylists && auth.signedIn -> PlaylistLibrary(onOpen = ::showPlaylist)
            !auth.signedIn -> FeedMessage(
                text = "Sign in to your YouTube account to see your home feed and subscriptions.",
                action = "Open YouTube account settings",
                onAction = onOpenSettings,
            )
            kind == YouTubeFeedKind.Search && searchQuery.isBlank() -> FeedMessage(
                text = "Type in the search box above and press Enter to search YouTube.",
                action = "Back to Home",
                onAction = { selectedName = YouTubeFeedKind.Home.name },
            )
            channel.needsChoice -> FeedMessage(
                text = "This Google account has several YouTube channels. Pick the one to watch as from \"Watching as\" above.",
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
                text = "You've watched everything here.",
                action = "Show watched videos",
                onAction = { YouTubeFeedRepository.setHideWatched(false) },
            )
            videos.isEmpty() -> FeedMessage(
                text = when (kind) {
                    YouTubeFeedKind.Subscriptions -> "No recent uploads from your subscriptions."
                    YouTubeFeedKind.Search -> "No videos found for \"$searchQuery\"."
                    YouTubeFeedKind.History ->
                        "Nothing in this account's watch history. YouTube keeps none while watch history is paused."
                    YouTubeFeedKind.Channel -> "This channel has no videos."
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(videos, key = { _, video -> video.videoId }) { index, video ->
                    val inPlaylist = openPlaylist?.takeIf { kind == YouTubeFeedKind.Playlist }
                    YouTubeVideoCard(
                        video = video,
                        opening = openingId == video.videoId,
                        deArrow = deArrow,
                        // Inside a playlist a video plays on through the rest of it, as on the website.
                        onClick = { if (inPlaylist != null) playFromPlaylist(index) else play(video) },
                        onLike = { liked -> like(video, liked) },
                        onSaveToWatchLater = { saveTo(WATCH_LATER_PLAYLIST_ID, "Watch later", video, add = true) },
                        onSaveToPlaylist = { savingVideo = video },
                        onComments = { commentsVideo = video },
                        // Liked videos is YouTube's own list: a like taken back leaves it, not a removal.
                        onRemoveFromPlaylist = inPlaylist?.takeIf { it.playlistId != LIKED_PLAYLIST_ID }?.let { playlist ->
                            { saveTo(playlist.playlistId, playlist.title, video, add = false) }
                        },
                        // Not from the channel's own page, which is where it would go.
                        onOpenChannel = video.channelId?.takeIf { kind != YouTubeFeedKind.Channel }?.let { id ->
                            { showChannel(id, video.channel ?: "Channel", null) }
                        },
                        focusRequester = firstCardFocus.takeIf { index == 0 },
                    )
                }
                if (feed.continuation != null || feed.error != null) {
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
private fun ChannelPicker(state: YouTubeChannelState) {
    var open by remember { mutableStateOf(false) }
    Box {
        val current = state.current
        YouTubePill(
            label = when {
                state.channels.isEmpty() -> "Channel: account default"
                state.needsChoice || current == null -> "Choose channel"
                else -> "Watching as ${current.name}"
            },
            selected = false,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.channels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Couldn't read this account's channels. Reload") },
                    onClick = {
                        open = false
                        YouTubeFeedRepository.loadChannels(force = true)
                    },
                )
            }
            state.channels.forEach { identity ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(identity.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = when {
                                    !identity.hasChannel -> "No channel"
                                    identity.handle != null -> identity.handle
                                    identity.pageId == null -> "Account profile"
                                    else -> "Brand channel"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        NuvioAsyncImage(
                            model = identity.photoUrl,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    },
                    trailingIcon = if (identity.pageId == state.pageId && !state.needsChoice) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Current") }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (identity.pageId != state.pageId || state.needsChoice) {
                            YouTubeFeedRepository.selectChannel(identity.pageId)
                        }
                    },
                )
            }
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

/** How many pages Back can step through inside the tab. */
private const val MAX_BACK_STEPS = 12

/** [YouTubeTabScreen]'s selection for the subscribed-channels list, beside the feed kinds' names. */
private const val CHANNELS_VIEW = "Channels"

/** [YouTubeTabScreen]'s selection for the playlist library. */
private const val PLAYLISTS_VIEW = "Playlists"

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
private fun PlaylistLibrary(onOpen: (YouTubePlaylist) -> Unit) {
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
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(state.playlists, key = { it.playlistId }) { playlist ->
                PlaylistTile(playlist = playlist, onClick = { onOpen(playlist) })
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: YouTubePlaylist, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
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
            val counts = listOfNotNull(
                comment.likes?.let { "$it likes" },
                comment.replies?.let { "$it ${if (it == "1") "reply" else "replies"}" },
            )
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
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
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
private fun SubscribedChannels(onOpen: (YouTubeChannel) -> Unit) {
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
                snapshotFlow {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= gridState.layoutInfo.totalItemsCount - 8
                }.distinctUntilChanged().collect { nearEnd ->
                    if (nearEnd) YouTubeFeedRepository.loadMoreSubscribedChannels()
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.channels, key = { it.channelId }) { channel ->
                    ChannelTile(channel = channel, onClick = { onOpen(channel) })
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(channel: YouTubeChannel, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
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
    onSaveToWatchLater: () -> Unit,
    onSaveToPlaylist: () -> Unit,
    onComments: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onOpenChannel: (() -> Unit)?,
    focusRequester: FocusRequester? = null,
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
    LaunchedEffect(menuAt != null, video.videoId) {
        if (menuAt != null && liked == null) YouTubeFeedRepository.loadLikeStatus(video.videoId)
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
                add(PosterZoomOverlayAction(icon = Icons.Rounded.WatchLater, label = "Save to Watch later", onSelected = onSaveToWatchLater))
                add(PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Save to playlist…", onSelected = onSaveToPlaylist))
                add(PosterZoomOverlayAction(icon = Icons.AutoMirrored.Rounded.Comment, label = "Comments", onSelected = onComments, group = 1))
                onOpenChannel?.let {
                    add(PosterZoomOverlayAction(icon = Icons.Rounded.AccountCircle, label = "Go to channel", onSelected = it, group = 1))
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
        modifier = Modifier
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
        listOfNotNull(video.channel, video.details).forEach { line ->
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
