package com.nuvio.app.features.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.i18n.localizedString

/** Hosts the add-to-playlist dialog once, at the app root. See [PlaylistAddController]. */
@Composable
fun PlaylistAddDialogHost() {
    val target by PlaylistAddController.target.collectAsStateWithLifecycle()
    target?.let { PlaylistAddDialog(target = it, onDismiss = PlaylistAddController::dismiss) }
}

/** Season picker state for a [PlaylistAddTarget.Series]. */
private sealed interface SeriesLoad {
    data object Loading : SeriesLoad
    data object Failed : SeriesLoad
    data class Loaded(val meta: MetaDetails, val seasons: Map<Int, List<MetaVideo>>) : SeriesLoad
}

/** How a show is added: its episodes, or one random-episode "channel" slot. */
private enum class SeriesAddMode { Episodes, RandomSlot }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistAddDialog(
    target: PlaylistAddTarget,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val playlists by remember {
        PlaylistRepository.ensureLoaded()
        PlaylistRepository.playlists
    }.collectAsStateWithLifecycle()
    var newName by remember(target) { mutableStateOf("") }

    var seriesLoad by remember(target) { mutableStateOf<SeriesLoad>(SeriesLoad.Loading) }
    // Several seasons can be ticked at once; they are queued in season order.
    var selectedSeasons by remember(target) { mutableStateOf<Set<Int>>(emptySet()) }
    var mode by remember(target) { mutableStateOf(SeriesAddMode.Episodes) }
    if (target is PlaylistAddTarget.Series) {
        LaunchedEffect(target) {
            val meta = runCatching { MetaDetailsRepository.fetch(target.type, target.id) }.getOrNull()
            seriesLoad = if (meta == null) {
                SeriesLoad.Failed
            } else {
                val seasons = meta.releasedPlayableEpisodes(CurrentDateProvider.todayIsoDate())
                    .groupBy { it.effectiveSeasonNumber() ?: 0 }
                    .toSortedMap()
                // Season 1 rather than specials: "add this show" means the show.
                selectedSeasons = setOfNotNull(seasons.keys.firstOrNull { it > 0 } ?: seasons.keys.firstOrNull())
                SeriesLoad.Loaded(meta, seasons)
            }
        }
    }

    // What an "add" click would queue right now. Null while a show is still loading, or when no
    // season is ticked.
    val pending: Pair<String, List<PlaylistEntry>>? = when (target) {
        is PlaylistAddTarget.Entries -> target.label to target.entries
        is PlaylistAddTarget.Series -> when (val load = seriesLoad) {
            is SeriesLoad.Loaded -> when {
                // A "series" with no episode list (some anime films are typed that way): queue it
                // as one item rather than offering an empty picker.
                load.seasons.isEmpty() -> target.name to listOf(PlaylistEntries.movie(load.meta))
                mode == SeriesAddMode.RandomSlot -> {
                    // No season ticked, or every main season, means "any main season".
                    val mainSeasons = load.seasons.keys.filter { it > 0 }.toSet()
                    val seasons = selectedSeasons.takeUnless { it.isEmpty() || it == mainSeasons }?.toList()
                    val slot = PlaylistEntries.randomSlot(load.meta, seasons)
                    "${target.name} · ${slot.randomSlotLabel()}" to listOf(slot)
                }
                selectedSeasons.isEmpty() -> null
                else -> {
                    val seasons = selectedSeasons.sorted()
                    "${target.name} · ${seasonsSummary(seasons)}" to seasons.flatMap { season ->
                        load.seasons[season].orEmpty().map { PlaylistEntries.episode(load.meta, it) }
                    }
                }
            }
            else -> null
        }
    }

    fun addTo(playlist: Playlist) {
        val (label, entries) = pending ?: return
        val added = PlaylistRepository.addEntries(playlist.id, entries)
        NuvioToastController.show(
            when {
                added == 0 -> localizedString(Res.string.playlist_add_toast_already, playlist.name)
                entries.size == 1 -> localizedString(Res.string.playlist_add_toast_added_one, playlist.name)
                added < entries.size -> localizedString(Res.string.playlist_add_toast_added_new, added, playlist.name)
                else -> localizedString(Res.string.playlist_add_toast_added_many, added, playlist.name)
            },
        )
        PlaylistPlaybackSession.log.i { "added $added/${entries.size} ($label) to playlist=${playlist.id}" }
        // Random slots stay open so a "channel" can be stacked with several clicks; everything
        // else is a one-shot add.
        if (!(target is PlaylistAddTarget.Series && mode == SeriesAddMode.RandomSlot)) onDismiss()
    }

    val subtitle = when (target) {
        is PlaylistAddTarget.Entries -> target.label
        is PlaylistAddTarget.Series -> target.name
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.playlist_add_title),
        subtitle = subtitle,
        maxWidth = 560.dp,
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    ) {
        if (target is PlaylistAddTarget.Series) {
            when (val load = seriesLoad) {
                SeriesLoad.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.playlist_add_loading_episodes), color = tokens.colors.textMuted)
                }
                SeriesLoad.Failed -> Text(
                    stringResource(Res.string.playlist_add_load_failed),
                    color = tokens.colors.danger,
                )
                is SeriesLoad.Loaded -> if (load.seasons.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeasonChip(
                            label = stringResource(Res.string.playlist_add_mode_episodes),
                            selected = mode == SeriesAddMode.Episodes,
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            onClick = { mode = SeriesAddMode.Episodes },
                        )
                        SeasonChip(
                            label = stringResource(Res.string.action_random_episode),
                            selected = mode == SeriesAddMode.RandomSlot,
                            icon = Icons.Default.Shuffle,
                            onClick = { mode = SeriesAddMode.RandomSlot },
                        )
                    }
                    val mainSeasons = load.seasons.keys.filter { it > 0 }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (mode == SeriesAddMode.RandomSlot) stringResource(Res.string.playlist_add_pick_from_seasons) else stringResource(Res.string.playlist_add_seasons),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (mainSeasons.size > 1) {
                            TextButton(onClick = { selectedSeasons = mainSeasons.toSet() }) { Text(stringResource(Res.string.playlist_add_all)) }
                        }
                        TextButton(
                            onClick = { selectedSeasons = emptySet() },
                            enabled = selectedSeasons.isNotEmpty(),
                        ) { Text(stringResource(Res.string.action_clear)) }
                    }
                    // Every season visible at once, wrapping, so a long-running show no longer hides
                    // its later seasons off the end of a row that cannot scroll.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 176.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Main seasons first; specials last, and never part of "All".
                        (mainSeasons + load.seasons.keys.filter { it <= 0 }).forEach { season ->
                            val selected = season in selectedSeasons
                            SeasonChip(
                                label = if (season <= 0) stringResource(Res.string.playlist_specials) else stringResource(Res.string.playlist_add_season, season),
                                selected = selected,
                                onClick = {
                                    selectedSeasons = if (selected) selectedSeasons - season else selectedSeasons + season
                                },
                            )
                        }
                    }
                    Text(
                        text = if (mode == SeriesAddMode.RandomSlot) {
                            val scope = pending?.second?.firstOrNull()?.randomSeasons
                                ?.let { stringResource(Res.string.playlist_add_random_scope_from, seasonsSummary(it)) }
                                ?: stringResource(Res.string.playlist_add_random_scope_any)
                            stringResource(Res.string.playlist_add_random_slot_note, scope)
                        } else {
                            val count = pending?.second?.size ?: 0
                            val seasons = selectedSeasons.size
                            if (seasons == 0) {
                                stringResource(Res.string.playlist_add_pick_seasons)
                            } else {
                                (if (seasons == 1) stringResource(Res.string.playlist_add_one_season) else stringResource(Res.string.playlist_add_n_seasons, seasons)) + " · " +
                                    if (count == 1) stringResource(Res.string.playlist_add_one_episode) else stringResource(Res.string.playlist_add_n_episodes, count)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            playlists.forEach { playlist ->
                PlaylistPickRow(
                    playlist = playlist,
                    enabled = pending != null,
                    onClick = { addTo(playlist) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.weight(1f),
                placeholder = if (playlists.isEmpty()) stringResource(Res.string.playlist_add_name_first) else stringResource(Res.string.playlist_add_new_playlist),
                onImeAction = {
                    if (newName.isNotBlank() && pending != null) {
                        addTo(PlaylistRepository.create(newName))
                        newName = ""
                    }
                },
            )
            NuvioPrimaryButton(
                text = stringResource(Res.string.playlist_add_create),
                modifier = Modifier.width(120.dp),
                enabled = newName.isNotBlank() && pending != null,
                onClick = {
                    addTo(PlaylistRepository.create(newName))
                    newName = ""
                },
            )
        }
    }
}

/** "Season 3", "Seasons 1, 2, 5", "Specials". */
private fun seasonsSummary(seasons: List<Int>): String {
    val sorted = seasons.sorted()
    return when {
        sorted.size == 1 && sorted[0] <= 0 -> localizedString(Res.string.playlist_specials)
        sorted.size == 1 -> localizedString(Res.string.playlist_add_season, sorted[0])
        else -> localizedString(
            Res.string.playlist_add_seasons_list,
            sorted.joinToString(", ") { if (it <= 0) localizedString(Res.string.playlist_specials) else "$it" },
        )
    }
}

@Composable
private fun PlaylistPickRow(
    playlist: Playlist,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = SolidColor(tokens.colors.surfaceCard.copy(alpha = tokens.opacity.medium)),
                shape = tokens.shapes.compactCard,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = tokens.colors.textMuted,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (playlist.entries.size == 1) stringResource(Res.string.playlist_one_item) else stringResource(Res.string.playlist_n_items, playlist.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
            contentDescription = stringResource(Res.string.playlist_add_cd_add),
            tint = if (enabled) tokens.colors.accent else tokens.colors.textDisabled,
        )
    }
}

@Composable
private fun SeasonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .background(
                brush = if (selected) {
                    tokens.colors.accentFill(tokens.opacity.selected)
                } else {
                    SolidColor(tokens.colors.surfaceCard.copy(alpha = tokens.opacity.medium))
                },
                shape = tokens.shapes.compactCard,
            )
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
        )
    }
}
