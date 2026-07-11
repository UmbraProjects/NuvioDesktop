package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether any editable text field on the Settings screen currently holds focus, so the
 * screen's single-key navigation shortcuts (e.g. H = Home) don't fire while the user is typing —
 * for example entering a Local Library catalog name. The settings search bar has its own focus
 * flag; this covers every other field via [trackSettingsTextFocus].
 *
 * All mutation happens on the Compose main thread (onFocusChanged / onDispose).
 */
internal object SettingsTextInputTracker {
    private val focusedFields = mutableSetOf<Any>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setFocused(id: Any, isFocused: Boolean) {
        val changed = if (isFocused) focusedFields.add(id) else focusedFields.remove(id)
        if (changed) _active.value = focusedFields.isNotEmpty()
    }

    /** Force-clears all tracked focus — used when leaving a settings page so the lock can't wedge. */
    fun reset() {
        if (focusedFields.isEmpty() && !_active.value) return
        focusedFields.clear()
        _active.value = false
    }
}

/**
 * Registers this field with [SettingsTextInputTracker] while focused, and clears its registration
 * on dispose so a field that leaves composition mid-focus can't leave shortcuts wedged off.
 */
@Composable
internal fun Modifier.trackSettingsTextFocus(): Modifier {
    val id = remember { Any() }
    DisposableEffect(id) {
        onDispose { SettingsTextInputTracker.setFocused(id, false) }
    }
    return this.onFocusChanged { SettingsTextInputTracker.setFocused(id, it.isFocused) }
}
