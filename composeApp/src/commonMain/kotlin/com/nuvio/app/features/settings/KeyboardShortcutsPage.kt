package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerShortcutAction
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutRebindDialog
import com.nuvio.app.features.player.PlayerShortcutRebindDialog
import com.nuvio.app.features.player.ensurePlayerShortcutBindingsLoaded
import com.nuvio.app.features.player.playerShortcutKeyLabels
import com.nuvio.app.features.player.resetAllPlayerShortcuts
import com.nuvio.app.features.player.appShortcutKeyLabels
import com.nuvio.app.features.player.ensureAppShortcutBindingsLoaded
import com.nuvio.app.features.player.resetAllAppShortcuts
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

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
                    horizontal = if (isTablet) 0.dp else NuvioTokens.Space.s12,
                    vertical = NuvioTokens.Space.s4,
                ),
        )
    }

    item(key = "keyboard-shortcuts-navigation") {
        LaunchedEffect(Unit) { ensureAppShortcutBindingsLoaded() }
        val wasdEnabled by ThemeSettingsRepository.wasdNavigationEnabled.collectAsState()
        val appLabels by appShortcutKeyLabels().collectAsState()
        var rebindingApp by remember { mutableStateOf<AppShortcutAction?>(null) }
        SettingsSection(title = stringResource(Res.string.settings_shortcuts_navigation), isTablet = isTablet) {
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
                    flushContent = true,
                    onSelected = { enabled ->
                        ThemeSettingsRepository.setWasdNavigationEnabled(enabled)
                        ensureAppShortcutBindingsLoaded()
                    },
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
        Text(
            text = stringResource(Res.string.settings_shortcuts_reset_navigation),
            color = MaterialTheme.nuvio.colors.accent,
            modifier = Modifier.clickable { resetAllAppShortcuts() }.padding(NuvioTokens.Space.s12),
        )
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
                SettingsSection(title = title, isTablet = isTablet) {
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
            ResetAllShortcutsRow(isTablet = isTablet, onResetAll = { resetAllPlayerShortcuts() })
        }
        rebinding?.let { action ->
            PlayerShortcutRebindDialog(action = action, onDismiss = { rebinding = null })
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: Shortcut, isTablet: Boolean, onRebind: (() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onRebind != null) Modifier.clickable(onClick = onRebind) else Modifier)
            .padding(
                horizontal = if (isTablet) 0.dp else NuvioTokens.Space.s12,
                vertical = NuvioTokens.Space.s10,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(
            text = shortcut.action,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
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
                slot.forEach { cap -> KeyCap(text = cap) }
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

@Composable
private fun ResetAllShortcutsRow(isTablet: Boolean, onResetAll: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = stringResource(Res.string.settings_shortcuts_reset_player),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.accent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable(onClick = onResetAll)
            .padding(
                horizontal = if (isTablet) 0.dp else NuvioTokens.Space.s12,
                vertical = NuvioTokens.Space.s10,
            ),
    )
}

@Composable
private fun KeyCap(text: String) {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = tokens.colors.surface,
        shape = RoundedCornerShape(NuvioTokens.Radius.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .border(
                    BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
                    RoundedCornerShape(NuvioTokens.Radius.sm),
                )
                .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
        )
    }
}
