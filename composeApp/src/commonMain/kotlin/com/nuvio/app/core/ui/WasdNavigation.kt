package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

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
    if (!WasdNavigation.enabled) return key
    return when (key) {
        Key.W -> Key.DirectionUp
        Key.A -> Key.DirectionLeft
        Key.S -> Key.DirectionDown
        Key.D -> Key.DirectionRight
        else -> key
    }
}
