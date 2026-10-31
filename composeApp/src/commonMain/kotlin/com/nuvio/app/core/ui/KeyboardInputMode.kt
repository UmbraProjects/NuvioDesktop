package com.nuvio.app.core.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/*
 * Whether the person is driving the app with the keyboard (or a pad, which arrives as keys) rather than the mouse. On
 * desktop a click focuses what it lands on, so a focus ring alone would light up under the pointer too; focus rings ask
 * this as well. The window sets it (Main.kt): a navigation key turns it on, moving or clicking the mouse turns it off.
 */
object KeyboardInputMode {
    var active by mutableStateOf(false)
}

/** Focused, and the keyboard is what is being used: when a focus ring should show. */
@Composable
fun InteractionSource.collectIsKeyboardFocusedAsState(): State<Boolean> {
    val focused = collectIsFocusedAsState()
    return remember(focused) { derivedStateOf { focused.value && KeyboardInputMode.active } }
}
