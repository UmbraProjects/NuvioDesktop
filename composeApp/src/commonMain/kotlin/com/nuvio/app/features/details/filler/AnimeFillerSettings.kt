package com.nuvio.app.features.details.filler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal expect object AnimeFillerSettingsStorage {
    fun loadShowBadges(): Boolean?
    fun saveShowBadges(value: Boolean)
    fun loadSkipFiller(): Boolean?
    fun saveSkipFiller(value: Boolean)
}

data class AnimeFillerSettingsState(
    /** Filler / mixed badges on anime episode rows (details page and the player's episode list). */
    val showBadges: Boolean = true,
    /** Next-episode autoplay passes over episodes AnimeFillerList calls pure filler. */
    val skipFiller: Boolean = false,
)

object AnimeFillerSettings {
    private val _state = MutableStateFlow(AnimeFillerSettingsState())
    val state: StateFlow<AnimeFillerSettingsState> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = AnimeFillerSettingsState(
            showBadges = AnimeFillerSettingsStorage.loadShowBadges() ?: true,
            skipFiller = AnimeFillerSettingsStorage.loadSkipFiller() ?: false,
        )
    }

    /** Whether anything wants the filler lookup at all. */
    fun lookupWanted(): Boolean {
        ensureLoaded()
        return _state.value.showBadges || _state.value.skipFiller
    }

    fun setShowBadges(enabled: Boolean) {
        ensureLoaded()
        _state.value = _state.value.copy(showBadges = enabled)
        AnimeFillerSettingsStorage.saveShowBadges(enabled)
    }

    fun setSkipFiller(enabled: Boolean) {
        ensureLoaded()
        _state.value = _state.value.copy(skipFiller = enabled)
        AnimeFillerSettingsStorage.saveSkipFiller(enabled)
    }
}
