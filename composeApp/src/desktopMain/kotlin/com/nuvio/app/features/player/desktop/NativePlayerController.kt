package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.AudioTrack
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
                )
                if (handle == 0L) error("Native player did not return a handle.")
            }.onFailure { error ->
                pending.onError(error.message)
            }
        }
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
