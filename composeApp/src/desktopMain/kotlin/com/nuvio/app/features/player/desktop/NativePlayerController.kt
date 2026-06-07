package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.SubtitleTrack
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile

internal class NativePlayerController(
    private val host: NativePlayerHost,
) : PlayerEngineController {
    @Volatile
    private var handle: Long = 0L
    private var pendingSource: PendingSource? = null
    private var controlsState = PlayerControlsState()
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    private val eventSink = NativePlayerEventSink { type, value ->
        SwingUtilities.invokeLater {
            handlePlayerEvent(type, value)
        }
    }

    fun attach(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        onError: (String?) -> Unit,
    ) {
        val pending = PendingSource(sourceUrl, sourceHeaders.toHeaderLines(), playWhenReady, onError)
        pendingSource = pending
        host.onPeerReady = { attachPending() }
        if (host.isDisplayable) {
            attachPending()
        }
    }

    private fun attachPending() {
        val pending = pendingSource ?: return
        SwingUtilities.invokeLater {
            if (!host.isDisplayable) return@invokeLater
            dispose()
            runCatching {
                val hostViewPtr = AwtNativeViewResolver.resolveNativeViewPointer(host)
                handle = NativePlayerBridge.create(
                    hostViewPtr = hostViewPtr,
                    sourceUrl = pending.sourceUrl,
                    headerLines = pending.headerLines.toTypedArray(),
                    playWhenReady = pending.playWhenReady,
                    controlsHtml = NativePlayerBridge.controlsHtml,
                    eventSink = eventSink,
                )
                if (handle == 0L) error("Native player did not return a handle.")
                updateControls(controlsState)
            }.onFailure { error ->
                pending.onError(error.message)
            }
        }
    }

    fun setControlCallbacks(
        onAction: (PlayerControlsAction) -> Boolean,
        onScrubChange: (Long) -> Boolean,
        onScrubFinished: (Long) -> Boolean,
    ) {
        this.onAction = onAction
        this.onScrubChange = onScrubChange
        this.onScrubFinished = onScrubFinished
    }

    fun updateControls(state: PlayerControlsState) {
        controlsState = state
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.updateControls(current, state.toControlsJson())
        }
    }

    private fun handlePlayerEvent(type: String, value: Double) {
        when (type) {
            "scrubChange" -> {
                if (!onScrubChange(value.toLong())) {
                    updateLocalProgress(value.toLong())
                }
            }
            "scrubFinish" -> {
                if (!onScrubFinished(value.toLong())) {
                    seekTo(value.toLong())
                }
            }
            else -> {
                val action = type.toPlayerControlsAction() ?: return
                if (!onAction(action)) {
                    handleFallbackAction(action)
                }
            }
        }
    }

    private fun updateLocalProgress(positionMs: Long) {
        controlsState = controlsState.copy(positionMs = positionMs)
        updateControls(controlsState)
    }

    private fun handleFallbackAction(action: PlayerControlsAction) {
        when (action) {
            PlayerControlsAction.TogglePlayback -> {
                handle.takeIf { it != 0L }?.let { current ->
                    NativePlayerBridge.setPaused(current, !NativePlayerBridge.isPaused(current))
                }
            }
            PlayerControlsAction.SeekBack -> seekBy(-10_000L)
            PlayerControlsAction.SeekForward -> seekBy(10_000L)
            PlayerControlsAction.DoubleTapSeekBack -> seekBy(-10_000L)
            PlayerControlsAction.DoubleTapSeekForward -> seekBy(10_000L)
            PlayerControlsAction.Speed -> cycleFallbackSpeed()
            else -> Unit
        }
    }

    private fun cycleFallbackSpeed() {
        val current = handle
        if (current == 0L) return
        val speeds = listOf(1f, 1.25f, 1.5f, 2f)
        val currentSpeed = NativePlayerBridge.speed(current)
        val next = speeds.firstOrNull { it > currentSpeed + 0.01f } ?: speeds.first()
        NativePlayerBridge.setSpeed(current, next)
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = !NativePlayerBridge.isPaused(current),
                isEnded = false,
                durationMs = NativePlayerBridge.durationMs(current),
                positionMs = NativePlayerBridge.positionMs(current),
                bufferedPositionMs = NativePlayerBridge.positionMs(current),
                playbackSpeed = NativePlayerBridge.speed(current),
            )
        }.getOrDefault(PlayerPlaybackSnapshot(isLoading = false))
    }

    fun dispose() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { NativePlayerBridge.dispose(current) }
        }
    }

    override fun play() {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, false) }
    }

    override fun pause() {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, true) }
    }

    override fun seekTo(positionMs: Long) {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekTo(it, positionMs) }
    }

    override fun seekBy(offsetMs: Long) {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekBy(it, offsetMs) }
    }

    override fun retry() {
        val pending = pendingSource ?: return
        attach(pending.sourceUrl, pending.headerLines.toHeaderMap(), pending.playWhenReady, pending.onError)
    }

    override fun setPlaybackSpeed(speed: Float) {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setSpeed(it, speed) }
    }

    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}

private data class PendingSource(
    val sourceUrl: String,
    val headerLines: List<String>,
    val playWhenReady: Boolean,
    val onError: (String?) -> Unit,
)

private fun Map<String, String>.toHeaderLines(): List<String> =
    entries.mapNotNull { (key, value) ->
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        if (cleanKey.isBlank() || cleanValue.isBlank()) {
            null
        } else {
            "$cleanKey: $cleanValue"
        }
    }

private fun List<String>.toHeaderMap(): Map<String, String> =
    mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()

private fun String.toPlayerControlsAction(): PlayerControlsAction? =
    when (this) {
        "toggleChrome" -> PlayerControlsAction.ToggleChrome
        "revealLockedOverlay" -> PlayerControlsAction.RevealLockedOverlay
        "back" -> PlayerControlsAction.Back
        "toggle" -> PlayerControlsAction.TogglePlayback
        "seekBack" -> PlayerControlsAction.SeekBack
        "seekForward" -> PlayerControlsAction.SeekForward
        "resize" -> PlayerControlsAction.ResizeMode
        "speed" -> PlayerControlsAction.Speed
        "subtitles" -> PlayerControlsAction.Subtitles
        "audio" -> PlayerControlsAction.Audio
        "sources" -> PlayerControlsAction.Sources
        "episodes" -> PlayerControlsAction.Episodes
        "external" -> PlayerControlsAction.OpenExternalPlayer
        "submitIntro" -> PlayerControlsAction.SubmitIntro
        "lock" -> PlayerControlsAction.LockToggle
        "videoSettings" -> PlayerControlsAction.VideoSettings
        "doubleTapSeekBack" -> PlayerControlsAction.DoubleTapSeekBack
        "doubleTapSeekForward" -> PlayerControlsAction.DoubleTapSeekForward
        else -> null
    }

private fun PlayerControlsState.toControlsJson(): String =
    buildString {
        append('{')
        appendJsonField("title", title)
        append(',')
        appendJsonField("episodeText", episodeText)
        append(',')
        appendJsonField("streamTitle", streamTitle)
        append(',')
        appendJsonField("providerName", providerName)
        append(',')
        appendJsonField("resizeModeLabel", resizeModeLabel)
        append(',')
        appendJsonField("playbackSpeedLabel", playbackSpeedLabel)
        append(',')
        appendJsonField("subtitlesLabel", subtitlesLabel)
        append(',')
        appendJsonField("audioLabel", audioLabel)
        append(',')
        appendJsonField("sourcesLabel", sourcesLabel)
        append(',')
        appendJsonField("episodesLabel", episodesLabel)
        append(',')
        appendJsonField("externalPlayerLabel", externalPlayerLabel)
        append(',')
        appendJsonField("playLabel", playLabel)
        append(',')
        appendJsonField("pauseLabel", pauseLabel)
        append(',')
        appendJsonField("closeLabel", closeLabel)
        append(',')
        appendJsonField("lockLabel", lockLabel)
        append(',')
        appendJsonField("unlockLabel", unlockLabel)
        append(',')
        appendJsonField("submitIntroLabel", submitIntroLabel)
        append(',')
        appendJsonField("videoSettingsLabel", videoSettingsLabel)
        append(',')
        appendJsonField("tapToUnlockLabel", tapToUnlockLabel)
        append(',')
        appendJsonField("isPlaying", isPlaying)
        append(',')
        appendJsonField("isLoading", isLoading)
        append(',')
        appendJsonField("isLocked", isLocked)
        append(',')
        appendJsonField("lockedOverlayVisible", lockedOverlayVisible)
        append(',')
        appendJsonField("controlsVisible", controlsVisible)
        append(',')
        appendJsonField("showSubmitIntro", showSubmitIntro)
        append(',')
        appendJsonField("showVideoSettings", showVideoSettings)
        append(',')
        appendJsonField("showSources", showSources)
        append(',')
        appendJsonField("showEpisodes", showEpisodes)
        append(',')
        appendJsonField("showExternalPlayer", showExternalPlayer)
        append(',')
        appendJsonField("durationMs", durationMs)
        append(',')
        appendJsonField("positionMs", positionMs)
        append('}')
    }

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":")
    append(value.toJsonString())
}

private fun StringBuilder.appendJsonField(name: String, value: Boolean) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Long) {
    append('"').append(name).append("\":").append(value)
}

private fun String.toJsonString(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@toJsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
