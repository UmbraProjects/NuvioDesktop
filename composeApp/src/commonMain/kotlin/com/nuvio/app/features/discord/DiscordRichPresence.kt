package com.nuvio.app.features.discord

internal enum class DiscordRichPresenceActivityType {
    Playback,
    Browsing,
}

internal enum class DiscordRichPresenceImageFit {
    Cover,
    Contain,
}

internal data class DiscordRichPresenceActivity(
    val title: String,
    val subtitle: String? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val imageUrl: String? = null,
    // Served by the resizing proxy when [imageUrl] is reachable but has nothing to give — a poster
    // service answering 404 for a title it holds no art for.
    val fallbackImageUrl: String? = null,
    val imageFit: DiscordRichPresenceImageFit = DiscordRichPresenceImageFit.Cover,
    val type: DiscordRichPresenceActivityType = DiscordRichPresenceActivityType.Playback,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Bumped periodically while paused so the platform re-pushes and re-anchors the (otherwise
    // live) progress bar to the frozen position instead of letting it creep toward the end.
    val refreshNonce: Long = 0L,
)

internal object DiscordRichPresenceController {
    private var browsingActivity: DiscordRichPresenceActivity? = null
    private var playbackActivity: DiscordRichPresenceActivity? = null

    fun setBrowsingActivity(activity: DiscordRichPresenceActivity?) {
        browsingActivity = activity
        publish()
    }

    fun setPlaybackActivity(activity: DiscordRichPresenceActivity?) {
        playbackActivity = activity
        publish()
    }

    fun refresh() {
        publish()
    }

    private fun publish() {
        DiscordPresenceSettingsRepository.ensureLoaded()
        if (DiscordPresenceSettingsRepository.uiState.value.mode == DiscordPresenceMode.Disabled) {
            DiscordRichPresencePlatform.update(null)
            return
        }
        // In Watching mode the browsing activity is never populated (the UI layer only sets it
        // in Full), so preferring playback then browsing yields the correct behaviour for both.
        DiscordRichPresencePlatform.update(playbackActivity ?: browsingActivity)
    }
}

internal expect object DiscordRichPresencePlatform {
    fun update(activity: DiscordRichPresenceActivity?)
    fun shutdown()
}
