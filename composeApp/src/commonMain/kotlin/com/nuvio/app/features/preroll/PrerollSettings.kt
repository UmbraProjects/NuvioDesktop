package com.nuvio.app.features.preroll

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal expect object PrerollSettingsStorage {
    fun loadTrailerCount(): Int?
    fun saveTrailerCount(count: Int)
}

const val PREROLL_MAX_TRAILERS = 5
// Off until chosen: the pre-show runs before every fresh playback, episodes included.
const val PREROLL_DEFAULT_TRAILERS = 0

/**
 * How many trailers play before a film starts. The trailers are the "More Like This" titles on
 * the film's own details page, so they are things the viewer might want next.
 *
 * 0 turns the trailer part off; the trivia pre-show is governed by its own AI setting.
 */
object PrerollSettings {
    private val _trailerCount = MutableStateFlow(
        (PrerollSettingsStorage.loadTrailerCount() ?: PREROLL_DEFAULT_TRAILERS)
            .coerceIn(0, PREROLL_MAX_TRAILERS),
    )
    val trailerCount: StateFlow<Int> = _trailerCount.asStateFlow()

    fun setTrailerCount(count: Int) {
        val clamped = count.coerceIn(0, PREROLL_MAX_TRAILERS)
        if (_trailerCount.value == clamped) return
        _trailerCount.value = clamped
        PrerollSettingsStorage.saveTrailerCount(clamped)
    }
}
