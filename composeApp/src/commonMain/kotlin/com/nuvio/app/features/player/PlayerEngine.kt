package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface PlayerEngineController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun retry()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean) {}
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)
    fun setSubtitleUri(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun setSubtitleDelayMs(delayMs: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
}

enum class PlayerControlsAction {
    ToggleChrome,
    RevealLockedOverlay,
    Back,
    TogglePlayback,
    SeekBack,
    SeekForward,
    ResizeMode,
    Speed,
    Subtitles,
    Audio,
    Sources,
    Episodes,
    OpenExternalPlayer,
    SubmitIntro,
    LockToggle,
    VideoSettings,
    DoubleTapSeekBack,
    DoubleTapSeekForward,
}

data class PlayerControlsState(
    val title: String = "",
    val episodeText: String = "",
    val streamTitle: String = "",
    val providerName: String = "",
    val resizeModeLabel: String = "Fit",
    val playbackSpeedLabel: String = "1x",
    val subtitlesLabel: String = "Subs",
    val audioLabel: String = "Audio",
    val sourcesLabel: String = "Sources",
    val episodesLabel: String = "Episodes",
    val externalPlayerLabel: String = "External",
    val playLabel: String = "Play",
    val pauseLabel: String = "Pause",
    val closeLabel: String = "Close player",
    val lockLabel: String = "Lock player controls",
    val unlockLabel: String = "Unlock player controls",
    val submitIntroLabel: String = "Submit Intro",
    val videoSettingsLabel: String = "Video settings",
    val tapToUnlockLabel: String = "Tap to unlock",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val isLocked: Boolean = false,
    val lockedOverlayVisible: Boolean = false,
    val controlsVisible: Boolean = true,
    val showSubmitIntro: Boolean = false,
    val showVideoSettings: Boolean = false,
    val showSources: Boolean = false,
    val showEpisodes: Boolean = false,
    val showExternalPlayer: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
)

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    useNativeController: Boolean = false,
    playerControlsState: PlayerControlsState = PlayerControlsState(),
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean = { false },
    onPlayerControlsScrubChange: (Long) -> Boolean = { false },
    onPlayerControlsScrubFinished: (Long) -> Boolean = { false },
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
