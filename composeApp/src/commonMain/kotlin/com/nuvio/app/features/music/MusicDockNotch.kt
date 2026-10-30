package com.nuvio.app.features.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.musicGlass
import kotlinx.coroutines.delay

/*
 * fork:music
 *
 * The minimised player: a small glass notch at the bottom of the window, so the rest of the app can
 * be used while the music plays. Hovering it (or giving it keyboard focus) opens it into a docked
 * panel with what is playing, the time, play / pause and skip, and the next four tracks. Clicking
 * the notch, or the panel's restore button, brings the full player back.
 */

/** How long the pointer may leave the panel before it folds back, so a brush past its edge does not shut it. */
private const val FOLD_DELAY_MS = 350L

private const val UP_NEXT_COUNT = 4

/** Left / Right on the dock's seek bar move this far. */
private const val DOCK_NUDGE_MS = 5_000L

@Composable
internal fun MusicDockNotch(
    state: MusicPlayerState,
    player: MusicPlayer,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while a real video plays: the open dock offers to show it. */
    onExpandVideo: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    // Opens at once on hover or focus; folds back a moment after both are gone.
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focused) {
        if (hovered || focused) {
            open = true
        } else {
            delay(FOLD_DELAY_MS)
            open = false
        }
    }
    val shape = RoundedCornerShape(if (open) 22.dp else 20.dp) // the folded notch is 40dp tall: a full pill
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(NuvioGlass.night(colors.background, colors.accent))
            .musicGlass(shape, keyFocused = focused)
            .hoverable(source)
            .focusable(interactionSource = source),
    ) {
        AnimatedContent(
            targetState = open,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "dockNotch",
        ) { expanded ->
            if (expanded) DockPanel(state, player, onRestore, onExpandVideo) else Notch(state, onRestore)
        }
    }
}

/** Folded: a cover dot, the title and a small arrow, the whole pill one restore button. */
@Composable
private fun Notch(state: MusicPlayerState, onRestore: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        Modifier
            .height(40.dp)
            .widthIn(min = 180.dp, max = 320.dp)
            .clickable(onClick = onRestore)
            // The round cover sits inside the pill's curve with the same gap all round, clear of the edge.
            .padding(start = 7.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Cover(state.coverUrl, size = 26, round = true)
        Text(
            state.track?.title.orEmpty().ifBlank { "Now playing" },
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fills the pill, so the arrow always sits at its right end whatever the title's length.
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = if (state.playWhenReady) "Show the player, playing" else "Show the player, paused",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Open: what is coming up on top (the panel grows upwards from the notch, so the list sits above
 * it), then the track, the time and the controls at the bottom, where the notch was.
 */
@Composable
private fun DockPanel(state: MusicPlayerState, player: MusicPlayer, onRestore: () -> Unit, onExpandVideo: (() -> Unit)?) {
    val colors = MaterialTheme.nuvio.colors
    val upNext = remember(state.track, state.trackIndex, state.shuffled) { player.upNext(UP_NEXT_COUNT) }
    Column(Modifier.width(440.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Up next", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        if (upNext.isEmpty()) {
            Text(
                if (state.hasNext) "The next album starts after this one" else "Nothing after this track",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        upNext.forEach { track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    formatMusicTime(track.durationSeconds * 1000L),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(NuvioGlass.EdgeColor))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(state.coverUrl, size = 56)
            Column(Modifier.weight(1f)) {
                Text(
                    state.track?.title.orEmpty().ifBlank { "Now playing" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatMusicTime(state.positionMs)} / ${formatMusicTime(state.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            DockButton(
                icon = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (state.playWhenReady) "Pause" else "Play",
                filled = true,
            ) { player.togglePlayback() }
            DockButton(Icons.Rounded.SkipNext, "Next", enabled = state.hasNext) { player.next() }
            onExpandVideo?.let { DockButton(Icons.Rounded.OpenInFull, "Expand video", onClick = it) }
            state.track?.let { track ->
                DockButton(Icons.Rounded.PlaylistAdd, "Add to playlist") {
                    com.nuvio.app.features.music.playlist.PlaylistPicker.request(
                        listOf(with(com.nuvio.app.features.music.playlist.MusicPlaylistTracks) { track.forPlaylist(state.album) }),
                    )
                }
            }
            DockButton(Icons.Rounded.KeyboardArrowUp, "Show the player") { onRestore() }
        }
        // The progress under the header: the bar's own seek bar, so a click, drag or Left/Right moves the song.
        SeekBar(
            fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeek = { fraction -> player.seekTo((fraction * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.durationMs > 0L,
            onNudge = { direction -> player.seekTo((state.positionMs + direction * DOCK_NUDGE_MS).coerceIn(0L, state.durationMs)) },
        )
    }
}

@Composable
private fun Cover(url: String?, size: Int, round: Boolean = false) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier.size(size.dp).clip(if (round) CircleShape else RoundedCornerShape((size / 5).dp)).background(colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            NuvioAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(colors.accentFill) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                filled -> colors.onAccent
                enabled -> colors.textPrimary
                else -> colors.textDisabled
            },
            modifier = Modifier.size(22.dp),
        )
    }
}
