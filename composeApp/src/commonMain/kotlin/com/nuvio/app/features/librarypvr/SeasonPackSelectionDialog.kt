package com.nuvio.app.features.librarypvr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAlertDialog
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_add_dialog_cancel
import nuvio.composeapp.generated.resources.library_manual_auto_match
import nuvio.composeapp.generated.resources.library_manual_auto_match_failed
import nuvio.composeapp.generated.resources.library_manual_auto_match_partial
import nuvio.composeapp.generated.resources.library_manual_conflict
import nuvio.composeapp.generated.resources.library_manual_download
import nuvio.composeapp.generated.resources.library_manual_episode_short
import nuvio.composeapp.generated.resources.library_manual_incomplete
import nuvio.composeapp.generated.resources.library_manual_pack_seasons_ambiguous
import nuvio.composeapp.generated.resources.library_manual_renumber
import nuvio.composeapp.generated.resources.library_manual_select_all
import nuvio.composeapp.generated.resources.library_manual_select_none
import nuvio.composeapp.generated.resources.library_manual_select_seasons
import nuvio.composeapp.generated.resources.library_manual_select_season
import nuvio.composeapp.generated.resources.library_manual_selection
import nuvio.composeapp.generated.resources.streams_download_pack_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.accentBrush

/** Tight enough that the two buttons read as controls on the count line, not as dialog actions. */
private val SelectionButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

/**
 * Review step for a season-pack grab launched from a stream row: the pack's real file listing with
 * per-file episode coordinates, editable for the same reason the manual-link breakdown is — when
 * filename detection mislabels an episode, the user gets the last word before anything downloads.
 *
 * The same review is what fixing a badly-numbered local anime series needs, so the dialog knows
 * about rows and a source name rather than about a debrid session, and its heading, confirm label
 * and closing note are the caller's to set. Nothing else differs between the two flows: both are
 * "here is a pile of files, tell me which episode each one is".
 */
@Composable
internal fun SeasonPackSelectionDialog(
    title: String,
    sourceName: String?,
    initialRows: List<ManualGrabRow>,
    entryRelative: Boolean,
    onConfirm: (List<ManualGrabRow>) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Episode count of the entry being grabbed, fetched on demand. Null disables "Auto match" —
     * it only means anything under entry-relative numbering, where the answer is a straight 1..N.
     */
    onCountEntryEpisodes: (suspend () -> Int?)? = null,
    dialogTitleRes: StringResource = Res.string.streams_download_pack_title,
    /** Confirm-button label; takes the selected-file count as its single argument. */
    confirmLabelRes: StringResource = Res.string.library_manual_download,
    /** Shown under the list — for a flow whose consequences are not obvious from the button. */
    note: String? = null,
    episodePreviews: Map<Int, ManualEpisodePreview> = emptyMap(),
    showEpisodePreviews: Boolean = false,
) {
    var rows by remember(initialRows) { mutableStateOf(initialRows) }
    val scope = rememberCoroutineScope()
    var autoMatching by remember(initialRows) { mutableStateOf(false) }
    // Held as numbers rather than a formatted string: the message is rendered with stringResource
    // in the composable, which a coroutine callback cannot call.
    var autoMatchShortfall by remember(initialRows) { mutableStateOf<Pair<Int, Int>?>(null) }
    var autoMatchFailed by remember(initialRows) { mutableStateOf(false) }
    // First entry-relative episode the renumbered run lands on. 1 is the common case (the entry
    // starts where the selection does); anything else is a TMDB season that begins mid-entry.
    var renumberStart by remember(initialRows) { mutableStateOf(1) }

    val included = rows.filter { it.included }
    val incomplete = included.count {
        !it.hasRequiredCoordinates(isMovie = false, entryRelative = entryRelative)
    }
    val selectedBytes = included.sumOf { it.sizeBytes ?: 0L }
    // Two files on the same destination slot would silently collapse into one path.
    val conflicted = included.groupBy { it.destinationSlot(entryRelative) }
        .any { (_, sharing) -> sharing.size > 1 }
    // A pack that carries seasons of its own needs them visible even under entry-relative
    // numbering, or every row reads "E1" with no way to tell the blocks apart.
    val packSeasons = remember(rows) { rows.mapNotNull { it.sourceSeason }.distinct().sorted() }
    val showSeasonField = !entryRelative || packSeasons.isNotEmpty()
    val ambiguousPack = entryRelative && packSeasons.size > 1
    // "Select season" acts on whatever the user has already ticked: pick one episode of a season,
    // then take the rest of it in one click. Only meaningful for a pack spanning several seasons —
    // with one season it would just be "Select all" under another name.
    val selectedSeasons = included.mapNotNull { it.sourceSeason }.distinct().sorted()
    val showSelectSeason = packSeasons.size > 1 && selectedSeasons.isNotEmpty()
    val seasonSelectionIncomplete = rows.any { it.sourceSeason in selectedSeasons && !it.included }
    // Auto match reads one anchor and renumbers from it, so it needs exactly one tick to work from.
    val canAutoMatch = entryRelative && onCountEntryEpisodes != null && included.size == 1
    // Renumber collapses a hand-picked selection onto one continuous run starting at [renumberStart],
    // which is the only way an entry-relative grab can span a franchise season boundary without
    // colliding. It is offered for a single season too: a TMDB season that starts partway into the
    // kitsu entry needs the same shift, just with a start other than 1.
    val canRenumber = entryRelative && included.size > 1

    /**
     * Treats the single ticked row as the entry's episode 1 and takes the [count] rows from there in
     * list order, renumbering them 1..count. Renumbering is the point: a release group's numbering
     * (BW051, "Ep25") rarely starts where the anime entry does, and the entry's own coordinates are
     * what the destination path and the `kitsu:<id>:<ep>` video id are built from.
     */
    fun applyAutoMatch(count: Int) {
        val anchor = rows.indexOfFirst { it.included }.takeIf { it >= 0 } ?: return
        rows = rows.autoMatchedFromAnchor(anchorIndex = anchor, count = count)
        val matched = minOf(count, rows.size - anchor)
        autoMatchShortfall = (matched to count).takeIf { matched < count }
    }

    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        // Wider than the 560dp default: these rows carry a full release filename plus two
        // coordinate columns, and at the default width every name truncated to uselessness.
        maxWidth = if (showEpisodePreviews) 1120.dp else 860.dp,
        title = { Text(stringResource(dialogTitleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                sourceName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.library_manual_selection,
                            included.size,
                            formatBytes(selectedBytes),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge.accentBrush(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (entryRelative && onCountEntryEpisodes != null) {
                        TextButton(
                            enabled = canAutoMatch && !autoMatching,
                            onClick = {
                                autoMatchShortfall = null
                                autoMatchFailed = false
                                autoMatching = true
                                scope.launch {
                                    val count = runCatching { onCountEntryEpisodes() }.getOrNull()
                                    autoMatching = false
                                    if (count == null) autoMatchFailed = true else applyAutoMatch(count)
                                }
                            },
                            contentPadding = SelectionButtonPadding,
                        ) { Text(stringResource(Res.string.library_manual_auto_match)) }
                    }
                    if (showSelectSeason) {
                        TextButton(
                            enabled = seasonSelectionIncomplete,
                            onClick = {
                                rows = rows.map {
                                    if (it.sourceSeason in selectedSeasons) it.copy(included = true) else it
                                }
                            },
                            contentPadding = SelectionButtonPadding,
                        ) {
                            Text(
                                text = if (selectedSeasons.size == 1) {
                                    stringResource(Res.string.library_manual_select_season, selectedSeasons.single())
                                } else {
                                    stringResource(Res.string.library_manual_select_seasons)
                                },
                            )
                        }
                    }
                    if (canRenumber) {
                        TextButton(
                            onClick = {
                                autoMatchShortfall = null
                                autoMatchFailed = false
                                rows = rows.renumberSelectionContinuously(startEpisode = renumberStart)
                            },
                            contentPadding = SelectionButtonPadding,
                        ) { Text(stringResource(Res.string.library_manual_renumber)) }
                        // The start sits next to the button rather than inside it because it is the
                        // one thing the user has to think about here; clicking it opens the same
                        // inline editor the per-file episode numbers use.
                        NumberField(
                            value = renumberStart,
                            label = stringResource(Res.string.library_manual_episode_short),
                            isError = false,
                            onChange = { renumberStart = (it ?: 1).coerceAtLeast(1) },
                        )
                    }
                    TextButton(
                        enabled = included.size < rows.size,
                        onClick = { rows = rows.map { it.copy(included = true) } },
                        contentPadding = SelectionButtonPadding,
                    ) { Text(stringResource(Res.string.library_manual_select_all)) }
                    TextButton(
                        enabled = included.isNotEmpty(),
                        onClick = { rows = rows.map { it.copy(included = false) } },
                        contentPadding = SelectionButtonPadding,
                    ) { Text(stringResource(Res.string.library_manual_select_none)) }
                }
                Column(
                    // Bounded so the title, selection row and the warnings below all still fit
                    // inside NuvioAlertDialog's 560dp text slot, which clips rather than scrolls.
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rows.forEach { row ->
                        ManualFileRow(
                            row = row,
                            isSeries = true,
                            entryRelative = entryRelative,
                            showSeasonField = showSeasonField,
                            episodePreview = row.episode?.let(episodePreviews::get),
                            showEpisodePreview = showEpisodePreviews,
                            onChange = { updated ->
                                rows = rows.map { if (it.fileId == updated.fileId) updated else it }
                            },
                        )
                    }
                }
                if (incomplete > 0) {
                    Text(
                        text = stringResource(Res.string.library_manual_incomplete, incomplete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                autoMatchShortfall?.let { (matched, count) ->
                    Text(
                        text = stringResource(Res.string.library_manual_auto_match_partial, matched, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (autoMatchFailed) {
                    Text(
                        text = stringResource(Res.string.library_manual_auto_match_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (ambiguousPack) {
                    Text(
                        text = stringResource(
                            Res.string.library_manual_pack_seasons_ambiguous,
                            packSeasons.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conflicted) {
                    Text(
                        text = stringResource(Res.string.library_manual_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = included.isNotEmpty() && incomplete == 0 && !conflicted,
                onClick = { onConfirm(rows) },
            ) { Text(stringResource(confirmLabelRes, included.size)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_add_dialog_cancel)) }
        },
    )
}

data class ManualEpisodePreview(
    val episode: Int,
    val title: String,
    val thumbnail: String? = null,
    val released: String? = null,
)
