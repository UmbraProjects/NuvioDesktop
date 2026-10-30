package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges OS-level "back" gestures (mouse side button / browser-back key) on
 * desktop to the in-app navigation stack.
 */
internal object DesktopNavigationGestureBridge {
    private val _backRequests = MutableSharedFlow<DesktopBackRequestSource>(extraBufferCapacity = 1)
    val backRequests: SharedFlow<DesktopBackRequestSource> = _backRequests.asSharedFlow()
    private val _horizontalScrollModifierActive = MutableStateFlow(false)
    val horizontalScrollModifierActive: StateFlow<Boolean> =
        _horizontalScrollModifierActive.asStateFlow()

    fun requestBack(source: DesktopBackRequestSource = DesktopBackRequestSource.Keyboard) {
        _backRequests.tryEmit(source)
    }

    fun setHorizontalScrollModifierActive(active: Boolean) {
        _horizontalScrollModifierActive.value = active
    }
}

/**
 * A page shown inside a root tab without a route of its own (the Music tab's ladder) claims "back" here while it is
 * open, so Escape or the mouse's back button closes it instead of leaving the tab for the one before. The newest
 * claim answers first; the app asks [handle] before it walks the tab history.
 */
internal object TabBackInterceptor {
    private val handlers = ArrayList<() -> Unit>()

    fun handle(): Boolean {
        val top = handlers.lastOrNull() ?: return false
        top()
        return true
    }

    fun push(handler: () -> Unit) { handlers += handler }
    fun remove(handler: () -> Unit) { handlers.remove(handler) }
}

/** Claims the tab's back (see [TabBackInterceptor]) while this is composed and [enabled]. */
@Composable
internal fun TabBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val handler: () -> Unit = { latest() }
        if (enabled) TabBackInterceptor.push(handler)
        onDispose { TabBackInterceptor.remove(handler) }
    }
}

internal enum class DesktopBackRequestSource {
    Keyboard,
    Mouse,
}
