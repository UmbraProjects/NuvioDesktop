package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge exposing the desktop player shortcut bindings + rebinding UI to shared settings code.
 * The label flow maps each rebindable [PlayerShortcutAction] to a human-readable key label
 * (e.g. "Space", "F10", "←"). Only a desktop actual exists (the sole KMP target).
 */
expect fun playerShortcutKeyLabels(): StateFlow<Map<PlayerShortcutAction, String>>

/** Ensures persisted overrides are loaded before the reference page reads [playerShortcutKeyLabels]. */
expect fun ensurePlayerShortcutBindingsLoaded()

/** Restores every rebindable player shortcut to its default key. */
expect fun resetAllPlayerShortcuts()

/** Reloads bindings for the newly active profile (bindings are profile-scoped). */
expect fun onPlayerShortcutsProfileChanged()

expect fun appShortcutKeyLabels(): StateFlow<Map<AppShortcutAction, String>>
expect fun ensureAppShortcutBindingsLoaded()
expect fun resetAllAppShortcuts()
expect fun appShortcutMatches(action: AppShortcutAction, event: KeyEvent): Boolean

object AppShortcutBridge {
    private val _events = MutableSharedFlow<AppShortcutAction>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    fun emit(action: AppShortcutAction) { _events.tryEmit(action) }
}

/**
 * Modal that captures the next key press and rebinds [action] to it, rejecting reserved/fixed
 * keys and keys already assigned to another action. Includes reset-to-default and cancel.
 */
@Composable
expect fun PlayerShortcutRebindDialog(action: PlayerShortcutAction, onDismiss: () -> Unit)

@Composable
expect fun AppShortcutRebindDialog(action: AppShortcutAction, onDismiss: () -> Unit)
