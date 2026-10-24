package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio

/**
 * An album: cover, a Play/Shuffle pair, the Audio/Video choice, and the track list.
 *
 * This is where playback starts. Play and Shuffle hand the album to [player]; a tap on a track
 * jumps inside the queue when this album is already loaded, and starts the album at that track
 * when it is not (see [musicTrackTap]). Flipping Audio/Video while this album plays switches the
 * same song at the same position; otherwise it only sets the mode the next Play starts in.
 */
@Composable
fun MusicAlbumScreen(
    browseId: String,
    onBack: () -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
    player: MusicPlayer = MusicPlayback.player,
) {
    val colors = MaterialTheme.nuvio.colors
    var attempt by remember { mutableStateOf(0) }
    val load = rememberMusicLoad(browseId, attempt) { catalog.album(browseId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 12.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
        when (load) {
            MusicLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            MusicLoad.Failed -> MusicMessage(
                text = "Couldn't load this album.",
                actionLabel = "Try again",
                onAction = { attempt++ },
            )
            is MusicLoad.Loaded -> AlbumContent(load.value, onArtistClick, player)
        }
    }
}

@Composable
private fun AlbumContent(
    album: MusicAlbum,
    onArtistClick: (String) -> Unit,
    player: MusicPlayer,
) {
    val colors = MaterialTheme.nuvio.colors
    val state by player.state.collectAsStateWithLifecycle()
    var chosenMode by rememberSaveable(album.browseId) { mutableStateOf(player.state.value.mode) }

    val loaded = state.holds(album)
    // While this album plays, the player's mode is the truth: a failed switch to video leaves it on audio.
    val mode = if (loaded) state.mode else chosenMode

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        item { AlbumHeader(album, onArtistClick) }
        item {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NuvioPrimaryButton(
                        text = when {
                            !loaded -> "Play"
                            state.playWhenReady -> "Pause"
                            else -> "Resume"
                        },
                        modifier = Modifier.width(140.dp),
                        height = 44.dp,
                        enabled = album.tracks.any { it.isAvailable },
                        onClick = {
                            if (loaded) player.togglePlayback()
                            else player.play(album, trackIndex = 0, mode = mode, shuffled = false)
                        },
                    )
                    ShuffleButton(
                        on = loaded && state.shuffled,
                        enabled = album.tracks.any { it.isAvailable },
                        onClick = {
                            if (loaded) player.setShuffled(!state.shuffled)
                            else player.play(album, trackIndex = randomPlayableIndex(album), mode = mode, shuffled = true)
                        },
                    )
                }
                MusicSegmented(
                    options = listOf(MusicPlayMode.Audio to "Audio", MusicPlayMode.Video to "Video"),
                    selected = mode,
                    onSelect = { picked ->
                        chosenMode = picked
                        if (loaded) player.switchMode(picked)
                    },
                )
                val note = when {
                    loaded && state.notice != null -> state.notice
                    mode == MusicPlayMode.Video -> "Tracks without a music video are skipped."
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loaded && state.notice != null) colors.warning else colors.textSecondary,
                    )
                }
            }
        }
        itemsIndexed(album.tracks) { index, track ->
            TrackRow(
                album = album,
                track = track,
                current = loaded && state.trackIndex == index,
                onClick = {
                    when (val tap = musicTrackTap(state, album, index, chosenMode)) {
                        is MusicTrackTap.JumpInQueue -> player.jumpTo(tap.trackIndex)
                        is MusicTrackTap.StartAlbum ->
                            player.play(album, trackIndex = tap.trackIndex, mode = tap.mode, shuffled = false)
                    }
                },
            )
        }
    }
}

@Composable
private fun AlbumHeader(album: MusicAlbum, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val artistId = album.artists.firstOrNull()?.browseId
    val minutes = album.tracks.sumOf { it.durationSeconds } / 60
    val meta = listOfNotNull(
        album.kind,
        album.year,
        "${album.tracks.size} track${if (album.tracks.size == 1) "" else "s"}",
        "$minutes min".takeIf { minutes > 0 },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        MusicCover(album.thumbnailUrl, album.title, size = 160.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (album.artists.isNotEmpty()) {
                Text(
                    text = album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (artistId != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (artistId != null) {
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable { onArtistClick(artistId) }
                    } else {
                        Modifier
                    },
                )
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ShuffleButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, if (on) colors.accent else colors.borderDefault, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (!enabled) colors.textDisabled else if (on) colors.accent else colors.textPrimary
        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text("Shuffle", style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

@Composable
private fun TrackRow(
    album: MusicAlbum,
    track: MusicTrack,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val albumArtists = album.artists.map { it.name }
    val trackArtists = track.artists.map { it.name }
    // Only when the song has someone the album does not (a feature), not on every row.
    val credit = trackArtists.takeIf { it.isNotEmpty() && it != albumArtists }?.joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = track.isAvailable, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = "Now playing", tint = colors.accent, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    text = track.trackNumber.takeIf { it > 0 }?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !track.isAvailable -> colors.textDisabled
                    current -> colors.accent
                    else -> colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (credit != null) {
                Text(
                    text = credit,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (track.isAvailable) colors.textSecondary else colors.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (track.durationSeconds > 0) formatMusicTime(track.durationSeconds * 1000L) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
        )
    }
}
