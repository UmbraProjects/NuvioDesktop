package com.nuvio.app.core.ui

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

internal enum class DesktopBackRequestSource {
    Keyboard,
    Mouse,
}
