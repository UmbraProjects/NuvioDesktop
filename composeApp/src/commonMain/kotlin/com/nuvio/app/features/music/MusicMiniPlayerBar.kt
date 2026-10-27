package com.nuvio.app.features.music

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

private val CoverSize = MusicBarCardHeight - 24.dp
private val VideoWidth = BarThumbnailWidth

/** Windows narrower than this drop the volume slider, the lyrics chip and the time labels. */
private val CompactBelow = 900.dp

/** How far one d-pad press on the progress bar jumps, in milliseconds. */
private const val NUDGE_MS = 5_000L

/** The side margin of the floating bar: 5% of the width, the same as the Music screens' rows. */
internal fun musicBarSideMargin(width: Dp): Dp = width * 0.05f

/**
 * The bar along the bottom of the app, drawn as a floating card: a large cover, the title and artist, a thick
 * progress bar with the elapsed and remaining time, transport and the audio/video switch. The card is tinted by the
 * playing artist's accent and takes a blurred wash of the cover. In video mode the picture is drawn over the card's
 * left end by [MusicPlayerHost], which owns the native surface; the card only leaves that room empty. The bar
 * reserves [MusicMiniPlayerHeight] (the card plus its bottom margin); when it is hidden it takes no room.
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
    onCollapse: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    // The picture only sits in the bar when there is a real video; a lyric video is audio.
    val video = state.mode == MusicPlayMode.Video && !state.lyricVideo
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(if (visible) MusicMiniPlayerHeight else 1.dp),
    ) {
        if (!visible) return@BoxWithConstraints

        val compact = maxWidth < CompactBelow
        val side = musicBarSideMargin(maxWidth)
        val shape = RoundedCornerShape(28.dp)
        val ground = lerp(colors.surfaceCard, colors.accent, 0.16f)
        val cover = state.album?.thumbnailUrl
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = side, end = side, bottom = MusicBarBottomMargin)
                .fillMaxWidth()
                .height(MusicBarCardHeight)
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = colors.accent.copy(alpha = 0.45f),
                    spotColor = colors.accent,
                )
                .clip(shape)
                .background(ground)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape),
        ) {
            // A wash of the cover, blurred and enlarged so its edges stay out of the card.
            if (cover != null) {
                NuvioAsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(48.dp)
                        .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.55f },
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(ground.copy(alpha = 0.90f), ground.copy(alpha = 0.62f))),
                ),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left end: the picture in video mode (drawn by the surface above), else the cover.
                // In a real video's thumbnail the host's native surface covers this box, so it is not a button then.
                Box(
                    Modifier.width(if (video && videoView == MusicVideoView.Bar) VideoWidth else if (video) 12.dp else CoverSize)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !video, onClick = onOpenNowPlaying),
                ) {
                    if (!video) {
                        if (cover != null) {
                            NuvioAsyncImage(
                                model = cover,
                                contentDescription = state.album?.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Column(Modifier.clickable(onClick = onOpenNowPlaying)) {
                        Text(
                            text = state.track?.title.orEmpty(),
                            color = colors.textPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val notice = state.notice
                        Text(
                            text = notice ?: subtitle(state),
                            color = if (notice != null) colors.warning else colors.textSecondary,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!compact) {
                            Text(formatMusicTime(state.positionMs), color = colors.textSecondary, fontSize = 16.sp, maxLines = 1)
                        }
                        SeekBar(
                            fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
                            onSeek = { fraction -> player.seekTo((fraction * state.durationMs).toLong()) },
                            modifier = Modifier.weight(1f).padding(horizontal = if (compact) 0.dp else 14.dp),
                            enabled = state.durationMs > 0L,
                            onNudge = { direction ->
                                player.seekTo((state.positionMs + direction * NUDGE_MS).coerceIn(0L, state.durationMs))
                            },
                        )
                        if (!compact) {
                            val left = (state.durationMs - state.positionMs).coerceAtLeast(0L)
                            Text("-" + formatMusicTime(left), color = colors.textSecondary, fontSize = 16.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Transport(state, player, !compact, onOpenNowPlaying, videoView, onSetVideoView)
                if (!compact) {
                    Icon(Icons.Rounded.VolumeUp, null, tint = colors.textSecondary, modifier = Modifier.padding(start = 8.dp).size(26.dp))
                    SeekBar(
                        fraction = state.volume / 100f,
                        onSeek = { player.setVolume((it * 100f).toInt()) },
                        modifier = Modifier.width(110.dp).padding(horizontal = 8.dp),
                        onNudge = { direction -> player.setVolume((state.volume + direction * 5).coerceIn(0, 100)) },
                    )
                }
                // Tuck the bar away; a real video's picture lives in the bar, so it stays then.
                if (onCollapse != null && !video) {
                    IconButton(onClick = onCollapse, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Hide player bar", tint = colors.textSecondary, modifier = Modifier.size(30.dp))
                    }
                }
                IconButton(onClick = { player.stop() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close player", tint = colors.textSecondary, modifier = Modifier.size(28.dp))
                }
            }
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
    IconButton(onClick = { player.previous() }, modifier = Modifier.size(52.dp)) {
        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = colors.textPrimary, modifier = Modifier.size(32.dp))
    }
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(colors.accentFill)
            .clickable { player.togglePlayback() },
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoading && state.playWhenReady) {
            CircularProgressIndicator(Modifier.size(28.dp), color = colors.onAccent, strokeWidth = 3.dp)
        } else {
            Icon(
                imageVector = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playWhenReady) "Pause" else "Play",
                tint = colors.onAccent,
                modifier = Modifier.size(36.dp),
            )
        }
    }
    IconButton(onClick = { player.next() }, enabled = state.hasNext, modifier = Modifier.size(52.dp)) {
        Icon(
            Icons.Rounded.SkipNext,
            contentDescription = "Next",
            tint = if (state.hasNext) colors.textPrimary else colors.textDisabled,
            modifier = Modifier.size(32.dp),
        )
    }
    if (showLyrics) MusicTextChip("Lyrics", onOpenNowPlaying)
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
internal fun MusicTextChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.nuvio.colors.textSecondary,
        fontSize = 16.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) colors.accent else colors.textSecondary,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * A thick, rounded bar you can click, drag or step with the d-pad. [fraction] and what [onSeek] receives are both
 * 0..1; [onNudge] gets -1 or +1 for Left and Right. Focused or dragged, the track grows and a handle appears.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onNudge: ((Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    // While dragging, follow the finger rather than the (older) playback position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val active = focused || dragging != null
    val trackHeight by animateDpAsState(if (active) 10.dp else 6.dp)
    val track = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(28.dp)
            .focusable(enabled = enabled, interactionSource = source)
            .onKeyEvent { event ->
                if (onNudge == null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onNudge(-1); true }
                    Key.DirectionRight -> { onNudge(1); true }
                    else -> false
                }
            }
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
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(track).background(Color.White.copy(alpha = 0.20f)))
        Box(Modifier.fillMaxWidth(shown).height(trackHeight).clip(track).background(colors.accent))
        if (active && enabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(shown.coerceIn(0.001f, 0.999f)))
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.weight((1f - shown).coerceIn(0.001f, 0.999f)))
            }
        }
    }
}
