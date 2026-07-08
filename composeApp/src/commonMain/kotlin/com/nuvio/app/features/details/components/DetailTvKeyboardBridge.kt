package com.nuvio.app.features.details.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Details-screen keyboard-navigation keys, routed independently of which component holds focus. */
enum class DetailTvKey { Up, Down, Left, Right, Select, TogglePeoplePanel, ToggleMute, VolumeDown, VolumeUp, Dismiss, Back }

/**
 * Bridge for routing details-screen navigation keys independently of which component holds focus.
 * The hero-trailer surface is now passive (it never takes OS keyboard focus — see
 * HeroTrailerPlayerSurface.desktop.kt), so the details screen's own Compose key handler owns all
 * keys and nothing currently emits here; the bridge is retained as a seam for that scenario.
 */
object DetailTvKeyboardBridge {
    private val _keys = MutableSharedFlow<DetailTvKey>(extraBufferCapacity = 8)
    val keys: SharedFlow<DetailTvKey> = _keys.asSharedFlow()

    fun emit(key: DetailTvKey) {
        _keys.tryEmit(key)
    }
}
