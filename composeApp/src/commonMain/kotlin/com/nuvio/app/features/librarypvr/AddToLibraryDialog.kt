package com.nuvio.app.features.librarypvr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalMatchCandidate
import com.nuvio.app.features.locallibrary.LocalMatchState
import com.nuvio.app.features.locallibrary.LocalMatcher
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_add_dialog_cancel
import nuvio.composeapp.generated.resources.library_add_dialog_confirm
import nuvio.composeapp.generated.resources.library_add_dialog_folder
import nuvio.composeapp.generated.resources.library_add_dialog_mode
import nuvio.composeapp.generated.resources.library_add_dialog_no_folders
import nuvio.composeapp.generated.resources.library_add_dialog_title
import nuvio.composeapp.generated.resources.library_add_no_results
import nuvio.composeapp.generated.resources.library_add_resolving
import nuvio.composeapp.generated.resources.library_add_search_hint
import nuvio.composeapp.generated.resources.library_add_search_title
import nuvio.composeapp.generated.resources.library_add_searching
import nuvio.composeapp.generated.resources.library_add_toast_added
import nuvio.composeapp.generated.resources.library_downloads_mode_all
import nuvio.composeapp.generated.resources.library_downloads_mode_picker_hint
import nuvio.composeapp.generated.resources.library_downloads_mode_selected_future
import nuvio.composeapp.generated.resources.library_downloads_mode_selected_only
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField

/** A title whose ids are already known (from the details screen or a local item). */
data class MonitorTarget(
    val contentId: String,
    val contentType: String, // "series" | "movie"
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val kitsuId: Int? = null,
    val malId: Int? = null,
    val isAnime: Boolean = false,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val background: String? = null,
)

private fun MonitorTarget.folderType(): LocalFolderType =
    if (contentType.equals("series", ignoreCase = true) || contentType.equals("anime", ignoreCase = true)) {
        LocalFolderType.SERIES
    } else {
        LocalFolderType.MOVIES
    }

/**
 * Adds [target] to library monitoring: pick the destination folder and (for series) the monitor
 * mode. Shared by the details screen and the local-item add flow. Movies have no mode choice
 * (always "when available").
 */
@Composable
fun AddToLibraryDialog(
    target: MonitorTarget,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit = {},
) {
    val local by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LocalLibraryRepository.ensureLoaded() }
    val folders = remember(local.folders, target.contentType) {
        local.folders.filter { it.type == target.folderType() }
    }
    val isSeries = target.folderType() == LocalFolderType.SERIES
    var selectedFolderId by remember(folders) { mutableStateOf(folders.firstOrNull()?.id) }
    var mode by remember {
        mutableStateOf(if (isSeries) MonitorMode.SELECTED_PLUS_FUTURE else MonitorMode.MOVIE_WHEN_AVAILABLE)
    }
    val addedText = stringResource(Res.string.library_add_toast_added)

    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_add_dialog_title)) },
        text = {
            if (folders.isEmpty()) {
                Text(
                    text = stringResource(Res.string.library_add_dialog_no_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${target.title}${target.year?.let { " ($it)" } ?: ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    FolderPicker(folders, selectedFolderId) { selectedFolderId = it }
                    if (isSeries) {
                        ModePicker(mode = mode, onSelect = { mode = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedFolderId != null,
                onClick = {
                    val folderId = selectedFolderId ?: return@TextButton
                    LibraryPvrRepository.newMonitoredItem(
                        contentId = target.contentId,
                        contentType = if (isSeries) "series" else "movie",
                        title = target.title,
                        targetFolderId = folderId,
                        mode = mode,
                        tmdbId = target.tmdbId,
                        imdbId = target.imdbId,
                        kitsuId = target.kitsuId,
                        malId = target.malId,
                        isAnime = target.isAnime,
                        year = target.year,
                        poster = target.poster,
                        background = target.background,
                    )
                    NuvioToastController.show(addedText)
                    onConfirmed()
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.library_add_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_add_dialog_cancel)) }
        },
    )
}

/**
 * Searches TMDB (or Kitsu, for anime folders) and monitors the chosen result — the "add a title
 * that isn't local yet" flow. Reuses the same [LocalMatcher.search] path the Fix-match dialog uses,
 * then resolves the pick to full ids (imdb back-fill) before creating the monitor.
 */
@Composable
fun SearchAddToLibraryDialog(
    onDismiss: () -> Unit,
) {
    val local by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LocalLibraryRepository.ensureLoaded() }
    val folders = local.folders
    val scope = rememberCoroutineScope()

    var selectedFolderId by remember(folders) { mutableStateOf(folders.firstOrNull()?.id) }
    val selectedFolder = folders.firstOrNull { it.id == selectedFolderId }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalMatchCandidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selectedCandidate by remember { mutableStateOf<LocalMatchCandidate?>(null) }
    var mode by remember { mutableStateOf(MonitorMode.SELECTED_PLUS_FUTURE) }
    var adding by remember { mutableStateOf(false) }
    val addedText = stringResource(Res.string.library_add_toast_added)

    val isSeriesFolder = selectedFolder?.type == LocalFolderType.SERIES

    // Debounced search whenever the query or target folder changes.
    LaunchedEffect(query, selectedFolderId) {
        selectedCandidate = null
        val folder = selectedFolder
        val trimmed = query.trim()
        if (folder == null || trimmed.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        results = runCatching { LocalMatcher.search(trimmed, folder.type, folder.isAnime) }.getOrDefault(emptyList())
        searching = false
    }

    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_add_search_title)) },
        text = {
            if (folders.isEmpty()) {
                Text(
                    text = stringResource(Res.string.library_add_dialog_no_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolderPicker(folders, selectedFolderId) { selectedFolderId = it }
                    NuvioTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(Res.string.library_add_search_hint),
                    )
                    when {
                        searching -> LoadingRow(stringResource(Res.string.library_add_searching))
                        query.trim().length >= 2 && results.isEmpty() ->
                            HintRow(stringResource(Res.string.library_add_no_results))
                        else -> Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            results.forEach { candidate ->
                                CandidateRow(
                                    candidate = candidate,
                                    selected = candidate === selectedCandidate,
                                    onClick = { selectedCandidate = candidate },
                                )
                            }
                        }
                    }
                    if (selectedCandidate != null && isSeriesFolder) {
                        ModePicker(mode = mode, onSelect = { mode = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCandidate != null && selectedFolder != null && !adding,
                onClick = {
                    val candidate = selectedCandidate ?: return@TextButton
                    val folder = selectedFolder ?: return@TextButton
                    adding = true
                    scope.launch {
                        val resolved = resolveCandidate(candidate, folder)
                        if (resolved != null) {
                            LibraryPvrRepository.newMonitoredItem(
                                contentId = resolved.contentId,
                                contentType = resolved.contentType,
                                title = resolved.title,
                                targetFolderId = folder.id,
                                mode = if (folder.type == LocalFolderType.SERIES) mode else MonitorMode.MOVIE_WHEN_AVAILABLE,
                                tmdbId = resolved.tmdbId,
                                imdbId = resolved.imdbId,
                                kitsuId = resolved.kitsuId,
                                malId = resolved.malId,
                                isAnime = resolved.isAnime,
                                year = resolved.year,
                                poster = resolved.poster,
                                background = resolved.background,
                            )
                            NuvioToastController.show(addedText)
                        }
                        adding = false
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(if (adding) Res.string.library_add_resolving else Res.string.library_add_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_add_dialog_cancel)) }
        },
    )
}

/** Resolves a search candidate to full ids by reusing the Fix-match back-fill on a throwaway item. */
private suspend fun resolveCandidate(candidate: LocalMatchCandidate, folder: LocalFolder): MonitorTarget? {
    val stub = LocalMediaItem(
        key = "pvr-stub",
        folderId = folder.id,
        type = folder.type,
        isAnime = folder.isAnime,
        title = candidate.title,
        year = candidate.year,
        poster = candidate.poster,
    )
    val resolved = runCatching {
        when {
            candidate.kitsuId != null -> LocalMatcher.applyKitsuId(stub, candidate.kitsuId!!, candidate.poster, LocalMatchState.MANUAL)
            candidate.tmdbId != null -> LocalMatcher.applyTmdbId(stub, candidate.tmdbId!!, LocalMatchState.MANUAL)
            else -> null
        }
    }.getOrNull() ?: return null
    return MonitorTarget(
        contentId = resolved.contentId,
        contentType = resolved.contentType,
        tmdbId = resolved.tmdbId,
        imdbId = resolved.imdbId,
        kitsuId = resolved.kitsuId,
        malId = resolved.malId,
        isAnime = resolved.isAnime,
        title = resolved.title,
        year = resolved.year,
        poster = resolved.poster,
    )
}

@Composable
private fun FolderPicker(folders: List<LocalFolder>, selectedId: String?, onSelect: (String) -> Unit) {
    if (folders.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(Res.string.library_add_dialog_folder),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folders.forEach { folder ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(folder.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (folder.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(folder.displayNameWithDrive, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Mode choice only. Which seasons/episodes are selected is edited afterwards in the episode picker
 * (open a monitored poster), which replaced the old "type 1, 3-5" seasons text field.
 *
 * [showLabelAndHint] is for the add flows, where the picker is not reachable yet and the wording has
 * to explain where selection happens. Inside the picker itself both would be noise, so it renders
 * as three centred pills and nothing else.
 */
@Composable
internal fun ModePicker(
    mode: MonitorMode,
    onSelect: (MonitorMode) -> Unit,
    showLabelAndHint: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (showLabelAndHint) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (showLabelAndHint) {
            Text(
                text = stringResource(Res.string.library_add_dialog_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == MonitorMode.SELECTED_PLUS_FUTURE,
                onClick = { onSelect(MonitorMode.SELECTED_PLUS_FUTURE) },
                label = { Text(stringResource(Res.string.library_downloads_mode_selected_future)) },
            )
            FilterChip(
                selected = mode == MonitorMode.SELECTED_ONLY,
                onClick = { onSelect(MonitorMode.SELECTED_ONLY) },
                label = { Text(stringResource(Res.string.library_downloads_mode_selected_only)) },
            )
            FilterChip(
                selected = mode == MonitorMode.ALL_MISSING,
                onClick = { onSelect(MonitorMode.ALL_MISSING) },
                label = { Text(stringResource(Res.string.library_downloads_mode_all)) },
            )
        }
        if (showLabelAndHint) {
            Text(
                text = stringResource(Res.string.library_downloads_mode_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: LocalMatchCandidate, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = "${candidate.title}${candidate.year?.let { " ($it)" } ?: ""}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
