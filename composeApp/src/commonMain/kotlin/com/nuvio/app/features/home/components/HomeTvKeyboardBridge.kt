package com.nuvio.app.features.home.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** TV-mode navigation keys, routed independently of which component holds focus. */
enum class HomeTvKey { Up, Down, Left, Right, Select, ToggleTrailer, Dismiss, Search, Library }

/**
 * Bridges keyboard navigation to the TV-mode home while the native hero-trailer surface
 * holds OS focus. The desktop surface installs a global key dispatcher that emits here, and
 * the home screen applies the same navigation it does for Compose key events — mirroring how
 * the desktop player keeps its shortcuts working when the native view is focused.
 */
object HomeTvKeyboardBridge {
    private val _keys = MutableSharedFlow<HomeTvKey>(extraBufferCapacity = 8)
    val keys: SharedFlow<HomeTvKey> = _keys.asSharedFlow()

    fun emit(key: HomeTvKey) {
        _keys.tryEmit(key)
    }
}
