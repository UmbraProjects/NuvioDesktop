package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.NuvioGlass
import nuvio.composeapp.generated.resources.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_playback_hud_button_audio
import nuvio.composeapp.generated.resources.settings_playback_hud_button_channels
import nuvio.composeapp.generated.resources.settings_playback_hud_button_episodes
import nuvio.composeapp.generated.resources.settings_playback_hud_button_keyboard_cycle_anime_mode
import nuvio.composeapp.generated.resources.settings_playback_hud_button_keyboard_cycle_anime_svp
import nuvio.composeapp.generated.resources.settings_playback_hud_button_keyboard_cycle_color_profile
import nuvio.composeapp.generated.resources.settings_playback_hud_button_keyboard_cycle_hdr_mode
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_down
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_up
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order_custom
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order_default
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order_description
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order_hidden
import nuvio.composeapp.generated.resources.settings_playback_hud_button_order_reset
import nuvio.composeapp.generated.resources.settings_playback_hud_button_party
import nuvio.composeapp.generated.resources.settings_playback_hud_button_picture_in_picture
import nuvio.composeapp.generated.resources.settings_playback_hud_button_resize
import nuvio.composeapp.generated.resources.settings_playback_hud_button_sources
import nuvio.composeapp.generated.resources.settings_playback_hud_button_speed
import nuvio.composeapp.generated.resources.settings_playback_hud_button_stream_info
import nuvio.composeapp.generated.resources.settings_playback_hud_button_submit_intro
import nuvio.composeapp.generated.resources.settings_playback_hud_button_subtitles
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop HUD's action row (the tool buttons right of the transport), by controls.html
 * `data-command`, in the order controls.html lays them out. The transport (previous, seek, play,
 * next) is not reorderable: play stays in the middle.
 */
internal val HudActionRowButtons: List<Pair<String, StringResource>> = listOf(
    "streamInfo" to Res.string.settings_playback_hud_button_stream_info,
    "resize" to Res.string.settings_playback_hud_button_resize,
    "speed" to Res.string.settings_playback_hud_button_speed,
    "subtitles" to Res.string.settings_playback_hud_button_subtitles,
    "audio" to Res.string.settings_playback_hud_button_audio,
    "keyboardCycleHdrMode" to Res.string.settings_playback_hud_button_keyboard_cycle_hdr_mode,
    "keyboardCycleColorProfile" to Res.string.settings_playback_hud_button_keyboard_cycle_color_profile,
    "keyboardCycleAnimeMode" to Res.string.settings_playback_hud_button_keyboard_cycle_anime_mode,
    "keyboardCycleAnimeSvp" to Res.string.settings_playback_hud_button_keyboard_cycle_anime_svp,
    "iptvChannels" to Res.string.settings_playback_hud_button_channels,
    "sources" to Res.string.settings_playback_hud_button_sources,
    "episodes" to Res.string.settings_playback_hud_button_episodes,
    "submitIntro" to Res.string.settings_playback_hud_button_submit_intro,
    "party" to Res.string.settings_playback_hud_button_party,
    "pictureInPicture" to Res.string.settings_playback_hud_button_picture_in_picture,
)

/** [saved] first (known commands only), then any action-row button it does not name, in authored order. */
internal fun effectiveHudButtonOrder(saved: List<String>): List<String> {
    val known = HudActionRowButtons.map { it.first }
    val listed = saved.filter { it in known }.distinct()
    return listed + known.filterNot { it in listed }
}

/**
 * The HUD buttons that keep their place (the transport either side of play, and the header's), which
 * the panel lists after the action row with a show switch but no arrows.
 */
internal val HudFixedButtons: List<Pair<String, StringResource>> = listOf(
    "previousEpisode" to Res.string.settings_playback_hud_button_previous_episode,
    "seekBack" to Res.string.settings_playback_hud_button_seek_back,
    "seekForward" to Res.string.settings_playback_hud_button_seek_forward,
    "nextEpisode" to Res.string.settings_playback_hud_button_next_episode,
    "toggleFullscreen" to Res.string.settings_playback_hud_button_toggle_fullscreen,
    "lock" to Res.string.settings_playback_hud_button_lock,
    "videoSettings" to Res.string.settings_playback_hud_button_video_settings,
)

/**
 * Settings > Player: one panel for the player's buttons, as Navigation order is for the bar: the
 * action row in order (drag or arrows) with a show switch on each, then the fixed buttons with
 * their switch. Replaces the separate Hidden player buttons picker (2026-11-23).
 */
@Composable
internal fun HudButtonOrderRow(order: List<String>, hidden: Set<String>, isTablet: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val custom = effectiveHudButtonOrder(order) != HudActionRowButtons.map { it.first }
    val orderLabel = stringResource(
        if (custom) Res.string.settings_playback_hud_button_order_custom
        else Res.string.settings_playback_hud_button_order_default,
    )
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_playback_hud_button_order),
        description = stringResource(Res.string.settings_playback_hud_button_order_description) + " " +
            stringResource(Res.string.settings_playback_hidden_hud_buttons_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = if (hidden.isEmpty()) {
                    orderLabel
                } else {
                    orderLabel + "  ·  " + stringResource(Res.string.settings_playback_hidden_hud_buttons_count, hidden.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
        },
        onClick = { editing = true },
    )
    if (editing) {
        HudButtonOrderDialog(order = order, hidden = hidden, onDismiss = { editing = false })
    }
}

@Composable
private fun HudButtonOrderDialog(order: List<String>, hidden: Set<String>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val hapticFeedback = LocalHapticFeedback.current
    val labels = (HudActionRowButtons + HudFixedButtons).toMap()
    val current = effectiveHudButtonOrder(order)
    fun moveCommand(command: String, toCommand: String) {
        // The saved order, not [current]: one drag can move several rows before the dialog redraws.
        val saved = effectiveHudButtonOrder(PlayerSettingsRepository.uiState.value.desktopHudButtonOrder)
        val from = saved.indexOf(command)
        val to = saved.indexOf(toCommand)
        if (from < 0 || to < 0 || from == to) return
        val next = saved.toMutableList()
        next.add(to, next.removeAt(from))
        PlayerSettingsRepository.setDesktopHudButtonOrder(next)
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // By key, not index: the list also holds the two headers and the fixed buttons, which never move.
        val fromCommand = hudOrderCommand(from.key) ?: return@rememberReorderableLazyListState
        val toCommand = hudOrderCommand(to.key) ?: return@rememberReorderableLazyListState
        moveCommand(fromCommand, toCommand)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hud_button_order),
        subtitle = stringResource(Res.string.settings_playback_hud_button_order_description) + " " +
            stringResource(Res.string.settings_playback_hidden_hud_buttons_description),
        maxWidth = 600.dp,
        actions = {
            SettingsGlassButton(
                text = stringResource(Res.string.settings_playback_hud_button_order_reset),
                onClick = { PlayerSettingsRepository.setDesktopHudButtonOrder(emptyList()) },
            )
            SettingsGlassButton(text = stringResource(Res.string.action_done), onClick = onDismiss, accent = true)
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp), state = listState) {
            item(key = "header:tools") {
                HudButtonPanelHeader(stringResource(Res.string.settings_playback_hud_buttons_tools_header), first = true)
            }
            itemsIndexed(current, key = { _, command -> HudOrderKeyPrefix + command }) { index, command ->
                ReorderableItem(reorderState, key = HudOrderKeyPrefix + command) { isDragging ->
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
                        HudButtonPanelRow(
                            index = index,
                            label = labels[command]?.let { stringResource(it) }.orEmpty(),
                            shown = command !in hidden,
                            onShownChange = { on -> PlayerSettingsRepository.setDesktopHudButtonHidden(command, !on) },
                            onMoveUp = { moveCommand(command, current[index - 1]) }.takeIf { index > 0 },
                            onMoveDown = { moveCommand(command, current[index + 1]) }.takeIf { index < current.lastIndex },
                            movable = true,
                        )
                    }
                }
            }
            item(key = "header:fixed") {
                HudButtonPanelHeader(stringResource(Res.string.settings_playback_hud_buttons_fixed_header), first = false)
            }
            itemsIndexed(HudFixedButtons, key = { _, entry -> "fixed:" + entry.first }) { index, entry ->
                val command = entry.first
                HudButtonPanelRow(
                    index = index,
                    label = stringResource(entry.second),
                    shown = command !in hidden,
                    onShownChange = { on -> PlayerSettingsRepository.setDesktopHudButtonHidden(command, !on) },
                    onMoveUp = null,
                    onMoveDown = null,
                    movable = false,
                )
            }
        }
    }
}

private const val HudOrderKeyPrefix = "order:"

/** The action-row command a panel item's key names, or null for a header or a fixed button. */
private fun hudOrderCommand(key: Any?): String? =
    (key as? String)?.takeIf { it.startsWith(HudOrderKeyPrefix) }?.removePrefix(HudOrderKeyPrefix)

/** A group's caption in the panel: what the rows under it are and whether they move. */
@Composable
private fun HudButtonPanelHeader(text: String, first: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (first) 4.dp else 20.dp, bottom = 6.dp),
    )
}

/**
 * One button in the panel: its number and name, the arrows when it can move, and its show switch. A fixed button keeps
 * the arrows' room empty, so every row is the same height and the switches line up in one column.
 */
@Composable
private fun HudButtonPanelRow(
    index: Int,
    label: String,
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    movable: Boolean,
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
                color = if (shown) colors.textPrimary else colors.textMuted,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (movable) {
                IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_up))
                }
                IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_down))
                }
            } else {
                Spacer(Modifier.width(96.dp))
            }
            SettingsSquareSwitch(
                checked = shown,
                onCheckedChange = onShownChange,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
