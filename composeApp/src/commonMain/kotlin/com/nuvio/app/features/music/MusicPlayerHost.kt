package com.nuvio.app.features.music

import com.nuvio.app.features.music.identity.MusicAccentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The floating bar's card, and the gap under it. */
val MusicBarCardHeight: Dp = 104.dp
val MusicBarBottomMargin: Dp = 16.dp

/** Height the bar reserves: the card plus its bottom margin. The app shell leaves this much room under its content while the bar shows. */
val MusicMiniPlayerHeight: Dp = MusicBarCardHeight + MusicBarBottomMargin

/** What the bar shrinks to while a ladder is open: a slim pill, plus its bottom margin. */
val MusicBarSlimHeight: Dp = 64.dp + MusicBarBottomMargin

/** Whether the bar is the slim pill: a screen asked for it, no real video needs the bar, and the lyric view is closed. */
internal fun musicBarSlim(state: MusicPlayerState, requested: Boolean, nowPlayingOpen: Boolean): Boolean =
    requested && !nowPlayingOpen && !(state.mode == MusicPlayMode.Video && !state.lyricVideo)

/** A real video's picture in the bar: 16:9-ish, inset 12dp inside the card at its left end. */
internal val BarThumbnailWidth = 136.dp
private val ExpandedHeaderHeight = 48.dp
private val ExpandedFrameTop = 16.dp

/** Room kept under the framed video for its title line. */
private val ExpandedCaptionRoom = 84.dp

private data class ExpandedFrame(val width: Dp, val height: Dp)

/** The 16:9 frame of the expanded video: about 70% of the width (as on board 4), as tall as the space leaves. */
private fun expandedFrame(windowWidth: Dp, windowHeight: Dp): ExpandedFrame {
    val room = (windowHeight - ExpandedHeaderHeight - MusicMiniPlayerHeight - ExpandedFrameTop - ExpandedCaptionRoom).coerceAtLeast(1.dp)
    val width = minOf(windowWidth * 0.72f, room * (16f / 9f))
    return ExpandedFrame(width, width * (9f / 16f))
}

/**
 * Plays [MusicPlayback.player] and draws the mini-player bar. Place it once, at the app root, over
 * the content, aligned to the bottom (it fills the width and sizes itself). The shell pads its own
 * content by [MusicMiniPlayerHeight] while [MusicPlayerState.isActive] so nothing sits under the bar.
 *
 * The host owns the one native video surface and resizes it rather than moving it: a thumbnail at
 * the bar's left end, a window over the app ([MusicVideoView.Expanded]), or the whole window
 * ([MusicVideoView.Fullscreen]). The surface keeps one place in the composition, so changing size
 * never reopens the stream. In the big views the host fills the window and the bar stays under the
 * picture, because compose controls cannot be drawn over a native surface.
 *
 * The surface stays composed while a track is loaded, even when the bar is [hidden] (a film is on
 * screen): hiding pauses the music rather than ending it, so it is still there afterwards.
 */
@Composable
fun MusicPlayerHost(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    player: MusicPlayer = MusicPlayback.player,
) {
    val state by player.state.collectAsStateWithLifecycle()
    val seek by player.seekRequests.collectAsStateWithLifecycle()
    val view by MusicPlayback.videoView.collectAsStateWithLifecycle()
    val realVideo = state.mode == MusicPlayMode.Video && !state.lyricVideo
    val collapsed by MusicPlayback.barCollapsed.collectAsStateWithLifecycle()
    // The bar comes back by itself when the player closes or a real video needs its place in the bar.
    LaunchedEffect(state.isActive, realVideo) {
        if (!state.isActive || realVideo) MusicPlayback.barCollapsed.value = false
    }

    LaunchedEffect(hidden) {
        if (hidden) player.pause()
    }
    // A big view only makes sense for a real video. When the track stops having one (the next song
    // has no official video, the player closed, a film opened) it hands back to the bar, and a lyric
    // video carries on in the lyric view.
    LaunchedEffect(realVideo, hidden) {
        if (MusicPlayback.videoView.value != MusicVideoView.Bar && (!realVideo || hidden)) {
            MusicPlayback.setVideoView(MusicVideoView.Bar)
            if (state.lyricVideo && !hidden) MusicPlayback.nowPlayingOpen.value = true
        }
    }

    val slimRequested by MusicPlayback.slimBar.collectAsStateWithLifecycle()
    val nowPlayingOpen by MusicPlayback.nowPlayingOpen.collectAsStateWithLifecycle()
    val reduced = rememberMusicReducedMotion()
    val barStyle = rememberMusicBarStyle()
    // Kept so the bar can slide away after the player stops, instead of vanishing with the surface.
    val lastActive = remember { arrayOfNulls<MusicPlayerState>(1) }
    val source = state.source
    if (source == null) {
        val gone = lastActive[0]
        var leaving by remember { mutableStateOf(gone != null) }
        LaunchedEffect(Unit) {
            delay(450)
            leaving = false
        }
        if (gone != null && leaving) {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                MusicAccentScope {
                    MusicMiniPlayerBar(
                        state = gone,
                        visible = false,
                        player = player,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        slim = musicBarSlim(gone, slimRequested, nowPlayingOpen),
                        initiallyShown = !musicBarCorner(gone, slimRequested, nowPlayingOpen, barStyle),
                    )
                    MusicCornerCard(
                        state = gone,
                        visible = false,
                        player = player,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        initiallyShown = musicBarCorner(gone, slimRequested, nowPlayingOpen, barStyle),
                    )
                }
            }
        }
        return
    }
    lastActive[0] = state
    val corner = musicBarCorner(state, slimRequested, nowPlayingOpen, barStyle)
    val videoVisible = realVideo && !hidden
    val big = videoVisible && view != MusicVideoView.Bar
    val keys = remember { FocusRequester() }
    LaunchedEffect(big) {
        if (big) runCatching { keys.requestFocus() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(if (big) Modifier.fillMaxHeight().background(Color.Black) else Modifier)
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!big || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        MusicPlayback.setVideoView(
                            if (view == MusicVideoView.Fullscreen) MusicVideoView.Expanded else MusicVideoView.Bar,
                        )
                        true
                    }
                    Key.Spacebar -> {
                        player.togglePlayback()
                        true
                    }
                    else -> false
                }
            },
    ) {
        val windowHeight = maxHeight
        val surfaceModifier = when {
            !videoVisible -> Modifier.align(Alignment.BottomStart).size(1.dp)
            view == MusicVideoView.Bar ->
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = musicBarSideMargin(maxWidth) + 12.dp, bottom = MusicBarBottomMargin + 12.dp)
                    .size(BarThumbnailWidth, MusicBarCardHeight - 24.dp)
            view == MusicVideoView.Expanded -> {
                // Framed 16:9 near the top with room for the title under it, so the ambient glow shows
                // round it (mpv letterboxes any other shape inside the frame).
                val frame = expandedFrame(maxWidth, windowHeight)
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = ExpandedHeaderHeight + ExpandedFrameTop)
                    .size(frame.width, frame.height)
            }
            else ->
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height((windowHeight - MusicMiniPlayerHeight).coerceAtLeast(1.dp))
        }
        // Behind the framed video (conditional group, so the surface below keeps its place in the composition).
        if (big && view == MusicVideoView.Expanded) {
            val frame = expandedFrame(maxWidth, windowHeight)
            MusicAccentScope {
                MusicVideoAmbient(
                    coverUrl = state.album?.thumbnailUrl,
                    title = state.track?.title.orEmpty(),
                    subtitle = listOfNotNull(
                        (state.track?.artists?.takeIf { it.isNotEmpty() } ?: state.album?.artists.orEmpty())
                            .joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                        state.album?.title,
                        state.album?.year,
                    ).joinToString(" · "),
                    modifier = Modifier.fillMaxSize().padding(top = ExpandedHeaderHeight, bottom = MusicMiniPlayerHeight),
                    captionStart = (maxWidth - frame.width) / 2,
                    captionTop = ExpandedFrameTop + frame.height + 18.dp,
                )
            }
        }
        // First, and always at this spot, so the native player is never torn down by a size change.
        MusicPlayerSurface(
            source = source,
            loadId = state.loadId,
            videoVisible = videoVisible,
            startPositionMs = state.startPositionMs,
            playWhenReady = state.playWhenReady,
            volume = state.volume,
            seekRequestId = seek?.id ?: 0L,
            seekRequestMs = seek?.positionMs ?: 0L,
            letterbox = big,
            modifier = surfaceModifier,
            onSnapshot = { id, snapshot -> if (id == player.state.value.loadId) player.onSnapshot(snapshot) },
            onEnded = { id -> if (id == player.state.value.loadId) player.onSurfaceEnded() },
            onError = { id -> if (id == player.state.value.loadId) player.onSurfaceError() },
        )
        if (big && view == MusicVideoView.Expanded) {
            ExpandedHeader(
                title = state.track?.title.orEmpty(),
                onCollapse = { MusicPlayback.setVideoView(MusicVideoView.Bar) },
                onFullscreen = { MusicPlayback.setVideoView(MusicVideoView.Fullscreen) },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        // The bar wears the playing artist's accent (the app's own until that artist has a palette).
        MusicAccentScope {
            MusicMiniPlayerBar(
                state = state,
                visible = !hidden && !collapsed && !corner,
                player = player,
                slim = musicBarSlim(state, slimRequested, nowPlayingOpen),
                modifier = Modifier.align(Alignment.BottomCenter),
                videoView = view,
                onSetVideoView = MusicPlayback::setVideoView,
                onOpenNowPlaying = { MusicPlayback.nowPlayingOpen.value = true },
                onCollapse = {
                    MusicPlayback.nowPlayingOpen.value = false
                    MusicPlayback.barCollapsed.value = true
                },
            )
            MusicCornerCard(
                state = state,
                visible = !hidden && !collapsed && corner,
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenNowPlaying = { MusicPlayback.nowPlayingOpen.value = true },
                onCollapse = {
                    MusicPlayback.nowPlayingOpen.value = false
                    MusicPlayback.barCollapsed.value = true
                },
            )
            AnimatedVisibility(
                visible = collapsed && !hidden && !realVideo,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = if (reduced) EnterTransition.None else fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
                exit = if (reduced) ExitTransition.None else fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
            ) {
                CollapsedChip(
                    title = state.track?.title.orEmpty(),
                    coverUrl = state.album?.thumbnailUrl,
                    playing = state.playWhenReady,
                    onExpand = { MusicPlayback.barCollapsed.value = false },
                    modifier = Modifier.padding(end = 28.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandedHeader(
    title: String,
    onCollapse: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(ExpandedHeaderHeight).background(Color.Black).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicTextChip("Collapse", onCollapse)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MusicTextChip("Fullscreen", onFullscreen)
    }
}

/** What is left of the bar while it is tucked away: the cover and title in a small pill. Click or Enter brings the bar back. */
@Composable
private fun CollapsedChip(
    title: String,
    coverUrl: String?,
    playing: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceCard.copy(alpha = 0.94f))
            .border(
                if (focused) BorderStroke(3.dp, colors.focusRing) else BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                shape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onExpand)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
            if (coverUrl != null) {
                NuvioAsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted)
            }
        }
        Text(
            if (title.isBlank()) "Now playing" else title,
            color = colors.textPrimary,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = if (playing) "Show player bar, playing" else "Show player bar, paused",
            tint = colors.accent,
        )
    }
}
