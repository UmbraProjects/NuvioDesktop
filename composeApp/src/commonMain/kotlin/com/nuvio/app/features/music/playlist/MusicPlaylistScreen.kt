package com.nuvio.app.features.music.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DetailIconAction
import com.nuvio.app.features.music.LocalMusicBarInset
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicEqualiser
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicPlayer
import com.nuvio.app.features.music.formatMusicTime
import com.nuvio.app.features.settings.settingsSkyBackground

/**
 * fork:music-playlists - one playlist: its cover mosaic and name over the round buttons the album page uses (Play,
 * Shuffle, Audio, Video, Rename, Delete), then its songs, each with its own cover, artist and album (the album opens its
 * page). Hovering a song shows buttons to move it up or down and to take it out.
 */
@Composable
fun MusicPlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    player: MusicPlayer = MusicPlayback.player,
) {
    val colors = MaterialTheme.nuvio.colors
    val playlists by MusicPlaylistRepository.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val state by player.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var chosenMode by rememberSaveable(playlistId) { mutableStateOf(player.state.value.mode) }

    Box(modifier.fillMaxSize().background(colors.background).settingsSkyBackground(colors.background, colors.accent)) {
        if (playlist == null) {
            MusicMessage("This playlist is gone.", actionLabel = "Back", onAction = onBack)
        } else {
            val loaded = state.album?.browseId == playlistAlbumId(playlist.id)
            val mode = if (loaded) state.mode else chosenMode
            fun start(index: Int, shuffled: Boolean) {
                if (playlist.tracks.isEmpty()) return
                player.play(playlist.asAlbum(), trackIndex = index, mode = mode, shuffled = shuffled)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 64.dp, bottom = LocalMusicBarInset.current + 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "header") {
                    Column(
                        Modifier.widthIn(max = 720.dp).padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlaylistCover(playlist.covers, 220.dp)
                        Text(
                            listOfNotNull("Playlist", playlistSummary(playlist), playlist.importedFrom?.let { "from $it" })
                                .joinToString(" · ").uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.6.sp,
                        )
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Serif,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val playing = loaded && state.playWhenReady
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                            RoundAction(if (!loaded) "Play" else if (playing) "Pause" else "Resume", if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, playing) {
                                if (loaded) player.togglePlayback() else start(0, shuffled = false)
                            }
                            RoundAction(if (loaded && state.shuffled) "Shuffle on" else "Shuffle", Icons.Rounded.Shuffle, loaded && state.shuffled) {
                                if (loaded) player.setShuffled(!state.shuffled)
                                else if (playlist.tracks.isNotEmpty()) start(playlist.tracks.indices.random(), shuffled = true)
                            }
                            RoundAction("Audio", Icons.Rounded.Headphones, mode == MusicPlayMode.Audio) {
                                chosenMode = MusicPlayMode.Audio
                                if (loaded) player.switchMode(MusicPlayMode.Audio)
                            }
                            RoundAction("Video", Icons.Rounded.Movie, mode == MusicPlayMode.Video) {
                                chosenMode = MusicPlayMode.Video
                                if (loaded) player.switchMode(MusicPlayMode.Video)
                            }
                            RoundAction("Rename", Icons.Rounded.Edit, false) { renaming = true }
                            RoundAction("Delete playlist", Icons.Rounded.Delete, false) { deleting = true }
                        }
                        if (playlist.tracks.isEmpty()) {
                            Text(
                                "No songs yet. Add them from an album's songs, or from the player's ⋯ menu while one plays.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                itemsIndexed(playlist.tracks, key = { _, t -> t.videoId }) { index, track ->
                    PlaylistTrackRow(
                        track = track,
                        number = index + 1,
                        current = loaded && state.track?.videoId == track.videoId,
                        first = index == 0,
                        last = index == playlist.tracks.lastIndex,
                        onClick = { if (loaded) player.jumpTo(index) else start(index, shuffled = false) },
                        onAlbum = track.albumBrowseId?.let { id -> { onAlbumClick(id) } },
                        onArtist = track.artists.firstOrNull()?.browseId?.let { id -> { onArtistClick(id) } },
                        onUp = { MusicPlaylistRepository.move(playlist.id, index, index - 1) },
                        onDown = { MusicPlaylistRepository.move(playlist.id, index, index + 1) },
                        onRemove = { MusicPlaylistRepository.remove(playlist.id, index) },
                    )
                }
            }
            if (renaming) RenamePlaylistDialog(playlist.name, onDismiss = { renaming = false }) { MusicPlaylistRepository.rename(playlist.id, it) }
            if (deleting) {
                NuvioAlertDialog(
                    onDismissRequest = { deleting = false },
                    title = { Text("Delete “${playlist.name}”?") },
                    text = { Text("The playlist goes; the songs stay on YouTube Music.") },
                    confirmButton = {
                        TextButton(onClick = {
                            deleting = false
                            MusicPlaylistRepository.delete(playlist.id)
                            onBack()
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
                )
            }
        }
        Box(Modifier.padding(start = 16.dp, top = 12.dp)) { NuvioBackButton(onClick = onBack) }
    }
}

@Composable
private fun RoundAction(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    NuvioPosterHoverTooltip(title = label) {
        DetailIconAction(label = label, icon = icon, active = active, size = 56.dp, onClick = onClick)
    }
}

@Composable
private fun PlaylistTrackRow(
    track: PlaylistTrack,
    number: Int,
    current: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onAlbum: (() -> Unit)?,
    onArtist: (() -> Unit)?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) colors.accent.copy(alpha = 0.12f) else if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            if (current) MusicEqualiser(colors.accent)
            else Text("$number", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        }
        MusicCover(track.coverUrl, track.title, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val artist = track.artists.joinToString(", ") { it.name }
                if (artist.isNotEmpty()) {
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(if (onArtist != null) Modifier.clickable(onClick = onArtist) else Modifier),
                    )
                }
                track.albumTitle?.let { album ->
                    if (artist.isNotEmpty()) Text("·", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                    Text(
                        album,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(if (onAlbum != null) Modifier.clickable(onClick = onAlbum) else Modifier),
                    )
                }
            }
        }
        if (hovered) {
            if (!first) SmallAction(Icons.Rounded.KeyboardArrowUp, "Move up", onUp)
            if (!last) SmallAction(Icons.Rounded.KeyboardArrowDown, "Move down", onDown)
            SmallAction(Icons.Rounded.Close, "Remove from the playlist", onRemove)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            if (track.durationSeconds > 0) formatMusicTime(track.durationSeconds * 1000L) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    NuvioPosterHoverTooltip(title = label) {
        Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun RenamePlaylistDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = { NuvioTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
