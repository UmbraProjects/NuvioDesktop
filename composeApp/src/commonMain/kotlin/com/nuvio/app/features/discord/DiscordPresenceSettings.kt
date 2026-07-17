package com.nuvio.app.features.discord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of the Discord Rich Presence integration is shared.
 *
 * - [Disabled]: no presence at all (default when nothing has been configured).
 * - [Watching]: presence only while actively playing something.
 * - [Full]: the complete integration — browsing, library, viewing, and playback.
 */
enum class DiscordPresenceMode {
    Disabled,
    Watching,
    Full,
}

data class DiscordPresenceSettings(
    val mode: DiscordPresenceMode = DiscordPresenceMode.Disabled,
) {
    /** Playback presence is shared in both Watching and Full. */
    val showPlaybackPresence: Boolean get() = mode != DiscordPresenceMode.Disabled

    /** Browsing/library presence is shared only in Full. */
    val showBrowsingPresence: Boolean get() = mode == DiscordPresenceMode.Full
}

internal expect object DiscordPresenceSettingsStorage {
    fun loadMode(): DiscordPresenceMode
    fun saveMode(mode: DiscordPresenceMode)
}

object DiscordPresenceSettingsRepository {
    private val _uiState = MutableStateFlow(DiscordPresenceSettings())
    val uiState: StateFlow<DiscordPresenceSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var mode = DiscordPresenceMode.Disabled

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        mode = DiscordPresenceSettingsStorage.loadMode()
        publish()
    }

    fun setMode(value: DiscordPresenceMode) {
        ensureLoaded()
        if (mode == value) return
        mode = value
        publish()
        DiscordPresenceSettingsStorage.saveMode(value)
    }

    private fun publish() {
        _uiState.value = DiscordPresenceSettings(
            mode = mode,
        )
    }
}
