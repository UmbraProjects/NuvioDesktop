package com.nuvio.app.features.librarypvr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_add_dialog_cancel
import nuvio.composeapp.generated.resources.library_manual_check
import nuvio.composeapp.generated.resources.library_manual_conflict
import nuvio.composeapp.generated.resources.library_manual_conflict_movie
import nuvio.composeapp.generated.resources.library_manual_download
import nuvio.composeapp.generated.resources.library_manual_episode_short
import nuvio.composeapp.generated.resources.library_manual_error_invalid
import nuvio.composeapp.generated.resources.library_manual_error_no_provider
import nuvio.composeapp.generated.resources.library_manual_error_no_video
import nuvio.composeapp.generated.resources.library_manual_error_not_cached
import nuvio.composeapp.generated.resources.library_manual_error_unknown
import nuvio.composeapp.generated.resources.library_manual_hint
import nuvio.composeapp.generated.resources.library_manual_incomplete
import nuvio.composeapp.generated.resources.library_manual_pack_seasons_ambiguous
import nuvio.composeapp.generated.resources.library_manual_inspecting
import nuvio.composeapp.generated.resources.library_manual_queued
import nuvio.composeapp.generated.resources.library_manual_season_short
import nuvio.composeapp.generated.resources.library_manual_selection
import nuvio.composeapp.generated.resources.library_manual_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.core.ui.accentBrush

/**
 * The manual-link panel: paste a link, review exactly which file lands on which episode, confirm.
 *
 * The breakdown is editable because this flow exists precisely for the cases where automatic
 * episode detection is wrong — a plan the user cannot correct would not solve anything.
 */
@Composable
internal fun ManualLinkDialog(item: MonitoredItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val errorStrings = rememberManualErrorStrings()
    var link by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<ManualGrabSession?>(null) }
    var rows by remember { mutableStateOf<List<ManualGrabRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val included = rows.filter { it.included }
    val incomplete = included.count { !it.hasRequiredCoordinates(item) }
    val selectedBytes = included.sumOf { it.sizeBytes ?: 0L }
    // Grabs are keyed on (title, season, episode), so two files claiming the same slot would
    // silently collapse into one. Catch it here instead.
    val conflicted = if (item.isMovie) {
        included.size > 1
    } else {
        included.groupBy { it.destinationSlot(item.usesEntryRelativeNumbering) }
            .any { (_, sharing) -> sharing.size > 1 }
    }
    val entryRelative = item.usesEntryRelativeNumbering
    val packSeasons = rows.mapNotNull { it.sourceSeason }.distinct().sorted()
    val showSeasonField = !entryRelative || packSeasons.isNotEmpty()
    val ambiguousPack = entryRelative && packSeasons.size > 1

    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${item.title}${item.year?.let { " ($it)" } ?: ""}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val active = session
                if (active == null) {
                    NuvioTextField(
                        value = link,
                        onValueChange = {
                            link = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(Res.string.library_manual_hint),
                    )
                } else {
                    active.sourceName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(
                            Res.string.library_manual_selection,
                            included.size,
                            formatBytes(selectedBytes),
                        ),
                        style = MaterialTheme.typography.labelLarge.accentBrush(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rows.forEach { row ->
                            ManualFileRow(
                                row = row,
                                isSeries = item.isSeries,
                                entryRelative = entryRelative,
                                showSeasonField = showSeasonField,
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
                            text = stringResource(
                                if (item.isMovie) {
                                    Res.string.library_manual_conflict_movie
                                } else {
                                    Res.string.library_manual_conflict
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(Res.string.library_manual_inspecting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            val active = session
            if (active == null) {
                TextButton(
                    enabled = link.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = ManualGrabService.inspect(link, item)) {
                                is ManualInspectResult.Success -> {
                                    session = result.session
                                    rows = result.session.rows
                                }
                                else -> error = result.message(errorStrings)
                            }
                            busy = false
                        }
                    },
                ) { Text(stringResource(Res.string.library_manual_check)) }
            } else {
                TextButton(
                    enabled = included.isNotEmpty() && incomplete == 0 && !conflicted && !busy,
                    onClick = {
                        val queued = ManualGrabService.enqueue(active, rows, item)
                        scope.launch {
                            NuvioToastController.show(getString(Res.string.library_manual_queued, queued))
                        }
                        onDismiss()
                    },
                ) { Text(stringResource(Res.string.library_manual_download, included.size)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_add_dialog_cancel)) }
        },
    )
}

/** One selectable file with its episode coordinates; shared with the stream-row pack dialog. */
@Composable
internal fun ManualFileRow(
    row: ManualGrabRow,
    isSeries: Boolean,
    entryRelative: Boolean,
    onChange: (ManualGrabRow) -> Unit,
    // Entry-relative files are absolute-numbered, so the season box is noise — except when the
    // source is a multi-season pack, where it is the only thing telling two "E1" rows apart.
    showSeasonField: Boolean = !entryRelative,
    episodePreview: ManualEpisodePreview? = null,
    showEpisodePreview: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = row.included,
            onCheckedChange = { onChange(row.copy(included = it)) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.fileName,
                style = MaterialTheme.typography.bodySmall,
                // Release names routinely run past one line even in the wide pack dialog; let the
                // row grow rather than ellipsing away the part that identifies the episode.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (row.included) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            row.sizeBytes?.takeIf { it > 0L }?.let { size ->
                Text(
                    text = formatBytes(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showEpisodePreview) {
            Row(
                modifier = Modifier.width(300.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                episodePreview?.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnail ->
                    NuvioAsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 80.dp, height = 45.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            row.episode?.let { append("E$it") }
                            episodePreview?.title?.takeIf { it.isNotBlank() }?.let { title ->
                                if (isNotEmpty()) append(" · ")
                                append(title)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.included) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    episodePreview?.released?.takeIf { it.isNotBlank() }?.let { released ->
                        Text(
                            text = released,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (isSeries) {
            if (showSeasonField) {
                if (entryRelative) {
                    // Read-only: the entry has no seasons, so this is the file's own label, shown
                    // only so the blocks of a multi-season pack can be told apart. Editing it would
                    // change nothing about where the file lands.
                    Text(
                        text = row.sourceSeason?.let { "S$it" }.orEmpty(),
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    NumberField(
                        value = row.season,
                        label = stringResource(Res.string.library_manual_season_short),
                        isError = row.included && row.season == null,
                        onChange = { onChange(row.copy(season = it)) },
                    )
                }
            }
            NumberField(
                value = row.episode,
                label = stringResource(Res.string.library_manual_episode_short),
                isError = row.included && row.episode == null,
                onChange = { onChange(row.copy(episode = it)) },
            )
        }
    }
}

/**
 * A season or episode number shown as plain text — "S1", "E12" — that becomes an inline field when
 * clicked.
 *
 * These were bordered text fields, which in a list of ten files meant twenty boxes taller than the
 * filenames they annotate, all shouting for attention over the thing the user is actually reading.
 * Editing them is the exception, so it costs a click and the resting state is quiet.
 */
@Composable
internal fun NumberField(
    value: Int?,
    label: String,
    isError: Boolean,
    onChange: (Int?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value, editing) { mutableStateOf(value?.toString().orEmpty()) }
    // A freshly composed field reports "not focused" before the request below can focus it. Without
    // this latch that first report reads as focus *loss* and commits immediately, so the field
    // appeared for one frame and closed again — it could never actually be typed into.
    var hasFocused by remember(editing) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val color = when {
        isError -> MaterialTheme.colorScheme.error
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    fun commit() {
        editing = false
        onChange(text.toIntOrNull())
    }

    Box(
        modifier = Modifier.width(44.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .trackTextInputFocus()
                    .onFocusChanged { state ->
                        if (state.isFocused) hasFocused = true
                        else if (hasFocused && editing) commit()
                    }
                    .onPreviewKeyEvent { event ->
                        // Enter would otherwise reach the dialog and fire Download.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            commit()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = color,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(color),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Text(
                text = value?.let { "$label$it" } ?: "$label–",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { editing = true }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

/** Error copy resolved while composing, so the inspect coroutine can pick one without recomposing. */
private data class ManualErrorStrings(
    val invalid: String,
    val noProvider: String,
    val notCached: String,
    val noVideo: String,
    val unknown: String,
)

@Composable
private fun rememberManualErrorStrings(): ManualErrorStrings = ManualErrorStrings(
    invalid = stringResource(Res.string.library_manual_error_invalid),
    noProvider = stringResource(Res.string.library_manual_error_no_provider),
    notCached = stringResource(Res.string.library_manual_error_not_cached),
    noVideo = stringResource(Res.string.library_manual_error_no_video),
    unknown = stringResource(Res.string.library_manual_error_unknown),
)

private fun ManualInspectResult.message(strings: ManualErrorStrings): String = when (this) {
    is ManualInspectResult.Success -> ""
    ManualInspectResult.InvalidLink -> strings.invalid
    ManualInspectResult.MissingProvider -> strings.noProvider
    ManualInspectResult.NotCached -> strings.notCached
    ManualInspectResult.NoVideoFiles -> strings.noVideo
    is ManualInspectResult.Failed -> message?.takeIf { it.isNotBlank() } ?: strings.unknown
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
