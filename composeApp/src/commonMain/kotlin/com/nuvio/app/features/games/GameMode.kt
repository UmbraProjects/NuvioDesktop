package com.nuvio.app.features.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is currently showing the game library instead of its media UI.
 *
 * Deliberately not a navigation route: game mode is a whole-window alternate presentation, and
 * routing to it would tear down the tab host — and with it the home rows, scroll positions and
 * catalog state the user expects to find exactly as they left them when they toggle back. It is
 * an overlay over a still-composed app instead, and the state is transient: a restart always
 * comes back up in the ordinary media UI.
 */
object GameModeController {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }

    fun toggle() {
        _active.value = !_active.value
    }
}

/**
 * The game library, sized to fill whatever it is placed in.
 *
 * [onExit] returns to the media UI; [onOpenSettings] leaves game mode and opens its settings page,
 * since game mode's configuration lives with the rest of the app's settings rather than behind a
 * dialog of its own.
 */
@Composable
expect fun GameModeScreen(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
)
