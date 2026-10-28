package com.nuvio.app.features.playlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import sh.calvin.reorderable.ReorderableColumn
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One playlist, as a popup over the Library: play it, reorder by dragging, remove entries, rename,
 * loop or delete it. A popup rather than a pushed screen because a playlist is a short list — a
 * full page left most of the screen empty.
 *
 * Clicking an entry plays the playlist from that entry on.
 */
@Composable
fun PlaylistDialog(
    playlistId: String,
    onDismiss: () -> Unit,
    onPlayEntry: (entryId: String) -> Unit,
    onOpenDetails: (PlaylistEntry) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val playlists by remember {
        PlaylistRepository.ensureLoaded()
        PlaylistRepository.playlists
    }.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (playlist == null) {
        // Deleted (here, or by a profile switch): nothing left to show.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = playlist.name,
        maxWidth = 760.dp,
        actions = {
            TextButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = tokens.colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.action_delete), color = tokens.colors.textMuted)
            }
            TextButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = tokens.colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.playlist_rename), color = tokens.colors.textMuted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
    ) {
        PlaylistControls(
            playlist = playlist,
            onPlay = { playlist.resumeEntry?.let { onPlayEntry(it.entryId) } },
            onPlayFromStart = { playlist.entries.firstOrNull()?.let { onPlayEntry(it.entryId) } },
            onToggleLoop = { PlaylistRepository.setLoop(playlist.id, !playlist.loop) },
            onToggleShuffle = { PlaylistRepository.setShuffled(playlist.id, !playlist.shuffled) },
            onToggleRemoveWatched = { PlaylistRepository.setRemoveWatched(playlist.id, !playlist.removeWatched) },
            onTogglePreferLocal = { PlaylistRepository.setPreferLocalLibrary(playlist.id, !playlist.preferLocalLibrary) },
        )
        if (playlist.entries.isEmpty()) {
            Text(
                text = stringResource(Res.string.playlist_empty_message),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            val listScroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(listScroll)
                        .padding(end = 12.dp),
                ) {
                    PlaylistEntryList(
                        playlist = playlist,
                        onPlayEntry = onPlayEntry,
                        onOpenDetails = onOpenDetails,
                    )
                }
                // The playlist dialog is not part of the TV UI, so TV mode does not hide its scrollbar.
                NuvioDesktopVerticalScrollbar(listScroll, Modifier.align(Alignment.CenterEnd), showInTvMode = true)
            }
        }
    }

    if (renaming) {
        RenamePlaylistDialog(
            currentName = playlist.name,
            onDismiss = { renaming = false },
            onRename = { name ->
                PlaylistRepository.rename(playlist.id, name)
                renaming = false
            },
        )
    }
    if (confirmingDelete) {
        NuvioAlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.playlist_delete_title, playlist.name)) },
            text = { Text(stringResource(Res.string.playlist_delete_message)) },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        PlaylistRepository.delete(playlist.id)
                    },
                ) { Text(stringResource(Res.string.action_delete), color = tokens.colors.danger) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistControls(
    playlist: Playlist,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRemoveWatched: () -> Unit,
    onTogglePreferLocal: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val resume = playlist.resumeEntry
    val resuming = playlist.resumeIndex > 0 && resume != null
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = buildString {
                append(if (playlist.entries.size == 1) stringResource(Res.string.playlist_one_item) else stringResource(Res.string.playlist_n_items, playlist.entries.size))
                if (resuming) append(" · " + stringResource(Res.string.playlist_resume_up_next, resume.displayTitle(), playlist.resumeIndex + 1, playlist.entries.size))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioPrimaryButton(
                text = if (resuming) stringResource(Res.string.action_resume) else stringResource(Res.string.action_play),
                modifier = Modifier.width(132.dp),
                height = 40.dp,
                enabled = resume != null,
                onClick = onPlay,
            )
            if (resuming) {
                TextButton(onClick = onPlayFromStart) { Text(stringResource(Res.string.playlist_play_from_start)) }
            }
        }
        // Playlist-wide options on a row of their own, wrapping on a narrow window.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistToggleChip(
                icon = Icons.Default.Shuffle,
                label = stringResource(Res.string.playlist_option_shuffle),
                enabled = playlist.shuffled,
                onClick = onToggleShuffle,
            )
            PlaylistToggleChip(
                icon = Icons.Default.Repeat,
                label = stringResource(Res.string.playlist_option_loop),
                enabled = playlist.loop,
                onClick = onToggleLoop,
            )
            PlaylistToggleChip(
                icon = Icons.Default.PlaylistRemove,
                label = stringResource(Res.string.playlist_option_remove_watched),
                enabled = playlist.removeWatched,
                onClick = onToggleRemoveWatched,
            )
            PlaylistToggleChip(
                icon = Icons.Default.Folder,
                label = stringResource(Res.string.playlist_option_prefer_local),
                enabled = playlist.preferLocalLibrary,
                onClick = onTogglePreferLocal,
            )
        }
    }
}

/** An on/off option for the whole playlist; "on" is the accent fill, the label never changes. */
@Composable
private fun PlaylistToggleChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val stateText = if (enabled) stringResource(Res.string.compose_action_on) else stringResource(Res.string.compose_action_off)
    Row(
        modifier = Modifier
            .clip(tokens.shapes.compactCard)
            .background(
                brush = if (enabled) {
                    tokens.colors.accentFill(tokens.opacity.selected)
                } else {
                    SolidColor(tokens.colors.surfaceCard.copy(alpha = tokens.opacity.medium))
                },
                shape = tokens.shapes.compactCard,
            )
            .clickable(onClick = onClick)
            .semantics { stateDescription = stateText }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaylistEntryList(
    playlist: Playlist,
    onPlayEntry: (String) -> Unit,
    onOpenDetails: (PlaylistEntry) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    // A plain reorderable Column inside the screen's LazyColumn, as in the collection editor: a
    // nested reorderable LazyColumn would add a second edge auto-scroller.
    ReorderableColumn(
        list = playlist.entries,
        onSettle = { fromIndex, toIndex -> PlaylistRepository.moveEntry(playlist.id, fromIndex, toIndex) },
        onMove = { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { index, entry, isDragging ->
        key(entry.entryId) {
            ReorderableItem {
                val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
                Surface(
                    color = Color.Transparent,
                    shadowElevation = elevation,
                    shape = MaterialTheme.nuvio.shapes.compactCard,
                ) {
                    PlaylistEntryRow(
                        entry = entry,
                        position = index + 1,
                        isUpNext = index == playlist.resumeIndex,
                        dragHandle = Modifier.draggableHandle(
                            onDragStarted = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                        ),
                        onPlay = { onPlayEntry(entry.entryId) },
                        onOpenDetails = { onOpenDetails(entry) },
                        onRemove = { PlaylistRepository.removeEntry(playlist.id, entry.entryId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: PlaylistEntry,
    position: Int,
    isUpNext: Boolean,
    dragHandle: Modifier,
    onPlay: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // A random slot has no fixed episode, so no progress of its own.
    val progress = remember(entry.videoId, entry.randomEpisode) {
        if (entry.randomEpisode) null else WatchProgressRepository.progressForVideo(entry.videoId)
    }
    val shape = tokens.shapes.compactCard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                brush = if (isUpNext) {
                    tokens.colors.accentFill(tokens.opacity.selected * 0.6f)
                } else {
                    SolidColor(tokens.colors.surfaceCard.copy(alpha = tokens.opacity.medium))
                },
                shape = shape,
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = stringResource(Res.string.playlist_drag_to_reorder),
            tint = tokens.colors.textMuted,
            modifier = dragHandle.size(24.dp),
        )
        Text(
            text = "$position",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textMuted,
            modifier = Modifier.width(28.dp),
        )
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.colors.surfaceCard),
        ) {
            // Some shows' episode stills are dead links (every Friends episode), so a failed load
            // steps down to the show's backdrop, then its poster, rather than leaving the box blank.
            val artwork = remember(entry.episodeThumbnail, entry.background, entry.poster) {
                listOfNotNull(entry.episodeThumbnail, entry.background, entry.poster).filter { it.isNotBlank() }
            }
            var artworkIndex by remember(artwork) { mutableStateOf(0) }
            NuvioAsyncImage(
                model = artwork.getOrNull(artworkIndex),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { if (artworkIndex < artwork.lastIndex) artworkIndex++ },
            )
            if (entry.randomEpisode) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(8.dp)
                        .size(22.dp),
                )
            }
            val fraction = progress
                ?.takeIf { it.durationMs > 0L }
                ?.let { if (it.isCompleted) 1f else (it.lastPositionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
            if (fraction != null && fraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(tokens.colors.accentFill),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (entry.randomEpisode) {
                    entry.randomSlotLabel()
                } else if (entry.isEpisode) {
                    listOfNotNull(
                        "S${entry.seasonNumber}E${entry.episodeNumber}",
                        entry.episodeTitle?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                } else {
                    entry.title
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (entry.isEpisode || entry.randomEpisode) entry.title else stringResource(Res.string.media_movie))
                    if (progress?.isCompleted == true) append(" · " + stringResource(Res.string.playlist_entry_watched))
                    if (isUpNext) append(" · " + stringResource(Res.string.playlist_entry_up_next))
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenDetails) {
            Icon(Icons.Default.Info, contentDescription = stringResource(Res.string.playlist_entry_details), tint = tokens.colors.textMuted)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.playlist_entry_remove), tint = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun RenamePlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.playlist_rename_title),
        maxWidth = 460.dp,
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_save)) }
        },
    ) {
        NuvioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            onImeAction = { if (name.isNotBlank()) onRename(name) },
        )
    }
}
