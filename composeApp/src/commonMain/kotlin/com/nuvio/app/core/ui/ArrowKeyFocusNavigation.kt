package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/*
 * Compose Desktop moves focus on Tab and Shift+Tab only: the arrow keys (and so a pad's D-pad, which arrives as them)
 * reach the focused control and stop there. The screens that run their own selection (Home's rows, the source picker)
 * handle the arrows themselves; a screen made of ordinary focusable controls needs this to move between them.
 */

/**
 * Arrow keys move focus to the nearest focusable control that way inside this subtree, as a TV remote does. Runs in
 * the bubble phase, so a control that uses the arrows itself (a slider, a text field's cursor, a list with its own
 * keys) gets them first. An arrow with nowhere to go is still taken, so it does not fall through to the window.
 */
@Composable
fun Modifier.arrowKeyFocusNavigation(): Modifier {
    val focusManager = LocalFocusManager.current
    return onKeyEvent { event ->
        val direction = arrowFocusDirection(event) ?: return@onKeyEvent false
        focusManager.moveFocus(direction)
        true
    }
}

/** The focus direction an arrow key-down asks for (W/A/S/D too in TKL mode, outside text fields), or null. */
fun arrowFocusDirection(event: KeyEvent): FocusDirection? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    val key = if (TextInputFocusTracker.active.value) event.key else event.navigationKey()
    return when (key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
}
