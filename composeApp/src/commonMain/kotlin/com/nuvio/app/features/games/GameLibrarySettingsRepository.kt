package com.nuvio.app.features.games

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GameLibrarySettingsRepository {
    private val _uiState = MutableStateFlow(GameLibrarySettings())
    val uiState: StateFlow<GameLibrarySettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var igdbClientId = ""
    private var igdbClientSecret = ""
    private var steamGridDbApiKey = ""
    private var backdropStyle = GameBackdropStyle.BlackShelf

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun snapshot(): GameLibrarySettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setIgdbClientId(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (igdbClientId == normalized) return
        igdbClientId = normalized
        publish()
        GameLibrarySettingsStorage.saveIgdbClientId(normalized)
    }

    fun setIgdbClientSecret(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (igdbClientSecret == normalized) return
        igdbClientSecret = normalized
        publish()
        GameLibrarySettingsStorage.saveIgdbClientSecret(normalized)
    }

    fun setSteamGridDbApiKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (steamGridDbApiKey == normalized) return
        steamGridDbApiKey = normalized
        publish()
        GameLibrarySettingsStorage.saveSteamGridDbApiKey(normalized)
    }

    fun setBackdropStyle(value: GameBackdropStyle) {
        ensureLoaded()
        if (backdropStyle == value) return
        backdropStyle = value
        publish()
        GameLibrarySettingsStorage.saveBackdropStyle(value.name)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        igdbClientId = GameLibrarySettingsStorage.loadIgdbClientId().orEmpty().trim()
        igdbClientSecret = GameLibrarySettingsStorage.loadIgdbClientSecret().orEmpty().trim()
        steamGridDbApiKey = GameLibrarySettingsStorage.loadSteamGridDbApiKey().orEmpty().trim()
        backdropStyle = GameLibrarySettingsStorage.loadBackdropStyle()
            ?.let { stored -> GameBackdropStyle.entries.firstOrNull { it.name == stored } }
            ?: GameBackdropStyle.BlackShelf
        publish()
    }

    private fun publish() {
        _uiState.value = GameLibrarySettings(
            igdbClientId = igdbClientId,
            igdbClientSecret = igdbClientSecret,
            steamGridDbApiKey = steamGridDbApiKey,
            backdropStyle = backdropStyle,
        )
    }
}
