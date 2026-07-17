package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.appShortcutMatches

/**
 * "TKL mode": when enabled, W/A/S/D act as the arrow keys for grid navigation, for keyboards
 * without a dedicated arrow cluster. The value is mirrored here from `ThemeSettingsRepository`
 * so low-level `onPreviewKeyEvent` handlers can read it (via [navigationKey]) without depending
 * on the settings layer. While on, Search moves off S to Q (handled at the Home search-activation
 * site). Text-input screens (Search, the search overlay) are intentionally excluded so W/A/S/D
 * still type.
 */
object WasdNavigation {
    @Volatile
    var enabled: Boolean = false
}

/** Maps W/A/S/D to arrow directions when [WasdNavigation] is enabled; otherwise the key unchanged. */
fun KeyEvent.navigationKey(): Key {
    val actionKey = when {
        appShortcutMatches(AppShortcutAction.SelectFocused, this) -> Key.Enter
        appShortcutMatches(AppShortcutAction.GoBack, this) -> Key.Backspace
        appShortcutMatches(AppShortcutAction.DismissOverlay, this) -> Key.Escape
        // Once rebound, the historical defaults must stop acting as hidden alternates.
        key == Key.Enter || key == Key.NumPadEnter || key == Key.Backspace || key == Key.Escape -> Key.Unknown
        else -> key
    }
    if (!WasdNavigation.enabled) return actionKey
    return when (actionKey) {
        Key.W -> Key.DirectionUp
        Key.A -> Key.DirectionLeft
        Key.S -> Key.DirectionDown
        Key.D -> Key.DirectionRight
        else -> actionKey
    }
}
