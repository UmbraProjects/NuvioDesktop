package com.nuvio.app.features.discord

internal enum class DiscordRichPresenceActivityType {
    Playback,
    Browsing,
}

internal data class DiscordRichPresenceActivity(
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val type: DiscordRichPresenceActivityType = DiscordRichPresenceActivityType.Playback,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
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
        if (!DiscordPresenceSettingsRepository.uiState.value.enabled) {
            DiscordRichPresencePlatform.update(null)
            return
        }
        DiscordRichPresencePlatform.update(playbackActivity ?: browsingActivity)
    }
}

internal expect object DiscordRichPresencePlatform {
    fun update(activity: DiscordRichPresenceActivity?)
    fun shutdown()
}
