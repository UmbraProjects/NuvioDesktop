package com.nuvio.app.features.themesong

import com.nuvio.app.features.preroll.PreshowSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val THEME_SONG_DEFAULT_VOLUME = 50

/**
 * Theme songs: a title's main theme played quietly behind the trivia pre-show and behind the
 * details screen. Both are off until switched on. Stored beside the pre-show settings.
 */
object ThemeSongSettings {
    private val storage = PreshowSettingsStorage

    private val _triviaEnabled = MutableStateFlow(storage.load("theme_trivia") == "true")
    /** Play the theme while the trivia cards are shown. */
    val triviaEnabled: StateFlow<Boolean> = _triviaEnabled.asStateFlow()

    private val _detailsEnabled = MutableStateFlow(storage.load("theme_details") == "true")
    /** Play the theme behind a movie's or show's details screen. */
    val detailsEnabled: StateFlow<Boolean> = _detailsEnabled.asStateFlow()

    private val _volume = MutableStateFlow(
        (storage.load("theme_volume")?.toIntOrNull() ?: THEME_SONG_DEFAULT_VOLUME).coerceIn(5, 100),
    )
    /** 5..100, the level the theme settles at after its fade in. */
    val volume: StateFlow<Int> = _volume.asStateFlow()

    fun setTriviaEnabled(enabled: Boolean) {
        if (_triviaEnabled.value == enabled) return
        _triviaEnabled.value = enabled
        storage.save("theme_trivia", if (enabled) "true" else null)
    }

    fun setDetailsEnabled(enabled: Boolean) {
        if (_detailsEnabled.value == enabled) return
        _detailsEnabled.value = enabled
        storage.save("theme_details", if (enabled) "true" else null)
    }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(5, 100)
        if (_volume.value == clamped) return
        _volume.value = clamped
        storage.save("theme_volume", clamped.toString())
    }
}
