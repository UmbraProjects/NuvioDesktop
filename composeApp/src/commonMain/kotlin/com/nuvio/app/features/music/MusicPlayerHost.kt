package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Height of the bar; the app shell reserves this much room under its content while it shows. */
val MusicMiniPlayerHeight: Dp = 72.dp

private val BarThumbnailWidth = 128.dp
private val ExpandedHeaderHeight = 48.dp

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

    val source = state.source ?: return
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
                Modifier.align(Alignment.BottomStart).size(BarThumbnailWidth, MusicMiniPlayerHeight)
            view == MusicVideoView.Expanded ->
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = ExpandedHeaderHeight)
                    .fillMaxWidth()
                    .height((windowHeight - ExpandedHeaderHeight - MusicMiniPlayerHeight).coerceAtLeast(1.dp))
            else ->
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height((windowHeight - MusicMiniPlayerHeight).coerceAtLeast(1.dp))
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
        MusicMiniPlayerBar(
            state = state,
            visible = !hidden,
            player = player,
            modifier = Modifier.align(Alignment.BottomCenter),
            videoView = view,
            onSetVideoView = MusicPlayback::setVideoView,
            onOpenNowPlaying = { MusicPlayback.nowPlayingOpen.value = true },
        )
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
