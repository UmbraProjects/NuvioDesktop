package com.nuvio.app.features.discord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscordPresenceSettings(
    val enabled: Boolean = false,
)

internal expect object DiscordPresenceSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}

object DiscordPresenceSettingsRepository {
    private val _uiState = MutableStateFlow(DiscordPresenceSettings())
    val uiState: StateFlow<DiscordPresenceSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var enabled = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        enabled = DiscordPresenceSettingsStorage.loadEnabled() ?: false
        publish()
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (enabled == value) return
        enabled = value
        publish()
        DiscordPresenceSettingsStorage.saveEnabled(value)
    }

    private fun publish() {
        _uiState.value = DiscordPresenceSettings(
            enabled = enabled,
        )
    }
}
