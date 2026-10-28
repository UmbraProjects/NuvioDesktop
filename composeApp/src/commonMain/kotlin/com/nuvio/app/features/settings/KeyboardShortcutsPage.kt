package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.NuvioKeyCap
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerShortcutAction
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutRebindDialog
import com.nuvio.app.features.player.PlayerShortcutRebindDialog
import com.nuvio.app.features.player.ensurePlayerShortcutBindingsLoaded
import com.nuvio.app.features.player.playerEscWindowedFirstState
import com.nuvio.app.features.player.playerShortcutKeyLabels
import com.nuvio.app.features.player.setPlayerEscWindowedFirst
import com.nuvio.app.features.player.resetPlayerShortcuts
import com.nuvio.app.features.player.appShortcutKeyLabels
import com.nuvio.app.features.player.ensureAppShortcutBindingsLoaded
import com.nuvio.app.features.player.resetAllAppShortcuts
import com.nuvio.app.features.input.ensureGamepadSettingsLoaded
import com.nuvio.app.features.input.gamepadConnectedState
import com.nuvio.app.features.input.gamepadDeadZoneRange
import com.nuvio.app.features.input.gamepadDeadZoneState
import com.nuvio.app.features.input.gamepadEnabledState
import com.nuvio.app.features.input.gamepadMappingRows
import com.nuvio.app.features.input.gamepadRepeatIntervalRange
import com.nuvio.app.features.input.gamepadRepeatIntervalState
import com.nuvio.app.features.input.gamepadSupported
import com.nuvio.app.features.input.mouseKeyboardState
import com.nuvio.app.features.input.setMouseKeyboard
import com.nuvio.app.features.input.resetGamepadSettings
import com.nuvio.app.features.input.setGamepadDeadZone
import com.nuvio.app.features.input.setGamepadEnabled
import com.nuvio.app.features.input.setGamepadRepeatIntervalMs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioActionLabel

/**
 * Read-only reference of every desktop keyboard shortcut, and the single authoritative inventory
 * of what each key does. Keys are handled in two places: the browsing screens (Compose
 * `onPreviewKeyEvent`, e.g. [com.nuvio.app.features.home.HomeScreen]) and the native player
 * dispatcher in [com.nuvio.app.features.player.PlayerEngine.desktop].
 *
 * The Navigation section and the player's fixed keys (arrows, K, Tab) are hardcoded here. The
 * rebindable player rows render live from [playerShortcutKeyLabels] so they reflect user
 * rebinds — keep the [PlayerShortcutAction] set (and the reference here) in sync with the
 * dispatcher.
 */

private data class Shortcut(
    val action: String,
    // Each inner list is one "chord slot" rendered as key-caps; multiple slots are shown as
    // interchangeable alternatives separated by a muted slash (e.g. Space / K).
    val keys: List<List<String>>,
    val appAction: AppShortcutAction? = null,
)

private fun key(vararg alternatives: String): List<List<String>> =
    alternatives.map { listOf(it) }

@Composable
private fun navigationShortcuts(labels: Map<AppShortcutAction, String>): List<Shortcut> =
    AppShortcutAction.entries.map { action ->
        Shortcut(stringResource(action.displayNameRes), key(labels[action] ?: "—"), appAction = action)
    } + browsingShortcuts()

/**
 * Fixed browsing keys on Home, Library, Search and Collections. Not rebindable, so they carry no
 * [AppShortcutAction] — they are listed here because this page is the shortcut inventory.
 */
@Composable
private fun browsingShortcuts(): List<Shortcut> = listOf(
    Shortcut(stringResource(Res.string.settings_shortcuts_page_down), key("Page Down")),
    Shortcut(stringResource(Res.string.settings_shortcuts_page_up), key("Page Up")),
    Shortcut(stringResource(Res.string.settings_shortcuts_jump_to_top), key("Home")),
    Shortcut(stringResource(Res.string.settings_shortcuts_jump_to_bottom), key("End")),
)

/**
 * A player shortcut row. When [action] is set, the primary key renders live from the current
 * binding; [extraKeys] are fixed, non-rebindable alternates shown after it (e.g. `K`, `←`).
 * When [action] is null the row is fully fixed and uses [fixedKeys].
 */
private class PlayerRowSpec(
    val label: StringResource,
    val action: PlayerShortcutAction?,
    val extraKeys: List<String> = emptyList(),
    val fixedKeys: List<String> = emptyList(),
)

@Composable
private fun PlayerRowSpec.toShortcut(labels: Map<PlayerShortcutAction, String>): Shortcut {
    val caps = if (action != null) listOfNotNull(labels[action]) + extraKeys else fixedKeys
    return Shortcut(stringResource(label), caps.map { listOf(it) })
}

private val playerRowSections: List<Pair<StringResource, List<PlayerRowSpec>>> = listOf(
    Res.string.settings_shortcuts_section_player_playback to listOf(
        PlayerRowSpec(PlayerShortcutAction.PlayPause.displayNameRes, PlayerShortcutAction.PlayPause),
        PlayerRowSpec(PlayerShortcutAction.AlternatePlayPause.displayNameRes, PlayerShortcutAction.AlternatePlayPause),
        PlayerRowSpec(PlayerShortcutAction.ToggleMute.displayNameRes, PlayerShortcutAction.ToggleMute),
        PlayerRowSpec(PlayerShortcutAction.SeekBackward.displayNameRes, PlayerShortcutAction.SeekBackward, extraKeys = listOf("←")),
        PlayerRowSpec(PlayerShortcutAction.SeekForward.displayNameRes, PlayerShortcutAction.SeekForward, extraKeys = listOf("→")),
        PlayerRowSpec(PlayerShortcutAction.SpeedUp.displayNameRes, PlayerShortcutAction.SpeedUp),
        PlayerRowSpec(PlayerShortcutAction.SpeedDown.displayNameRes, PlayerShortcutAction.SpeedDown),
        PlayerRowSpec(PlayerShortcutAction.ToggleSpeed.displayNameRes, PlayerShortcutAction.ToggleSpeed),
        PlayerRowSpec(Res.string.settings_shortcuts_volume_up, null, fixedKeys = listOf("↑")),
        PlayerRowSpec(Res.string.settings_shortcuts_volume_down, null, fixedKeys = listOf("↓")),
    ),
    Res.string.settings_shortcuts_section_player_tracks to listOf(
        PlayerRowSpec(PlayerShortcutAction.NextSubtitle.displayNameRes, PlayerShortcutAction.NextSubtitle),
        PlayerRowSpec(PlayerShortcutAction.NextAudio.displayNameRes, PlayerShortcutAction.NextAudio),
        PlayerRowSpec(PlayerShortcutAction.OpenSources.displayNameRes, PlayerShortcutAction.OpenSources),
        PlayerRowSpec(PlayerShortcutAction.OpenEpisodes.displayNameRes, PlayerShortcutAction.OpenEpisodes),
        PlayerRowSpec(PlayerShortcutAction.CycleZoom.displayNameRes, PlayerShortcutAction.CycleZoom),
        PlayerRowSpec(PlayerShortcutAction.SkipInterval.displayNameRes, PlayerShortcutAction.SkipInterval),
    ),
    Res.string.settings_shortcuts_section_player_video to listOf(
        PlayerRowSpec(PlayerShortcutAction.CycleSvp.displayNameRes, PlayerShortcutAction.CycleSvp),
        PlayerRowSpec(PlayerShortcutAction.CycleHdr.displayNameRes, PlayerShortcutAction.CycleHdr),
        PlayerRowSpec(PlayerShortcutAction.CycleColorProfile.displayNameRes, PlayerShortcutAction.CycleColorProfile),
        PlayerRowSpec(PlayerShortcutAction.CycleAnime.displayNameRes, PlayerShortcutAction.CycleAnime),
        PlayerRowSpec(PlayerShortcutAction.ToggleMpvDiagnostics.displayNameRes, PlayerShortcutAction.ToggleMpvDiagnostics),
    ),
)

internal fun LazyListScope.keyboardShortcutsContent(isTablet: Boolean) {
    item(key = "keyboard-shortcuts-intro") {
        Text(
            text = stringResource(Res.string.settings_shortcuts_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ShortcutRowHorizontalPadding(isTablet),
                    vertical = NuvioTokens.Space.s4,
                ),
        )
    }

    item(key = "keyboard-shortcuts-navigation") {
        LaunchedEffect(Unit) { ensureAppShortcutBindingsLoaded() }
        val wasdEnabled by ThemeSettingsRepository.wasdNavigationEnabled.collectAsState()
        val holdToSelectEnabled by ThemeSettingsRepository.holdToSelectEnabled.collectAsState()
        val appLabels by appShortcutKeyLabels().collectAsState()
        var rebindingApp by remember { mutableStateOf<AppShortcutAction?>(null) }
        SettingsSection(
            title = stringResource(Res.string.settings_shortcuts_navigation),
            isTablet = isTablet,
            actions = { ShortcutsResetLabel(onClick = { resetAllAppShortcuts() }) },
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_shortcuts_move_focus),
                    description = null,
                    options = listOf(
                        SettingsChoiceOption(false, stringResource(Res.string.settings_shortcuts_arrows)),
                        SettingsChoiceOption(true, stringResource(Res.string.settings_shortcuts_wasd)),
                    ),
                    selectedValue = wasdEnabled,
                    isTablet = isTablet,
                    onSelected = { enabled ->
                        ThemeSettingsRepository.setWasdNavigationEnabled(enabled)
                        ensureAppShortcutBindingsLoaded()
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_shortcuts_hold_to_select),
                    description = stringResource(Res.string.settings_shortcuts_hold_to_select_description),
                    checked = holdToSelectEnabled,
                    isTablet = isTablet,
                    onCheckedChange = ThemeSettingsRepository::setHoldToSelectEnabled,
                )
                navigationShortcuts(appLabels).forEach { shortcut ->
                    SettingsGroupDivider(isTablet = isTablet)
                    ShortcutRow(
                        shortcut = shortcut,
                        isTablet = isTablet,
                        onRebind = shortcut.appAction?.let { action -> { rebindingApp = action } },
                    )
                }
            }
        }
        rebindingApp?.let { action ->
            AppShortcutRebindDialog(action, onDismiss = { rebindingApp = null })
        }
    }

    item(key = "keyboard-shortcuts-player") {
        LaunchedEffect(Unit) { ensurePlayerShortcutBindingsLoaded() }
        val labels by playerShortcutKeyLabels().collectAsState()
        val escWindowedFirst by playerEscWindowedFirstState().collectAsState()
        var rebinding by remember { mutableStateOf<PlayerShortcutAction?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s18)) {
            playerRowSections.forEachIndexed { sectionIndex, (title, specs) ->
                val sectionActions = specs.mapNotNull { it.action }
                SettingsSection(
                    title = stringResource(title),
                    isTablet = isTablet,
                    actions = { ShortcutsResetLabel(onClick = { resetPlayerShortcuts(sectionActions) }) },
                ) {
                    SettingsGroup(isTablet = isTablet) {
                        // First in the section, as Navigation leads with its own switches: a
                        // setting among the key bindings was easy to scroll past.
                        if (sectionIndex == 0) {
                            SettingsSwitchRow(
                                title = stringResource(Res.string.settings_shortcuts_esc_windowed_first),
                                description = stringResource(Res.string.settings_shortcuts_esc_windowed_first_description),
                                checked = escWindowedFirst,
                                isTablet = isTablet,
                                onCheckedChange = ::setPlayerEscWindowedFirst,
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        specs.forEachIndexed { index, spec ->
                            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                            ShortcutRow(
                                shortcut = spec.toShortcut(labels),
                                isTablet = isTablet,
                                onRebind = spec.action?.let { action -> { rebinding = action } },
                            )
                        }
                    }
                }
            }
        }
        rebinding?.let { action ->
            PlayerShortcutRebindDialog(action = action, onDismiss = { rebinding = null })
        }
    }

    // Last on the page: the keyboard sections read as one continuous inventory, and slotting the
    // controller between them broke that in half.
    if (gamepadSupported()) {
        item(key = "keyboard-shortcuts-gamepad") { GamepadSection(isTablet = isTablet) }
    }
}

/** The desktop rows carry the 16.dp every settings row does, so they line up with the section
 * heading and the Controller rows rather than hugging the card edge. */
private fun ShortcutRowHorizontalPadding(isTablet: Boolean) = if (isTablet) 16.dp else NuvioTokens.Space.s12

@Composable
private fun ShortcutRow(
    shortcut: Shortcut,
    isTablet: Boolean,
    onRebind: (() -> Unit)? = null,
    /** Optional second line under the action, for a row that means two things (see the pad rows). */
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onRebind != null) Modifier.clickable(onClick = onRebind) else Modifier)
            .padding(
                horizontal = ShortcutRowHorizontalPadding(isTablet),
                vertical = NuvioTokens.Space.s10,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = shortcut.action,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            description?.let { SettingsSubtext(text = it, isTablet = isTablet) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            shortcut.keys.forEachIndexed { index, slot ->
                if (index > 0) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                slot.forEach { cap -> NuvioKeyCap(text = cap) }
            }
        }
        if (onRebind != null) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(Res.string.settings_shortcuts_rebind, shortcut.action),
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(tokens.icons.sm),
            )
        }
    }
}

/** The heading-level reset every section of this page carries, on the far right of its title. */
@Composable
private fun ShortcutsResetLabel(onClick: () -> Unit) {
    NuvioActionLabel(text = stringResource(Res.string.action_reset), onClick = onClick)
}


/**
 * Controller settings live on the shortcuts page rather than a page of their own because a pad is
 * not a separate input model here — every button is replayed as the keystroke listed beside it, so
 * this inventory and the keyboard inventory describe the same bindings.
 *
 * The mapping list is read-only: rebinding a button means rebinding the underlying shortcut in the
 * sections above, which moves both at once.
 */
@Composable
private fun GamepadSection(isTablet: Boolean) {
    LaunchedEffect(Unit) { ensureGamepadSettingsLoaded() }
    val enabled by gamepadEnabledState().collectAsState()
    val connected by gamepadConnectedState().collectAsState()
    val deadZone by gamepadDeadZoneState().collectAsState()
    val repeatInterval by gamepadRepeatIntervalState().collectAsState()
    val mouseKeyboard by mouseKeyboardState().collectAsState()
    val rows = remember { gamepadMappingRows() }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s18)) {
        // Its own section rather than a Controller row: it works with no controller at all, and
        // the Controller rows grey out while gamepad control is off.
        SettingsSection(title = stringResource(Res.string.settings_shortcuts_on_screen_keyboard), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_shortcuts_osk_show_for_mouse),
                    description = stringResource(Res.string.settings_shortcuts_osk_show_for_mouse_description),
                    checked = mouseKeyboard,
                    isTablet = isTablet,
                    onCheckedChange = { setMouseKeyboard(it) },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_shortcuts_controller),
            isTablet = isTablet,
            actions = { ShortcutsResetLabel(onClick = { resetGamepadSettings() }) },
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_shortcuts_gamepad_control),
                    description = when {
                        !enabled -> stringResource(Res.string.settings_shortcuts_gamepad_control_off_description)
                        connected -> stringResource(Res.string.settings_shortcuts_gamepad_connected)
                        else -> stringResource(Res.string.settings_shortcuts_gamepad_not_detected)
                    },
                    checked = enabled,
                    isTablet = isTablet,
                    onCheckedChange = { setGamepadEnabled(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                GamepadSliderRow(
                    title = stringResource(Res.string.settings_shortcuts_gamepad_dead_zone),
                    description = stringResource(Res.string.settings_shortcuts_gamepad_dead_zone_description),
                    value = deadZone,
                    valueRange = gamepadDeadZoneRange(),
                    valueLabel = "${(deadZone * 100).roundToInt()}%",
                    enabled = enabled,
                    isTablet = isTablet,
                    onValueChange = { setGamepadDeadZone(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                GamepadSliderRow(
                    title = stringResource(Res.string.settings_shortcuts_gamepad_repeat_speed),
                    description = stringResource(Res.string.settings_shortcuts_gamepad_repeat_speed_description),
                    value = repeatInterval.toFloat(),
                    valueRange = gamepadRepeatIntervalRange().let { it.first.toFloat()..it.last.toFloat() },
                    valueLabel = "$repeatInterval ms",
                    enabled = enabled,
                    isTablet = isTablet,
                    onValueChange = { setGamepadRepeatIntervalMs(it.roundToInt()) },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_shortcuts_controller_buttons),
            isTablet = isTablet,
            subtitle = stringResource(Res.string.settings_shortcuts_controller_buttons_note),
        ) {
            SettingsGroup(isTablet = isTablet) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    // Rendered through the same row as every keyboard shortcut above: action on the
                    // left, the thing you press on the right. The pad button is the key cap here.
                    ShortcutRow(
                        shortcut = Shortcut(row.browsingLabel, key(row.button.displayName)),
                        isTablet = isTablet,
                        description = stringResource(Res.string.settings_shortcuts_gamepad_in_player, row.playerLabel),
                    )
                }
            }
        }
    }
}

@Composable
private fun GamepadSliderRow(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean,
    isTablet: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // Held locally so the thumb tracks the drag; the repository only sees the settled value.
    var draft by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 16.dp else NuvioTokens.Space.s12, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
                fontWeight = FontWeight.Medium,
            )
            SettingsSubtext(text = description, isTablet = isTablet)
        }
        Row(
            modifier = Modifier.width(if (isTablet) DesktopControlWidth else 220.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsModernSlider(
                value = draft,
                onValueChange = { if (enabled) draft = it },
                onValueChangeFinished = { if (enabled) onValueChange(draft) },
                enabled = enabled,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
                valueLabel = valueLabel,
            )
        }
    }
}

