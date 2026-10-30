package com.nuvio.app.features.music

import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Composable
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




/**
 * Plays [MusicPlayback.player] and draws the mini-player bar. Place it once, at the app root, over
 * the content, aligned to the bottom (it fills the width and sizes itself). The shell pads its own
 * content by [MusicMiniPlayerHeight] while [MusicPlayerState.isActive] so nothing sits under the bar.
 *
 * The host owns the one native video surface and resizes it rather than moving it: a thumbnail at
 * the bar's left end, the lyric view's frame above the lyrics, or the whole window ([MusicVideoView.Fullscreen]). The surface keeps one place in the composition, so changing size
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
                MusicMiniPlayerBar(
                    state = gone,
                    visible = false,
                    player = player,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    slim = musicBarSlim(gone, slimRequested, nowPlayingOpen),
                    initiallyShown = !musicBarCorner(gone, slimRequested, nowPlayingOpen, barStyle) &&
                        !musicPlayerDocked(gone, nowPlayingOpen, barStyle),
                    areaHeight = maxHeight,
                )
                MusicCornerCard(
                    state = gone,
                    visible = false,
                    player = player,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    initiallyShown = musicBarCorner(gone, slimRequested, nowPlayingOpen, barStyle),
                    areaHeight = maxHeight,
                )
            }
        }
        return
    }
    lastActive[0] = state
    val corner = musicBarCorner(state, slimRequested, nowPlayingOpen, barStyle)
    val docked = musicPlayerDocked(state, nowPlayingOpen, barStyle)
    val videoVisible = realVideo && !hidden
    // Docked, a video in the Bar view has no thumbnail to show in: the surface is parked out of sight until it is
    // expanded. It still plays the video stream (a surface told it is not visible opens the audio alone, and
    // expanding then showed a black frame), so expanding only resizes it.
    // The lyric view's frame, while that view is open on a real video: the video draws there, above the lyrics.
    val inlineFrame by MusicPlayback.inlineVideoFrame.collectAsStateWithLifecycle()
    val inline = videoVisible && view == MusicVideoView.Bar && nowPlayingOpen && inlineFrame != null
    // The native video draws over the app's own popups, so while the "Add to playlist" picker is open the picture
    // steps out of sight (it keeps playing) and comes back when the picker closes.
    val pickerOpen = com.nuvio.app.features.music.playlist.PlaylistPicker.pending.collectAsStateWithLifecycle().value != null
    val videoParked = (docked && view == MusicVideoView.Bar && !inline) || pickerOpen
    val big = videoVisible && view == MusicVideoView.Fullscreen
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val keys = remember { FocusRequester() }
    LaunchedEffect(big) {
        if (big) runCatching { keys.requestFocus() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    big -> Modifier.fillMaxHeight().background(Color.Black)
                    // The whole window, see-through, so the surface can sit on the lyric view's frame.
                    inline -> Modifier.fillMaxHeight()
                    else -> Modifier
                },
            )
            .onGloballyPositioned { hostOrigin = it.positionInRoot() }
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!big || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        MusicPlayback.setVideoView(MusicVideoView.Bar)
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
        val barPlacement by MusicPlayback.barPlacement.collectAsStateWithLifecycle()
        val surfaceModifier = when {
            !videoVisible || videoParked -> Modifier.align(Alignment.BottomStart).size(1.dp)
            inline -> {
                val frame = inlineFrame!!
                val density = LocalDensity.current
                Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset((frame.left - hostOrigin.x).roundToInt(), (frame.top - hostOrigin.y).roundToInt()) }
                    .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
            }
            view == MusicVideoView.Bar -> {
                // Inset 12dp inside the bar's left end, wherever the bar was dragged to.
                val bar = musicBarFrame(maxWidth, windowHeight, MusicBarCardHeight, barPlacement)
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = bar.left + 12.dp, y = bar.dy)
                    .padding(bottom = MusicBarBottomMargin + 12.dp)
                    .size(BarThumbnailWidth, MusicBarCardHeight - 24.dp)
            }
            else ->
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height((windowHeight - MusicVideoStripHeight).coerceAtLeast(1.dp))
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
            letterbox = big || inline,
            modifier = surfaceModifier,
            onSnapshot = { id, snapshot -> if (id == player.state.value.loadId) player.onSnapshot(snapshot) },
            onEnded = { id -> if (id == player.state.value.loadId) player.onSurfaceEnded() },
            onError = { id -> if (id == player.state.value.loadId) player.onSurfaceError() },
        )
        // The bar and the card wear the app's own glass and accent, like the rest of the app.
        run {
            MusicMiniPlayerBar(
                state = state,
                // The big video views have their own slim strip instead (below).
                // The big video has its own strip; the lyric view is its own player. The bar steps aside for both.
                visible = !hidden && !collapsed && !corner && !docked && !big && !nowPlayingOpen,
                videoInline = inline,
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
                areaHeight = windowHeight,
            )
            MusicCornerCard(
                state = state,
                visible = !hidden && !collapsed && corner && !docked && !nowPlayingOpen,
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenNowPlaying = { MusicPlayback.nowPlayingOpen.value = true },
                onCollapse = {
                    MusicPlayback.nowPlayingOpen.value = false
                    MusicPlayback.barCollapsed.value = true
                },
                areaHeight = windowHeight,
            )
            // fork:music - the expanded and fullscreen video's own controls, under the picture.
            if (big) {
                MusicVideoControlStrip(
                    state = state,
                    player = player,
                    fullscreen = view == MusicVideoView.Fullscreen,
                    onFullscreen = { MusicPlayback.setVideoView(MusicVideoView.Bar) },
                    onCollapse = { MusicPlayback.setVideoView(MusicVideoView.Bar) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            // The dock: the player itself in the Dock style, or where another style was minimised to. It can be dragged
            // anywhere (a double-click puts it back) and still opens on hover.
            val dockPlacement by MusicPlayback.dockPlacement.collectAsStateWithLifecycle()
            val areaWidth = maxWidth
            AnimatedVisibility(
                visible = ((collapsed && !realVideo) || docked) && !hidden && !big && !nowPlayingOpen,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = dockPlacement.dx, y = dockPlacement.dy)
                    .musicPlayerDrag(MusicPlayback.dockPlacement) { clampDockPlacement(areaWidth, windowHeight, it) },
                enter = if (reduced) EnterTransition.None else fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
                exit = if (reduced) ExitTransition.None else fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
            ) {
                MusicDockNotch(
                    state = state,
                    player = player,
                    // Minimised, the arrow brings the bar or card back; as the player itself it opens Now Playing, or
                    // closes it when it is already open.
                    onRestore = {
                        if (collapsed) MusicPlayback.barCollapsed.value = false
                        else MusicPlayback.nowPlayingOpen.value = !MusicPlayback.nowPlayingOpen.value
                    },
                    onExpandVideo = if (realVideo && !inline) ({ MusicPlayback.showVideo() }) else null,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
    }
}

