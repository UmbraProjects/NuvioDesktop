package com.nuvio.app.features.home.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** TV-mode navigation keys, routed independently of which component holds focus. */
enum class HomeTvKey {
    Up,
    Down,
    Left,
    Right,
    // Page Up/Down move a screenful. Which axis that is depends on the layout: TV mode is a
    // horizontal carousel, every other mode is a vertical list. Home/End jump to either end.
    PageUp,
    PageDown,
    Home,
    End,
    Select,
    ToggleTrailer,
    ToggleMute,
    VolumeDown,
    VolumeUp,
    TogglePeoplePanel,
    Dismiss,
    Search,
    Library,
}

// How far [HomeTvKey.PageUp]/[HomeTvKey.PageDown] jump. These lists are virtualised, so the number
// of items actually on screen is not knowable from a key handler — the steps approximate a
// screenful instead: the catalog rows that fit down the page, and the posters across a TV row.
internal const val PAGE_SECTION_STEP = 3
internal const val PAGE_ITEM_STEP = 6

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
