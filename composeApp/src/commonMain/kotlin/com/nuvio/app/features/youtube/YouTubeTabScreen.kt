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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import com.nuvio.app.core.ui.NuvioTextField
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
    var selectedName by rememberSaveable { mutableStateOf(YouTubeFeedKind.Home.name) }
    val kind = YouTubeFeedKind.valueOf(selectedName)
    val feeds by YouTubeFeedRepository.feeds.collectAsStateWithLifecycle()
    val feed = feeds.getValue(kind)
    val channel by YouTubeFeedRepository.channel.collectAsStateWithLifecycle()
    val searchQuery by YouTubeFeedRepository.searchQuery.collectAsStateWithLifecycle()
    val deArrow by remember { YouTubeFeedRepository.deArrow }.collectAsStateWithLifecycle()
    val hideWatched by remember { YouTubeFeedRepository.hideWatched }.collectAsStateWithLifecycle()
    val videos = if (hideWatched) feed.videos.filterNot(::isYouTubeVideoWatched) else feed.videos
    // A page that was all watched leaves nothing on screen to scroll to the end of, so ask for the next one here.
    val allHidden = videos.isEmpty() && feed.videos.isNotEmpty()
    LaunchedEffect(allHidden, feed.continuation, feed.loading) {
        if (allHidden && feed.continuation != null && !feed.loading && feed.error == null) YouTubeFeedRepository.loadMore(kind)
    }
    var searchText by rememberSaveable { mutableStateOf(YouTubeFeedRepository.searchQuery.value) }
    fun submitSearch() {
        if (searchText.isBlank()) return
        YouTubeFeedRepository.search(searchText)
        selectedName = YouTubeFeedKind.Search.name
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
    fun play(video: YouTubeVideo) {
        openJob?.cancel()
        openError = null
        openingId = video.videoId
        openJob = scope.launch {
            val source = try {
                youTubeOpenVideo(video.videoId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }
            if (openingId != video.videoId) return@launch
            openingId = null
            val title = YouTubeDeArrow.cached(video.videoId)?.title?.takeIf { deArrow } ?: video.title
            if (source == null) {
                openError = "\"$title\" couldn't be opened. YouTube may be limiting requests; try again shortly."
                return@launch
            }
            onPlay(
                PlayerLaunch(
                    title = title,
                    sourceUrl = source.videoUrl,
                    sourceAudioUrl = source.audioUrl,
                    sourceHeaders = source.headers,
                    streamTitle = title,
                    streamSubtitle = video.channel,
                    providerName = "YouTube",
                    poster = video.thumbnailUrl,
                    background = video.thumbnailUrl,
                    contentType = YOUTUBE_META_TYPE,
                    videoId = video.videoId,
                    parentMetaId = YOUTUBE_META_ID_PREFIX + video.videoId,
                    parentMetaType = YOUTUBE_META_TYPE,
                    // Picks up where YouTube says this account left off, unless it was finished. A source that
                    // cannot be opened part way in starts from the top instead: mpv would wait on that seek for good.
                    initialProgressFraction = video.watchedPercent
                        ?.takeIf { it in 1..94 && source.openableMidway }
                        ?.div(100f),
                    // Kept out of Continue Watching and the trackers; YouTube's own history gets it
                    // instead (PlayerYouTubeWatchTime.kt).
                    disableProgressTracking = true,
                ),
            )
        }
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(kind, searchQuery) { gridState.scrollToItem(0) }
    LaunchedEffect(gridState, kind) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= gridState.layoutInfo.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) YouTubeFeedRepository.loadMore(kind)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
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
            YouTubeFeedKind.entries.filter { it != YouTubeFeedKind.Search }.forEach { entry ->
                if (entry == kind) {
                    Button(onClick = { YouTubeFeedRepository.load(entry) }) { Text(entry.label) }
                } else {
                    OutlinedButton(onClick = { selectedName = entry.name }) { Text(entry.label) }
                }
            }
            if (auth.signedIn) {
                NuvioTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.width(340.dp),
                    placeholder = "Search YouTube",
                    imeAction = ImeAction.Search,
                    onImeAction = ::submitSearch,
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = if (kind == YouTubeFeedKind.Search) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = ::submitSearch),
                        )
                    },
                    trailingContent = if (searchText.isNotEmpty() || kind == YouTubeFeedKind.Search) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).clip(CircleShape).clickable {
                                    searchText = ""
                                    if (kind == YouTubeFeedKind.Search) selectedName = YouTubeFeedKind.Home.name
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
                if (hideWatched) {
                    FilledTonalButton(onClick = { YouTubeFeedRepository.setHideWatched(false) }) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("Hide watched")
                    }
                } else {
                    OutlinedButton(onClick = { YouTubeFeedRepository.setHideWatched(true) }) { Text("Hide watched") }
                }
                IconButton(
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

        when {
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
                items(videos, key = { it.videoId }) { video ->
                    YouTubeVideoCard(
                        video = video,
                        opening = openingId == video.videoId,
                        deArrow = deArrow,
                        onClick = { play(video) },
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
        OutlinedButton(onClick = { open = true }) {
            val current = state.current
            Text(
                text = when {
                    state.channels.isEmpty() -> "Channel: account default"
                    state.needsChoice || current == null -> "Choose channel"
                    else -> "Watching as ${current.name}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun YouTubeVideoCard(video: YouTubeVideo, opening: Boolean, deArrow: Boolean, onClick: () -> Unit) {
    // DeArrow's title and thumbnail once looked up (cards ask as they come into view); YouTube's meanwhile.
    val branding by produceState(YouTubeDeArrow.cached(video.videoId), video.videoId, deArrow) {
        if (deArrow) value = YouTubeDeArrow.branding(video.videoId)
    }
    val title = branding?.title?.takeIf { deArrow } ?: video.title
    // A frame DeArrow's thumbnail service could not cut falls back to YouTube's thumbnail.
    var brandedThumbnailFailed by remember(video.videoId) { mutableStateOf(false) }
    val thumbnail = branding?.thumbnailUrl?.takeIf { deArrow && !brandedThumbnailFailed } ?: video.thumbnailUrl
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = focused || hovered
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
