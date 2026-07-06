package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, session-scoped audio state for hero trailers (both the home hero and the details
 * hero). [volume] is the audible level (1..100) and is kept independent of [muted] so that
 * consumers which gate sound their own way (e.g. the home hero uses a settings toggle) still
 * get a sensible non-zero level instead of silence. The overlay volume slider shows the
 * effective level (0 when muted); dragging it to 0 mutes, and to any positive value unmutes.
 */
object HeroTrailerAudioState {
    private const val DEFAULT_VOLUME = 60

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    // 1..100 audible level, retained across mute toggles.
    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    fun setMuted(muted: Boolean) {
        _muted.value = muted
    }

    fun toggleMuted() {
        _muted.value = !_muted.value
    }

    /** Slider-driven: 0 mutes (level retained); any positive value sets the level and unmutes. */
    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        if (clamped <= 0) {
            _muted.value = true
        } else {
            _volume.value = clamped
            _muted.value = false
        }
    }
}
