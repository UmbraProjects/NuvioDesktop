package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.playlist.MusicPlaylistTracks.forPlaylist
import com.nuvio.app.features.music.playlist.PlaylistPicker

/**
 * fork:music - the controls of the expanded and fullscreen video. Compose cannot draw over the native video surface,
 * so the bar used to sit under the picture, full size; this is a slim strip in its place, the video ending just above
 * it: a seek line along the top, transport and time on the left, what is playing in the middle, and on the right
 * volume, Add to playlist, shuffle and collapse.
 */
@Composable
internal fun MusicVideoControlStrip(
    state: MusicPlayerState,
    player: MusicPlayer,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    // The level to come back to after a mute.
    var unmutedVolume by remember { mutableIntStateOf(if (state.volume > 0) state.volume else 50) }
    Column(modifier.fillMaxWidth().height(MusicVideoStripHeight).background(VIDEO_STRIP_GROUND)) {
        SeekBar(
            fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeek = { player.seekTo((it * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            enabled = state.durationMs > 0L,
            onNudge = { d -> player.seekTo((state.positionMs + d * 5_000L).coerceIn(0L, state.durationMs)) },
        )
        // Centred in the space under the seek line rather than in the row (the line's touch area sits above it).
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: previous, play / pause, next, the time.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StripButton(Icons.Rounded.SkipPrevious, "Previous") { player.previous() }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { player.togglePlayback() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playWhenReady) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                StripButton(Icons.Rounded.SkipNext, "Next", enabled = state.hasNext) { player.next() }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${formatMusicTime(state.positionMs)} / ${formatMusicTime(state.durationMs)}",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
            // Middle: the cover, the title, the artist and album.
            Row(
                Modifier.widthIn(max = 520.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MusicCover(state.coverUrl, state.track?.title, size = 40.dp)
                Column(Modifier.widthIn(max = 460.dp)) {
                    Text(
                        state.track?.title.orEmpty(),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(subtitle(state), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Right: volume, add to playlist, shuffle, and the arrow back to the player (which also leaves fullscreen).
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                val muted = state.volume == 0
                StripButton(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Unmute" else "Mute") {
                    if (muted) player.setVolume(unmutedVolume) else { unmutedVolume = state.volume; player.setVolume(0) }
                }
                SeekBar(
                    fraction = state.volume / 100f,
                    onSeek = { player.setVolume((it * 100).toInt()) },
                    modifier = Modifier.width(96.dp),
                    onNudge = { d -> player.setVolume((state.volume + d * 5).coerceIn(0, 100)) },
                )
                // Grouped with shuffle: the two buttons about the queue, after the volume.
                state.track?.let { track ->
                    StripButton(Icons.Rounded.PlaylistAdd, "Add to playlist") { PlaylistPicker.request(listOf(track.forPlaylist(state.album))) }
                }
                StripButton(Icons.Rounded.Shuffle, if (state.shuffled) "Shuffle on" else "Shuffle", active = state.shuffled) {
                    player.setShuffled(!state.shuffled)
                }
                StripButton(Icons.Rounded.KeyboardArrowDown, "Back to the player", onClick = onCollapse)
            }
        }
    }
}

/** The strip's height: the seek line's touch area and the row of controls. */
internal val MusicVideoStripHeight: Dp = 84.dp

private val VIDEO_STRIP_GROUND = Color(0xFF0F0F10)

@Composable
private fun StripButton(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    // Above the button: the strip is at the bottom of the window, where a card under the cursor was pushed up over
    // the pointer and took the clicks meant for the button.
    NuvioPosterHoverTooltip(title = label, aboveControl = true) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.3f)
                    active -> colors.accent
                    else -> Color.White.copy(alpha = 0.9f)
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
