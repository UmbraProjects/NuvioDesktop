package com.nuvio.app.features.discord

internal data class DiscordRichPresenceActivity(
    val title: String,
    val subtitle: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
)

internal expect object DiscordRichPresencePlatform {
    fun update(activity: DiscordRichPresenceActivity?)
    fun shutdown()
}
