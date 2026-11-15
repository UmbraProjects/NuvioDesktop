package com.nuvio.app.features.profiles

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The digit a key types on a PIN pad, top row or number pad, or null. */
internal fun pinDigitFor(key: Key): String? = when (key) {
    Key.Zero, Key.NumPad0 -> "0"
    Key.One, Key.NumPad1 -> "1"
    Key.Two, Key.NumPad2 -> "2"
    Key.Three, Key.NumPad3 -> "3"
    Key.Four, Key.NumPad4 -> "4"
    Key.Five, Key.NumPad5 -> "5"
    Key.Six, Key.NumPad6 -> "6"
    Key.Seven, Key.NumPad7 -> "7"
    Key.Eight, Key.NumPad8 -> "8"
    Key.Nine, Key.NumPad9 -> "9"
    else -> null
}

/**
 * Lets a PIN pad be typed on: the number keys (and the number pad) type digits, Backspace or Delete takes one off,
 * Escape cancels when [onCancel] is given. Put it on the pad's container: it takes focus when shown, and still sees
 * the keys while a pad button has the focus (a gamepad walking the keys). The window's own shortcuts run after
 * it (onKeyEvent), so a digit it takes never also fires one.
 */
internal fun Modifier.pinKeyInput(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCancel: (() -> Unit)? = null,
): Modifier = composed {
    val digit by rememberUpdatedState(onDigit)
    val back by rememberUpdatedState(onBackspace)
    val cancel by rememberUpdatedState(onCancel)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    this
        .onPreviewKeyEvent { event ->
            val typed = pinDigitFor(event.key)
            val handled = when {
                typed != null -> { if (event.type == KeyEventType.KeyDown) digit(typed); true }
                event.key == Key.Backspace || event.key == Key.Delete -> {
                    if (event.type == KeyEventType.KeyDown) back(); true
                }
                event.key == Key.Escape && cancel != null -> {
                    if (event.type == KeyEventType.KeyUp) cancel?.invoke(); true
                }
                else -> false
            }
            handled
        }
        .focusRequester(focus)
        .focusable()
}
