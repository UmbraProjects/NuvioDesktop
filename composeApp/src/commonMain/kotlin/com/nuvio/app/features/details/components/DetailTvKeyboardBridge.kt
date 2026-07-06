package com.nuvio.app.features.details.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Details-screen keyboard-navigation keys, routed independently of which component holds focus. */
enum class DetailTvKey { Up, Down, Left, Right, Select, Dismiss, Back }

/**
 * Bridges keyboard navigation to the details screen while the native hero-trailer surface holds
 * OS focus. The native mpv surface can become the AWT focus owner even though its Swing host is
 * marked non-focusable, which stops Compose from receiving key events and breaks the details
 * keyboard navigation. The desktop trailer surface installs a global key dispatcher that emits
 * here while it is mounted, and the details screen applies the same navigation it does for
 * Compose key events — mirroring [com.nuvio.app.features.home.components.HomeTvKeyboardBridge].
 */
object DetailTvKeyboardBridge {
    private val _keys = MutableSharedFlow<DetailTvKey>(extraBufferCapacity = 8)
    val keys: SharedFlow<DetailTvKey> = _keys.asSharedFlow()

    fun emit(key: DetailTvKey) {
        _keys.tryEmit(key)
    }
}
