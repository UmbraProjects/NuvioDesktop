package com.nuvio.app.features.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.StateFlow
import java.awt.event.KeyEvent

actual fun playerShortcutKeyLabels(): StateFlow<Map<PlayerShortcutAction, String>> =
    PlayerShortcutsRepository.keyLabels

actual fun ensurePlayerShortcutBindingsLoaded() = PlayerShortcutsRepository.ensureLoaded()

actual fun resetAllPlayerShortcuts() = PlayerShortcutsRepository.resetAll()

actual fun onPlayerShortcutsProfileChanged() = PlayerShortcutsRepository.onProfileChanged()

/**
 * Human-readable label for an AWT [KeyEvent] `VK_` key code, tuned for the shortcuts reference:
 * arrows and brackets get glyphs, everything else falls back to [KeyEvent.getKeyText].
 */
internal fun playerShortcutKeyCodeLabel(keyCode: Int): String = when (keyCode) {
    KeyEvent.VK_SPACE -> "Space"
    KeyEvent.VK_LEFT -> "←"
    KeyEvent.VK_RIGHT -> "→"
    KeyEvent.VK_UP -> "↑"
    KeyEvent.VK_DOWN -> "↓"
    KeyEvent.VK_OPEN_BRACKET -> "["
    KeyEvent.VK_CLOSE_BRACKET -> "]"
    KeyEvent.VK_ESCAPE -> "Esc"
    else -> KeyEvent.getKeyText(keyCode)
}

// Keys the dispatcher handles as fixed/navigation and therefore cannot be reassigned. Escape is
// handled separately (cancels the capture).
private val ReservedKeyCodes = setOf(
    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
    KeyEvent.VK_ENTER, KeyEvent.VK_TAB, KeyEvent.VK_K,
)

// Pure modifier presses are ignored so the capture keeps listening for a real key.
private val ModifierKeyCodes = setOf(
    KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH,
    KeyEvent.VK_META, KeyEvent.VK_WINDOWS, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_CONTEXT_MENU,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun PlayerShortcutRebindDialog(action: PlayerShortcutAction, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val focusRequester = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    val currentLabel = playerShortcutKeyCodeLabel(PlayerShortcutsRepository.keyCode(action))

    LaunchedEffect(action) { runCatching { focusRequester.requestFocus() } }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = tokens.shapes.dialog, color = tokens.colors.surfaceDialog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.spacing.dialogPadding)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val vk = event.key.nativeKeyCode
                        when {
                            vk == KeyEvent.VK_ESCAPE -> onDismiss()
                            vk in ModifierKeyCodes -> Unit // keep listening
                            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ->
                                error = "Modifier combos aren't supported — press a single key."
                            vk in ReservedKeyCodes ->
                                error = "\"${playerShortcutKeyCodeLabel(vk)}\" is reserved for navigation and playback."
                            else -> {
                                val existing = PlayerShortcutsRepository.actionForKeyCode(vk)
                                if (existing != null && existing != action) {
                                    error = "\"${playerShortcutKeyCodeLabel(vk)}\" is already assigned to \"${existing.displayName}\"."
                                } else {
                                    PlayerShortcutsRepository.setBinding(action, vk)
                                    onDismiss()
                                }
                            }
                        }
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                Text(
                    text = action.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Currently bound to \"$currentLabel\". Press a new key to rebind.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = tokens.shapes.compactCard,
                    color = tokens.colors.surface,
                    border = BorderStroke(tokens.borders.medium, tokens.colors.borderFocus),
                ) {
                    Text(
                        text = "Listening for a key…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTokens.Space.s16),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            PlayerShortcutsRepository.resetToDefault(action)
                            onDismiss()
                        },
                    ) {
                        Text(text = "Reset to default", maxLines = 1)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = "Cancel", maxLines = 1)
                    }
                }
            }
        }
    }
}
