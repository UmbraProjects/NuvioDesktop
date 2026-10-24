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
import com.nuvio.app.features.player.playerShortcutKeyLabels
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
import com.nuvio.app.features.input.resetGamepadSettings
import com.nuvio.app.features.input.setGamepadDeadZone
import com.nuvio.app.features.input.setGamepadEnabled
import com.nuvio.app.features.input.setGamepadRepeatIntervalMs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
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

private fun navigationShortcuts(labels: Map<AppShortcutAction, String>): List<Shortcut> =
    AppShortcutAction.entries.map { action ->
        Shortcut(action.displayName, key(labels[action] ?: "—"), appAction = action)
    } + browsingShortcuts()

/**
 * Fixed browsing keys on Home, Library, Search and Collections. Not rebindable, so they carry no
 * [AppShortcutAction] — they are listed here because this page is the shortcut inventory.
 */
private fun browsingShortcuts(): List<Shortcut> = listOf(
    Shortcut("Page down (row in TV mode, page otherwise)", key("Page Down")),
    Shortcut("Page up (row in TV mode, page otherwise)", key("Page Up")),
    Shortcut("Jump to top", key("Home")),
    Shortcut("Jump to bottom", key("End")),
)

/**
 * A player shortcut row. When [action] is set, the primary key renders live from the current
 * binding; [extraKeys] are fixed, non-rebindable alternates shown after it (e.g. `K`, `←`).
 * When [action] is null the row is fully fixed and uses [fixedKeys].
 */
private class PlayerRowSpec(
    val label: String,
    val action: PlayerShortcutAction?,
    val extraKeys: List<String> = emptyList(),
    val fixedKeys: List<String> = emptyList(),
)

private fun PlayerRowSpec.toShortcut(labels: Map<PlayerShortcutAction, String>): Shortcut {
    val caps = if (action != null) listOfNotNull(labels[action]) + extraKeys else fixedKeys
    return Shortcut(label, caps.map { listOf(it) })
}

private val playerRowSections: List<Pair<String, List<PlayerRowSpec>>> = listOf(
    "Player · Playback" to listOf(
        PlayerRowSpec(PlayerShortcutAction.PlayPause.displayName, PlayerShortcutAction.PlayPause),
        PlayerRowSpec(PlayerShortcutAction.AlternatePlayPause.displayName, PlayerShortcutAction.AlternatePlayPause),
        PlayerRowSpec(PlayerShortcutAction.ToggleMute.displayName, PlayerShortcutAction.ToggleMute),
        PlayerRowSpec(PlayerShortcutAction.SeekBackward.displayName, PlayerShortcutAction.SeekBackward, extraKeys = listOf("←")),
        PlayerRowSpec(PlayerShortcutAction.SeekForward.displayName, PlayerShortcutAction.SeekForward, extraKeys = listOf("→")),
        PlayerRowSpec(PlayerShortcutAction.SpeedUp.displayName, PlayerShortcutAction.SpeedUp),
        PlayerRowSpec(PlayerShortcutAction.SpeedDown.displayName, PlayerShortcutAction.SpeedDown),
        PlayerRowSpec(PlayerShortcutAction.ToggleSpeed.displayName, PlayerShortcutAction.ToggleSpeed),
        PlayerRowSpec("Volume up", null, fixedKeys = listOf("↑")),
        PlayerRowSpec("Volume down", null, fixedKeys = listOf("↓")),
    ),
    "Player · Tracks & Panels" to listOf(
        PlayerRowSpec(PlayerShortcutAction.NextSubtitle.displayName, PlayerShortcutAction.NextSubtitle),
        PlayerRowSpec(PlayerShortcutAction.NextAudio.displayName, PlayerShortcutAction.NextAudio),
        PlayerRowSpec(PlayerShortcutAction.OpenSources.displayName, PlayerShortcutAction.OpenSources),
        PlayerRowSpec(PlayerShortcutAction.OpenEpisodes.displayName, PlayerShortcutAction.OpenEpisodes),
        PlayerRowSpec(PlayerShortcutAction.CycleZoom.displayName, PlayerShortcutAction.CycleZoom),
        PlayerRowSpec(PlayerShortcutAction.SkipInterval.displayName, PlayerShortcutAction.SkipInterval),
    ),
    "Player · Video Enhancement" to listOf(
        PlayerRowSpec(PlayerShortcutAction.CycleSvp.displayName, PlayerShortcutAction.CycleSvp),
        PlayerRowSpec(PlayerShortcutAction.CycleHdr.displayName, PlayerShortcutAction.CycleHdr),
        PlayerRowSpec(PlayerShortcutAction.CycleColorProfile.displayName, PlayerShortcutAction.CycleColorProfile),
        PlayerRowSpec(PlayerShortcutAction.CycleAnime.displayName, PlayerShortcutAction.CycleAnime),
        PlayerRowSpec(PlayerShortcutAction.ToggleMpvDiagnostics.displayName, PlayerShortcutAction.ToggleMpvDiagnostics),
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
        var rebinding by remember { mutableStateOf<PlayerShortcutAction?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s18)) {
            playerRowSections.forEach { (title, specs) ->
                val sectionActions = specs.mapNotNull { it.action }
                SettingsSection(
                    title = title,
                    isTablet = isTablet,
                    actions = { ShortcutsResetLabel(onClick = { resetPlayerShortcuts(sectionActions) }) },
                ) {
                    SettingsGroup(isTablet = isTablet) {
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
    val rows = remember { gamepadMappingRows() }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s18)) {
        SettingsSection(
            title = "Controller",
            isTablet = isTablet,
            actions = { ShortcutsResetLabel(onClick = { resetGamepadSettings() }) },
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Gamepad control",
                    description = when {
                        !enabled -> "Drive Nuvio with a PlayStation or Xbox controller, wired or over Bluetooth."
                        connected -> "Controller connected."
                        else -> "No controller detected. Connect one over USB or Bluetooth — no extra drivers needed."
                    },
                    checked = enabled,
                    isTablet = isTablet,
                    onCheckedChange = { setGamepadEnabled(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                GamepadSliderRow(
                    title = "Stick dead zone",
                    description = "How far the left stick must move before it counts. Raise this if the UI drifts on its own.",
                    value = deadZone,
                    valueRange = gamepadDeadZoneRange(),
                    valueLabel = "${(deadZone * 100).roundToInt()}%",
                    enabled = enabled,
                    isTablet = isTablet,
                    onValueChange = { setGamepadDeadZone(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                GamepadSliderRow(
                    title = "Repeat speed",
                    description = "Gap between steps while a direction is held.",
                    value = repeatInterval.toFloat(),
                    valueRange = gamepadRepeatIntervalRange().let { it.first.toFloat()..it.last.toFloat() },
                    valueLabel = "$repeatInterval ms",
                    enabled = enabled,
                    isTablet = isTablet,
                    onValueChange = { setGamepadRepeatIntervalMs(it.roundToInt()) },
                )
            }
        }

        SettingsSection(title = "Controller · Buttons", isTablet = isTablet) {
            SettingsSectionNote(
                text = "While a panel is open during playback, the D-pad navigates the panel instead.",
                isTablet = isTablet,
            )
            SettingsGroup(isTablet = isTablet) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    // Rendered through the same row as every keyboard shortcut above: action on the
                    // left, the thing you press on the right. The pad button is the key cap here.
                    ShortcutRow(
                        shortcut = Shortcut(row.browsingLabel, key(row.button.displayName)),
                        isTablet = isTablet,
                        description = "In player: ${row.playerLabel}",
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
        Column(
            modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ValueBox(text = valueLabel)
            SettingsModernSlider(
                value = draft,
                onValueChange = { if (enabled) draft = it },
                onValueChangeFinished = { if (enabled) onValueChange(draft) },
                enabled = enabled,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

