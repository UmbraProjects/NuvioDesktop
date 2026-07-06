package com.nuvio.app.features.player.desktop

import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.PlayerControlAddonSubtitleItem
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlFilterItem
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleCueItem
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.ParentalWarning
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.inferForcedSubtitleTrack
import com.nuvio.app.features.player.toStorageHexString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread

/**
 * mpv audio-filter chain applied to trailer playback (hero previews, fullscreen trailers, and
 * manual trailer clicks — anything using [NativePlayerController] outside the main player).
 * Trailer loudness varies wildly between uploads with no consistent mastering, unlike a film's
 * own audio track; `dynaudnorm` continuously adjusts gain toward a target loudness in real
 * time (no pre-analysis pass needed, so it works on a streamed URL), smoothing out the
 * silent-then-jump-scare swings. `f=150` (a 150ms analysis frame, shorter than the 500ms
 * default) reacts fast enough to catch a sudden loud spike rather than only the next one.
 */
internal const val TRAILER_AUDIO_NORMALIZATION_FILTER = "dynaudnorm=f=150:g=15"

internal class NativePlayerController(
    private val host: NativePlayerHost,
) : PlayerEngineController {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    @Volatile
    private var handle: Long = 0L
    @Volatile
    private var lastResizeMode: PlayerResizeMode? = null
    private val handleLock = Any()
    private val nativeLifecycleLock = Any()
    private val attachGeneration = AtomicLong(0L)
    @Volatile
    private var disposed = false
    @Volatile
    private var pendingSource: PendingSource? = null
    private val pendingMpvProperties = linkedMapOf<String, String>()
    private var pendingVideoRedraw = false
    @Volatile
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    @Volatile
    private var pendingSubtitleDelayMs: Int? = null
    @Volatile
    private var keyboardPanelOpen = false
    private var controlsState = PlayerControlsState()
    private var lastSentControlsStructureKey: PlayerControlsState? = null
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    private val eventSink = NativePlayerEventSink { type, value ->
        SwingUtilities.invokeLater {
            handlePlayerEvent(type, value)
        }
    }

    fun attach(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean,
        onError: (String?) -> Unit,
        controlsPageUrlSuffix: String = "",
        // Records this attach on PlaybackStartTrace. Only the main player surface passes true;
        // hero trailers share this controller but must not pollute the playback-start timeline.
        tracePlaybackStart: Boolean = false,
        // Allows init-time SVP / vapoursynth to be explicitly requested. Keep the default off:
        // the main player applies SVP after fileLoaded, once mpv has resolved a real video
        // stream, and hero trailers should never start the heavy interpolation runtime.
        animeSvpEnabled: Boolean = false,
    ) {
        if (disposed) return
        // Re-attaching the same stream (surface recreation, RTX/settings toggles) must resume
        // from where playback currently is — restarting at the original initialPositionMs
        // looks like playback randomly jumping back. New sources keep the caller's position.
        val carriedPositionMs = if (pendingSource?.sourceUrl == sourceUrl) {
            synchronized(handleLock) { handle }
                .takeIf { it != 0L }
                ?.let { current ->
                    runCatching {
                        NativePlayerBridge.positionMs(current)
                            .takeIf { it > 0L && !NativePlayerBridge.isEnded(current) }
                    }.getOrNull()
                }
        } else {
            null
        }
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            headerLines = sourceHeaders.toHeaderLines(),
            playWhenReady = playWhenReady,
            initialPositionMs = (carriedPositionMs ?: initialPositionMs).coerceAtLeast(0L),
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            animeSvpFilter = if (animeSvpEnabled && PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled) DesktopAnimeSvp.vapoursynthArgument() else null,
            onError = onError,
            controlsPageUrl = NativePlayerBridge.controlsPageUrl + controlsPageUrlSuffix,
            tracePlaybackStart = tracePlaybackStart,
        )
        pendingSource = pending
        host.onPeerReady = { attachPending() }
        if (host.isDisplayable) {
            attachPending()
        }
    }

    private fun attachPending() {
        if (disposed) return
        val pending = pendingSource ?: return
        val generation = attachGeneration.incrementAndGet()
        SwingUtilities.invokeLater {
            if (disposed || !host.isDisplayable) {
                return@invokeLater
            }
            val hostViewPtr = AwtNativeViewResolver.resolveNativeViewPointer(host)
            val previousHandle = takePlayerHandle()
            keyboardPanelOpen = false
            lastSentControlsStructureKey = null
            // Dispose the outgoing player on its own thread rather than inline before create().
            // Native shutdown() posts a cleanup task to that player's own UI thread and blocks
            // up to 5s waiting for it (see player_bridge.cpp's sendUiTask); a session that has
            // been actively rendering for a while is more likely to still be mid-task when
            // asked to tear down, so that wait was landing squarely in front of the new
            // attach and delaying it by however much of the 5s window got eaten. Each player
            // instance owns independent child windows/mpv instance/WebView2 environment under
            // the shared host HWND, so the outgoing and incoming instances don't contend for
            // the same native resources during the brief overlap this allows.
            if (previousHandle != 0L) {
                thread(isDaemon = true, name = "Nuvio-Player-Dispose") {
                    runCatching { NativePlayerBridge.dispose(previousHandle) }
                }
            }
            thread(isDaemon = true, name = "Nuvio-Player-Attach") {
                synchronized(nativeLifecycleLock) {
                    if (pending.tracePlaybackStart) PlaybackStartTrace.mark("nativeAttachThread")
                    if (disposed || generation != attachGeneration.get()) {
                        return@synchronized
                    }
                    var newHandle = 0L
                    val result = runCatching {
                        newHandle = NativePlayerBridge.create(
                            hostViewPtr = hostViewPtr,
                            sourceUrl = pending.sourceUrl,
                            sourceAudioUrl = pending.sourceAudioUrl,
                            headerLines = pending.headerLines.toTypedArray(),
                            playWhenReady = pending.playWhenReady,
                            initialPositionMs = pending.initialPositionMs,
                            controlsPageUrl = pending.controlsPageUrl,
                            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
                            nvidiaRtxHdrEnabled = pending.nvidiaRtxHdrEnabled,
                            animeSvpFilter = pending.animeSvpFilter,
                            eventSink = eventSink,
                        )
                        if (newHandle == 0L) error("Native player did not return a handle.")
                    }
                    result.onFailure { error ->
                        if (!disposed && generation == attachGeneration.get()) {
                            SwingUtilities.invokeLater {
                                if (!disposed && generation == attachGeneration.get()) {
                                    pending.onError(error.message)
                                }
                            }
                        }
                        return@synchronized
                    }
                    val keepHandle = synchronized(handleLock) {
                        if (disposed || generation != attachGeneration.get()) {
                            false
                        } else {
                            handle = newHandle
                            true
                        }
                    }
                    if (!keepHandle) {
                        NativePlayerBridge.dispose(newHandle)
                        return@synchronized
                    }
                    if (pending.tracePlaybackStart) PlaybackStartTrace.mark("nativeCreateReturned")
                    synchronized(pendingMpvProperties) {
                        pendingMpvProperties.forEach { (key, value) ->
                            NativePlayerBridge.setMpvProperty(newHandle, key, value)
                        }
                    }
                    lastResizeMode?.let { setResizeMode(it) }
                    applyPendingSubtitleConfiguration(newHandle)
                    if (pendingVideoRedraw) {
                        pendingVideoRedraw = false
                        NativePlayerBridge.forceVideoRedraw(newHandle)
                    }
                    updateControls(controlsState)
                }
            }
        }
    }

    private fun takePlayerHandle(): Long = synchronized(handleLock) {
        val value = handle
        handle = 0L
        value
    }

    fun setControlCallbacks(
        onAction: (PlayerControlsAction) -> Boolean,
        onEvent: (String, Double) -> Boolean,
        onScrubChange: (Long) -> Boolean,
        onScrubFinished: (Long) -> Boolean,
    ) {
        this.onAction = onAction
        this.onEvent = onEvent
        this.onScrubChange = onScrubChange
        this.onScrubFinished = onScrubFinished
    }

    fun updateControls(state: PlayerControlsState) {
        controlsState = state
        val currentHandle = handle
        val structureKey = state.nativeControlsStructureKey()
        val current = currentHandle.takeIf { it != 0L } ?: return
        if (structureKey == lastSentControlsStructureKey) return
        lastSentControlsStructureKey = structureKey
        NativePlayerBridge.updateControls(current, state.toControlsJson())
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        lastResizeMode = mode
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setResizeMode(
                handle = current,
                mode = when (mode) {
                    PlayerResizeMode.Fit -> 0
                    PlayerResizeMode.Fill -> 1
                    PlayerResizeMode.Zoom -> 2
                },
            )
            forceVideoRedraw()
        }
    }

    fun setMpvProperty(key: String, value: String) {
        synchronized(pendingMpvProperties) {
            pendingMpvProperties[key] = value
        }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setMpvProperty(it, key, value) }
    }

    /** Forces mpv to repaint the embedded video surface (see native forceVideoRedraw). */
    fun forceVideoRedraw() {
        val current = handle
        if (current == 0L) {
            pendingVideoRedraw = true
        } else {
            NativePlayerBridge.forceVideoRedraw(current)
        }
    }

    /** Shows a transient pill in the controls overlay, e.g. when cycling a video preset. */
    fun showPresetPill(title: String, value: String) {
        val current = handle.takeIf { it != 0L } ?: return
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioShowPresetPill && window.nuvioShowPresetPill('${title.jsEscape()}', '${value.jsEscape()}')",
        )
    }

    fun dispatchKeyboardShortcut(type: String, value: Double = 0.0) {
        handlePlayerEvent(type, value)
        if (type == "volumeUp" || type == "volumeDown") {
            showVolumePillFromNative()
        }
    }

    fun cycleDesktopHdrMode() {
        val modes = DesktopHdrMode.entries
        val current = PlayerSettingsRepository.uiState.value.desktopHdrMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        PlayerSettingsRepository.setDesktopHdrMode(next)
        showPresetPill("HDR Mode", next.label)
    }

    fun cycleDesktopColorProfile() {
        val profiles = DesktopColorProfile.entries
        val current = PlayerSettingsRepository.uiState.value.desktopColorProfile
        val next = profiles[(profiles.indexOf(current) + 1) % profiles.size]
        PlayerSettingsRepository.setDesktopColorProfile(next)
        showPresetPill("Color Profile", next.label)
    }

    fun cycleDesktopAnimeMode() {
        val modes = DesktopAnimeMode.entries
        val current = PlayerSettingsRepository.uiState.value.desktopAnimeMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        PlayerSettingsRepository.setDesktopAnimeMode(next)
        showPresetPill("Anime", next.label)
    }

    fun cycleDesktopAnimeSvpMode() {
        val current = PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled
        val next = !current
        PlayerSettingsRepository.setDesktopAnimeSvpEnabled(next)
        showPresetPill("Anime SVP", if (next) "On" else "Off")
    }

    /**
     * Triggers the skip-intro/outro action if the skip prompt is currently on screen (Tab hotkey,
     * matching the official client). Returns true if a skip was dispatched so the caller can consume
     * the key; false when no skip is available (so Tab keeps its normal behavior).
     */
    fun triggerSkipIntervalIfAvailable(): Boolean {
        if (!controlsState.skipPromptVisible) return false
        dispatchKeyboardShortcut("skipInterval", 0.0)
        return true
    }

    fun openKeyboardPanel(panel: String) {
        if (panel != "sources" && panel != "episodes") return
        val current = handle.takeIf { it != 0L } ?: return
        keyboardPanelOpen = true
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioOpenKeyboardPanel && window.nuvioOpenKeyboardPanel(${panel.toJsonString()})",
        )
    }

    fun dispatchKeyboardPanelKey(code: String): Boolean {
        if (!keyboardPanelOpen) return false
        val current = handle.takeIf { it != 0L } ?: return false
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioHandleKeyboardPanelKey && window.nuvioHandleKeyboardPanelKey(${code.toJsonString()})",
        )
        return true
    }

    private fun showVolumePillFromNative() {
        val current = handle.takeIf { it != 0L } ?: return
        val percentage = NativePlayerBridge.volume(current).toInt().coerceIn(0, 200)
        NativePlayerBridge.runJavaScript(current, "window.nuvioShowVolumePill && window.nuvioShowVolumePill($percentage)")
    }

    private fun handlePlayerEvent(type: String, value: Double) {
        if (type == "keyboardCycleHdrMode") {
            cycleDesktopHdrMode()
            return
        }
        if (type == "keyboardCycleColorProfile") {
            cycleDesktopColorProfile()
            return
        }
        if (type == "keyboardCycleAnimeMode") {
            cycleDesktopAnimeMode()
            return
        }
        if (type == "keyboardPanelOpened") {
            keyboardPanelOpen = true
            return
        }
        if (type == "keyboardPanelClosed") {
            keyboardPanelOpen = false
            return
        }
        when (type) {
            "scrubChange" -> {
                if (!onScrubChange(value.toLong())) {
                    updateLocalProgress(value.toLong())
                }
            }
            "scrubFinish" -> {
                val scrubHandled = onScrubFinished(value.toLong())
                if (!scrubHandled) {
                    seekTo(value.toLong())
                }
            }
            "toggleFullscreen" -> toggleDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
            "cursorVisibility" -> {
                val current = handle.takeIf { it != 0L } ?: return
                NativePlayerBridge.setCursorHidden(current, value == 0.0)
            }
            else -> {
                if (type == "fileLoaded") {
                    handle.takeIf { it != 0L }?.let(::applyPendingSubtitleConfiguration)
                }
                val eventHandled = onEvent(type, value)
                if (eventHandled) return
                val action = type.toPlayerControlsAction()
                if (action == null) return
                val actionHandled = onAction(action)
                if (!actionHandled) {
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
            PlayerControlsAction.TogglePlayback,
            PlayerControlsAction.KeyboardTogglePlayback -> {
                val current = handle
                if (current == 0L) return
                val isEnded = NativePlayerBridge.isEnded(current)
                val isPaused = NativePlayerBridge.isPaused(current)
                if (isEnded) {
                    NativePlayerBridge.seekTo(current, 0L)
                    NativePlayerBridge.setPaused(current, false)
                } else {
                    NativePlayerBridge.setPaused(current, !isPaused)
                }
            }
            PlayerControlsAction.SeekBack,
            PlayerControlsAction.KeyboardSeekBack -> fallbackSeekBy(-10_000L)
            PlayerControlsAction.SeekForward,
            PlayerControlsAction.KeyboardSeekForward -> fallbackSeekBy(10_000L)
            PlayerControlsAction.Speed -> cycleFallbackSpeed()
            else -> Unit
        }
    }

    private fun fallbackSeekBy(offsetMs: Long) {
        val current = handle
        if (current != 0L) {
            NativePlayerBridge.seekBy(current, offsetMs)
        }
    }

    private fun cycleFallbackSpeed() {
        val current = handle
        if (current == 0L) return
        val speeds = listOf(1f, 1.25f, 1.5f, 2f)
        val currentSpeed = NativePlayerBridge.speed(current)
        val next = speeds.firstOrNull { it > currentSpeed + 0.01f } ?: speeds.first()
        NativePlayerBridge.setSpeed(current, next)
        // Buffer sizing is owned here (not in the native bridge), so every speed change must
        // re-apply the preset with the new rate — see setPlaybackSpeed for the main path.
        applyDesktopBufferPreset(PlayerSettingsRepository.uiState.value.desktopBufferPreset, next)
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            val isLoading = NativePlayerBridge.isLoading(current)
            val isEnded = NativePlayerBridge.isEnded(current)
            PlayerPlaybackSnapshot(
                isLoading = isLoading,
                isPlaying = !NativePlayerBridge.isPaused(current) && !isLoading && !isEnded,
                isEnded = isEnded,
                durationMs = NativePlayerBridge.durationMs(current),
                positionMs = NativePlayerBridge.positionMs(current),
                bufferedPositionMs = NativePlayerBridge.bufferedPositionMs(current),
                playbackSpeed = NativePlayerBridge.speed(current),
            )
        }.getOrDefault(PlayerPlaybackSnapshot(isLoading = true))
    }

    fun dispose() {
        disposed = true
        pendingSource = null
        host.onPeerReady = null
        disposePlayerHandle()
    }

    fun applyDesktopBufferPreset(preset: DesktopBufferPreset, playbackSpeed: Float? = null) {
        val current = handle.takeIf { it != 0L }
        val speed = playbackSpeed
            ?: current?.let { runCatching { NativePlayerBridge.speed(it) }.getOrNull() }
            ?: 1f
        val factor = speed.coerceAtLeast(1f)
        val limits = when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS -> when (preset) {
                DesktopBufferPreset.LowData -> BufferLimits(15, 30, "64MiB", "16MiB", "32MiB")
                DesktopBufferPreset.Balanced -> BufferLimits(60, 120, "256MiB", "64MiB", "64MiB")
                DesktopBufferPreset.Resilient -> BufferLimits(180, 600, "1GiB", "128MiB", "256MiB")
            }
            DesktopHostOs.MACOS -> when (preset) {
                DesktopBufferPreset.LowData -> BufferLimits(10, 10, "32MiB", "8MiB", "16MiB")
                DesktopBufferPreset.Balanced -> BufferLimits(20, 20, "48MiB", "12MiB", "32MiB")
                // Preserve the previous macOS defaults for existing installations.
                DesktopBufferPreset.Resilient -> BufferLimits(30, 30, "64MiB", "16MiB", "64MiB")
            }
            else -> return
        }
        setMpvProperty("demuxer-readahead-secs", (limits.readaheadSeconds * factor).toString())
        setMpvProperty("cache-secs", (limits.cacheSeconds * factor).toString())
        setMpvProperty("demuxer-max-bytes", limits.maxBytes)
        setMpvProperty("demuxer-max-back-bytes", limits.maxBackBytes)
        setMpvProperty("stream-buffer-size", limits.streamBufferSize)
        // Media buffered before (re)starting playback: keep small so startup and post-seek
        // resume stay snappy — the readahead limits above provide the stall resilience.
        setMpvProperty("cache-pause-wait", if (speed > 1f) "6" else "2")
    }

    private fun disposePlayerHandle() {
        val current = synchronized(handleLock) {
            val value = handle
            handle = 0L
            value
        }
        keyboardPanelOpen = false
        lastSentControlsStructureKey = null
        if (current != 0L) {
            kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-Player-Dispose") {
                runCatching { NativePlayerBridge.dispose(current) }
            }
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
        attach(
            sourceUrl = pending.sourceUrl,
            sourceAudioUrl = pending.sourceAudioUrl,
            sourceHeaders = pending.headerLines.toHeaderMap(),
            playWhenReady = pending.playWhenReady,
            initialPositionMs = pending.initialPositionMs,
            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = pending.nvidiaRtxHdrEnabled,
            onError = pending.onError,
            tracePlaybackStart = pending.tracePlaybackStart,
        )
    }

    override fun setPlaybackSpeed(speed: Float) {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setSpeed(it, speed) }
        applyDesktopBufferPreset(PlayerSettingsRepository.uiState.value.desktopBufferPreset, speed)
    }

    override fun setMuted(muted: Boolean) {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setMute(it, muted) }
    }

    // Desktop/mpv supports software amplification above 100% (volume-max=200 in the native
    // bridge) so quiet content can be boosted. 2.0 == 200%.
    override val maxVolumeFraction: Float get() = 2f

    override fun setVolume(fraction: Float): PlayerAudioLevel? {
        val current = handle.takeIf { it != 0L } ?: return null
        val clamped = fraction.coerceIn(0f, maxVolumeFraction)
        NativePlayerBridge.setVolume(current, clamped * 100f)
        if (clamped > 0f && NativePlayerBridge.isMuted(current)) {
            NativePlayerBridge.setMute(current, false)
        }
        return PlayerAudioLevel(fraction = clamped, isMuted = clamped <= 0f || NativePlayerBridge.isMuted(current))
    }

    override fun getVolume(): PlayerAudioLevel? {
        val current = handle.takeIf { it != 0L } ?: return null
        val fraction = (NativePlayerBridge.volume(current) / 100f).coerceIn(0f, maxVolumeFraction)
        return PlayerAudioLevel(fraction = fraction, isMuted = fraction <= 0f || NativePlayerBridge.isMuted(current))
    }

    private data class BufferLimits(
        val readaheadSeconds: Int,
        val cacheSeconds: Int,
        val maxBytes: String,
        val maxBackBytes: String,
        val streamBufferSize: String,
    )

    override fun getAudioTracks(): List<AudioTrack> =
        decodeTracks { NativePlayerBridge.audioTracksJson(it) }.map { track ->
            AudioTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
            )
        }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }.map { track ->
            SubtitleTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
                isForced = track.forced || inferForcedSubtitleTrack(
                    label = track.label,
                    language = track.language,
                    trackId = track.id,
                ),
            )
        }

    override fun selectAudioTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val trackId = resolveTrackId(index, decodeTracks { NativePlayerBridge.audioTracksJson(it) }) ?: return
        NativePlayerBridge.selectAudioTrack(current, trackId)
    }

    override fun selectSubtitleTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        if (index < 0) {
            NativePlayerBridge.selectSubtitleTrack(current, -1)
            return
        }
        val trackId = resolveTrackId(index, decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }) ?: return
        NativePlayerBridge.selectSubtitleTrack(current, trackId)
        applyPendingSubtitleConfiguration(current)
    }

    override fun setSubtitleUri(url: String) {
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.addSubtitleUrl(current, url)
            applyPendingSubtitleConfiguration(current)
        }
    }

    override fun clearExternalSubtitle() {
        handle.takeIf { it != 0L }?.let(NativePlayerBridge::clearExternalSubtitles)
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val trackId = if (trackIndex < 0) {
            -1
        } else {
            resolveTrackId(trackIndex, decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }) ?: return
        }
        NativePlayerBridge.clearExternalSubtitlesAndSelect(current, trackId)
        applyPendingSubtitleConfiguration(current)
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        pendingSubtitleDelayMs = clamped
        handle.takeIf { it != 0L }?.let { current -> NativePlayerBridge.setSubtitleDelayMs(current, clamped) }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        pendingSubtitleStyle = style
        handle.takeIf { it != 0L }?.let { current -> applySubtitleStyle(current, style) }
    }

    private fun applyPendingSubtitleConfiguration(current: Long) {
        pendingSubtitleDelayMs?.let { delayMs -> NativePlayerBridge.setSubtitleDelayMs(current, delayMs) }
        pendingSubtitleStyle?.let { style -> applySubtitleStyle(current, style) }
    }

    private fun applySubtitleStyle(current: Long, style: SubtitleStyleState) {
        NativePlayerBridge.applySubtitleStyle(
            handle = current,
            textColor = style.textColor.toMpvColorString(),
            backgroundColor = style.backgroundColor.toMpvColorString(),
            outlineColor = style.outlineColor.toMpvColorString(),
            outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
            bold = style.bold,
            fontSize = style.toMpvSubtitleFontSize(),
            subPos = style.toMpvSubtitlePosition(),
            fontName = style.fontFamily,
        )
    }

    private fun decodeTracks(readJson: (Long) -> String): List<NativeMpvTrack> {
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NativeMpvTrack>>(readJson(current))
        }.getOrDefault(emptyList())
    }
}

@Serializable
private data class NativeMpvTrack(
    val index: Int = 0,
    val id: String = "",
    val label: String = "",
    val language: String = "",
    val selected: Boolean = false,
    val forced: Boolean = false,
)

private fun resolveTrackId(index: Int, tracks: List<NativeMpvTrack>): Int? =
    tracks.firstNotNullOfOrNull { track ->
        if (track.index == index) {
            track.id.toIntOrNull()
        } else {
            null
        }
    } ?: tracks.getOrNull(index)?.id?.toIntOrNull()

private fun Color.toMpvColorString(): String {
    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
    val redInt = (red * 255f).toInt().coerceIn(0, 255)
    val greenInt = (green * 255f).toInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).toInt().coerceIn(0, 255)
    return buildString {
        append('#')
        append(alphaInt.toHexByte())
        append(redInt.toHexByte())
        append(greenInt.toHexByte())
        append(blueInt.toHexByte())
    }
}

private fun SubtitleStyleState.toMpvSubtitlePosition(): Int =
    (100 - (bottomOffset / 2)).coerceIn(0, 150)

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Float =
    (fontSizeSp * 3f).coerceIn(24f, 96f)

private fun Int.toHexByte(): String {
    val digits = "0123456789ABCDEF"
    val value = coerceIn(0, 255)
    return buildString {
        append(digits[value / 16])
        append(digits[value % 16])
    }
}

private fun String.jsEscape(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")

private data class PendingSource(
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val headerLines: List<String>,
    val playWhenReady: Boolean,
    val initialPositionMs: Long,
    val nvidiaRtxSuperResolutionEnabled: Boolean,
    val nvidiaRtxHdrEnabled: Boolean,
    val animeSvpFilter: String?,
    val onError: (String?) -> Unit,
    val controlsPageUrl: String,
    val tracePlaybackStart: Boolean = false,
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
        "keyboardToggle" -> PlayerControlsAction.KeyboardTogglePlayback
        "seekBack" -> PlayerControlsAction.SeekBack
        "keyboardSeekBack" -> PlayerControlsAction.KeyboardSeekBack
        "seekForward" -> PlayerControlsAction.SeekForward
        "keyboardSeekForward" -> PlayerControlsAction.KeyboardSeekForward
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
        "heroTrailerMute" -> PlayerControlsAction.HeroTrailerMute
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
        appendJsonField("pauseOverlayWatchingLabel", pauseOverlayWatchingLabel)
        append(',')
        appendJsonField("pauseOverlayLogo", pauseOverlayLogo.orEmpty())
        append(',')
        appendJsonField("pauseOverlayEpisodeInfo", pauseOverlayEpisodeInfo)
        append(',')
        appendJsonField("pauseOverlayEpisodeTitle", pauseOverlayEpisodeTitle)
        append(',')
        appendJsonField("pauseOverlayDescription", pauseOverlayDescription)
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
        appendJsonField("playbackErrorTitle", playbackErrorTitle)
        append(',')
        appendJsonField("playbackErrorMessage", playbackErrorMessage)
        append(',')
        appendJsonField("playbackErrorActionLabel", playbackErrorActionLabel)
        append(',')
        appendJsonField("sourcesPanelTitle", sourcesPanelTitle)
        append(',')
        appendJsonField("episodesPanelTitle", episodesPanelTitle)
        append(',')
        appendJsonField("streamsPanelTitle", streamsPanelTitle)
        append(',')
        appendJsonField("allFilterLabel", allFilterLabel)
        append(',')
        appendJsonField("reloadLabel", reloadLabel)
        append(',')
        appendJsonField("backLabel", backLabel)
        append(',')
        appendJsonField("panelCloseLabel", panelCloseLabel)
        append(',')
        appendJsonField("cancelLabel", cancelLabel)
        append(',')
        appendJsonField("playingLabel", playingLabel)
        append(',')
        appendJsonField("noStreamsLabel", noStreamsLabel)
        append(',')
        appendJsonField("noEpisodesLabel", noEpisodesLabel)
        append(',')
        appendJsonField("submitIntroPanelTitle", submitIntroPanelTitle)
        append(',')
        appendJsonField("submitIntroSegmentTypeLabel", submitIntroSegmentTypeLabel)
        append(',')
        appendJsonField("submitIntroSegmentIntroLabel", submitIntroSegmentIntroLabel)
        append(',')
        appendJsonField("submitIntroSegmentRecapLabel", submitIntroSegmentRecapLabel)
        append(',')
        appendJsonField("submitIntroSegmentOutroLabel", submitIntroSegmentOutroLabel)
        append(',')
        appendJsonField("submitIntroStartTimeLabel", submitIntroStartTimeLabel)
        append(',')
        appendJsonField("submitIntroEndTimeLabel", submitIntroEndTimeLabel)
        append(',')
        appendJsonField("submitIntroCaptureLabel", submitIntroCaptureLabel)
        append(',')
        appendJsonField("submitIntroSubmitLabel", submitIntroSubmitLabel)
        append(',')
        appendJsonField("p2pConsentTitle", p2pConsentTitle)
        append(',')
        appendJsonField("p2pConsentBody", p2pConsentBody)
        append(',')
        appendJsonField("p2pConsentEnableLabel", p2pConsentEnableLabel)
        append(',')
        appendJsonField("p2pConsentCancelLabel", p2pConsentCancelLabel)
        append(',')
        appendJsonField("subtitlesPanelTitle", subtitlesPanelTitle)
        append(',')
        appendJsonField("subtitleBuiltInTabLabel", subtitleBuiltInTabLabel)
        append(',')
        appendJsonField("subtitleAddonsTabLabel", subtitleAddonsTabLabel)
        append(',')
        appendJsonField("subtitleStyleTabLabel", subtitleStyleTabLabel)
        append(',')
        appendJsonField("noneLabel", noneLabel)
        append(',')
        appendJsonField("fetchSubtitlesLabel", fetchSubtitlesLabel)
        append(',')
        appendJsonField("subtitleDelayLabel", subtitleDelayLabel)
        append(',')
        appendJsonField("resetLabel", resetLabel)
        append(',')
        appendJsonField("autoSyncLabel", autoSyncLabel)
        append(',')
        appendJsonField("reloadSmallLabel", reloadSmallLabel)
        append(',')
        appendJsonField("captureLineLabel", captureLineLabel)
        append(',')
        appendJsonField("selectAddonSubtitleFirstLabel", selectAddonSubtitleFirstLabel)
        append(',')
        appendJsonField("loadingSubtitleLinesLabel", loadingSubtitleLinesLabel)
        append(',')
        appendJsonField("fontSizeLabel", fontSizeLabel)
        append(',')
        appendJsonField("outlineLabel", outlineLabel)
        append(',')
        appendJsonField("boldLabel", boldLabel)
        append(',')
        appendJsonField("bottomOffsetLabel", bottomOffsetLabel)
        append(',')
        appendJsonField("colorLabel", colorLabel)
        append(',')
        appendJsonField("textOpacityLabel", textOpacityLabel)
        append(',')
        appendJsonField("outlineColorLabel", outlineColorLabel)
        append(',')
        appendJsonField("resetDefaultsLabel", resetDefaultsLabel)
        append(',')
        appendJsonField("onLabel", onLabel)
        append(',')
        appendJsonField("offLabel", offLabel)
        append(',')
        appendJsonField("themeAccentColor", themeAccentColor)
        append(',')
        appendJsonField("themeAccentStrongColor", themeAccentStrongColor)
        append(',')
        appendJsonField("themeOnAccentColor", themeOnAccentColor)
        append(',')
        appendJsonField("themeFocusColor", themeFocusColor)
        append(',')
        appendJsonField("themeSelectedSurfaceColor", themeSelectedSurfaceColor)
        append(',')
        appendJsonField("themeSelectedSurfaceHoverColor", themeSelectedSurfaceHoverColor)
        append(',')
        appendJsonField("themeSelectedRingColor", themeSelectedRingColor)
        append(',')
        appendJsonField("themeTimelineFillColor", themeTimelineFillColor)
        append(',')
        appendJsonField("themeTimelineTrackColor", themeTimelineTrackColor)
        append(',')
        appendJsonField("themeBufferingColor", themeBufferingColor)
        append(',')
        appendJsonField("themeBufferingTrackColor", themeBufferingTrackColor)
        append(',')
        appendJsonField("themeControlForegroundColor", themeControlForegroundColor)
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
        appendJsonField("mouseMoveRevealsControlsEnabled", mouseMoveRevealsControlsEnabled)
        append(',')
        appendJsonArrayField("parentalWarnings", parentalWarnings) { appendParentalWarningJson(it) }
        append(',')
        appendJsonField("showParentalGuide", showParentalGuide)
        append(',')
        appendJsonField("showOpeningOverlay", showOpeningOverlay)
        append(',')
        appendJsonField("openingArtwork", openingArtwork.orEmpty())
        append(',')
        appendJsonField("openingLogo", openingLogo.orEmpty())
        append(',')
        appendJsonField("openingTitle", openingTitle)
        append(',')
        appendJsonField("openingMessage", openingMessage.orEmpty())
        append(',')
        appendJsonField("openingProgress", openingProgress)
        append(',')
        appendJsonField("skipPromptVisible", skipPromptVisible)
        append(',')
        appendJsonField("skipPromptLabel", skipPromptLabel)
        append(',')
        appendJsonField("skipPromptStartMs", skipPromptStartMs)
        append(',')
        appendJsonField("skipPromptEndMs", skipPromptEndMs)
        append(',')
        appendJsonField("skipPromptDismissed", skipPromptDismissed)
        append(',')
        appendJsonField("nextEpisodeVisible", nextEpisodeVisible)
        append(',')
        appendJsonField("nextEpisodeHeaderLabel", nextEpisodeHeaderLabel)
        append(',')
        appendJsonField("nextEpisodeTitle", nextEpisodeTitle)
        append(',')
        appendJsonField("nextEpisodeThumbnail", nextEpisodeThumbnail)
        append(',')
        appendJsonField("nextEpisodeStatus", nextEpisodeStatus)
        append(',')
        appendJsonField("nextEpisodeActionLabel", nextEpisodeActionLabel)
        append(',')
        appendJsonField("nextEpisodePlayable", nextEpisodePlayable)
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
        append(',')
        appendJsonField("sourceIsLoading", sourceIsLoading)
        append(',')
        appendJsonArrayField("sourceFilters", sourceFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("sourceItems", sourceItems) { appendSourceItemJson(it) }
        append(',')
        appendJsonArrayField("episodeItems", episodeItems) { appendEpisodeItemJson(it) }
        append(',')
        appendJsonArrayField("episodeSeasons", episodeSeasons) { appendSeasonItemJson(it) }
        append(',')
        appendJsonField("episodeStreamsVisible", episodeStreamsVisible)
        append(',')
        appendJsonField("episodeStreamsIsLoading", episodeStreamsIsLoading)
        append(',')
        appendJsonField("selectedEpisodeLabel", selectedEpisodeLabel)
        append(',')
        appendJsonArrayField("episodeStreamFilters", episodeStreamFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("episodeStreamItems", episodeStreamItems) { appendSourceItemJson(it) }
        append(',')
        appendJsonField("submitIntroSegmentType", submitIntroSegmentType)
        append(',')
        appendJsonField("submitIntroStartTime", submitIntroStartTime)
        append(',')
        appendJsonField("submitIntroEndTime", submitIntroEndTime)
        append(',')
        appendJsonField("isSubmitIntroSubmitting", isSubmitIntroSubmitting)
        append(',')
        appendJsonField("submitIntroStatusMessage", submitIntroStatusMessage)
        append(',')
        appendJsonField("showP2pConsent", showP2pConsent)
        append(',')
        appendJsonField("subtitleActiveTab", subtitleActiveTab)
        append(',')
        appendJsonArrayField("addonSubtitleItems", addonSubtitleItems) { appendAddonSubtitleItemJson(it) }
        append(',')
        appendJsonField("isLoadingAddonSubtitles", isLoadingAddonSubtitles)
        append(',')
        appendJsonField("selectedAddonSubtitleId", selectedAddonSubtitleId)
        append(',')
        appendJsonField("useCustomSubtitles", useCustomSubtitles)
        append(',')
        appendJsonField("subtitleDelayMs", subtitleDelayMs)
        append(',')
        appendJsonField("hasSelectedAddonSubtitle", hasSelectedAddonSubtitle)
        append(',')
        appendJsonField("subtitleAutoSyncCapturedPositionMs", subtitleAutoSyncCapturedPositionMs)
        append(',')
        appendJsonArrayField("subtitleAutoSyncCues", subtitleAutoSyncCues) { appendSubtitleCueItemJson(it) }
        append(',')
        appendJsonField("subtitleAutoSyncIsLoading", subtitleAutoSyncIsLoading)
        append(',')
        appendJsonField("subtitleAutoSyncErrorMessage", subtitleAutoSyncErrorMessage)
        append(',')
        appendJsonField("subtitleStyle", subtitleStyle)
        append(',')
        appendJsonArrayField("subtitleFontFamilies", subtitleFontFamilies) { append(it.toJsonString()) }
        append(',')
        appendJsonArrayField("subtitleColorSwatches", SubtitleColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonField("closeModalsToken", closeModalsToken)
        append(',')
        appendJsonField("heroTrailerMode", heroTrailerMode)
        append(',')
        appendJsonField("heroTrailerBackgroundColor", heroTrailerBackgroundColor)
        append(',')
        appendJsonField("heroTrailerLogoUrl", heroTrailerLogoUrl)
        append(',')
        appendJsonField("heroTrailerTitle", heroTrailerTitle)
        append(',')
        appendJsonField("heroTrailerMeta", heroTrailerMeta)
        append(',')
        appendJsonField("heroTrailerDescription", heroTrailerDescription)
        append(',')
        appendJsonField("heroTrailerMuted", heroTrailerMuted)
        append(',')
        appendJsonField("heroTrailerVolume", heroTrailerVolume)
        append('}')
    }

private fun PlayerControlsState.nativeControlsStructureKey(): PlayerControlsState =
    copy(
        isPlaying = false,
        isLoading = false,
        durationMs = 0L,
        positionMs = 0L,
        // Volatile data (driven by the overlay volume slider); not a structural change.
        heroTrailerVolume = 0,
    )

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

private fun StringBuilder.appendJsonField(name: String, value: Float?) {
    append('"').append(name).append("\":")
    if (value == null || value.isNaN() || value.isInfinite()) {
        append("null")
    } else {
        append(value.coerceIn(0f, 1f))
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Int) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: SubtitleStyleState) {
    append('"').append(name).append("\":")
    appendSubtitleStyleJson(value)
}

private inline fun <T> StringBuilder.appendJsonArrayField(
    name: String,
    values: List<T>,
    appendValue: StringBuilder.(T) -> Unit,
) {
    append('"').append(name).append("\":[")
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        appendValue(value)
    }
    append(']')
}

private fun StringBuilder.appendFilterItemJson(item: PlayerControlFilterItem) {
    append('{')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append(',')
    appendJsonField("isLoading", item.isLoading)
    append(',')
    appendJsonField("hasError", item.hasError)
    append('}')
}

private fun StringBuilder.appendSeasonItemJson(item: PlayerControlSeasonItem) {
    append('{')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSourceItemJson(item: PlayerControlSourceItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("filterId", item.filterId)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("subtitle", item.subtitle)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isEnabled", item.isEnabled)
    append('}')
}

private fun StringBuilder.appendEpisodeItemJson(item: PlayerControlEpisodeItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("code", item.code)
    append(',')
    appendJsonField("overview", item.overview)
    append(',')
    appendJsonField("thumbnail", item.thumbnail)
    append(',')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("episode", item.episode)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isWatched", item.isWatched)
    append('}')
}

private fun StringBuilder.appendAddonSubtitleItemJson(item: PlayerControlAddonSubtitleItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("display", item.display)
    append(',')
    appendJsonField("languageLabel", item.languageLabel)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleCueItemJson(item: PlayerControlSubtitleCueItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("timeMs", item.timeMs)
    append(',')
    appendJsonField("timeLabel", item.timeLabel)
    append(',')
    appendJsonField("text", item.text)
    append('}')
}

private fun StringBuilder.appendParentalWarningJson(item: ParentalWarning) {
    append('{')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("severity", item.severity)
    append('}')
}

private fun StringBuilder.appendSubtitleStyleJson(style: SubtitleStyleState) {
    append('{')
    appendJsonField("textColor", style.textColor.toStorageHexString())
    append(',')
    appendJsonField("outlineColor", style.outlineColor.toStorageHexString())
    append(',')
    appendJsonField("outlineEnabled", style.outlineEnabled)
    append(',')
    appendJsonField("bold", style.bold)
    append(',')
    appendJsonField("fontSizeSp", style.fontSizeSp)
    append(',')
    appendJsonField("bottomOffset", style.bottomOffset)
    append(',')
    appendJsonField("fontFamily", style.fontFamily)
    append('}')
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
