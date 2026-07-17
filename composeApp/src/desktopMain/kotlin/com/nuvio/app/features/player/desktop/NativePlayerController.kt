package com.nuvio.app.features.player.desktop

import androidx.compose.ui.graphics.Color
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerControlAddonSubtitleItem
import com.nuvio.app.features.player.PlayerControlBuiltInSubtitleItem
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlFilterItem
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleCueItem
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopAnimeSessionOverride
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.DesktopCustomShaderCatalog
import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.ParentalWarning
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerShortcutAction
import com.nuvio.app.features.player.PlayerShortcutsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.inferForcedSubtitleTrack
import com.nuvio.app.features.player.isExplicitProviderDiagnosticVideoUrl
import com.nuvio.app.features.player.isProviderPlaybackEndpoint
import com.nuvio.app.features.player.preferredSubtitleTargetsForSettings
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets
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
    private var diagnosticsOverlayEnabled = false
    private data class AnimeShaderChoice(
        val mode: DesktopAnimeMode,
        val customShaderPath: String = "",
        val label: String = mode.label,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val CONTROLS_PAGE_REVISION = "20260716-icon-shine"
        const val MPV_STARTUP_ERROR_EVENT_PREFIX = "mpvStartupError:"
        const val MPV_PLAYBACK_ERROR_EVENT_PREFIX = "mpvPlaybackError:"

        // libmpv instances are independent, but some libraries loaded by mpv are not. In
        // particular, VapourSynth maintains process-global state even when no vapoursynth filter
        // is active. Overlapping mpv_terminate_destroy() for an outgoing stream with mpv_create()
        // for its replacement can therefore crash inside libvapoursynth.dll. Keep native player
        // creation and destruction process-wide serial while still doing both off the UI thread.
        val nativeProcessLifecycleLock = Any()

        // Last volume the user set on the main player, as a 0..maxVolumeFraction value. Persisted
        // across native player instances (in memory, for the app session) so that starting a new
        // episode — which creates a fresh mpv instance that would otherwise default to 100% — keeps
        // whatever level was last chosen. Only main-player attaches restore this (see restoreVolume);
        // hero trailers manage their own volume separately.
        @Volatile
        var persistedVolumeFraction: Float? = null
    }

    @Volatile
    private var handle: Long = 0L
    @Volatile
    private var lastResizeMode: PlayerResizeMode? = null
    private val handleLock = Any()
    private val attachGeneration = AtomicLong(0L)
    @Volatile
    private var disposed = false
    @Volatile
    private var pendingSource: PendingSource? = null
    private val pendingMpvProperties = linkedMapOf<String, String>()
    private var pendingVideoRedraw = false
    // Transient overlay message held across a source switch; shown once the next attach completes.
    @Volatile
    private var transientMessageForNextAttach: Pair<String, String>? = null
    @Volatile
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    @Volatile
    private var pendingSubtitleDelayMs: Int? = null
    @Volatile
    private var keyboardPanelOpen = false
    // Whether the current title was detected as anime (genre heuristic / caches). Pushed in by the
    // desktop engine so the F10 cycle and the shader context menu can mark which choice is actually
    // in effect right now (auto-applied preset vs Off) before any session force exists.
    @Volatile
    var isAnimeContentDetected = false
    private var controlsState = PlayerControlsState()
    private var lastSentControlsStructureKey: PlayerControlsState? = null
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    fun attach(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        initialProgressFraction: Float = 0f,
        initialPlaybackSpeed: Float = 1f,
        isAnimeContent: Boolean = false,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean,
        onError: (String?) -> Unit,
        controlsPageUrlSuffix: String = "",
        // Records this attach on PlaybackStartTrace. Only the main player surface passes true;
        // hero trailers share this controller but must not pollute the playback-start timeline.
        tracePlaybackStart: Boolean = false,
        // Requests SVP for a known anime session. Native code defers the actual VapourSynth
        // filter until fileLoaded (before the first playback restart), so URL probing remains
        // filter-free. Keep the default off so hero trailers never start the heavy runtime.
        animeSvpEnabled: Boolean = false,
        // Applies the user's desktop mpv options (audio passthrough toggle + custom options box).
        // Only the main player passes true; hero trailers must never bitstream audio to a receiver
        // or inherit user render tweaks.
        enableUserMpvOptions: Boolean = false,
        // Re-applies the last user-set volume ([persistedVolumeFraction]) once the fresh mpv
        // instance exists, so volume carries over between episodes. Only the main player passes true.
        restoreVolume: Boolean = false,
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
        val resumePositionMs = (carriedPositionMs ?: initialPositionMs).coerceAtLeast(0L)
        val resumeProgressFraction = initialProgressFraction
            .takeIf { resumePositionMs <= 0L && it > 0f }
            ?.coerceIn(0f, 1f)
            ?: 0f
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            headerLines = sourceHeaders.withDefaultPlaybackUserAgent(sourceUrl).toHeaderLines(),
            playWhenReady = playWhenReady,
            initialPositionMs = resumePositionMs,
            initialProgressFraction = resumeProgressFraction,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            isAnimeContent = isAnimeContent,
            animeSvpFilter = if (animeSvpEnabled && PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled) DesktopAnimeSvp.vapoursynthArgument() else null,
            extraMpvOptions = buildList {
                if (isProviderPlaybackEndpoint(sourceUrl) || isExplicitProviderDiagnosticVideoUrl(sourceUrl)) {
                    add("ytdl=no")
                }
                if (enableUserMpvOptions) addAll(buildDesktopUserMpvOptions())
                // mpv applies these before mpv_initialize/loadfile. This must follow custom
                // options so the app's explicit Default Playback Speed remains authoritative and
                // speed-sensitive filters can see the final value during their first setup.
                add("speed=${initialPlaybackSpeed.coerceIn(0.25f, 4f)}")
            },
            onError = onError,
            controlsPageUrl = buildString {
                append(NativePlayerBridge.controlsPageUrl)
                append("?ui=")
                append(CONTROLS_PAGE_REVISION)
                controlsPageUrlSuffix.removePrefix("?").takeIf { it.isNotBlank() }?.let { suffix ->
                    append('&')
                    append(suffix)
                }
            },
            tracePlaybackStart = tracePlaybackStart,
            restoreVolume = restoreVolume,
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
            thread(isDaemon = true, name = "Nuvio-Player-Attach") {
                synchronized(nativeProcessLifecycleLock) {
                    // Replacement is intentionally sequential. Both operations remain on this
                    // worker, so a slow native shutdown delays the next stream without freezing
                    // Compose or allowing shared mpv dependencies to tear down under a new player.
                    if (previousHandle != 0L) {
                        runCatching { NativePlayerBridge.dispose(previousHandle) }
                    }
                    if (pending.tracePlaybackStart) PlaybackStartTrace.mark("nativeAttachThread")
                    if (disposed || generation != attachGeneration.get()) {
                        return@synchronized
                    }
                    var newHandle = 0L
                    // Each native instance gets a generation-bound sink. The outgoing player is
                    // intentionally disposed asynchronously, so its final EOF/error callbacks can
                    // otherwise arrive after a replacement has attached and mutate the new session.
                    val generationEventSink = NativePlayerEventSink { type, value ->
                        SwingUtilities.invokeLater {
                            if (!disposed && generation == attachGeneration.get()) {
                                handlePlayerEvent(type, value)
                            }
                        }
                    }
                    val result = runCatching {
                        newHandle = NativePlayerBridge.create(
                            hostViewPtr = hostViewPtr,
                            sourceUrl = pending.sourceUrl,
                            sourceAudioUrl = pending.sourceAudioUrl,
                            headerLines = pending.headerLines.toTypedArray(),
                            playWhenReady = pending.playWhenReady,
                            initialPositionMs = pending.initialPositionMs,
                            initialProgressFraction = pending.initialProgressFraction.toDouble(),
                            controlsPageUrl = pending.controlsPageUrl,
                            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
                            nvidiaRtxHdrEnabled = pending.nvidiaRtxHdrEnabled,
                            isAnimeContent = pending.isAnimeContent,
                            animeSvpFilter = pending.animeSvpFilter,
                            extraMpvOptions = pending.extraMpvOptions.toTypedArray(),
                            eventSink = generationEventSink,
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
                            diagnosticsOverlayEnabled = false
                            true
                        }
                    }
                    if (!keepHandle) {
                        NativePlayerBridge.dispose(newHandle)
                        return@synchronized
                    }
                    if (pending.tracePlaybackStart) PlaybackStartTrace.mark("nativeCreateReturned")
                    // Carry the last-set volume onto the fresh mpv instance so a new episode doesn't
                    // reset to 100%. The mpv `volume` property is settable before the file finishes
                    // loading, so applying it here (rather than waiting for fileLoaded) avoids an
                    // audible full-volume blip at the start of the next episode.
                    if (pending.restoreVolume) {
                        persistedVolumeFraction?.let { fraction ->
                            NativePlayerBridge.setVolume(newHandle, fraction.coerceIn(0f, maxVolumeFraction) * 100f)
                        }
                    }
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
        AppShortcutsRepository.ensureLoaded()
        PlayerShortcutsRepository.ensureLoaded()
        NativePlayerBridge.updateControls(
            current,
            state.toControlsJson(
                appFullscreenKeyCode = AppShortcutsRepository.keyCode(AppShortcutAction.ToggleFullscreen),
                playerShortcutKeyCodes = PlayerShortcutAction.entries.associate { action ->
                    action.id to PlayerShortcutsRepository.keyCode(action)
                },
            ),
        )
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

    fun completeSvpStartupProfile() {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.completeSvpStartupProfile(it) }
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
    fun showPresetPill(title: String, value: String, durationMs: Int? = null) {
        val current = handle.takeIf { it != 0L } ?: return
        val durationArg = durationMs?.let { ", $it" } ?: ""
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioShowPresetPill && window.nuvioShowPresetPill('${title.jsEscape()}', '${value.jsEscape()}'$durationArg)",
        )
    }

    override fun showTransientMessage(title: String, value: String) {
        showPresetPill(title, value)
    }

    override fun showTransientMessageAfterNextAttach(title: String, value: String) {
        // A source switch tears the native surface (and its controls page) down, so a pill shown
        // now dies before anyone can read it. Hold the message and deliver it to the replacement
        // player once it attaches; the bridge queues the script until that page is ready.
        transientMessageForNextAttach = title to value
    }

    fun dispatchKeyboardShortcut(type: String, value: Double = 0.0) {
        handlePlayerEvent(type, value)
        if (type == "volumeUp" || type == "volumeDown") {
            showVolumePillFromNative()
        } else if (type == "keyboardSpeedStep") {
            showPlaybackSpeedPillFromNative()
        }
    }

    private fun showPlaybackSpeedPillFromNative() {
        val current = handle.takeIf { it != 0L } ?: return
        val speed = NativePlayerBridge.speed(current)
        val value = if (speed % 1f == 0f) {
            "${speed.toInt()}x"
        } else {
            "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
        }
        showPresetPill("Playback speed", value)
    }

    fun cycleDesktopHdrMode() {
        val modes = DesktopHdrMode.entries
        val current = PlayerSettingsRepository.uiState.value.desktopHdrMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        PlayerSettingsRepository.setDesktopHdrMode(next)
        showPresetPill("HDR Mode", next.label)
    }

    private fun selectDesktopHdrMode(index: Int) {
        DesktopHdrMode.entries.getOrNull(index)?.let { mode ->
            PlayerSettingsRepository.setDesktopHdrMode(mode)
            showPresetPill("HDR Mode", mode.label)
        }
    }

    fun cycleDesktopColorProfile() {
        val profiles = DesktopColorProfile.entries
        val current = PlayerSettingsRepository.uiState.value.desktopColorProfile
        val next = profiles[(profiles.indexOf(current) + 1) % profiles.size]
        PlayerSettingsRepository.setDesktopColorProfile(next)
        showPresetPill("Color Profile", next.label)
    }

    private fun selectDesktopColorProfile(index: Int) {
        DesktopColorProfile.entries.getOrNull(index)?.let { profile ->
            PlayerSettingsRepository.setDesktopColorProfile(profile)
            showPresetPill("Color Profile", profile.label)
        }
    }

    fun cycleDesktopAnimeMode() {
        val settings = PlayerSettingsRepository.uiState.value
        val choices = animeShaderChoices(settings)
        val currentIndex = choices.indexOfFirst { it.isSelected(settings) }
        applyAnimeShaderChoice(choices[((currentIndex.takeIf { it >= 0 } ?: -1) + 1) % choices.size])
    }

    private fun animeShaderChoices(settings: com.nuvio.app.features.player.PlayerSettingsUiState): List<AnimeShaderChoice> {
        val customShaders = DesktopCustomShaderCatalog.availableShaders(settings.desktopCustomShaderPaths)
        return DesktopAnimeMode.entries
            .filter { it != DesktopAnimeMode.CustomShader }
            .map { AnimeShaderChoice(mode = it) } +
            customShaders.map { shader ->
                AnimeShaderChoice(
                    mode = DesktopAnimeMode.CustomShader,
                    customShaderPath = shader.path,
                    label = shader.fileName,
                )
            }
    }

    // The choice currently in effect: a session force wins; otherwise the persisted preset counts
    // only when it would actually auto-apply (auto-detect on + detected anime), else Off. Cycling
    // therefore always starts from what the user is really seeing on screen.
    private fun AnimeShaderChoice.isSelected(settings: com.nuvio.app.features.player.PlayerSettingsUiState): Boolean {
        val override = settings.desktopAnimeSessionOverride
        val (effectiveMode, effectiveShaderPath) = when {
            override != null -> override.mode to override.customShaderPath
            settings.desktopAnimeModeAutoEnabled && isAnimeContentDetected ->
                settings.desktopAnimeMode to settings.desktopCustomShaderSelectedPath
            else -> DesktopAnimeMode.Off to ""
        }
        return if (effectiveMode == DesktopAnimeMode.CustomShader) {
            mode == DesktopAnimeMode.CustomShader && customShaderPath == effectiveShaderPath
        } else {
            mode == effectiveMode
        }
    }

    private fun applyAnimeShaderChoice(choice: AnimeShaderChoice) {
        // Session-scoped force: applies to this playback session (including binged next episodes)
        // regardless of anime detection, and never touches the persisted preset/auto settings.
        PlayerSettingsRepository.setDesktopAnimeSessionOverride(
            DesktopAnimeSessionOverride(
                mode = choice.mode,
                customShaderPath = choice.customShaderPath,
                label = choice.label,
            ),
        )
        showPresetPill("Anime", choice.label)
    }

    private fun openAnimeShaderContextMenu() {
        val current = handle.takeIf { it != 0L } ?: return
        val settings = PlayerSettingsRepository.uiState.value
        val choicesJson = animeShaderChoices(settings).joinToString(prefix = "[", postfix = "]") { choice ->
            "{\"label\":${choice.label.toJsonString()},\"selected\":${choice.isSelected(settings)}}"
        }
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioOpenAnimeShaderContextMenu && window.nuvioOpenAnimeShaderContextMenu($choicesJson)",
        )
    }

    private fun selectAnimeShader(index: Int) {
        val settings = PlayerSettingsRepository.uiState.value
        animeShaderChoices(settings).getOrNull(index)?.let(::applyAnimeShaderChoice)
        // Re-push so a kept-open context menu reflects the new selection right away.
        openAnimeShaderContextMenu()
    }

    // Curated, runtime-settable mpv properties surfaced in the "Advanced (mpv)" context submenu.
    // Each option maps to one mpv property; the choice flagged `isDefault` carries mpv's own
    // default value so reverting is a plain property set. Add a row here to expose a new option —
    // no new persisted field is needed (all live in the desktopMpvPropertyOverrides map).
    private data class MpvChoice(val label: String, val value: String, val isDefault: Boolean = false)
    private data class MpvOption(val key: String, val label: String, val choices: List<MpvChoice>)

    private val mpvOptionCatalog = listOf(
        MpvOption(
            key = "deband",
            label = "Debanding",
            // Nuvio's baseline enables deband (player_bridge startMpv), so On is the default here.
            choices = listOf(MpvChoice("On", "yes", isDefault = true), MpvChoice("Off", "no")),
        ),
        MpvOption(
            key = "deinterlace",
            label = "Deinterlace",
            choices = listOf(MpvChoice("Off", "no", isDefault = true), MpvChoice("On", "yes")),
        ),
        MpvOption(
            key = "sharpen",
            label = "Sharpen",
            choices = listOf(
                MpvChoice("Off", "0", isDefault = true),
                MpvChoice("Low", "1.0"),
                MpvChoice("High", "2.0"),
            ),
        ),
        MpvOption(
            // Luma upscaler. Only honoured for live-action; anime/custom-shader profiles force their
            // own sharp scaler (see applyDesktopVideoProfile). Default matches the live-action baseline.
            key = "scale",
            label = "Upscaling",
            choices = listOf(
                MpvChoice("Default", "spline36", isDefault = true),
                MpvChoice("Sharp", "ewa_lanczossharp"),
                MpvChoice("Fast", "bilinear"),
            ),
        ),
        MpvOption(
            // Loudness normalisation ("night mode"). Nothing else sets `af` on the main player.
            key = "af",
            label = "Loudness normalize",
            choices = listOf(
                MpvChoice("Off", "", isDefault = true),
                MpvChoice("On", "dynaudnorm=f=150:g=15"),
            ),
        ),
    )

    private fun selectedMpvValue(option: MpvOption, overrides: Map<String, String>): String? =
        overrides[option.key] ?: option.choices.firstOrNull { it.isDefault }?.value

    private fun openMpvOptionsContextMenu() {
        val current = handle.takeIf { it != 0L } ?: return
        val overrides = PlayerSettingsRepository.uiState.value.desktopMpvPropertyOverrides
        var flatIndex = 0
        val optionsJson = mpvOptionCatalog.joinToString(prefix = "[", postfix = "]") { option ->
            val currentValue = selectedMpvValue(option, overrides)
            val choicesJson = option.choices.joinToString(prefix = "[", postfix = "]") { choice ->
                val entry = "{\"label\":${choice.label.toJsonString()},\"index\":$flatIndex," +
                    "\"selected\":${choice.value == currentValue}}"
                flatIndex += 1
                entry
            }
            "{\"label\":${option.label.toJsonString()},\"choices\":$choicesJson}"
        }
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioSetMpvOptionsMenu && window.nuvioSetMpvOptionsMenu($optionsJson)",
        )
    }

    private fun selectMpvOption(index: Int) {
        var flat = 0
        mpvOptionCatalog.forEach { option ->
            option.choices.forEach { choice ->
                if (flat == index) {
                    // Persist only genuine overrides; a default selection clears the stored key but
                    // still applies the default value so the change is visible immediately.
                    PlayerSettingsRepository.setDesktopMpvPropertyOverride(
                        key = option.key,
                        value = if (choice.isDefault) null else choice.value,
                    )
                    setMpvProperty(option.key, choice.value)
                    showPresetPill(option.label, choice.label)
                    // Re-push so a kept-open menu reflects the new selection.
                    openMpvOptionsContextMenu()
                    return
                }
                flat += 1
            }
        }
    }

    fun cycleDesktopAnimeSvpMode() {
        val current = PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled
        val next = !current
        PlayerSettingsRepository.setDesktopAnimeSvpEnabled(next)
        // An explicit in-player toggle also forces SVP for this session even when the title isn't
        // detected as anime (the persisted toggle alone only fires on effectively-anime content).
        PlayerSettingsRepository.setDesktopAnimeSvpSessionForced(next)
        showPresetPill("Anime SVP", if (next) "On" else "Off")
    }

    /**
     * Triggers the skip-intro/outro action if the skip prompt is currently on screen (Tab hotkey,
     * matching the official client). Returns true if a skip was dispatched so the caller can consume
     * the key; false when no skip is available (so Tab keeps its normal behavior).
     */
    fun triggerSkipIntervalIfAvailable(): Boolean {
        return when {
            controlsState.skipPromptVisible && !controlsState.skipPromptDismissed -> {
                dispatchKeyboardShortcut("skipInterval", 0.0)
                true
            }
            controlsState.nextEpisodeVisible && controlsState.nextEpisodePlayable -> {
                dispatchKeyboardShortcut("playNextEpisode", 0.0)
                true
            }
            else -> false
        }
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

    /** Pushes the hero-trailer volume to the overlay slider/mute icon. Needed because volume is
     * excluded from the controls structure key (to avoid re-sending the full JSON on every slider
     * drag), so a programmatic change (the [ / ] shortcuts) wouldn't otherwise reach the overlay. */
    fun setHeroTrailerVolume(effectiveVolume: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioSetHeroTrailerVolume && window.nuvioSetHeroTrailerVolume(${effectiveVolume.coerceIn(0, 100)})",
        )
    }

    private fun showVolumePillFromNative() {
        val current = handle.takeIf { it != 0L } ?: return
        val percentage = NativePlayerBridge.volume(current).toInt().coerceIn(0, 200)
        val muted = NativePlayerBridge.isMuted(current)
        NativePlayerBridge.runJavaScript(current, "window.nuvioShowVolumePill && window.nuvioShowVolumePill($percentage, $muted)")
    }

    private fun handlePlayerEvent(type: String, value: Double) {
        if (disposed) return
        if (type == "playbackRestart") {
            // First rendered frame of the replacement source. Deliver a message held across a
            // source switch (the failover "trying next source" pill) only now: showing it when
            // the surface attached burned its display window during connect/buffering, leaving
            // it visible for barely a second once video appeared. Not consumed — the event
            // still falls through to the engine callbacks below.
            transientMessageForNextAttach?.let { (pillTitle, pillValue) ->
                transientMessageForNextAttach = null
                showPresetPill(pillTitle, pillValue, durationMs = 5000)
            }
        }
        val errorPrefix = when {
            type.startsWith(MPV_STARTUP_ERROR_EVENT_PREFIX) -> MPV_STARTUP_ERROR_EVENT_PREFIX
            type.startsWith(MPV_PLAYBACK_ERROR_EVENT_PREFIX) -> MPV_PLAYBACK_ERROR_EVENT_PREFIX
            else -> null
        }
        if (errorPrefix != null) {
            val message = type.removePrefix(errorPrefix).trim()
                .ifBlank { "MPV failed to start playback." }
            pendingSource?.onError(message)
            return
        }
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
        if (type == "openAnimeShaderContextMenu") {
            openAnimeShaderContextMenu()
            return
        }
        if (type == "selectAnimeShader") {
            selectAnimeShader(value.toInt())
            return
        }
        if (type == "openMpvOptionsContextMenu") {
            openMpvOptionsContextMenu()
            return
        }
        if (type == "selectMpvOption") {
            selectMpvOption(value.toInt())
            return
        }
        if (type == "selectDesktopHdrMode") {
            selectDesktopHdrMode(value.toInt())
            return
        }
        if (type == "selectDesktopColorProfile") {
            selectDesktopColorProfile(value.toInt())
            return
        }
        if (type == "setDesktopUiScalePercent") {
            PlayerSettingsRepository.setDesktopUiScalePercent(value.toInt())
            return
        }
        if (type == "seekThumbnail") {
            handle.takeIf { it != 0L }?.let { current ->
                NativePlayerBridge.requestSeekThumbnail(current, value.toLong().coerceAtLeast(0L))
            }
            return
        }
        if (type == "keyboardToggleMute") {
            val current = handle.takeIf { it != 0L } ?: return
            NativePlayerBridge.setMute(current, !NativePlayerBridge.isMuted(current))
            showVolumePillFromNative()
            return
        }
        if (type == "keyboardCycleAnimeSvp") {
            cycleDesktopAnimeSvpMode()
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
            "pictureInPicture" -> {
                val actionHandled = onAction(PlayerControlsAction.PictureInPicture)
                if (!actionHandled) setDesktopPictureInPicture(!desktopPictureInPictureState.value, SwingUtilities.getWindowAncestor(host))
            }
            "beginPictureInPictureInteraction" -> beginNativeCompactPlayerWindowInteraction(
                window = SwingUtilities.getWindowAncestor(host),
                mode = value.toInt(),
            )
            "updatePictureInPictureInteraction" -> updateNativeCompactPlayerWindowInteraction(
                SwingUtilities.getWindowAncestor(host),
            )
            "endPictureInPictureInteraction" -> endNativeCompactPlayerWindowInteraction(
                SwingUtilities.getWindowAncestor(host),
            )
            "cursorVisibility" -> {
                val current = handle.takeIf { it != 0L } ?: return
                NativePlayerBridge.setCursorHidden(current, value == 0.0)
            }
            else -> {
                if (type == "fileLoaded") {
                    handle.takeIf { it != 0L }?.let { current ->
                        applyPendingSubtitleConfiguration(current)
                        // Align the overlay's tracked volume with mpv's real (possibly restored)
                        // volume now that the controls page is definitely loaded, so the on-screen
                        // percentage matches after a volume-carrying episode change.
                        val volumePercent = NativePlayerBridge.volume(current).toInt().coerceIn(0, 200)
                        val muted = NativePlayerBridge.isMuted(current)
                        NativePlayerBridge.runJavaScript(
                            current,
                            "window.nuvioSyncVolume && window.nuvioSyncVolume($volumePercent, $muted)",
                        )
                    }
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
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f)
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
        attachGeneration.incrementAndGet()
        pendingSource = null
        host.onPeerReady = null
        onAction = { false }
        onEvent = { _, _ -> false }
        onScrubChange = { false }
        onScrubFinished = { false }
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
        // Media buffered before (re)starting playback. A quarter-second at 1x is around 10% of
        // the former two-second gate; scale content-time with playback speed so wall-clock startup
        // remains equally quick at faster rates. Presets still control the read-ahead capacity.
        setMpvProperty("cache-pause-wait", (0.25f * factor).toString())
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
                synchronized(nativeProcessLifecycleLock) {
                    runCatching { NativePlayerBridge.dispose(current) }
                }
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

    // Use mpv's bundled stats overlay—the same detailed file/cache/display/video/audio readout
    // exposed by mpv's default `i` binding—instead of maintaining a shortened imitation.
    override fun setDiagnosticsOverlayEnabled(enabled: Boolean) {
        val current = handle.takeIf { it != 0L } ?: return
        if (diagnosticsOverlayEnabled == enabled) return
        NativePlayerBridge.toggleStatsOverlay(current)
        diagnosticsOverlayEnabled = enabled
    }

    /**
     * Keyboard-shortcut entry point for the MPV diagnostics overlay. Routed through the HUD's own
     * toggle (rather than [setDiagnosticsOverlayEnabled] directly) so the context-menu indicator,
     * the `mpv-diagnostics` chrome class, and the confirmation pill stay in sync — the HUD owns
     * that state and forwards the resulting `toggleMpvDiagnostics` event back to Kotlin.
     */
    fun toggleMpvDiagnosticsOverlay() {
        val current = handle.takeIf { it != 0L } ?: return
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioToggleMpvDiagnostics && window.nuvioToggleMpvDiagnostics()",
        )
    }

    // Desktop/mpv supports software amplification above 100% (volume-max=200 in the native
    // bridge) so quiet content can be boosted. 2.0 == 200%.
    override val maxVolumeFraction: Float get() = 2f

    override fun setVolume(fraction: Float): PlayerAudioLevel? {
        val current = handle.takeIf { it != 0L } ?: return null
        val clamped = fraction.coerceIn(0f, maxVolumeFraction)
        persistedVolumeFraction = clamped
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

    override fun selectSubtitleTrack(index: Int): Boolean {
        val current = handle.takeIf { it != 0L } ?: return false
        if (index < 0) {
            return NativePlayerBridge.selectSubtitleTrack(current, -1)
        }
        val trackId = resolveTrackId(index, decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }) ?: return false
        if (!NativePlayerBridge.selectSubtitleTrack(current, trackId)) return false
        applyPendingSubtitleConfiguration(current)
        return true
    }

    override fun selectSecondarySubtitleTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        if (index < 0) {
            NativePlayerBridge.setMpvProperty(current, "secondary-sid", "no")
            return
        }
        val trackId = resolveTrackId(index, decodeTracks { NativePlayerBridge.subtitleTracksJson(it) }) ?: return
        NativePlayerBridge.setMpvProperty(current, "secondary-sid", trackId.toString())
        NativePlayerBridge.setMpvProperty(current, "secondary-sub-pos", "10")
    }

    override fun getChapters(): List<PlayerChapter> {
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NativeMpvChapter>>(NativePlayerBridge.chaptersJson(current))
                .map { chapter -> PlayerChapter(chapter.startTime, chapter.title) }
        }.getOrDefault(emptyList())
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
        // Drop shadow is driven purely through mpv properties (no native-signature change). It only
        // renders in the "outline-and-shadow" border style that applySubtitleStyle selects for a
        // transparent background, and only when the offset is non-zero. Set it before the native
        // call so the redraw it triggers also flushes the shadow change while paused.
        NativePlayerBridge.setMpvProperty(
            current,
            "sub-shadow-offset",
            if (style.shadowEnabled) SUBTITLE_SHADOW_OFFSET else "0",
        )
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
        // mpv's sub-shadow-color is an alias of sub-back-color. applySubtitleStyle writes the
        // user's (usually transparent) background to that property, so the shadow colour must be
        // applied afterwards or it is immediately overwritten and the offset appears to do
        // nothing. In outline-and-shadow mode sub-back-color is used for the shadow itself.
        if (style.shadowEnabled && style.backgroundColor.alpha <= 0f) {
            NativePlayerBridge.setMpvProperty(current, "sub-back-color", SUBTITLE_SHADOW_COLOR)
            forceVideoRedraw()
        }
        reapplyCustomSubtitleOverrides(current)
    }

    private fun reapplyCustomSubtitleOverrides(current: Long) {
        val settings = PlayerSettingsRepository.uiState.value
        if (settings.desktopMpvConfigMode != DesktopMpvConfigMode.Replace &&
            settings.desktopMpvConfigMode != DesktopMpvConfigMode.Full
        ) return

        settings.desktopCustomMpvOptions.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .forEach { option ->
                val separator = option.indexOf('=')
                if (separator <= 0) return@forEach
                val key = option.substring(0, separator).trim()
                if (!key.startsWith("sub-")) return@forEach
                val rawValue = option.substring(separator + 1).trim()
                val value = rawValue
                    .takeIf { it.length >= 2 && it.first() == it.last() && it.first() in charArrayOf('"', '\'') }
                    ?.substring(1, rawValue.length - 1)
                    ?: rawValue
                NativePlayerBridge.setMpvProperty(current, key, value)
            }
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

@Serializable
private data class NativeMpvChapter(
    val startTime: Double = -1.0,
    val title: String = "",
)

private fun resolveTrackId(index: Int, tracks: List<NativeMpvTrack>): Int? =
    tracks.firstNotNullOfOrNull { track ->
        if (track.index == index) {
            track.id.toIntOrNull()
        } else {
            null
        }
    } ?: tracks.getOrNull(index)?.id?.toIntOrNull()

// Subtitle drop-shadow tuning (mpv scaled pixels + #AARRGGBB). The colour must be applied after
// the subtitle background because mpv aliases sub-shadow-color to sub-back-color.
// Offset trimmed 2 -> 1.5 so the shadow sits closer to the glyph and reads less like a hard
// duplicate copy.
private const val SUBTITLE_SHADOW_OFFSET = "1.5"
// Alpha lowered over time (0xC0 -> 0x99 -> 0x66) for a progressively softer, less harsh drop
// shadow. mpv renders the shadow as a solid offset copy (no blur), so opacity and offset are the
// only levers for "softness".
private const val SUBTITLE_SHADOW_COLOR = "#66000000"

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

// mpv's sub-font-size is an unbounded positive double; the floor mirrors the UI's 6 sp minimum
// (×3) so a corrupt persisted value can't render invisible subtitles. The 96f ceiling is kept
// deliberately: UI sizes above 32 have always flattened to 96 and raising it would suddenly
// enlarge subtitles for existing profiles.
private fun SubtitleStyleState.toMpvSubtitleFontSize(): Float =
    (fontSizeSp * 3f).coerceIn(18f, 96f)

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
    val initialProgressFraction: Float,
    val nvidiaRtxSuperResolutionEnabled: Boolean,
    val nvidiaRtxHdrEnabled: Boolean,
    val isAnimeContent: Boolean,
    val animeSvpFilter: String?,
    val extraMpvOptions: List<String> = emptyList(),
    val onError: (String?) -> Unit,
    val controlsPageUrl: String,
    val tracePlaybackStart: Boolean = false,
    val restoreVolume: Boolean = false,
)

// Standard bitstream formats to hand untouched to a receiver when passthrough is on. dts-hd
// covers DTS-HD High Resolution; dts-hd-ma covers DTS-HD Master Audio.
private const val AUDIO_PASSTHROUGH_SPDIF_CODECS = "ac3,dts,eac3,truehd,dts-hd,dts-hd-ma"

/**
 * Builds the `key=value` mpv option lines derived from the user's desktop playback settings,
 * applied (native side) just before mpv_initialize so they override Nuvio's built-in options.
 * Passthrough is emitted first so a custom `audio-spdif=` line in the options box can still win.
 */
private fun buildDesktopUserMpvOptions(): List<String> {
    val settings = PlayerSettingsRepository.uiState.value
    return buildList {
        add("@nuvio-config-mode=${settings.desktopMpvConfigMode.name.lowercase()}")
        val useNuvioOptions = settings.desktopMpvConfigMode != DesktopMpvConfigMode.Full
        // Let mpv select an embedded preferred-language subtitle as part of file loading. Waiting
        // for the Compose-side track poll is unnecessarily fragile for tracks already present in
        // the container, and can leave `sid=no` active if startup takes longer than that poll.
        // Per-title persisted selection still runs afterward and can override this default.
        val preferredSubtitleLanguages = preferredSubtitleTargetsForSettings(settings)
            .filterNot { it.equals("forced", ignoreCase = true) || it.equals("none", ignoreCase = true) }
            .distinct()
        if (useNuvioOptions && preferredSubtitleLanguages.isNotEmpty()) {
            add("slang=${preferredSubtitleLanguages.joinToString(",")}")
            add("sid=auto")
        }
        // Select embedded audio while mpv loads the file. Otherwise a container's default flag
        // can start Polish (or another language) even though an English track is already present.
        val preferredAudioLanguages = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = settings.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )
        if (useNuvioOptions && preferredAudioLanguages.isNotEmpty()) {
            add("alang=${preferredAudioLanguages.joinToString(",")}")
            add("aid=auto")
        }
        if (useNuvioOptions && settings.desktopAudioPassthroughEnabled) {
            add("audio-spdif=$AUDIO_PASSTHROUGH_SPDIF_CODECS")
            // If the output device can't bitstream (no receiver / shared-mode-only device), the ao
            // open fails; force the null-audio fallback so video keeps playing (silent) instead of
            // the failure being able to stall playback start.
            add("audio-fallback-to-null=yes")
        }
        // Full verbose diagnostics: mpv's own --log-file writes a complete debug-level log
        // independently of the bridge's warnings-only capture (and without the per-line event-thread
        // write cost that gates the bridge log), so troubleshooting gets the unshortened stream.
        if (useNuvioOptions && settings.desktopVerboseMpvLoggingEnabled) {
            val verboseLogPath = runCatching {
                DesktopStorage.rootDir.resolve("logs").resolve("mpv-verbose.log").also {
                    it.parent?.toFile()?.mkdirs()
                }.toString()
            }.getOrNull()
            if (verboseLogPath != null) add("log-file=$verboseLogPath")
        }
        // Curated overrides from the "Advanced (mpv)" menu, before the raw options box so a
        // hand-typed line can still win over a menu selection for the same property.
        if (useNuvioOptions) {
            settings.desktopMpvPropertyOverrides.forEach { (key, value) -> add("$key=$value") }
        }
        if (settings.desktopMpvConfigMode != DesktopMpvConfigMode.Off) {
            settings.desktopCustomMpvOptions.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .forEach { add("@nuvio-user:$it") }
        }
    }
}

/**
 * Web (non-debrid) addon streams are served by streaming-site CDNs that reject libmpv's default
 * `User-Agent`. Stremio never hits this because its local streaming server makes the origin
 * request; we hand the URL to mpv directly, so supply a browser-like UA whenever the stream's
 * `proxyHeaders` didn't specify one. ffmpeg's http protocol only adds its own User-Agent when the
 * custom header block lacks one, so an addon-supplied UA (or this default) is sent exactly once.
 */
private const val DESKTOP_PLAYBACK_FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private fun Map<String, String>.withDefaultPlaybackUserAgent(sourceUrl: String): Map<String, String> {
    val isHttp = sourceUrl.startsWith("http://", ignoreCase = true) ||
        sourceUrl.startsWith("https://", ignoreCase = true)
    if (!isHttp || keys.any { it.trim().equals("User-Agent", ignoreCase = true) }) return this
    return this + ("User-Agent" to DESKTOP_PLAYBACK_FALLBACK_USER_AGENT)
}

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
        "pictureInPicture" -> PlayerControlsAction.PictureInPicture
        "heroTrailerMute" -> PlayerControlsAction.HeroTrailerMute
        else -> null
    }

private fun PlayerControlsState.toControlsJson(
    appFullscreenKeyCode: Int,
    playerShortcutKeyCodes: Map<String, Int>,
): String =
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
        appendJsonField("pictureInPictureLabel", pictureInPictureLabel)
        append(',')
        appendJsonField("pictureInPictureActive", pictureInPictureActive)
        append(',')
        appendJsonField("desktopHdrModeLabel", desktopHdrModeLabel)
        append(',')
        appendJsonField("desktopColorProfileLabel", desktopColorProfileLabel)
        append(',')
        appendJsonField("desktopAnimeModeLabel", desktopAnimeModeLabel)
        append(',')
        appendJsonField("desktopAnimeSvpEnabled", desktopAnimeSvpEnabled)
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
        appendJsonField("shadowLabel", shadowLabel)
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
        appendJsonField("legacyHudEnabled", legacyHudEnabled)
        append(',')
        appendJsonField("alwaysShowClock", alwaysShowClock)
        append(',')
        appendJsonField("playbackSpeedFineIncrementsEnabled", playbackSpeedFineIncrementsEnabled)
        append(',')
        appendJsonField("appFullscreenKeyCode", appFullscreenKeyCode)
        append(',')
        appendJsonMapField("playerShortcutKeyCodes", playerShortcutKeyCodes)
        append(',')
        appendJsonField("uiScalePercent", uiScalePercent)
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
        appendJsonArrayField("chapters", chapters) { appendChapterJson(it) }
        append(',')
        appendJsonField("sourceIsLoading", sourceIsLoading)
        append(',')
        appendJsonField("sourceBadgePlacement", sourceBadgePlacement)
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
        appendJsonField("builtInSubtitleFilterActive", builtInSubtitleFilterActive)
        append(',')
        appendJsonArrayField("builtInSubtitleItems", builtInSubtitleItems) { appendBuiltInSubtitleItemJson(it) }
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
        append(',')
        appendJsonField("heroTrailerNavDismissEdge", heroTrailerNavDismissEdge)
        append(',')
        appendJsonField("heroTrailerNavDismissBandFraction", heroTrailerNavDismissBandFraction.toDouble())
        append(',')
        appendJsonField("streamFailoverEnabled", streamFailoverEnabled)
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

private fun StringBuilder.appendJsonField(name: String, value: Double) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Long) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendChapterJson(chapter: PlayerChapter) {
    append('{')
    appendJsonField("startTime", chapter.startTime)
    append(',')
    appendJsonField("title", chapter.title)
    append('}')
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

private fun StringBuilder.appendJsonMapField(name: String, values: Map<String, Int>) {
    append('"').append(name).append("\":{")
    values.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) append(',')
        append(key.toJsonString()).append(':').append(value)
    }
    append('}')
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
    appendJsonArrayField("badges", item.badges) { badge ->
        append('{')
        appendJsonField("name", badge.name)
        append(',')
        appendJsonField("imageUrl", badge.imageUrl)
        append(',')
        appendJsonField("backgroundColor", badge.backgroundColor)
        append(',')
        appendJsonField("textColor", badge.textColor)
        append(',')
        appendJsonField("borderColor", badge.borderColor)
        append('}')
    }
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

private fun StringBuilder.appendBuiltInSubtitleItemJson(item: PlayerControlBuiltInSubtitleItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("label", item.label)
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
    appendJsonField("shadowEnabled", style.shadowEnabled)
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
