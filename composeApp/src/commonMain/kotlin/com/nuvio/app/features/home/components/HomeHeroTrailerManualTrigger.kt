package com.nuvio.app.features.home.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets the TV-mode keyboard handler request the focused hero item's trailer to play
 * immediately (the `T` shortcut), independent of the auto-play setting. The hero surface
 * observes [token] and resolves/plays for whatever item is currently focused.
 */
object HomeHeroTrailerManualTrigger {
    private var nextToken = 0
    private val _tokens = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val tokens: SharedFlow<Int> = _tokens.asSharedFlow()

    /** Whether a hero trailer is currently showing, so the key handler can map Escape to dismiss. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun trigger() {
        nextToken += 1
        _tokens.tryEmit(nextToken)
    }

    fun setActive(active: Boolean) {
        _active.value = active
    }
}
