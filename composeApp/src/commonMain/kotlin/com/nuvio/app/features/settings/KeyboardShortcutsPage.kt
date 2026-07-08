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
import com.nuvio.app.features.player.PlayerShortcutRebindDialog
import com.nuvio.app.features.player.ensurePlayerShortcutBindingsLoaded
import com.nuvio.app.features.player.playerShortcutKeyLabels
import com.nuvio.app.features.player.resetAllPlayerShortcuts

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
)

private fun key(vararg alternatives: String): List<List<String>> =
    alternatives.map { listOf(it) }

private fun navigationShortcuts(wasdEnabled: Boolean): List<Shortcut> = listOf(
    Shortcut("Go to Home", key("H")),
    Shortcut("Open Search", key(if (wasdEnabled) "Q" else "S")),
    Shortcut("Open Library (toggle back to Home)", key("L")),
    Shortcut("Open Calendar", key("C")),
    Shortcut("Play / dismiss focused trailer", key("T")),
    Shortcut("Toggle trailer mute", key("M")),
    Shortcut("Swap Starring / Production in hero", key("P")),
    Shortcut("Toggle fullscreen / windowed", key("F11")),
    Shortcut("Move focus", if (wasdEnabled) key("W", "A", "S", "D") else key("↑", "↓", "←", "→")),
    Shortcut("Select / open focused item", key("Enter")),
    Shortcut("Go back", key("Backspace")),
    Shortcut("Close / dismiss overlay", key("Esc")),
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
        PlayerRowSpec(PlayerShortcutAction.PlayPause.displayName, PlayerShortcutAction.PlayPause, extraKeys = listOf("K")),
        PlayerRowSpec(PlayerShortcutAction.SeekBackward.displayName, PlayerShortcutAction.SeekBackward, extraKeys = listOf("←")),
        PlayerRowSpec(PlayerShortcutAction.SeekForward.displayName, PlayerShortcutAction.SeekForward, extraKeys = listOf("→")),
        PlayerRowSpec(PlayerShortcutAction.SpeedUp.displayName, PlayerShortcutAction.SpeedUp),
        PlayerRowSpec(PlayerShortcutAction.SpeedDown.displayName, PlayerShortcutAction.SpeedDown),
        PlayerRowSpec("Volume up", null, fixedKeys = listOf("↑")),
        PlayerRowSpec("Volume down", null, fixedKeys = listOf("↓")),
    ),
    "Player · Tracks & Panels" to listOf(
        PlayerRowSpec(PlayerShortcutAction.NextSubtitle.displayName, PlayerShortcutAction.NextSubtitle),
        PlayerRowSpec(PlayerShortcutAction.NextAudio.displayName, PlayerShortcutAction.NextAudio),
        PlayerRowSpec(PlayerShortcutAction.OpenSources.displayName, PlayerShortcutAction.OpenSources),
        PlayerRowSpec(PlayerShortcutAction.OpenEpisodes.displayName, PlayerShortcutAction.OpenEpisodes),
        PlayerRowSpec(PlayerShortcutAction.CycleZoom.displayName, PlayerShortcutAction.CycleZoom),
        PlayerRowSpec("Skip intro / outro (while prompt is shown)", null, fixedKeys = listOf("Tab")),
    ),
    "Player · Video Enhancement" to listOf(
        PlayerRowSpec(PlayerShortcutAction.CycleSvp.displayName, PlayerShortcutAction.CycleSvp),
        PlayerRowSpec(PlayerShortcutAction.CycleHdr.displayName, PlayerShortcutAction.CycleHdr),
        PlayerRowSpec(PlayerShortcutAction.CycleColorProfile.displayName, PlayerShortcutAction.CycleColorProfile),
        PlayerRowSpec(PlayerShortcutAction.CycleAnime.displayName, PlayerShortcutAction.CycleAnime),
    ),
)

internal fun LazyListScope.keyboardShortcutsContent(isTablet: Boolean) {
    item(key = "keyboard-shortcuts-intro") {
        Text(
            text = "Shortcuts work while the app is focused. Player shortcuts apply during " +
                "playback; text fields (like Search) always keep their keys. Click a player " +
                "shortcut below to rebind it.",
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
        val wasdEnabled by ThemeSettingsRepository.wasdNavigationEnabled.collectAsState()
        SettingsSection(title = "Navigation", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "WASD navigation (TKL mode)",
                    description = "Use W/A/S/D like the arrow keys for browsing; Search moves to Q. " +
                        "For keyboards without an arrow cluster. Doesn't apply while typing (e.g. Search).",
                    checked = wasdEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { ThemeSettingsRepository.setWasdNavigationEnabled(it) },
                )
                navigationShortcuts(wasdEnabled).forEach { shortcut ->
                    SettingsGroupDivider(isTablet = isTablet)
                    ShortcutRow(shortcut = shortcut, isTablet = isTablet)
                }
            }
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
                contentDescription = "Rebind ${shortcut.action}",
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
        text = "Reset all player shortcuts to defaults",
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
