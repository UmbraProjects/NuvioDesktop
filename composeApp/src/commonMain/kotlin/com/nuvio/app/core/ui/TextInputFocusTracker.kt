package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether any editable text field currently holds focus, so the app's single-key shortcuts
 * (e.g. H = Home, S = Search) don't fire while the user is typing. This is app-wide, not tied to any
 * one screen: the content search bar, episode search, collection editors, settings pages and the
 * dialogs all register through [trackTextInputFocus].
 *
 * Focus callbacks can arrive a frame after a field has left composition, and one of those stale
 * `focused = true` callbacks used to wedge the lock on with no field left alive to clear it. Fields
 * are therefore registered while composed and dropped on forget, and [setFocused] ignores anything
 * from an unregistered field. Do not swap that per-field guard for a per-screen one — the lock
 * protects every text field in the app, so gating it on one screen leaves the rest unguarded.
 *
 * All mutation happens on the Compose main thread (apply phase / onFocusChanged).
 */
internal object TextInputFocusTracker {
    private val liveFields = mutableSetOf<Any>()
    private val focusedFields = mutableSetOf<Any>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun register(id: Any) {
        liveFields.add(id)
    }

    fun unregister(id: Any) {
        liveFields.remove(id)
        setFocusedInternal(id, false)
    }

    fun setFocused(id: Any, isFocused: Boolean) {
        // A field that is no longer composed can never be the one receiving keystrokes, so its
        // late callbacks must not be able to re-arm the lock.
        if (isFocused && id !in liveFields) return
        setFocusedInternal(id, isFocused)
    }

    private fun setFocusedInternal(id: Any, isFocused: Boolean) {
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
 * Registers this field with [TextInputFocusTracker] while focused, and drops its registration
 * when it leaves composition so a field that disappears mid-focus can't leave shortcuts wedged off.
 */
@Composable
internal fun Modifier.trackTextInputFocus(): Modifier {
    // A RememberObserver, not a DisposableEffect: onRemembered runs in the apply phase, so the
    // field is registered before it can possibly receive a focus callback.
    val registration = remember { TextInputFocusRegistration() }
    return this.onFocusChanged { registration.setFocused(it.isFocused) }
}

private class TextInputFocusRegistration : RememberObserver {
    private val id = Any()

    fun setFocused(isFocused: Boolean) = TextInputFocusTracker.setFocused(id, isFocused)

    override fun onRemembered() = TextInputFocusTracker.register(id)

    override fun onForgotten() = TextInputFocusTracker.unregister(id)

    override fun onAbandoned() = TextInputFocusTracker.unregister(id)
}
