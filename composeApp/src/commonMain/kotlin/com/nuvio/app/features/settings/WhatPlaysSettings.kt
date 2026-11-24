package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.streams.NextEpisodePick
import com.nuvio.app.features.streams.PickBy
import com.nuvio.app.features.streams.PlayClick
import com.nuvio.app.features.streams.PlaybackPickSettings
import com.nuvio.app.features.streams.PlaybackPickSettingsRepository
import com.nuvio.app.features.streams.PlaybackSources
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.usenet.UsenetSettingsRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Settings > Playback > What plays: the few choices that decide which stream plays, for a Play click and for the next
 * episode, and a sentence saying what they add up to. See `Docs/plans/playback-priority.md`.
 */
@Composable
internal fun WhatPlaysSettingsRows(isTablet: Boolean, onEditRegex: () -> Unit) {
    val pick by PlaybackPickSettingsRepository.uiState.collectAsStateWithLifecycle()
    val scoring by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val sources = rememberAvailablePlaybackSources()
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_what_plays_on_play),
        description = stringResource(Res.string.settings_what_plays_on_play_desc),
        options = listOf(
            SettingsChoiceOption(PlayClick.PLAY_FOR_ME, stringResource(Res.string.settings_what_plays_play_for_me)),
            SettingsChoiceOption(PlayClick.SHOW_SOURCES, stringResource(Res.string.settings_what_plays_show_sources)),
        ),
        selectedValue = pick.onPlay,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-on-play")),
        onSelected = PlaybackPickSettingsRepository::setOnPlay,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_what_plays_pick_by),
        description = stringResource(Res.string.settings_what_plays_pick_by_desc) +
            if (pick.pickBy == PickBy.BEST_SCORE && !scoring.enabled) " " + stringResource(Res.string.settings_what_plays_scoring_off) else "",
        options = listOf(
            SettingsChoiceOption(PickBy.PRIORITY_ORDER, stringResource(Res.string.settings_what_plays_priority_order)),
            SettingsChoiceOption(PickBy.BEST_SCORE, stringResource(Res.string.settings_what_plays_best_score)),
        ),
        selectedValue = pick.pickBy,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-pick-by")),
        onSelected = PlaybackPickSettingsRepository::setPickBy,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_what_plays_next_episode),
        description = stringResource(Res.string.settings_what_plays_next_episode_desc),
        options = listOf(
            SettingsChoiceOption(NextEpisodePick.SAME_RELEASE_FIRST, stringResource(Res.string.settings_what_plays_same_release)),
            SettingsChoiceOption(NextEpisodePick.SAME_RULES_AS_PLAY, stringResource(Res.string.settings_what_plays_same_rules)),
            SettingsChoiceOption(NextEpisodePick.ASK_ME, stringResource(Res.string.settings_what_plays_ask_me)),
        ),
        selectedValue = pick.nextEpisode,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-next-episode")),
        onSelected = PlaybackPickSettingsRepository::setNextEpisode,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SourcePriorityRow(pick = pick, sources = sources, isTablet = isTablet)
    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_what_plays_regex),
        description = stringResource(Res.string.settings_what_plays_regex_desc),
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-regex")),
        trailingContent = {
            Text(
                text = pick.regexFilter.ifBlank { stringResource(Res.string.settings_playback_not_set) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
            )
        },
        onClick = onEditRegex,
    )
    SettingsGroupDivider(isTablet = isTablet)
    WhatPlaysSummary(pick = pick, sources = sources, isTablet = isTablet)
}

/**
 * The Local library page's way to the local files row: where it sits in Source priority, and the same list to move it
 * or switch it off.
 */
@Composable
internal fun LocalFilesPriorityRow(isTablet: Boolean) {
    val pick by PlaybackPickSettingsRepository.uiState.collectAsStateWithLifecycle()
    val sources = rememberAvailablePlaybackSources()
    var editing by remember { mutableStateOf(false) }
    val position = sources.keys.indexOf(PlaybackSources.LOCAL)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_what_plays_local_files),
        description = stringResource(Res.string.settings_what_plays_local_files_desc),
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-local-files")),
        trailingContent = {
            Text(
                text = if (!pick.mayAutoPlay(PlaybackSources.LOCAL)) {
                    stringResource(Res.string.settings_what_plays_local_never)
                } else {
                    stringResource(Res.string.settings_what_plays_local_position, position + 1, sources.keys.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
            )
        },
        onClick = { editing = true },
    )
    if (editing) {
        SourcePriorityDialog(sources = sources, onDismiss = { editing = false })
    }
}

/** The sources this install has, in What plays order: the rows of the source priority list. */
internal data class AvailablePlaybackSources(val addonNames: List<String>, val keys: List<String>)

@Composable
private fun rememberAvailablePlaybackSources(): AvailablePlaybackSources {
    val pick by PlaybackPickSettingsRepository.uiState.collectAsStateWithLifecycle()
    val addons by AddonRepository.uiState.collectAsStateWithLifecycle()
    val debrid by DebridSettingsRepository.uiState.collectAsStateWithLifecycle()
    val servers by ServerRepository.uiState.collectAsStateWithLifecycle()
    val usenet by UsenetSettingsRepository.uiState.collectAsStateWithLifecycle()
    val plugins by PluginRepository.uiState.collectAsStateWithLifecycle()
    val addonNames = addons.addons.enabledAddons()
        .filter { addon -> addon.manifest?.resources?.any { it.name == "stream" } == true }
        .map { addon -> addon.displayTitle.ifBlank { addon.manifest?.name.orEmpty() } }
        .filter { it.isNotBlank() }
        .distinct()
    val present = buildSet {
        add(PlaybackSources.LOCAL)
        if (debrid.canResolvePlayableLinks) add(PlaybackSources.DEBRID)
        if (servers.connections.any { it.enabled }) add(PlaybackSources.SERVERS)
        if (usenet.sources.ready) add(PlaybackSources.USENET)
        if (AppFeaturePolicy.pluginsEnabled && plugins.scrapers.any { it.enabled }) add(PlaybackSources.PLUGINS)
        addonNames.forEach { add(PlaybackSources.addon(it)) }
    }
    val keys = PlaybackSources.effectiveOrder(pick.order, addonNames).filter { it in present }
    return AvailablePlaybackSources(addonNames, keys)
}

@Composable
private fun sourceLabel(key: String): String = when (key) {
    PlaybackSources.DEBRID -> stringResource(Res.string.settings_what_plays_row_debrid)
    PlaybackSources.SERVERS -> stringResource(Res.string.settings_what_plays_row_servers)
    PlaybackSources.USENET -> stringResource(Res.string.settings_what_plays_row_usenet)
    PlaybackSources.PLUGINS -> stringResource(Res.string.settings_what_plays_row_plugins)
    PlaybackSources.LOCAL -> stringResource(Res.string.settings_what_plays_local_files)
    else -> PlaybackSources.addonName(key) ?: key
}

@Composable
private fun SourcePriorityRow(pick: PlaybackPickSettings, sources: AvailablePlaybackSources, isTablet: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val off = sources.keys.count { !pick.mayAutoPlay(it) }
    val first = sources.keys.firstOrNull { pick.mayAutoPlay(it) }
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_what_plays_source_priority),
        description = stringResource(Res.string.settings_what_plays_source_priority_desc),
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("what-plays-source-priority")),
        trailingContent = {
            val firstLabel = first?.let { sourceLabel(it) }
            Text(
                text = listOfNotNull(
                    firstLabel?.let { stringResource(Res.string.settings_what_plays_source_priority_summary, it) },
                    stringResource(Res.string.settings_what_plays_sources_off, off).takeIf { off > 0 },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
            )
        },
        onClick = { editing = true },
    )
    if (editing) {
        SourcePriorityDialog(sources = sources, onDismiss = { editing = false })
    }
}

@Composable
private fun SourcePriorityDialog(sources: AvailablePlaybackSources, onDismiss: () -> Unit) {
    val pick by PlaybackPickSettingsRepository.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.nuvio.colors
    val hapticFeedback = LocalHapticFeedback.current
    fun move(key: String, toKey: String) {
        // The whole stored order, hidden rows included, so a source that comes back keeps its place.
        val current = PlaybackPickSettingsRepository.current
        val full = PlaybackSources.effectiveOrder(current.order, sources.addonNames)
        val from = full.indexOf(key)
        val to = full.indexOf(toKey)
        if (from < 0 || to < 0 || from == to) return
        val next = full.toMutableList()
        next.add(to, next.removeAt(from))
        val gone = current.order.filterNot { it in next }
        PlaybackPickSettingsRepository.setOrder(next + gone)
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = (from.key as? String) ?: return@rememberReorderableLazyListState
        val toKey = (to.key as? String) ?: return@rememberReorderableLazyListState
        move(fromKey, toKey)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_what_plays_source_priority),
        subtitle = stringResource(Res.string.settings_what_plays_source_priority_desc),
        maxWidth = 600.dp,
        actions = {
            SettingsGlassButton(
                text = stringResource(Res.string.settings_what_plays_reset_order),
                onClick = { PlaybackPickSettingsRepository.setOrder(emptyList()) },
            )
            SettingsGlassButton(text = stringResource(Res.string.action_done), onClick = onDismiss, accent = true)
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp), state = listState) {
            itemsIndexed(sources.keys, key = { _, key -> key }) { index, key ->
                ReorderableItem(reorderState, key = key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    Surface(
                        modifier = with(this@ReorderableItem) {
                            Modifier.draggableHandle(
                                onDragStarted = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                            )
                        },
                        shadowElevation = elevation,
                        color = if (isDragging) NuvioGlass.night(colors.background, colors.accent) else Color.Transparent,
                    ) {
                        SourcePriorityItem(
                            index = index,
                            label = sourceLabel(key),
                            autoPlay = pick.mayAutoPlay(key),
                            onAutoPlayChange = { on -> PlaybackPickSettingsRepository.setAutoPlay(key, on) },
                            onMoveUp = { move(key, sources.keys[index - 1]) }.takeIf { index > 0 },
                            onMoveDown = { move(key, sources.keys[index + 1]) }.takeIf { index < sources.keys.lastIndex },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePriorityItem(
    index: Int,
    label: String,
    autoPlay: Boolean,
    onAutoPlayChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val colors = MaterialTheme.nuvio.colors
    Column {
        if (index > 0) SettingsGroupDivider(isTablet = false)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(32.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (autoPlay) colors.textPrimary else colors.textMuted,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_up))
            }
            IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_down))
            }
            SettingsSquareSwitch(
                checked = autoPlay,
                onCheckedChange = onAutoPlayChange,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The sentence under the choices: what a Play click and the next episode will do. */
@Composable
private fun WhatPlaysSummary(pick: PlaybackPickSettings, sources: AvailablePlaybackSources, isTablet: Boolean) {
    // Best score ranks every source together, so its list is not an order: commas, not arrows.
    val order = sources.keys.filter { pick.mayAutoPlay(it) }.map { sourceLabel(it) }
        .joinToString(if (pick.pickBy == PickBy.BEST_SCORE) ", " else " → ")
    val sentences = buildList {
        add(
            stringResource(
                if (pick.onPlay == PlayClick.PLAY_FOR_ME) Res.string.settings_what_plays_summary_play else Res.string.settings_what_plays_summary_show,
            ),
        )
        val picksAutomatically = pick.onPlay == PlayClick.PLAY_FOR_ME || pick.nextEpisode != NextEpisodePick.ASK_ME
        if (picksAutomatically && order.isNotEmpty()) {
            add(
                stringResource(
                    if (pick.pickBy == PickBy.BEST_SCORE) Res.string.settings_what_plays_summary_score else Res.string.settings_what_plays_summary_priority,
                    order,
                ),
            )
        }
        add(
            stringResource(
                when (pick.nextEpisode) {
                    NextEpisodePick.SAME_RELEASE_FIRST -> Res.string.settings_what_plays_summary_next_same
                    NextEpisodePick.SAME_RULES_AS_PLAY -> Res.string.settings_what_plays_summary_next_rules
                    NextEpisodePick.ASK_ME -> Res.string.settings_what_plays_summary_next_ask
                },
            ),
        )
    }
    SettingsSubtext(
        text = sentences.joinToString(" "),
        isTablet = isTablet,
        color = MaterialTheme.nuvio.colors.textPrimary,
        modifier = Modifier.padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 12.dp),
    )
}
