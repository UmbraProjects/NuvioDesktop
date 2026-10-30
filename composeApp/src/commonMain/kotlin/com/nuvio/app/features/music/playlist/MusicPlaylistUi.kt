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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.library.musicGlass
import kotlinx.coroutines.launch

/*
 * fork:music-playlists - the shared pieces: a playlist's cover, the Library's Playlists view, the "Add to playlist"
 * picker and the import dialog.
 */

/** Up to four covers as a 2x2 mosaic (one cover fills the tile; none shows a list glyph). */
@Composable
internal fun PlaylistCover(covers: List<String>, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(size * 0.06f)
    Box(modifier.size(size).clip(shape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
        when {
            covers.isEmpty() -> Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(size * 0.4f))
            covers.size < 4 -> NuvioAsyncImage(covers.first(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Column {
                covers.chunked(2).forEach { row ->
                    Row {
                        row.forEach { url ->
                            NuvioAsyncImage(url, null, Modifier.size(size / 2), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
    }
}

/** The Library's Playlists view: a tile to make one, a tile to import one, then the playlists, newest first. */
@Composable
internal fun PlaylistsView(onOpenPlaylist: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val playlists by MusicPlaylistRepository.playlists.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PLAYLIST_TILE),
        modifier = modifier,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "new") {
            ActionTile(Icons.Rounded.Add, "New playlist", "Add songs from any album") {
                onOpenPlaylist(MusicPlaylistRepository.create().id)
            }
        }
        item(key = "import") {
            ActionTile(Icons.Rounded.Download, "Import", "From YouTube Music, Spotify, Apple Music or a list") { importing = true }
        }
        items(playlists, key = { it.id }) { playlist ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Column(
                Modifier
                    .musicGlass(RoundedCornerShape(16.dp), raised = hovered)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onOpenPlaylist(playlist.id) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // As wide as the cell, like the New playlist and Import tiles beside it.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) { PlaylistCover(playlist.covers, maxWidth) }
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(playlistSummary(playlist), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
    if (importing) ImportPlaylistDialog(onDismiss = { importing = false }, onOpen = { importing = false; onOpenPlaylist(it) })
}

private val PLAYLIST_TILE = 220.dp

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier
            .musicGlass(RoundedCornerShape(16.dp), raised = hovered)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(56.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "24 songs · 1 hr 32 min". */
internal fun playlistSummary(playlist: MusicPlaylist): String {
    val n = playlist.tracks.size
    val minutes = playlist.totalSeconds / 60
    val length = when {
        minutes <= 0 -> null
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
    return listOfNotNull("$n song${if (n == 1) "" else "s"}", length).joinToString(" · ")
}

/**
 * "Add to playlist": the playlists to pick from, or a name for a new one. [tracks] go in at the end; songs a playlist
 * already holds are left out. [onDone] gets a line saying what happened, for the caller to show.
 */
@Composable
internal fun AddToPlaylistDialog(tracks: List<PlaylistTrack>, onDismiss: () -> Unit, onDone: (String) -> Unit = {}) {
    val colors = MaterialTheme.nuvio.colors
    val playlists by MusicPlaylistRepository.playlists.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    fun addTo(id: String, name: String) {
        val added = MusicPlaylistRepository.add(id, tracks)
        onDone(
            when {
                added == 0 -> "Already in $name"
                tracks.size == 1 -> "Added to $name"
                else -> "Added $added song${if (added == 1) "" else "s"} to $name"
            },
        )
        onDismiss()
    }
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size == 1) "Add “${tracks.first().title}” to a playlist" else "Add ${tracks.size} songs to a playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (playlists.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            val source = remember { MutableInteractionSource() }
                            val hovered by source.collectIsHoveredAsState()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (hovered) colors.surfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
                                    .hoverable(source)
                                    .clickable(interactionSource = source, indication = null) { addTo(playlist.id, playlist.name) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PlaylistCover(playlist.covers, 44.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(playlistSummary(playlist), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }
                Text("New playlist", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                NuvioTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = freshPlaylistName(playlists),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val made = MusicPlaylistRepository.create(newName, tracks)
                onDone("Added to ${made.name}")
                onDismiss()
            }) { Text("Create and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Import: a link (YouTube Music, YouTube, Spotify, Apple Music) or a pasted list. Shows progress while songs are found
 * on YouTube Music, then what came in and what did not, with a button to open the new playlist.
 */
@Composable
internal fun ImportPlaylistDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    var text by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var made by remember { mutableStateOf<MusicPlaylist?>(null) }
    val recognised = remember(text) { recogniseImport(text) }
    NuvioAlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Import a playlist") },
        maxWidth = 640.dp,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val done = outcome) {
                    null -> {
                        Text(
                            "Paste a playlist or album link from YouTube Music, YouTube, Spotify or Apple Music, or a list of songs, one “Artist - Title” per line.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        NuvioTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "https://open.spotify.com/playlist/…",
                            singleLine = false,
                            minLines = 3,
                            maxLines = 10,
                            enabled = progress == null,
                        )
                        Text(
                            when (recognised) {
                                null -> if (text.isBlank()) "" else "Nothing to import in that."
                                is ImportSource.YouTube -> "A YouTube playlist: read as it is."
                                is ImportSource.Spotify -> "A Spotify ${recognised.kind}: each song is found on YouTube Music (up to 100)."
                                is ImportSource.AppleMusic -> "An Apple Music page: each song is found on YouTube Music."
                                is ImportSource.TextList -> "${recognised.lines.size} song${if (recognised.lines.size == 1) "" else "s"}: each is found on YouTube Music."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        progress?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                        }
                    }
                    is ImportOutcome.Failed -> Text(done.message, style = MaterialTheme.typography.bodyMedium, color = colors.warning)
                    is ImportOutcome.Ready -> {
                        Text(
                            "“${made?.name ?: done.name}”: ${done.tracks.size} song${if (done.tracks.size == 1) "" else "s"} from ${done.source}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        if (done.missed.isNotEmpty()) {
                            Text(
                                "${done.missed.size} couldn't be found on YouTube Music:",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                                items(done.missed) { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val done = outcome) {
                null -> TextButton(
                    enabled = recognised != null && progress == null,
                    onClick = {
                        val source = recognised ?: return@TextButton
                        scope.launch {
                            val result = runImport(source, catalog) { progress = it }
                            progress = null
                            if (result is ImportOutcome.Ready) {
                                made = MusicPlaylistRepository.create(result.name, result.tracks, importedFrom = result.source)
                            }
                            outcome = result
                        }
                    },
                ) { Text("Import") }
                is ImportOutcome.Ready -> TextButton(onClick = { made?.let { onOpen(it.id) } ?: onDismiss() }) { Text("Open playlist") }
                is ImportOutcome.Failed -> TextButton(onClick = { outcome = null }) { Text("Try again") }
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onDismiss) { Text(if (outcome is ImportOutcome.Ready) "Close" else "Cancel") }
        },
    )
}

/**
 * The one "Add to playlist" picker, opened from anywhere (an album's songs, the player's menu, the dock) through
 * [request]; [PlaylistPickerHost] at the app root draws it and then a short line saying where the songs went.
 */
object PlaylistPicker {
    internal val pending = kotlinx.coroutines.flow.MutableStateFlow<List<PlaylistTrack>?>(null)
    internal val toast = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    internal fun request(tracks: List<PlaylistTrack>) {
        if (tracks.isNotEmpty()) pending.value = tracks
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.PlaylistPickerHost() {
    val colors = MaterialTheme.nuvio.colors
    val pending by PlaylistPicker.pending.collectAsStateWithLifecycle()
    val toast by PlaylistPicker.toast.collectAsStateWithLifecycle()
    pending?.let { tracks ->
        AddToPlaylistDialog(tracks, onDismiss = { PlaylistPicker.pending.value = null }, onDone = { PlaylistPicker.toast.value = it })
    }
    toast?.let { line ->
        androidx.compose.runtime.LaunchedEffect(line) {
            kotlinx.coroutines.delay(2_500)
            if (PlaylistPicker.toast.value == line) PlaylistPicker.toast.value = null
        }
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .musicGlass(RoundedCornerShape(20.dp))
                .background(colors.background.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
