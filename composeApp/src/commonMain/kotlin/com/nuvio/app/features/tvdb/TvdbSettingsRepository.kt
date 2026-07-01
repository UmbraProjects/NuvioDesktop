package com.nuvio.app.features.tvdb

import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TvdbSettingsRepository {
    private val _uiState = MutableStateFlow(TvdbSettings())
    val uiState: StateFlow<TvdbSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var apiKey = ""

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun snapshot(): TvdbSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setApiKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (apiKey == normalized) return
        apiKey = normalized
        publish()
        TvdbSettingsStorage.saveApiKey(normalized)
        TvdbImageService.clearCache()
        if (apiKey.isBlank()) {
            // The "TMDB movies + TVDB shows" hero image mode requires this key — without it the
            // toggle is left checked-but-disabled, which is confusing and stale. Fall back to
            // the next best mode the user can actually use.
            val tmdbSettings = TmdbSettingsRepository.snapshot()
            if (tmdbSettings.heroImageSource == HeroImageSource.TmdbMoviesTvdbShows) {
                val fallback = if (tmdbSettings.hasApiKey) HeroImageSource.TmdbOnly else HeroImageSource.Addon
                TmdbSettingsRepository.setHeroImageSource(fallback)
            }
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        apiKey = TvdbSettingsStorage.loadApiKey()?.trim().orEmpty()
        publish()
    }

    private fun publish() {
        _uiState.value = TvdbSettings(apiKey = apiKey)
    }
}
