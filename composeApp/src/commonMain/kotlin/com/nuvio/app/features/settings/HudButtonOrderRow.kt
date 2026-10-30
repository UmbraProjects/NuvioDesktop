package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Settings > Playback: opens an editor that moves the action-row buttons up and down. */
@Composable
internal fun HudButtonOrderRow(order: List<String>, hidden: Set<String>, isTablet: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val custom = effectiveHudButtonOrder(order) != HudActionRowButtons.map { it.first }
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_playback_hud_button_order),
        description = stringResource(Res.string.settings_playback_hud_button_order_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = stringResource(
                    if (custom) Res.string.settings_playback_hud_button_order_custom
                    else Res.string.settings_playback_hud_button_order_default,
                ),
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
    val labels = HudActionRowButtons.toMap()
    val current = effectiveHudButtonOrder(order)
    fun move(index: Int, by: Int) {
        val target = index + by
        if (target !in current.indices) return
        val next = current.toMutableList()
        next.add(target, next.removeAt(index))
        PlayerSettingsRepository.setDesktopHudButtonOrder(next)
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hud_button_order),
        subtitle = stringResource(Res.string.settings_playback_hud_button_order_description),
        maxWidth = 520.dp,
        actions = {
            SettingsGlassButton(
                text = stringResource(Res.string.settings_playback_hud_button_order_reset),
                onClick = { PlayerSettingsRepository.setDesktopHudButtonOrder(emptyList()) },
            )
            SettingsGlassButton(text = stringResource(Res.string.action_done), onClick = onDismiss, accent = true)
        },
    ) {
        val colors = MaterialTheme.nuvio.colors
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            current.forEachIndexed { index, command ->
                val isHidden = command in hidden
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.  " + labels[command]?.let { stringResource(it) }.orEmpty() +
                            if (isHidden) "  " + stringResource(Res.string.settings_playback_hud_button_order_hidden) else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isHidden) colors.textMuted else colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_up))
                    }
                    IconButton(onClick = { move(index, 1) }, enabled = index < current.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_down))
                    }
                }
            }
        }
    }
}
