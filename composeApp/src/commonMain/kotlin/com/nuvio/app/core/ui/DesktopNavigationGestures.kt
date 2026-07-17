package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges OS-level "back" gestures (mouse side button / browser-back key) on
 * desktop to the in-app navigation stack.
 */
internal object DesktopNavigationGestureBridge {
    private val _backRequests = MutableSharedFlow<DesktopBackRequestSource>(extraBufferCapacity = 1)
    val backRequests: SharedFlow<DesktopBackRequestSource> = _backRequests.asSharedFlow()

    fun requestBack(source: DesktopBackRequestSource = DesktopBackRequestSource.Keyboard) {
        _backRequests.tryEmit(source)
    }
}

internal enum class DesktopBackRequestSource {
    Keyboard,
    Mouse,
}
