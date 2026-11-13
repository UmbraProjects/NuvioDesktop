package com.nuvio.app.features.player

import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.youtube.WATCH_LATER_PLAYLIST_ID
import com.nuvio.app.features.youtube.YouTubeComment
import com.nuvio.app.features.youtube.YouTubeFeedRepository
import com.nuvio.app.features.youtube.YouTubeLikeCounts
import com.nuvio.app.features.youtube.YouTubeMiniPlayerHost
import com.nuvio.app.features.youtube.YouTubePlaylistOption
import com.nuvio.app.features.youtube.YouTubeVideo
import com.nuvio.app.features.youtube.YouTubeVideoDetails
import com.nuvio.app.features.youtube.YouTubeWatchLayout
import com.nuvio.app.features.youtube.YouTubeWatchQueue
import com.nuvio.app.features.youtube.formatYouTubeCount
import com.nuvio.app.features.youtube.isYouTubeVideoWatched
import com.nuvio.app.features.youtube.requestYouTubeFullscreen
import com.nuvio.app.features.youtube.youTubeDisplayTitle
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * fork:youtube-watch-page
 *
 * A YouTube tab video as a watch page, the way video sites lay one out: the video at the top with the
 * queue and related videos down the right, and under the video its title, channel, the card actions,
 * the description and the comments (with their replies, and a box to comment or reply). Theater mode
 * fills the window with the video, as full screen does, and scrolling down brings the rest of the page
 * up (the video going to its corner). A narrow window stacks everything.
 *
 * The page scrolls as one, so the comments get the whole window once scrolled to. The video is still
 * the native player, an OS window, and dragging one along with the page lagged everything under it; so
 * as soon as the page leaves the top the video goes to a fixed box in the bottom right corner (controls
 * hidden, resizable from its top left corner, a click brings it back), the way browser extensions do,
 * and returns to its place at the top. It is placed after the page so nothing of Compose's lies over it
 * (Compose content over a native window has it re-cut every frame), and nothing is drawn over its box:
 * no dialogs or toasts; the playlist picker opens in the page.
 */

/** Below this the page stacks in one column: the queue and related videos go under the comments. */
private val TWO_COLUMN_MIN_WIDTH = 1000.dp
private val SIDE_COLUMN_WIDTH = 428.dp


/** Theater: how much of the full-window video stays in view above the title once scrolled down to it. */
private val THEATER_PEEK = 120.dp

/** What is left of the window under the video in each layout, so the title shows without scrolling. */
private val DEFAULT_VIDEO_CLEARANCE = 150.dp

/**
 * The player laid out: filling the screen, or in its box on the watch page ([YouTubeWatchLayout]).
 * [player] keeps its place in the composition either way, so switching between the two never restarts
 * playback; only its size and position change.
 */
@Composable
internal fun PlayerScreenRuntime.YouTubeWatchFrame(player: @Composable (Modifier) -> Unit) {
    val windowed by YouTubeWatchLayout.windowed.collectAsState()
    // fork:youtube-mini-player - the tab around the video in its corner (YouTubeWatchLayout.browsing).
    val browsing by YouTubeWatchLayout.browsing.collectAsState()
    // fork:pip-browse - nor in the corner box over the app: the video just fills the box.
    val inBrowsePipBox by PlayerBrowsePip.active.collectAsState()
    val pageShown = (windowed || browsing) && !pictureInPictureActive && !inBrowsePipBox
    var frameOrigin by remember { mutableStateOf(Offset.Zero) }
    var videoBox by remember { mutableStateOf<Rect?>(null) }
    val mini by YouTubeWatchLayout.mini.collectAsState()
    val inCorner = pageShown && (mini || browsing)
    // fork:youtube-mini-player - moved and resized from the HUD, with PiP's gestures (YouTubeWatchLayout.miniBox).
    val miniBoxState by YouTubeWatchLayout.miniBox.collectAsState()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(if (pageShown) MaterialTheme.nuvio.colors.background else Color.Transparent)
            .onGloballyPositioned { frameOrigin = it.positionInWindow() }
            // Movement over the page brings back a cursor the player hid (it only sees the video's).
            .pointerInput(pageShown) {
                if (!pageShown) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Move) YouTubeWatchLayout.revealCursor?.invoke()
                    }
                }
            },
    ) {
        // Always called, and first: the player below keeps its place whether or not the page shows.
        YouTubeWatchPage(shown = pageShown && !browsing, onVideoBox = { videoBox = it })
        YouTubeMiniPlayerBrowsePage(shown = pageShown && browsing)
        YouTubeWatchLayout.miniFrameDp = maxWidth.value to maxHeight.value
        // A window made smaller since keeps the box inside it.
        val miniBox = com.nuvio.app.features.youtube.clampMiniBox(miniBoxState, maxWidth.value, maxHeight.value)
        // The HUD's X over the box (the tab's corner player only): closing it stops the video, and the
        // tab it was over stays. The box stays in its corner until then, not going back to the full
        // video on its way out.
        val closeRequests by YouTubeWatchLayout.closeRequests.collectAsState()
        var handledClose by remember { mutableStateOf(closeRequests) }
        LaunchedEffect(closeRequests) {
            if (closeRequests == handledClose) return@LaunchedEffect
            handledClose = closeRequests
            if (!browsing) return@LaunchedEffect
            YouTubeWatchLayout.closeFromCorner()
            // A still over the swap: the player going and the tab under it coming on screen.
            PlayerHandoff.coverClose()
            flushWatchProgress()
            args.onBack()
        }
        val box = videoBox
        player(
            when {
                !pageShown -> Modifier.fillMaxSize()
                inCorner -> Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = miniBox.rightDp.dp, bottom = miniBox.bottomDp.dp)
                    .size(miniBox.widthDp.dp, miniBox.heightDp.dp)
                // Until the page has laid out its box: out of sight rather than over the page.
                box == null -> Modifier.size(1.dp)
                else -> with(density) {
                    Modifier
                        .offset { IntOffset((box.left - frameOrigin.x).roundToInt(), (box.top - frameOrigin.y).roundToInt()) }
                        .size(box.width.toDp(), box.height.toDp())
                }
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.YouTubeWatchPage(shown: Boolean, onVideoBox: (Rect) -> Unit) {
    if (!shown) return
    val theater by YouTubeFeedRepository.theaterMode.collectAsState()
    val videoId = youTubeVideoId
    val scope = rememberCoroutineScope()
    val comments = remember(videoId) { YouTubeWatchComments(videoId, scope) }
    val scroll = rememberScrollState()
    // Off the top: the video to its corner (see the header).
    val mini by remember { androidx.compose.runtime.derivedStateOf { scroll.value > 0 } }
    LaunchedEffect(mini) { YouTubeWatchLayout.setMini(mini) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { YouTubeWatchLayout.setMini(false) } }
    // A click on the corner player: back up to the video.
    // The wheel over the video (which takes it from the page) scrolls the page too.
    val density = LocalDensity.current
    LaunchedEffect(scroll) {
        YouTubeWatchLayout.scrollRequests.collect { deltaDp -> scroll.scrollBy(with(density) { deltaDp.dp.toPx() }) }
    }
    LaunchedEffect(scroll) {
        val handled = YouTubeWatchLayout.backToVideo.value
        YouTubeWatchLayout.backToVideo.collect { if (it != handled) scroll.animateScrollTo(0) }
    }
    LaunchedEffect(comments) { comments.loadMore() }
    // The next page of comments as the end of the page comes near.
    LaunchedEffect(scroll, comments) {
        snapshotFlow { scroll.maxValue - scroll.value < 1200 }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) comments.loadMore() }
    }
    // The related column ends well before the page does when the comments run long, so its next page is
    // asked for by its own end: once that is within two screens of the bottom of the window, so the page
    // is in before it is scrolled to. Keyed on the list's length too, so a page that arrives while the
    // end is still that close asks for the one after.
    var sideColumnBottom by remember { mutableStateOf(Float.MAX_VALUE) }
    var pageTop by remember { mutableStateOf(0f) }
    var pageHeight by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val nearEnd = pageHeight > 0 && sideColumnBottom - (pageTop + pageHeight) < pageHeight * RELATED_PREFETCH_SCREENS
            nearEnd to youTubeRelated.size
        }
            .distinctUntilChanged()
            .collect { (nearEnd, _) -> if (nearEnd) loadMoreYouTubeRelated() }
    }
    val sideColumnPosition = Modifier.onGloballyPositioned { sideColumnBottom = it.positionInWindow().y + it.size.height }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            pageTop = it.positionInWindow().y
            pageHeight = it.size.height
        },
    ) {
        val twoColumns = maxWidth >= TWO_COLUMN_MIN_WIDTH
        val viewport = maxHeight
        if (theater) {
            // Theater: a step down off the full-window video goes straight to the title (a strip of the
            // video left above it), and a step up from there straight back. Only once a scroll has
            // settled, so it never fights the one in progress.
            val details = with(density) { (viewport - THEATER_PEEK).toPx() }.roundToInt().coerceAtLeast(1)
            LaunchedEffect(scroll, details) {
                var settled = scroll.value
                snapshotFlow { scroll.isScrollInProgress to scroll.value }.collect { (moving, value) ->
                    if (moving) return@collect
                    val goal = when {
                        settled == 0 && value in 1 until details -> details
                        settled >= details && value in 1 until details -> 0
                        else -> null
                    }
                    if (goal == null) {
                        settled = value
                        return@collect
                    }
                    try {
                        scroll.animateScrollTo(goal)
                    } catch (interrupted: kotlinx.coroutines.CancellationException) {
                        // Taken over by the person's own scroll; this effect carries on.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    settled = scroll.value
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            when {
                theater -> {
                    // Edge to edge, as tall as leaves the title showing.
                    // The whole window, as full screen is; scrolling down brings the page up under it.
                    VideoBox(maxHeight = viewport, fillHeight = viewport, placeholder = poster.takeIf { mini }, onVideoBox = onVideoBox)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        WatchDetails(comments, Modifier.weight(1f))
                        if (twoColumns) SideColumn(Modifier.width(SIDE_COLUMN_WIDTH).then(sideColumnPosition))
                    }
                    if (!twoColumns) SideColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).then(sideColumnPosition))
                }
                twoColumns -> Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        VideoBox(maxHeight = viewport - DEFAULT_VIDEO_CLEARANCE, placeholder = poster.takeIf { mini }, onVideoBox = onVideoBox)
                        Spacer(Modifier.height(16.dp))
                        WatchDetails(comments, Modifier.fillMaxWidth())
                    }
                    SideColumn(Modifier.width(SIDE_COLUMN_WIDTH).then(sideColumnPosition))
                }
                else -> Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    VideoBox(maxHeight = viewport - DEFAULT_VIDEO_CLEARANCE, placeholder = poster.takeIf { mini }, onVideoBox = onVideoBox)
                    Spacer(Modifier.height(16.dp))
                    WatchDetails(comments, Modifier.fillMaxWidth())
                    SideColumn(Modifier.fillMaxWidth().then(sideColumnPosition))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The video's box: as wide as there is room for at 16:9, no taller than [maxHeight], centred; or, with
 * [fillHeight] (theater), the full width at that height, the player letterboxing the picture itself
 * as it does full screen. While the video is in its corner the box keeps its place with [placeholder]
 * (the thumbnail) dimmed in it.
 */
@Composable
private fun VideoBox(maxHeight: Dp, placeholder: String?, onVideoBox: (Rect) -> Unit, fillHeight: Dp? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        val width = minOf(maxWidth, maxHeight * 16f / 9f)
        Box(
            (if (fillHeight != null) Modifier.fillMaxWidth().height(fillHeight) else Modifier.width(width).aspectRatio(16f / 9f))
                .onGloballyPositioned { onVideoBox(it.boundsInWindow()) },
        ) {
            if (placeholder != null) {
                NuvioAsyncImage(model = placeholder, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            }
        }
    }
}

/**
 * fork:youtube-mini-player - the YouTube tab filling the window while the video plays on in its corner
 * box: what the app put in [YouTubeMiniPlayerHost]. Called always, like the watch page, so the player
 * after it keeps its place in the composition.
 */
@Composable
private fun YouTubeMiniPlayerBrowsePage(shown: Boolean) {
    if (!shown) return
    val page = YouTubeMiniPlayerHost.page ?: return
    // The bar's keys and the mouse's back button step back inside the tab (TabBackInterceptor); with
    // nothing to step back to they leave the tab where it is, as the video is still playing.
    page(Modifier.fillMaxSize())
}

/** Under the video: title, channel and actions, description, then the comments. */
@Composable
private fun PlayerScreenRuntime.WatchDetails(comments: YouTubeWatchComments, modifier: Modifier) {
    val details = youTubeDetails
    val signedIn = YouTubeMusicAuth.isSignedIn
    val current = YouTubeVideo(
        videoId = youTubeVideoId,
        title = details?.title ?: title,
        channel = details?.channelName,
        details = null,
        duration = null,
        thumbnailUrl = poster,
        channelId = details?.channelId,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = details?.title ?: title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        WatchActions(current, details, signedIn)
        WatchDescription(details?.views, details?.published, details?.description)
        WatchComments(comments, signedIn)
    }
}

/** The channel (with Subscribe) on the left; the actions as icons on the right, named on hover. */
@Composable
private fun WatchActions(current: YouTubeVideo, details: YouTubeVideoDetails?, signedIn: Boolean) {
    val scope = rememberCoroutineScope()
    // A line under the actions when YouTube refused one (toasts would be drawn under the video); what
    // went through shows in the button's own colour.
    var status by remember(current.videoId) { mutableStateOf<String?>(null) }
    LaunchedEffect(status) {
        if (status != null) {
            delay(3_000)
            status = null
        }
    }
    val likeStatus by YouTubeFeedRepository.likeStatus.collectAsState()
    val liked = likeStatus[current.videoId] == true
    val likes by produceState(YouTubeLikeCounts.cached(current.videoId), current.videoId) {
        if (value == null) value = YouTubeLikeCounts.likes(current.videoId)
    }
    val watchLater by YouTubeFeedRepository.watchLater.collectAsState()
    val inWatchLater = current.videoId in watchLater
    val queue by YouTubeWatchQueue.videos.collectAsState()
    val queued = queue.any { it.videoId == current.videoId }
    val theater by YouTubeFeedRepository.theaterMode.collectAsState()
    var choosingPlaylist by remember(current.videoId) { mutableStateOf(false) }
    fun failed() {
        status = "YouTube didn't take that. Try again shortly."
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelBlock(current, details, signedIn, onFailed = ::failed, modifier = Modifier.weight(1f))
            if (signedIn) {
                WatchIconButton(Icons.Rounded.ThumbUp, if (liked) "Remove like" else "Like", on = liked, badge = likes?.let(::formatYouTubeCount)) {
                    scope.launch {
                        if (!YouTubeFeedRepository.setLiked(current.videoId, !liked)) failed()
                    }
                }
                WatchIconButton(
                    if (inWatchLater) Icons.Rounded.Done else Icons.Rounded.WatchLater,
                    if (inWatchLater) "Remove from Watch later" else "Save to Watch later",
                    on = inWatchLater,
                ) {
                    scope.launch {
                        if (!YouTubeFeedRepository.saveToPlaylist(WATCH_LATER_PLAYLIST_ID, current, !inWatchLater)) failed()
                    }
                }
            }
            WatchIconButton(
                if (queued) Icons.Rounded.PlaylistAddCheck else Icons.Rounded.QueuePlayNext,
                if (queued) "Remove from queue" else "Add to queue",
                on = queued,
            ) {
                if (queued) YouTubeWatchQueue.remove(current.videoId) else YouTubeWatchQueue.add(current)
            }
            if (signedIn) {
                WatchIconButton(Icons.Rounded.PlaylistAdd, "Save to playlist", on = choosingPlaylist) { choosingPlaylist = !choosingPlaylist }
            }
            WatchIconButton(TheaterIcon, if (theater) "Default view" else "Theater mode", on = theater) {
                YouTubeFeedRepository.setTheaterMode(!theater)
            }
            WatchIconButton(Icons.Rounded.Fullscreen, "Full screen", on = false, onClick = ::requestYouTubeFullscreen)
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (choosingPlaylist) PlaylistChooser(current, onFailed = ::failed)
    }
}

/** The uploader: picture, name and subscribers, and Subscribe. */
@Composable
private fun ChannelBlock(
    current: YouTubeVideo,
    details: YouTubeVideoDetails?,
    signedIn: Boolean,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var subscribed by remember(current.channelId, details?.subscribed) { mutableStateOf(details?.subscribed) }
    // The picture and name open the channel's page: the tab around the video, which goes to its corner
    // (fork:youtube-mini-player). Only where the mini player can show the tab.
    val channelId = current.channelId
    val openChannel: (() -> Unit)? = if (channelId != null && YouTubeMiniPlayerHost.page != null) {
        { YouTubeWatchLayout.openChannel(channelId, current.channel.orEmpty(), details?.channelAvatarUrl) }
    } else {
        null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(24.dp))
                .then(if (openChannel != null) Modifier.clickable(onClick = openChannel) else Modifier)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        NuvioAsyncImage(
            model = details?.channelAvatarUrl,
            contentDescription = current.channel,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = current.channel.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            details?.subscribers?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        }
        if (signedIn && channelId != null && subscribed != null) {
            val isSubscribed = subscribed == true
            WatchPill(
                if (isSubscribed) Icons.Rounded.NotificationsActive else null,
                if (isSubscribed) "Subscribed" else "Subscribe",
                on = !isSubscribed,
            ) {
                scope.launch {
                    if (YouTubeFeedRepository.setSubscribedTo(channelId, !isSubscribed)) subscribed = !isSubscribed else onFailed()
                }
            }
        }
    }
}

/** The account's playlists, ticked where the video is in them; a tick saves or removes it. In the page, not a dialog. */
@Composable
private fun PlaylistChooser(current: YouTubeVideo, onFailed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var options by remember(current.videoId) { mutableStateOf<List<YouTubePlaylistOption>?>(null) }
    var failed by remember(current.videoId) { mutableStateOf(false) }
    LaunchedEffect(current.videoId) {
        options = YouTubeFeedRepository.playlistOptions(current.videoId)
        failed = options == null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
    ) {
        when {
            failed -> Text("YouTube didn't list the playlists. Try again shortly.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            options == null -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
            else -> options.orEmpty().forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val add = !option.containsVideo
                            scope.launch {
                                if (YouTubeFeedRepository.saveToPlaylist(option.playlistId, current, add)) {
                                    options = options?.map { if (it.playlistId == option.playlistId) it.copy(containsVideo = add) else it }
                                } else {
                                    onFailed()
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (option.containsVideo) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (option.containsVideo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(option.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Views, date and the description, three lines until opened. */
@Composable
private fun WatchDescription(views: String?, published: String?, description: String?) {
    var expanded by remember { mutableStateOf(false) }
    val headline = listOfNotNull(views, published).joinToString(" • ")
    if (headline.isEmpty() && description == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (headline.isNotEmpty()) {
            Text(headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (expanded) "Show less" else "…more",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The comments: a box to add one, then each with its replies (opened on demand) and a box to reply. */
@Composable
private fun WatchComments(comments: YouTubeWatchComments, signedIn: Boolean) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeading("Comments")
        if (signedIn && comments.createParams != null) {
            CommentBox(placeholder = "Add a comment…") { text -> comments.post(text, parent = null) }
        }
        comments.comments.forEach { comment -> CommentThread(comment, comments, signedIn) }
        when {
            comments.loading -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
            comments.failed -> Text(
                "Couldn't load the comments. Click to try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { comments.retry() },
            )
            comments.loaded && comments.comments.isEmpty() -> Text(
                "No comments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommentThread(comment: YouTubeComment, comments: YouTubeWatchComments, signedIn: Boolean) {
    var replying by remember(comment.id) { mutableStateOf(false) }
    val open = comments.repliesOpen[comment.id] == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CommentRow(
            comment = comment,
            avatarSize = 40.dp,
            onReply = if (signedIn && comment.replyParams != null) ({ replying = !replying }) else null,
            comments = comments,
            signedIn = signedIn,
        )
        // Indented to the comment's text: the 40dp avatar plus its 14dp gap.
        Column(Modifier.padding(start = 54.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (replying) {
                CommentBox(placeholder = "Add a reply…", autoFocus = true) { text ->
                    comments.post(text, parent = comment).also { if (it) replying = false }
                }
            }
            if (comment.repliesToken != null && comment.replies != null) {
                Row(
                    Modifier.offset(x = (-8).dp).clip(RoundedCornerShape(50)).clickable { comments.toggleReplies(comment) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        "${comment.replies} ${if (comment.replies == "1") "reply" else "replies"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (open || comments.replies[comment.id]?.any { it.id.startsWith(LOCAL_COMMENT_PREFIX) } == true) {
                comments.replies[comment.id].orEmpty().forEach { reply ->
                    CommentRow(reply, avatarSize = 28.dp, onReply = null, comments = comments, signedIn = signedIn)
                }
                when {
                    comments.repliesLoading[comment.id] == true -> CircularProgressIndicator(Modifier.size(20.dp))
                    open && comments.repliesNext[comment.id] != null -> Text(
                        "Show more replies",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { comments.moreReplies(comment) }.padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: YouTubeComment,
    avatarSize: Dp,
    onReply: (() -> Unit)?,
    comments: YouTubeWatchComments,
    signedIn: Boolean,
) {
    val liked = comments.isLiked(comment)
    val likes = com.nuvio.app.features.youtube.youTubeCommentLikes(comment, liked)
    val canLike = signedIn && (if (liked) comment.unlikeAction else comment.likeAction) != null
    // YouTube's proportions: name bold and white with the time in grey, body at 14/20 with no extra
    // tracking (the theme's label styles are letter-spaced, which made names hard to read), and
    // the actions a clear step below the text.
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NuvioAsyncImage(
            model = comment.avatarUrl,
            contentDescription = comment.author,
            modifier = Modifier.size(avatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comment.pinned) {
                    Text("Pinned", style = CommentMetaStyle, color = muted)
                }
                if (comment.author.isNotBlank()) {
                    Text(
                        comment.author,
                        style = CommentMetaStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                comment.published?.let { Text(it, style = CommentMetaStyle, color = muted, maxLines = 1) }
            }
            Spacer(Modifier.height(4.dp))
            Text(comment.text, style = CommentBodyStyle, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            // The pills' padding is pulled back by the same amount so the thumb lines up with the text.
            Row(
                Modifier.offset(x = (-8).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (likes != null || canLike) {
                    val tint = if (liked) MaterialTheme.colorScheme.primary else muted
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .then(if (canLike) Modifier.clickable { comments.toggleLike(comment) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) tint else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        likes?.let { Text(it, style = CommentMetaStyle, color = tint) }
                    }
                }
                onReply?.let {
                    Text(
                        "Reply",
                        style = CommentMetaStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = it).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private val CommentMetaStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
private val CommentBodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)

/** A text box with a send button; [onSend] returns whether it went, and the box clears when it did. */
@Composable
private fun CommentBox(placeholder: String, autoFocus: Boolean = false, onSend: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocusLogged() }
    fun send() {
        val message = text.trim()
        if (message.isEmpty() || sending) return
        sending = true
        scope.launch {
            if (onSend(message)) text = ""
            sending = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NuvioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            singleLine = false,
            maxLines = 6,
            focusRequester = focus,
            enabled = !sending,
        )
        WatchPill(null, if (sending) "Sending…" else "Send", on = text.isNotBlank() && !sending, onClick = ::send)
    }
}

/** Down the right (or under the comments when stacked): the queue when it has anything, then the related videos. */
@Composable
private fun PlayerScreenRuntime.SideColumn(modifier: Modifier) {
    val queue by YouTubeWatchQueue.videos.collectAsState()
    val playingId = youTubeVideoId
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (queue.isNotEmpty()) {
            SectionHeading("Queue · ${queue.size}")
            queue.forEach { video ->
                val playing = video.videoId == playingId
                SideVideoRow(
                    video = video,
                    highlighted = playing,
                    onClick = { if (!playing) openYouTubeVideoFromPlayer(video) },
                    onRemove = { YouTubeWatchQueue.remove(video.videoId) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        val hideWatched by YouTubeFeedRepository.hideWatched.collectAsState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (queue.isNotEmpty()) SectionHeading("Up next") }
            // The tab's own Hide watched: one setting for the feeds and this column.
            WatchPill(null, "Hide watched", on = hideWatched) { YouTubeFeedRepository.setHideWatched(!hideWatched) }
        }
        val shown = visibleYouTubeRelated
        shown.forEach { video -> SideVideoRow(video, highlighted = false, onClick = { openYouTubeVideoFromPlayer(video) }) }
        // Hide watched can leave only a few: keep fetching until the column fills or YouTube runs out.
        LaunchedEffect(shown.size, youTubeRelated.size, hideWatched) {
            if (youTubeRelated.isNotEmpty() && shown.size < RELATED_MIN_SHOWN) loadMoreYouTubeRelated()
        }
        when {
            youTubeRelated.isEmpty() || youTubeRelatedLoading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            shown.isEmpty() -> Text(
                "You've watched everything suggested here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** With Hide watched on, related pages are fetched until at least this many show (or the column ends). */
private const val RELATED_MIN_SHOWN = 12

/** How many window heights before the related column's end its next page is fetched. */
private const val RELATED_PREFETCH_SCREENS = 2f

@Composable
private fun SideVideoRow(video: YouTubeVideo, highlighted: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    highlighted -> MaterialTheme.nuvio.colors.accent.copy(alpha = 0.16f)
                    hovered -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(196.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            NuvioAsyncImage(model = video.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            video.duration?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (isYouTubeVideoWatched(video)) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0xFFFF0033)))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                youTubeDisplayTitle(video),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.channel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            if (highlighted) {
                Text("Playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.accent)
            } else {
                video.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
        if (onRemove != null && (hovered || highlighted)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove).padding(4.dp),
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

/** A round action button: its name on hover, the app accent while [on], and a small count beside it ([badge]) when it has one. */
@Composable
private fun WatchIconButton(icon: ImageVector, label: String, on: Boolean, badge: String? = null, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(percent = 50)
    val tint = if (on) tokens.colors.accent else Color.White
    NuvioPosterHoverTooltip(title = label) {
        Row(
            Modifier
                .height(40.dp)
                .clip(shape)
                .background(
                    when {
                        on -> tokens.colors.accent.copy(alpha = if (hovered) 0.28f else 0.18f)
                        hovered -> Color.White.copy(alpha = 0.14f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = if (badge == null) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            badge?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1) }
        }
    }
}

/** A pill with words, for Subscribe and Send: the app accent while [on], plain glass otherwise. */
@Composable
private fun WatchPill(icon: ImageVector?, label: String, on: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(percent = 50)
    val tint = if (on) tokens.colors.accent else Color.White
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(
                when {
                    on -> tokens.colors.accent.copy(alpha = if (hovered) 0.28f else 0.18f)
                    hovered -> Color.White.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/**
 * Theater mode's icon: a wide screen (a frame across the whole width) with the picture spanning it,
 * drawn here as Material has no icon for it.
 */
private val TheaterIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Theater", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // The frame: an outer wide rectangle with the inside cut out.
            moveTo(1.5f, 5.5f)
            lineTo(22.5f, 5.5f)
            lineTo(22.5f, 18.5f)
            lineTo(1.5f, 18.5f)
            close()
            moveTo(3.5f, 7.5f)
            lineTo(3.5f, 16.5f)
            lineTo(20.5f, 16.5f)
            lineTo(20.5f, 7.5f)
            close()
        }
        .path(fill = SolidColor(Color.Black)) {
            // The picture, edge to edge inside it.
            moveTo(5f, 9.5f)
            lineTo(19f, 9.5f)
            lineTo(19f, 14.5f)
            lineTo(5f, 14.5f)
            close()
        }
        .build()
}

/** Comments posted from the page before YouTube's own copy is loaded: shown at once under this id prefix. */
private const val LOCAL_COMMENT_PREFIX = "local-"

/** One video's comments on the page: pages as they scroll in, replies opened per comment, and posting. */
private class YouTubeWatchComments(val videoId: String, private val scope: CoroutineScope) {
    var comments by mutableStateOf<List<YouTubeComment>>(emptyList())
    var createParams by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private var continuation: String? = null
    val replies = mutableStateMapOf<String, List<YouTubeComment>>()
    val repliesNext = mutableStateMapOf<String, String?>()
    val repliesOpen = mutableStateMapOf<String, Boolean>()
    val repliesLoading = mutableStateMapOf<String, Boolean>()
    /** The account's like on a comment as changed here, over what the page said. */
    private val likedHere = mutableStateMapOf<String, Boolean>()
    private val likeSending = mutableSetOf<String>()

    fun isLiked(comment: YouTubeComment): Boolean = likedHere[comment.id] ?: comment.liked

    /** Likes [comment] or takes the like back: shown at once, put back if YouTube refuses. */
    fun toggleLike(comment: YouTubeComment) {
        if (!likeSending.add(comment.id)) return
        val liked = !isLiked(comment)
        likedHere[comment.id] = liked
        scope.launch {
            try {
                if (!YouTubeFeedRepository.setCommentLiked(comment, liked)) likedHere[comment.id] = !liked
            } finally {
                likeSending.remove(comment.id)
            }
        }
    }

    suspend fun loadMore() {
        if (loading || failed || (loaded && continuation == null)) return
        loading = true
        try {
            val page = YouTubeFeedRepository.comments(videoId, continuation.takeIf { loaded })
            if (page == null) {
                failed = true
                return
            }
            if (!loaded) createParams = page.createParams
            comments = (comments + page.comments).distinctBy { it.id }
            continuation = page.continuation
            loaded = true
        } finally {
            loading = false
        }
    }

    fun retry() {
        failed = false
        scope.launch { loadMore() }
    }

    fun toggleReplies(comment: YouTubeComment) {
        val open = repliesOpen[comment.id] == true
        repliesOpen[comment.id] = !open
        if (!open && replies[comment.id].orEmpty().none { !it.id.startsWith(LOCAL_COMMENT_PREFIX) }) {
            comment.repliesToken?.let { loadReplies(comment.id, it) }
        }
    }

    fun moreReplies(comment: YouTubeComment) {
        repliesNext[comment.id]?.let { loadReplies(comment.id, it) }
    }

    private fun loadReplies(commentId: String, token: String) {
        if (repliesLoading[commentId] == true) return
        repliesLoading[commentId] = true
        scope.launch {
            try {
                val page = YouTubeFeedRepository.commentReplies(token) ?: return@launch
                replies[commentId] = (replies[commentId].orEmpty() + page.comments).distinctBy { it.id }
                repliesNext[commentId] = page.continuation
            } finally {
                repliesLoading[commentId] = false
            }
        }
    }

    /** Posts [text] on the video or as a reply to [parent]; shown at once when YouTube takes it. */
    suspend fun post(text: String, parent: YouTubeComment?): Boolean {
        val params = if (parent == null) createParams else parent.replyParams
        params ?: return false
        if (!YouTubeFeedRepository.postComment(params, text, reply = parent != null)) return false
        val me = YouTubeFeedRepository.channel.value.current
        val mine = YouTubeComment(
            id = LOCAL_COMMENT_PREFIX + com.nuvio.app.features.streams.epochMs(),
            author = me?.handle ?: me?.name ?: "You",
            avatarUrl = me?.photoUrl,
            text = text,
            published = "just now",
            likes = null,
            replies = null,
        )
        if (parent == null) {
            comments = listOf(mine) + comments
        } else {
            replies[parent.id] = replies[parent.id].orEmpty() + mine
        }
        return true
    }
}
