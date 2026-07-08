package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Modal that captures the next key press and rebinds [action] to it, rejecting reserved/fixed
 * keys and keys already assigned to another action. Includes reset-to-default and cancel.
 */
@Composable
expect fun PlayerShortcutRebindDialog(action: PlayerShortcutAction, onDismiss: () -> Unit)
