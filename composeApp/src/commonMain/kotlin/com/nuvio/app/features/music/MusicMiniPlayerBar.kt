package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

private val ArtSize = MusicMiniPlayerHeight
private val VideoWidth = 128.dp

/** Windows narrower than this drop the volume slider and the time label. */
private val CompactBelow = 720.dp

/**
 * The bar along the bottom of the app: what is playing, transport, a seek bar, the audio/video
 * switch. In video mode the picture is drawn over the bar's left end by [MusicPlayerHost], which
 * owns the native surface; the bar only leaves that room empty. When the bar is hidden it takes no room.
 */
@Composable
fun MusicMiniPlayerBar(
    state: MusicPlayerState,
    visible: Boolean,
    player: MusicPlayer,
    modifier: Modifier = Modifier,
    videoView: MusicVideoView = MusicVideoView.Bar,
    onSetVideoView: (MusicVideoView) -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
) {
    val colors = MaterialTheme.nuvio.colors
    // The picture only sits in the bar when there is a real video; a lyric video is audio.
    val video = state.mode == MusicPlayMode.Video && !state.lyricVideo
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (visible) MusicMiniPlayerHeight else 1.dp)
            .background(colors.surfaceCard),
    ) {
        if (!visible) return@Box

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < CompactBelow
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left end: the picture in video mode (drawn by the surface above), else the cover.
                // In a real video's thumbnail the host's native surface covers this box, so it is not a button then.
                Box(
                    Modifier.width(if (video && videoView == MusicVideoView.Bar) VideoWidth else if (video) 12.dp else ArtSize)
                        .fillMaxHeight()
                        .clickable(enabled = !video, onClick = onOpenNowPlaying),
                ) {
                    if (!video) {
                        val art = state.album?.thumbnailUrl
                        if (art != null) {
                            NuvioAsyncImage(
                                model = art,
                                contentDescription = state.album?.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable(onClick = onOpenNowPlaying), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = state.track?.title.orEmpty(),
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val notice = state.notice
                    Text(
                        text = notice ?: subtitle(state),
                        color = if (notice != null) colors.warning else colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact) {
                    Text(
                        text = "${formatMusicTime(state.positionMs)} / ${formatMusicTime(state.durationMs)}",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Transport(state, player, !compact, onOpenNowPlaying, videoView, onSetVideoView)
                if (!compact) {
                    Icon(Icons.Rounded.VolumeUp, null, tint = colors.textMuted, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    SeekBar(
                        fraction = state.volume / 100f,
                        onSeek = { player.setVolume((it * 100f).toInt()) },
                        modifier = Modifier.width(88.dp).padding(horizontal = 8.dp),
                    )
                }
                IconButton(onClick = { player.stop() }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close player", tint = colors.textSecondary)
                }
            }
            // Progress runs along the top edge, over the picture and the cover alike.
            SeekBar(
                fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
                onSeek = { fraction -> player.seekTo((fraction * state.durationMs).toLong()) },
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                enabled = state.durationMs > 0L,
            )
        }
    }
}

private fun subtitle(state: MusicPlayerState): String {
    val artist = (state.track?.artists?.takeIf { it.isNotEmpty() } ?: state.album?.artists.orEmpty())
        .joinToString(", ") { it.name }
    return listOf(artist, state.album?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun Transport(
    state: MusicPlayerState,
    player: MusicPlayer,
    showLyrics: Boolean,
    onOpenNowPlaying: () -> Unit,
    videoView: MusicVideoView,
    onSetVideoView: (MusicVideoView) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val video = state.mode == MusicPlayMode.Video
    ToggleIcon(
        icon = Icons.Rounded.Shuffle,
        description = if (state.shuffled) "Shuffle on" else "Shuffle off",
        active = state.shuffled,
        onClick = { player.setShuffled(!state.shuffled) },
    )
    IconButton(onClick = { player.previous() }) {
        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = colors.textPrimary)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.accentFill)
            .clickable { player.togglePlayback() },
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoading && state.playWhenReady) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playWhenReady) "Pause" else "Play",
                tint = colors.onAccent,
            )
        }
    }
    IconButton(onClick = { player.next() }, enabled = state.hasNext) {
        Icon(
            Icons.Rounded.SkipNext,
            contentDescription = "Next",
            tint = if (state.hasNext) colors.textPrimary else colors.textDisabled,
        )
    }
    if (showLyrics) MusicTextChip("Lyrics", onClick = onOpenNowPlaying)
    if (video && !state.lyricVideo) {
        when (videoView) {
            MusicVideoView.Bar -> MusicTextChip("Expand") { onSetVideoView(MusicVideoView.Expanded) }
            MusicVideoView.Expanded -> MusicTextChip("Fullscreen") { onSetVideoView(MusicVideoView.Fullscreen) }
            MusicVideoView.Fullscreen -> MusicTextChip("Exit fullscreen") { onSetVideoView(MusicVideoView.Expanded) }
        }
    }
    // Shows the mode a tap switches to.
    ToggleIcon(
        icon = if (video) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
        description = if (video) "Switch to audio" else "Switch to video",
        active = false,
        onClick = { player.switchMode(if (video) MusicPlayMode.Audio else MusicPlayMode.Video) },
    )
}

/** A small text button for the bar and the expanded video's header. */
@Composable
internal fun MusicTextChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.nuvio.colors.textSecondary,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = if (active) colors.accent else colors.textSecondary)
    }
}

/** A thin bar you can click or drag. [fraction] and what [onSeek] receives are both 0..1. */
@Composable
private fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.nuvio.colors
    // While dragging, follow the finger rather than the (older) playback position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(14.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectDragGestures(
                    onDragStart = { offset: Offset ->
                        last = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = last
                    },
                    onDrag = { change, _ ->
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragging = last
                    },
                    onDragEnd = {
                        onSeek(last)
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                )
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f)))
        Box(Modifier.fillMaxWidth(shown).height(3.dp).background(colors.accent))
    }
}
