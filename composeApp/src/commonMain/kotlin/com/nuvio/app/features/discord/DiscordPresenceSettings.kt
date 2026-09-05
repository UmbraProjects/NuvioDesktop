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

/**
 * Which artwork an episode's presence should show.
 *
 * Only meaningful for episodic playback — a film has no episode still, so this changes nothing for
 * one. The candidate list is a *preference*, not a requirement: whichever is picked, the other is
 * still the fallback, because Discord fetches artwork from its own servers and a poster that only
 * resolves on the user's LAN is unusable there (see [isExternallyFetchableArtworkUrl]). A user who
 * asks for posters and gets stills anyway is looking at that, not at this setting.
 */
enum class DiscordEpisodeArtwork {
    /** The series poster — portrait, letterboxed into Discord's square. */
    Poster,

    /** The episode still — landscape, and it fills the square without bars. */
    EpisodeThumbnail,
}

data class DiscordPresenceSettings(
    val mode: DiscordPresenceMode = DiscordPresenceMode.Disabled,
    val episodeArtwork: DiscordEpisodeArtwork = DiscordEpisodeArtwork.Poster,
) {
    /** Playback presence is shared in both Watching and Full. */
    val showPlaybackPresence: Boolean get() = mode != DiscordPresenceMode.Disabled

    /** Browsing/library presence is shared only in Full. */
    val showBrowsingPresence: Boolean get() = mode == DiscordPresenceMode.Full
}

internal expect object DiscordPresenceSettingsStorage {
    fun loadMode(): DiscordPresenceMode
    fun saveMode(mode: DiscordPresenceMode)
    fun loadEpisodeArtwork(): DiscordEpisodeArtwork
    fun saveEpisodeArtwork(value: DiscordEpisodeArtwork)
}

object DiscordPresenceSettingsRepository {
    private val _uiState = MutableStateFlow(DiscordPresenceSettings())
    val uiState: StateFlow<DiscordPresenceSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var mode = DiscordPresenceMode.Disabled
    private var episodeArtwork = DiscordEpisodeArtwork.Poster

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        mode = DiscordPresenceSettingsStorage.loadMode()
        episodeArtwork = DiscordPresenceSettingsStorage.loadEpisodeArtwork()
        publish()
    }

    fun setMode(value: DiscordPresenceMode) {
        ensureLoaded()
        if (mode == value) return
        mode = value
        publish()
        DiscordPresenceSettingsStorage.saveMode(value)
    }

    fun setEpisodeArtwork(value: DiscordEpisodeArtwork) {
        ensureLoaded()
        if (episodeArtwork == value) return
        episodeArtwork = value
        publish()
        DiscordPresenceSettingsStorage.saveEpisodeArtwork(value)
        // Note the presence already on screen is rebuilt by the player's own effect, which reads
        // this value as one of its keys. Republishing the stored activity here would not help:
        // it was assembled under the old preference and still carries the old URL.
    }

    private fun publish() {
        _uiState.value = DiscordPresenceSettings(
            mode = mode,
            episodeArtwork = episodeArtwork,
        )
    }
}
