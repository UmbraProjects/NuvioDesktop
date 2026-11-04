package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Support for replacing one player with another (playlist advance).
 *
 * Two players must never be composed at once: each owns a native mpv surface, and each one's
 * dispose clears process-wide flags (gamepad routing, screensaver guard, download pause, stream
 * prefetch parking) that the incoming one has just set. So the outgoing player is popped first, and
 * the next is only opened once [activeRoutes] says it has gone; the shield hides the gap.
 */
object PlayerHandoff {
    private val _activeRoutes = MutableStateFlow(0)
    val activeRoutes: StateFlow<Int> = _activeRoutes.asStateFlow()

    fun routeEntered() {
        _activeRoutes.value += 1
    }

    fun routeLeft() {
        _activeRoutes.value = (_activeRoutes.value - 1).coerceAtLeast(0)
    }

    /** Black-out the window until the next player's first frame. */
    fun holdShield() = holdPlayerHandoffShield()

    /** fork:youtube-mini-player - a still of the window over the mini player's close, for a moment. */
    fun coverClose() = coverPlayerClose()
}

internal expect fun holdPlayerHandoffShield()

internal expect fun coverPlayerClose()
