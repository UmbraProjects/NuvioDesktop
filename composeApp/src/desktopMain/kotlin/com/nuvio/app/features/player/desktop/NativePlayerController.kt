package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.i18n.localizedString
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.badgeFillCssColorOrNull
import com.nuvio.app.features.streams.toBadgeCssColorOrNull
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.io.File
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopHudLayout
import com.nuvio.app.features.player.PlayerControlAddonSubtitleItem
import com.nuvio.app.features.player.PlayerControlCastItem
import com.nuvio.app.features.player.PlayerControlAudioTrackItem
import com.nuvio.app.features.player.PlayerControlBuiltInSubtitleItem
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlFilterItem
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleCueItem
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import com.nuvio.app.features.player.DESKTOP_AUDIO_CHANNELS_OVERRIDE_KEY
import com.nuvio.app.features.player.DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY
import com.nuvio.app.features.player.desktopAudioChannelChoices
import com.nuvio.app.features.player.desktopAudioDialogueChoices
import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopAnimeSessionOverride
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.DesktopColorGrade
import com.nuvio.app.features.player.desktopColorGrade
import com.nuvio.app.features.player.presetGrade
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
import com.nuvio.app.features.player.PlayerControlPlaylistItem
import com.nuvio.app.features.player.PlayerControlPartyMember
import com.nuvio.app.features.player.PlayerControlPartyState
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerShortcutAction
import com.nuvio.app.features.player.PlayerShortcutsRepository
import com.nuvio.app.features.player.playerShortcutKeyCodeLabel
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.effectiveRtxTrueHdrAnimeEnabled
import com.nuvio.app.features.player.effectiveRtxTrueHdrEnabled
import com.nuvio.app.features.player.effectiveRtxVsrEnabled
import com.nuvio.app.features.player.SUBTITLE_ASS_SCALE_MAX
import com.nuvio.app.features.player.SUBTITLE_ASS_SCALE_MIN
import com.nuvio.app.features.player.SUBTITLE_BLUR_MAX
import com.nuvio.app.features.player.SUBTITLE_BLUR_MIN
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleBackgroundColorSwatches
import com.nuvio.app.features.player.SubtitleShadowColorSwatches
import com.nuvio.app.features.player.SubtitleAssStyleMode
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.subtitleShadowOffsetLabel
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.inferForcedSubtitleTrack
import com.nuvio.app.features.player.isExplicitProviderDiagnosticVideoUrl
import com.nuvio.app.features.player.isProviderPlaybackEndpoint
import com.nuvio.app.features.player.OriginalLanguageCache
import com.nuvio.app.features.player.primarySubtitleTargetsForSettings
import com.nuvio.app.features.player.preferredMpvMediaTitle
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets
import com.nuvio.app.features.player.toStorageHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread
import kotlin.math.floor
import nuvio.composeapp.generated.resources.*

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
        val label: String = localizedString(mode.labelRes),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val CONTROLS_PAGE_REVISION = "20261027-hud-labels-1"
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

    /**
     * Whether a handle of this controller may have touched VapourSynth: SVP requested, the user's
     * mpv options/config applied, or any `vf` written. Sticky, so it only ever errs towards the
     * serialized teardown. A hero trailer never sets it, and its teardown then skips
     * [nativeProcessLifecycleLock]: a trailer whose mpv_terminate_destroy took 1.9s held the main
     * player's create for exactly that long (2026-10-20).
     */
    @Volatile
    private var mayHostVapourSynth = false
    @Volatile
    private var lastResizeMode: PlayerResizeMode? = null
    @Volatile
    private var lastQuickAutoCrop = false
    private val handleLock = Any()
    private val attachGeneration = AtomicLong(0L)
    @Volatile
    private var disposed = false
    @Volatile
    private var pendingSource: PendingSource? = null
    private val pendingMpvProperties = linkedMapOf<String, String>()
    // True while the Metered preset's paused-prefetch clamp is applied to the live handle. Session
    // state only — never persisted into pendingMpvProperties, so it cannot leak into the next source.
    @Volatile
    private var meteredPrefetchFrozen = false
    private var pendingVideoRedraw = false
    // Transient overlay message held across a source switch; shown once the next attach completes.
    @Volatile
    private var transientMessageForNextAttach: Pair<String, String>? = null
    @Volatile
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    // The player [pendingSubtitleStyle] was last applied to through applySubtitleStyle; a new
    // player (stream swap) gets it again even when the style is unchanged.
    @Volatile
    private var subtitleStyleAppliedHandle = 0L
    // Top of the HUD's visible chrome as a percentage of the window height from the top, reported
    // by the overlay while the chrome is up and 0 while it is hidden. Subtitles whose bottom edge
    // would fall below it are lifted onto it for as long as it is there; see
    // effectiveSubtitlePosition.
    private var hudSubtitleClearancePercent = 0.0
    @Volatile
    private var pendingSubtitleDelayMs: Int? = null
    @Volatile
    private var keyboardPanelOpen = false
    // Whether the current title was detected as anime (genre heuristic / caches). Pushed in by the
    // desktop engine so the F10 cycle and the shader context menu can mark which choice is actually
    // in effect right now (auto-applied preset vs Off) before any session force exists.
    @Volatile
    var isAnimeContentDetected = false
    // Where the last definitively failed seek was headed (the bridge reports it just before the
    // error). Read by the engine's rate-limit recovery; cleared by every attach.
    @Volatile
    var lastSeekFailureTargetMs: Long? = null
        private set
    // Installed by the desktop engine for each attach; see reconnectAfterRateLimit.
    @Volatile
    var rateLimitFailoverFallback: ((String) -> Boolean)? = null
    private var controlsState = PlayerControlsState()
    private var lastSentControlsStructureKey: PlayerControlsState? = null
    // The HUD's own text (see HudLabels.kt) rides on the first controls push of each page.
    private var hudLabelsSent = false
    private var lastSentMediaSessionKey: String? = null
    private var lastSentMpvMediaTitle: String? = null
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    fun attach(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        mediaTitle: String = "",
        playWhenReady: Boolean,
        initialPositionMs: Long,
        initialProgressFraction: Float = 0f,
        initialPlaybackSpeed: Float = 1f,
        isAnimeContent: Boolean = false,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean,
        // Whether Anime4K / a custom shader is expected on this session. With SVP, it tells the
        // native file-load RTX install the same thing applyDesktopAnimeProfile decides later.
        animeShaderRequested: Boolean = false,
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
        // fork:preshow - extra "key=value" mpv init options applied before mpv_initialize/loadfile.
        // For anything that must be in place before the first audio frame (volume, mute, af):
        // as runtime properties they land after create() returns, after loading has started,
        // which rebuilds the audio chain mid-start and is audible as a dip or blip.
        initMpvOptions: List<String> = emptyList(),
    ) {
        if (disposed) return
        if (enableUserMpvOptions || animeSvpEnabled) mayHostVapourSynth = true
        // A network-share source (\\host\share, file://host/…) from an addon or scraper would make
        // Windows authenticate to that host and hand it the user's NTLM hash.
        listOfNotNull(sourceUrl, sourceAudioUrl?.takeIf { it.isNotBlank() }).forEach { source ->
            val verdict = com.nuvio.app.features.player.PlaybackSourcePolicy.checkInProcess(source)
            if (verdict is com.nuvio.app.features.player.PlaybackSourcePolicy.Verdict.Rejected) {
                Logger.withTag("PlaybackSourcePolicy").w { "Refusing playback source: ${verdict.reason}" }
                onError(localizedString(Res.string.player_hud_this_stream_points_at_a_network))
                return
            }
        }
        // Re-attaching the same stream (surface recreation, RTX/settings toggles) must resume
        // from where playback currently is — restarting at the original initialPositionMs
        // looks like playback randomly jumping back. New sources keep the caller's position.
        // Never for live TV: a reconnect reopens the channel at the live edge, and a stream that
        // cannot seek left its first frame waiting on the carried position (once a bogus 26 h one
        // from the provider's timestamps) until the start-up watchdog gave up on it.
        val carriedPositionMs = if (
            pendingSource?.sourceUrl == sourceUrl &&
            !com.nuvio.app.features.iptv.IptvPlaybackSession.isIptvUrl(sourceUrl)
        ) {
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
        synchronized(pendingMpvProperties) {
            // A previous source's rendering graph must never be replayed before the next probe.
            pendingMpvProperties.clear()
        }
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            mediaTitle = mediaTitle,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            headerLines = sourceHeaders.withDefaultPlaybackUserAgent(sourceUrl).toHeaderLines(),
            playWhenReady = playWhenReady,
            initialPositionMs = resumePositionMs,
            initialProgressFraction = resumeProgressFraction,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            isAnimeContent = isAnimeContent,
            animeSvpFilter = if (animeSvpEnabled && PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled) {
                DesktopAnimeSvp.vapoursynthArgument(
                    debugOverlay = PlayerSettingsRepository.uiState.value.desktopAnimeSvpDebugOverlayEnabled,
                )
            } else null,
            extraMpvOptions = buildList {
                if (tracePlaybackStart && DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                    add("@nuvio-trace-id=${PlaybackStartTrace.currentId}")
                }
                if (isAnimeContent && DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                    add("@nuvio-anime-layer=${if (animeShaderRequested || animeSvpEnabled) "yes" else "no"}")
                    if (PlayerSettingsRepository.uiState.value.effectiveRtxTrueHdrAnimeEnabled) {
                        add("@nuvio-anime-true-hdr=yes")
                    }
                }
                // youtube-dl has nothing to add for a provider endpoint or a debrid CDN link, and
                // when mpv cannot open one it hands the URL to ytdl_hook, whose "youtube-dl failed:
                // not found" errors then bury the real failure in the playback log.
                if (isProviderPlaybackEndpoint(sourceUrl) ||
                    isExplicitProviderDiagnosticVideoUrl(sourceUrl) ||
                    PlaybackRedirectResolver.isDirectMediaHost(sourceUrl)
                ) {
                    add("ytdl=no")
                }
                // Live TV: reconnect options and, with an IPTV proxy set, the local relay. Before the
                // user's options so an mpv.conf can still override them.
                addAll(com.nuvio.app.features.iptv.iptvMpvOptions(sourceUrl))
                // A Nuvio option like the rest: Replace lets a custom network-timeout override it,
                // Add keeps this one, and Full (the user's own configuration only) never gets it.
                if (PlayerSettingsRepository.uiState.value.desktopMpvConfigMode != DesktopMpvConfigMode.Full) {
                    directMediaNetworkTimeoutOption(sourceUrl)?.let(::add)
                }
                if (enableUserMpvOptions) addAll(buildDesktopUserMpvOptions(initialPlaybackSpeed))
                // After the buffer preset in the user options, which also sets demuxer-max-back-bytes
                // and cache-pause-wait.
                addAll(com.nuvio.app.features.iptv.iptvLiveBufferMpvOptions(sourceUrl))
                addAll(com.nuvio.app.features.iptv.iptvRewindMpvOptions(sourceUrl))
                addAll(initMpvOptions) // fork:preshow
                // An init option rather than a runtime property: mpv filters text subtitles as it
                // parses them, so it has to be set before the first track loads. Only ever turned
                // on here, never forced off, so an mpv.conf that enables it keeps working.
                if (PlayerSettingsRepository.uiState.value.stripSdhSubtitles) add("sub-filter-sdh=yes")
                mediaTitle.takeIf(String::isNotBlank)?.let { title ->
                    // Unlike mpv's inferred media-title this never exposes the resolved URL. Keep
                    // it after custom options so the app's presentation/security boundary wins.
                    add("force-media-title=$title")
                }
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
        lastSeekFailureTargetMs = null
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
            hudLabelsSent = false
            lastSentMediaSessionKey = null
            lastSentMpvMediaTitle = null
            thread(isDaemon = true, name = "Nuvio-Player-Attach") {
                // Live TV over HLS with subtitles plays a local copy of its master playlist so the
                // provider's repeated subtitle cues can be dropped (IptvHlsSubtitleServer). One
                // playlist fetch, made here off the UI thread and outside the lifecycle lock.
                val mpvSourceUrl = if (com.nuvio.app.features.iptv.IptvPlaybackSession.isIptvUrl(pending.sourceUrl)) {
                    com.nuvio.app.features.iptv.IptvHlsSubtitleServer.playbackUrlFor(pending.sourceUrl, pending.headerLines)
                } else {
                    pending.sourceUrl
                }
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
                            sourceUrl = withMpvFilenameHint(mpvSourceUrl, pending.mediaTitle),
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
                    lastResizeMode?.let { setResizeMode(it, lastQuickAutoCrop) }
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
        val mpvMediaTitle = preferredMpvMediaTitle(
            streamTitle = state.streamTitle,
            streamFilename = state.streamFilename,
            title = state.title,
            episodeText = state.episodeText,
        )
        if (mpvMediaTitle.isNotBlank() && mpvMediaTitle != lastSentMpvMediaTitle) {
            lastSentMpvMediaTitle = mpvMediaTitle
            // Also update the live property: metadata can resolve after loadfile, and source
            // credential refreshes can replace a URL without rebuilding the visible controls.
            NativePlayerBridge.setMpvProperty(current, "force-media-title", mpvMediaTitle)
        }
        // Tracked separately from the controls structure key: the media-session widget only
        // cares about the title/artwork fields, and it must still update on an episode change that
        // leaves the HUD's button structure identical.
        val mediaSessionKey = "${state.title} ${state.episodeText} ${state.mediaSessionArtwork}"
        if (mediaSessionKey != lastSentMediaSessionKey) {
            lastSentMediaSessionKey = mediaSessionKey
            NativePlayerBridge.setMediaSessionMetadata(
                current,
                state.title,
                state.episodeText,
                state.mediaSessionArtwork,
            )
        }
        if (structureKey == lastSentControlsStructureKey) return
        lastSentControlsStructureKey = structureKey
        AppShortcutsRepository.ensureLoaded()
        PlayerShortcutsRepository.ensureLoaded()
        val controlsJson = state.toControlsJson(
            appFullscreenKeyCode = AppShortcutsRepository.keyCode(AppShortcutAction.ToggleFullscreen),
            playerShortcutKeyCodes = PlayerShortcutAction.entries.associate { action ->
                action.id to PlayerShortcutsRepository.keyCode(action)
            },
            skipIntervalKeyLabel = playerShortcutKeyCodeLabel(
                PlayerShortcutsRepository.keyCode(PlayerShortcutAction.SkipInterval),
            ),
        )
        val payload = if (hudLabelsSent) controlsJson else controlsJson.withHudLabels()
        if (!controlsJsonIsWellFormed(payload)) return
        NativePlayerBridge.updateControls(current, payload)
        hudLabelsSent = true
    }

    /**
     * [quickAutoCrop] is for the hero trailers: Auto Crop that settles in about two seconds from
     * the first frame instead of after five seconds of learning (native mode 4).
     */
    fun setResizeMode(mode: PlayerResizeMode, quickAutoCrop: Boolean = false) {
        lastResizeMode = mode
        lastQuickAutoCrop = quickAutoCrop
        handle.takeIf { it != 0L }?.let { current ->
            NativePlayerBridge.setResizeMode(
                handle = current,
                mode = when (mode) {
                    PlayerResizeMode.Fit -> 0
                    PlayerResizeMode.Fill -> 1
                    PlayerResizeMode.Zoom -> 2
                    PlayerResizeMode.AutoCrop -> if (quickAutoCrop) 4 else 3
                },
            )
            forceVideoRedraw()
        }
    }

    private class VideoProfileBatch {
        val properties = linkedMapOf<String, String>()
        var redraw = false
    }

    // Per thread: the profile is applied off the UI thread (endVideoProfile waits on mpv's VO, ~1 s
    // with SVP + Anime4K), and a volume or subtitle change made on the UI thread meanwhile must go
    // straight to mpv, not into another thread's batch.
    private val videoProfileBatch = ThreadLocal<VideoProfileBatch?>()

    /**
     * Collect one final value per property before touching the decoder or rendering surface.
     * Blocks until native code has taken the profile, so call it off the UI thread.
     */
    fun <T> withVideoProfile(block: () -> T): T {
        check(videoProfileBatch.get() == null)
        val batch = VideoProfileBatch()
        videoProfileBatch.set(batch)
        try {
            val result = block()
            videoProfileBatch.set(null)
            val current = handle
            val windows = DesktopHostOs.current == DesktopHostOs.WINDOWS && current != 0L
            if (windows) NativePlayerBridge.beginVideoProfile(current)
            try {
                batch.properties.forEach { (key, value) -> setMpvProperty(key, value) }
            } finally {
                if (windows) NativePlayerBridge.endVideoProfile(current)
                else if (batch.redraw) forceVideoRedraw()
            }
            return result
        } finally {
            videoProfileBatch.remove()
        }
    }

    fun setMpvProperty(key: String, value: String) {
        if (key == "vf") mayHostVapourSynth = true
        videoProfileBatch.get()?.let { it.properties[key] = value; return }
        synchronized(pendingMpvProperties) {
            pendingMpvProperties[key] = value
        }
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setMpvProperty(it, key, value) }
    }

    /**
     * The chain native code runs instead of the next `vf` while it bypasses SVP for speed (see
     * applySpeedSensitiveVideoFiltersLocked); "" means that `vf` minus VapourSynth. Set it right
     * before every `vf` write. Windows-only pseudo-property; nothing else understands it.
     */
    fun setSvpBypassVideoFilters(value: String) {
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) setMpvProperty("@nuvio-svp-bypass-vf", value)
    }

    fun completeSvpStartupProfile() {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.completeSvpStartupProfile(it) }
    }

    /** Forces mpv to repaint the embedded video surface (see native forceVideoRedraw). */
    fun forceVideoRedraw() {
        videoProfileBatch.get()?.let { it.redraw = true; return }
        val current = handle
        if (current == 0L) {
            pendingVideoRedraw = true
        } else {
            NativePlayerBridge.forceVideoRedraw(current)
        }
    }

    /**
     * Publishes the pipeline half of the playback-info panel: what mpv is actually doing with the
     * picture, which only the desktop video-profile pass knows (the settings alone don't say — an
     * HDR preset does nothing to an SDR file, and an anime preset does nothing to live action).
     * The subtitle row travels with the ordinary controls state instead, since it can change
     * mid-playback. [videoLabel] is the colour preset in effect and [shaderLabel] the shader chain
     * on top of it; they are separate because both can be running at once.
     *
     * [session] identifies the playback attempt. This pass re-runs on every settings change as well
     * as on file load, and the panel is a start-of-playback greeting — the token is what tells the
     * overlay which of those it is looking at.
     */
    fun setPlaybackInfo(
        session: String,
        hdrLabel: String?,
        svpActive: Boolean,
        videoLabel: String,
        shaderLabel: String?,
        qualityLabel: String = "",
    ) {
        // The same signal drives the pad's right stick: with a shader running its down direction
        // cycles the shader chain, and the HDR mode when none is. This pass is the only place that
        // knows which, and it re-runs on every settings change and file load.
        com.nuvio.app.features.input.GamepadContext.animeShaderActive = !shaderLabel.isNullOrBlank()
        val current = handle.takeIf { it != 0L } ?: return
        val payload = "{\"session\":${session.toJsonString()}," +
            "\"hdr\":${(hdrLabel ?: "").toJsonString()}," +
            "\"svp\":$svpActive," +
            "\"video\":${videoLabel.toJsonString()}," +
            "\"shader\":${(shaderLabel ?: "").toJsonString()}," +
            "\"quality\":${qualityLabel.toJsonString()}}"
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioSetPlaybackInfo && window.nuvioSetPlaybackInfo($payload)",
        )
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

    override fun reconnectAfterRateLimit(message: String): Boolean =
        rateLimitFailoverFallback?.invoke(message) ?: false

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
        }
        // Speed events pill themselves inside handlePlayerEvent, since the HUD sends them too.
    }

    private fun showPlaybackSpeedPillFromNative() {
        val current = handle.takeIf { it != 0L } ?: return
        val speed = NativePlayerBridge.speed(current)
        val value = if (speed % 1f == 0f) {
            "${speed.toInt()}x"
        } else {
            "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
        }
        showPresetPill(localizedString(Res.string.player_hud_playback_speed), value)
    }

    fun cycleDesktopHdrMode() {
        val modes = DesktopHdrMode.entries
        val current = PlayerSettingsRepository.uiState.value.desktopHdrMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        PlayerSettingsRepository.setDesktopHdrMode(next)
        showPresetPill(localizedString(Res.string.player_hud_hdr_mode), localizedString(next.labelRes))
    }

    private fun selectDesktopHdrMode(index: Int) {
        DesktopHdrMode.entries.getOrNull(index)?.let { mode ->
            PlayerSettingsRepository.setDesktopHdrMode(mode)
            showPresetPill(localizedString(Res.string.player_hud_hdr_mode), localizedString(mode.labelRes))
        }
    }

    fun cycleDesktopColorProfile() {
        val profiles = DesktopColorProfile.entries
        val current = PlayerSettingsRepository.uiState.value.desktopColorProfile
        val next = profiles[(profiles.indexOf(current) + 1) % profiles.size]
        PlayerSettingsRepository.setDesktopColorProfile(next)
        showPresetPill(localizedString(Res.string.player_hud_color_profile), localizedString(next.labelRes))
    }

    /**
     * Nudges one channel of the Custom grade from the HUD's colour panel, and makes Custom the
     * active profile so the change is actually on screen.
     *
     * A grade that has never been touched is seeded from whichever preset was showing, so nudging
     * saturation while on Cinematic starts from Cinematic instead of snapping the picture back to
     * neutral for the one frame before the nudge lands. A grade the user has already dialled in is
     * theirs — that gets adjusted as-is rather than being overwritten by a preset.
     */
    private fun adjustDesktopColorGrade(command: String, delta: Int) {
        val settings = PlayerSettingsRepository.uiState.value
        val current = settings.desktopColorGrade()
        val neutral = DesktopColorGrade(0, 0, 0, 0)
        val base = if (settings.desktopColorProfile != DesktopColorProfile.Custom && current == neutral) {
            settings.desktopColorProfile.presetGrade()
        } else {
            current
        }
        val next = when (command) {
            "adjustDesktopColorContrast" -> base.copy(contrast = base.contrast + delta)
            "adjustDesktopColorBrightness" -> base.copy(brightness = base.brightness + delta)
            "adjustDesktopColorSaturation" -> base.copy(saturation = base.saturation + delta)
            "adjustDesktopColorGamma" -> base.copy(gamma = base.gamma + delta)
            else -> return
        }
        // Values before the profile: switching to Custom last means the picture never shows a
        // half-applied grade, and each setter no-ops when its value is unchanged.
        PlayerSettingsRepository.setDesktopColorContrast(next.contrast)
        PlayerSettingsRepository.setDesktopColorBrightness(next.brightness)
        PlayerSettingsRepository.setDesktopColorSaturation(next.saturation)
        PlayerSettingsRepository.setDesktopColorGamma(next.gamma)
        PlayerSettingsRepository.setDesktopColorProfile(DesktopColorProfile.Custom)
    }

    private fun selectDesktopColorProfile(index: Int) {
        DesktopColorProfile.entries.getOrNull(index)?.let { profile ->
            PlayerSettingsRepository.setDesktopColorProfile(profile)
            showPresetPill(localizedString(Res.string.player_hud_color_profile), localizedString(profile.labelRes))
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
        showPresetPill(localizedString(Res.string.player_hud_anime), choice.label)
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
            label = localizedString(Res.string.player_hud_debanding),
            // Nuvio's baseline enables deband (player_bridge startMpv), so On is the default here.
            choices = listOf(MpvChoice(localizedString(Res.string.compose_action_on), "yes", isDefault = true), MpvChoice(localizedString(Res.string.compose_action_off), "no")),
        ),
        MpvOption(
            key = "deinterlace",
            label = localizedString(Res.string.player_hud_deinterlace),
            choices = listOf(MpvChoice(localizedString(Res.string.compose_action_off), "no", isDefault = true), MpvChoice(localizedString(Res.string.compose_action_on), "yes")),
        ),
        MpvOption(
            // Not mpv's `sharpen` (vo=gpu only, a no-op on gpu-next): the saved value picks a CAS
            // strength that the video profile appends to the active shader chain.
            key = DESKTOP_SHARPEN_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_sharpen),
            choices = listOf(
                MpvChoice(localizedString(Res.string.compose_action_off), "0", isDefault = true),
                MpvChoice(localizedString(Res.string.player_hud_low), "1.0"),
                MpvChoice(localizedString(Res.string.player_hud_high), "2.0"),
            ),
        ),
        MpvOption(
            // Luma upscaler. Only honoured for live-action; anime/custom-shader profiles force their
            // own sharp scaler (see applyDesktopVideoProfile). Default matches the live-action baseline.
            key = "scale",
            label = localizedString(Res.string.player_hud_upscaling),
            choices = listOf(
                MpvChoice(localizedString(Res.string.player_hud_default), "spline36", isDefault = true),
                MpvChoice(localizedString(Res.string.player_hud_sharp), "ewa_lanczossharp"),
                MpvChoice(localizedString(Res.string.player_hud_fast), "bilinear"),
            ),
        ),
        MpvOption(
            // Added grain (deband-grain), not the grain an AV1 encode carries, which mpv always
            // applies. Owned by the video profile; Light/Heavy need Deband, see selectMpvOption.
            key = DESKTOP_FILM_GRAIN_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_film_grain),
            choices = listOf(
                MpvChoice(localizedString(Res.string.player_hud_default), "default", isDefault = true),
                MpvChoice(localizedString(Res.string.player_hud_none), DESKTOP_FILM_GRAIN_NONE),
                MpvChoice(localizedString(Res.string.player_hud_light), DESKTOP_FILM_GRAIN_LIGHT),
                MpvChoice(localizedString(Res.string.player_hud_heavy), DESKTOP_FILM_GRAIN_HEAVY),
            ),
        ),
        MpvOption(
            // target-contrast for SDR tone mapping. Owned by the video profile (HDR mode sets it).
            key = DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_display_type),
            choices = listOf(
                MpvChoice("LCD", "lcd", isDefault = true),
                MpvChoice("OLED", DESKTOP_DISPLAY_PANEL_OLED),
            ),
        ),
        MpvOption(
            // mpv's built-in frame-timing interpolation. Owned by the video profile so it can never
            // run together with SVP.
            key = DESKTOP_INTERPOLATION_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_motion_interpolation),
            choices = listOf(MpvChoice(localizedString(Res.string.compose_action_off), "no", isDefault = true), MpvChoice(localizedString(Res.string.compose_action_on), "yes")),
        ),
        MpvOption(
            // Nothing else sets `af` on the main player. The filters are one chain, so each choice
            // replaces the last. Loudness keeps its old value so a saved choice carries over.
            key = "af",
            label = localizedString(Res.string.player_hud_audio_profile),
            choices = listOf(
                MpvChoice(localizedString(Res.string.compose_action_off), "", isDefault = true),
                MpvChoice(localizedString(Res.string.player_hud_loudness_normalize), "dynaudnorm=f=150:g=15"),
                MpvChoice(localizedString(Res.string.player_hud_night_mode), "lavfi=[acompressor=ratio=3:threshold=-20dB:attack=20:release=300:makeup=4dB]"),
                MpvChoice(localizedString(Res.string.player_hud_vocal_clarity), "lavfi=[equalizer=f=300:t=q:w=1:g=-3,equalizer=f=2800:t=q:w=1:g=5]"),
                MpvChoice(localizedString(Res.string.player_hud_bass_boost), "lavfi=[bass=g=7:f=110:w=0.6]"),
                MpvChoice(localizedString(Res.string.player_hud_less_bass), "lavfi=[bass=g=-8:f=110:w=0.6]"),
            ),
        ),
        // Shared with the Playback settings page; see DesktopAudioDownmix.kt.
        MpvOption(
            key = DESKTOP_AUDIO_CHANNELS_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_audio_channels),
            choices = desktopAudioChannelChoices.map { MpvChoice(it.label, it.value, it.isDefault) },
        ),
        MpvOption(
            key = DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY,
            label = localizedString(Res.string.player_hud_dialogue_boost_downmix),
            choices = desktopAudioDialogueChoices.map { MpvChoice(it.label, it.value, it.isDefault) },
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
                    applyMpvOptionSideEffects(option.key, choice.value)
                    // Profile-owned rows are applied by the profile rebuild the override change
                    // triggers; writing them here would only be overwritten.
                    if (option.key !in DESKTOP_PROFILE_OWNED_OVERRIDE_KEYS) setMpvProperty(option.key, choice.value)
                    showPresetPill(option.label, mpvOptionPillLabel(option.key, choice))
                    // Re-push so a kept-open menu reflects the new selection.
                    openMpvOptionsContextMenu()
                    return
                }
                flat += 1
            }
        }
    }

    /** Keeps rows that depend on each other consistent; see the catalog notes. */
    private fun applyMpvOptionSideEffects(key: String, value: String) {
        val overrides = PlayerSettingsRepository.uiState.value.desktopMpvPropertyOverrides
        val addsGrain = value == DESKTOP_FILM_GRAIN_LIGHT || value == DESKTOP_FILM_GRAIN_HEAVY
        when {
            // mpv adds grain inside the deband pass, so grain without deband does nothing.
            key == DESKTOP_FILM_GRAIN_OVERRIDE_KEY && addsGrain && overrides["deband"] == "no" -> {
                PlayerSettingsRepository.setDesktopMpvPropertyOverride(key = "deband", value = null)
                setMpvProperty("deband", "yes")
            }
            key == "deband" && value == "no" &&
                overrides[DESKTOP_FILM_GRAIN_OVERRIDE_KEY].let {
                    it == DESKTOP_FILM_GRAIN_LIGHT || it == DESKTOP_FILM_GRAIN_HEAVY
                } -> PlayerSettingsRepository.setDesktopMpvPropertyOverride(
                    key = DESKTOP_FILM_GRAIN_OVERRIDE_KEY,
                    value = null,
                )
            // Left at Off the profile writes nothing (a custom options line may own these), so
            // undo the interpolation it had switched on.
            key == DESKTOP_INTERPOLATION_OVERRIDE_KEY && value != "yes" ->
                DESKTOP_INTERPOLATION_OFF.forEach { (property, setting) -> setMpvProperty(property, setting) }
        }
    }

    /** Says when a choice is saved but held back, so a row that seems to do nothing explains itself. */
    private fun mpvOptionPillLabel(key: String, choice: MpvChoice): String {
        if (key != DESKTOP_INTERPOLATION_OVERRIDE_KEY || choice.value != "yes") return choice.label
        val settings = PlayerSettingsRepository.uiState.value
        return when {
            settings.desktopAudioPassthroughEnabled -> localizedString(Res.string.player_hud_on_off_during_audio_passthrough)
            settings.desktopAnimeSvpEnabled -> localizedString(Res.string.player_hud_on_off_while_svp_runs)
            else -> choice.label
        }
    }

    fun cycleDesktopAnimeSvpMode() {
        val current = PlayerSettingsRepository.uiState.value.desktopAnimeSvpEnabled
        val next = !current
        PlayerSettingsRepository.setDesktopAnimeSvpEnabled(next)
        // An explicit in-player toggle also forces SVP for this session even when the title isn't
        // detected as anime (the persisted toggle alone only fires on effectively-anime content).
        PlayerSettingsRepository.setDesktopAnimeSvpSessionForced(next)
        showPresetPill(localizedString(Res.string.player_hud_anime_svp), if (next) localizedString(Res.string.compose_action_on) else localizedString(Res.string.compose_action_off))
    }

    // The player menu's RTX rows: session-only, so the video profile swaps the d3d11vpp filter
    // live instead of the saved setting re-attaching the player. The pill names the one hold-back
    // a viewer can act on from here; the others (HDR source, Windows HDR off) are in the settings.
    // The NVIDIA App switch is named too: without it the row would seem to do nothing.
    private fun toggleRtxVsrForSession() {
        val settings = PlayerSettingsRepository.uiState.value
        val next = !settings.effectiveRtxVsrEnabled
        PlayerSettingsRepository.setDesktopRtxVsrSessionOverride(next)
        val driverOff = NvidiaRtxDriverState.settings.value.superResolutionEnabled == false
        showPresetPill(localizedString(Res.string.player_hud_rtx_super_resolution), rtxPillValue(next, settings.desktopAnimeSvpEnabled, driverOff))
    }

    private fun toggleRtxTrueHdrForSession() {
        val settings = PlayerSettingsRepository.uiState.value
        val next = !settings.effectiveRtxTrueHdrEnabled
        PlayerSettingsRepository.setDesktopRtxTrueHdrSessionOverride(next)
        val driverOff = NvidiaRtxDriverState.settings.value.trueHdrEnabled == false
        showPresetPill(localizedString(Res.string.player_hud_rtx_true_hdr), rtxPillValue(next, settings.desktopAnimeSvpEnabled, driverOff))
    }

    private fun rtxPillValue(enabled: Boolean, svpEnabled: Boolean, driverOff: Boolean): String = when {
        !enabled -> localizedString(Res.string.compose_action_off)
        driverOff -> localizedString(Res.string.player_hud_on_off_in_the_nvidia_app)
        svpEnabled -> localizedString(Res.string.player_hud_on_off_while_svp_runs)
        else -> localizedString(Res.string.compose_action_on)
    }

    /**
     * The skip key (Tab by default, Start on a pad): skips the prompt on screen, plays the next
     * episode, or drives the SkipDB submission toast — whichever `skipKeyAction` resolved to when
     * the controls state was last built (see PlayerScreenRuntime.resolveSkipKeyAction). Returns
     * true if something was dispatched so the caller consumes the key; false when there was
     * nothing to do, so the key keeps its normal behaviour.
     */
    fun triggerSkipIntervalIfAvailable(): Boolean {
        val action = controlsState.skipKeyAction
        if (action.isEmpty()) return false
        dispatchKeyboardShortcut(action, 0.0)
        return true
    }

    /** Escape while the submission toast is asking something dismisses it instead of leaving. */
    /**
     * The "Escape goes windowed first" shortcut option: in fullscreen (not PiP) Escape leaves
     * fullscreen instead of the player. Already windowed, Escape closes the player as usual.
     */
    fun escapeShouldGoWindowed(): Boolean =
        PlayerShortcutsRepository.escWindowedFirst() &&
            desktopAppFullscreenState.value &&
            !desktopPictureInPictureState.value

    fun dismissSkipSubmitToastIfShown(): Boolean {
        if (!controlsState.skipSubmitToastDismissible) return false
        dispatchKeyboardShortcut("skipSubmitDismiss", 0.0)
        return true
    }

    fun openKeyboardPanel(panel: String) {
        if (panel != "sources" && panel != "episodes" && panel != "iptvChannels") return
        val current = handle.takeIf { it != 0L } ?: return
        keyboardPanelOpen = true
        NativePlayerBridge.runJavaScript(
            current,
            "window.nuvioOpenKeyboardPanel && window.nuvioOpenKeyboardPanel(${panel.toJsonString()})",
        )
    }

    /** Live TV: one typed digit of a channel number; the HUD collects them and sends the number. */
    fun dispatchIptvDigit(digit: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        NativePlayerBridge.runJavaScript(current, "window.nuvioIptvDigit && window.nuvioIptvDigit(${digit.coerceIn(0, 9)})")
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
        if (type == "seekFailureTargetMs") {
            lastSeekFailureTargetMs = value.toLong().coerceAtLeast(0L)
            return
        }
        val errorPrefix = when {
            type.startsWith(MPV_STARTUP_ERROR_EVENT_PREFIX) -> MPV_STARTUP_ERROR_EVENT_PREFIX
            type.startsWith(MPV_PLAYBACK_ERROR_EVENT_PREFIX) -> MPV_PLAYBACK_ERROR_EVENT_PREFIX
            else -> null
        }
        if (errorPrefix != null) {
            val message = type.removePrefix(errorPrefix).trim()
                .ifBlank { localizedString(Res.string.player_hud_mpv_failed_to_start_playback) }
            pendingSource?.onError(message)
            return
        }
        if (type == "audioPassthroughFallback") {
            // The bridge already rebuilt the audio chain as PCM (value=1) or found no track to
            // reload (0). Either way video is still buffering, so hold the notice for the first
            // rendered frame like the failover pill — shown now it would expire unseen.
            audioPassthroughLog.i { "rejected by output device; bridge fell back to PCM (trackReloaded=${value >= 1.0})" }
            transientMessageForNextAttach = localizedString(Res.string.player_hud_audio_passthrough) to localizedString(Res.string.player_hud_not_supported_by_output_device_decoding)
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
        if (type in DESKTOP_COLOR_GRADE_COMMANDS) {
            adjustDesktopColorGrade(type, value.toInt())
            return
        }
        if (type == "resetDesktopColorGrade") {
            PlayerSettingsRepository.resetDesktopColorTuning()
            return
        }
        if (type == "setDesktopUiScalePercent") {
            PlayerSettingsRepository.setDesktopUiScalePercent(value.toInt())
            return
        }
        if (type == "selectDesktopHudLayout") {
            DesktopHudLayout.entries.getOrNull(value.toInt())?.let(PlayerSettingsRepository::setDesktopHudLayout)
            return
        }
        if (type == "seekThumbnail") {
            // Belt and braces with the HUD flag: the preview decoder is a second stream opened
            // against the same host, so a stale controls page must not be able to start it.
            // controlsState carries the runtime's verdict (mode, Metered preset, local vs
            // streaming source), so this cannot disagree with what the HUD was told.
            if (!controlsState.seekThumbnailsEnabled || SeekThumbnailRateLimitGate.isSuppressed()) {
                return
            }
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
        if (type == "toggleRtxVsr") {
            toggleRtxVsrForSession()
            return
        }
        if (type == "toggleRtxTrueHdr") {
            toggleRtxTrueHdrForSession()
            return
        }
        // Both the AWT key dispatcher and the HUD (its speed button and the step/toggle keys, which
        // this page owns whenever the WebView holds focus) route relative speed changes here, so the
        // Kotlin rules run against mpv's live speed either way and the pill is raised in one place.
        if (type == "keyboardSpeedStep" || type == "keyboardSpeedToggle") {
            onEvent(type, value)
            showPlaybackSpeedPillFromNative()
            return
        }
        // The overlay reports an episode card whose artwork never arrived, after its own retries
        // and the fallback have both failed. Without this the failure is invisible from the app
        // side: the card is simply blank, and nothing distinguishes "the payload carried no URL"
        // (missing still upstream) from "the URL was there and the WebView could not fetch it".
        if (type == "episodeArtworkFailed") {
            val index = value.toInt()
            val item = controlsState.episodeItems.firstOrNull { it.index == index }
            episodeArtworkLog.w {
                "Episode card artwork failed: " +
                    "index=$index code=${item?.code.orEmpty()} title=${item?.title.orEmpty()} " +
                    "thumbnail=${item?.thumbnail?.ifBlank { "<none>" } ?: "<unknown item>"} " +
                    "fallback=${controlsState.episodeFallbackThumbnail.ifBlank { "<none>" }}"
            }
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
            "goLive" -> {
                releaseMeteredPrefetchFreeze()
                handle.takeIf { it != 0L }?.let { NativePlayerBridge.seekToCachedLiveEdge(it, value.toLong()) }
            }
            "scrubFinish" -> {
                val scrubHandled = onScrubFinished(value.toLong())
                if (!scrubHandled) {
                    seekTo(value.toLong())
                }
            }
            "toggleFullscreen" -> toggleDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
            // The HUD's Escape (with nothing of its own to close). The back button still sends "back".
            "escapeBack" -> {
                if (escapeShouldGoWindowed()) {
                    toggleDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
                } else {
                    handlePlayerEvent("back", value)
                }
            }
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
            "hudSubtitleClearance" -> {
                val percent = value.coerceIn(0.0, 100.0)
                if (percent == hudSubtitleClearancePercent) return
                hudSubtitleClearancePercent = percent
                val current = handle.takeIf { it != 0L } ?: return
                val style = pendingSubtitleStyle ?: return
                // The bridge queues its light paused repaint for any sub-* write; the full
                // forceVideoRedraw flashed the video black whenever the HUD showed or hid paused.
                NativePlayerBridge.setMpvProperty(current, "sub-pos", effectiveSubtitlePosition(style).toString())
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
                if (NativePlayerBridge.isEnded(current)) {
                    NativePlayerBridge.seekTo(current, 0L)
                    releaseMeteredPrefetchFreeze()
                    NativePlayerBridge.setPaused(current, false)
                } else {
                    val play = NativePlayerBridge.isPaused(current)
                    if (play) releaseMeteredPrefetchFreeze()
                    NativePlayerBridge.setPaused(current, !play)
                }
            }
            PlayerControlsAction.SeekBack,
            PlayerControlsAction.KeyboardSeekBack -> fallbackSeekBy(-configuredSeekStepMs())
            PlayerControlsAction.SeekForward,
            PlayerControlsAction.KeyboardSeekForward -> fallbackSeekBy(configuredSeekStepMs())
            PlayerControlsAction.Speed -> cycleFallbackSpeed()
            else -> Unit
        }
    }

    /**
     * The fallback path runs when Compose never saw the action, so it has no settings snapshot to
     * read — go to the repository directly, the same way the HUD's own preset writes do.
     */
    private fun configuredSeekStepMs(): Long {
        PlayerSettingsRepository.ensureLoaded()
        return PlayerSettingsRepository.uiState.value.seekStepSeconds.toLong() * 1_000L
    }

    private fun fallbackSeekBy(offsetMs: Long) {
        val current = handle
        if (current != 0L) {
            releaseMeteredPrefetchFreeze()
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

    /**
     * [snapshot] off the UI thread. Every field is an mpv property read that waits on mpv's core,
     * which stalled the UI thread 150-190 ms per poll while a hero trailer opened (`(UiStall)`,
     * 2026-10-19). Holds the process-wide lifecycle lock, which the native dispose also takes, so
     * the handle cannot be freed mid-read. A trailer's dispose skips that lock (see
     * [mayHostVapourSynth]), so its polls do too rather than hold up the main player's create;
     * the native handle registry already makes a read on a disposed id a no-op.
     */
    suspend fun snapshotOffUiThread(): PlayerPlaybackSnapshot = withContext(Dispatchers.IO) {
        if (mayHostVapourSynth) {
            synchronized(nativeProcessLifecycleLock) { snapshot() }
        } else {
            snapshot()
        }
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            val isLoading = NativePlayerBridge.isLoading(current)
            val isEnded = NativePlayerBridge.isEnded(current)
            val isPaused = NativePlayerBridge.isPaused(current)
            val positionMs = NativePlayerBridge.positionMs(current)
            val bufferedPositionMs = NativePlayerBridge.bufferedPositionMs(current)
            updateMeteredPrefetchFreeze(
                isPaused = isPaused,
                isLoading = isLoading,
                isEnded = isEnded,
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
            )
            PlayerPlaybackSnapshot(
                isLoading = isLoading,
                isPlaying = !isPaused && !isLoading && !isEnded,
                isEnded = isEnded,
                durationMs = NativePlayerBridge.durationMs(current),
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
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
        val settings = PlayerSettingsRepository.uiState.value
        val customOptionNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
        desktopBufferPresetMpvOptions(preset, DesktopHostOs.current, speed)
            .filterNot { (name, _) -> name == "stream-buffer-size" }
            // Live TV's rewind buffer owns the back buffer; the preset would shrink it mid-stream.
            .filterNot { (name, _) ->
                name == "demuxer-max-back-bytes" && com.nuvio.app.features.iptv.iptvRewindActive(pendingSource?.sourceUrl)
            }
            // Likewise its resume threshold (see IPTV_CACHE_PAUSE_WAIT_SECONDS).
            .filterNot { (name, _) ->
                name == "cache-pause-wait" && com.nuvio.app.features.iptv.iptvLiveBufferActive(pendingSource?.sourceUrl)
            }
            // While the Metered freeze is engaged the clamp outranks the preset. Without this an
            // unrelated re-apply (the video-profile flow re-runs this on every settings change, and
            // so does every speed change) would restore full readahead mid-pause and start
            // downloading again.
            .filterNot { (name, _) -> meteredPrefetchFrozen && name in METERED_PREFETCH_PROPERTIES }
            .forEach { (name, value) ->
                if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customOptionNames, name)) {
                    setMpvProperty(name, value)
                }
            }
    }

    /**
     * Applies or lifts the Metered preset's paused-prefetch clamp. Driven from [snapshot] so it
     * follows mpv's real paused state no matter which input path toggled playback (HUD, keyboard,
     * app, or mpv itself); the unpause and seek paths call [releaseMeteredPrefetchFreeze] directly so
     * resuming doesn't have to wait for the next poll.
     */
    private fun updateMeteredPrefetchFreeze(
        isPaused: Boolean,
        isLoading: Boolean,
        isEnded: Boolean,
        positionMs: Long,
        bufferedPositionMs: Long,
    ) {
        val settings = PlayerSettingsRepository.uiState.value
        if (settings.desktopBufferPreset != DesktopBufferPreset.Metered) {
            releaseMeteredPrefetchFreeze()
            return
        }
        // Never freeze while mpv is still filling, at EOF, or without a buffer to resume from —
        // those are exactly the states where clamping prefetch could strand a paused player.
        val hasResumableBuffer = bufferedPositionMs - positionMs >= METERED_FREEZE_MIN_BUFFER_MS
        val shouldFreeze = isPaused && !isLoading && !isEnded && hasResumableBuffer
        if (shouldFreeze == meteredPrefetchFrozen) return
        if (!shouldFreeze) {
            releaseMeteredPrefetchFreeze()
            return
        }
        val customOptionNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
        meteredPrefetchFrozen = true
        METERED_PREFETCH_PROPERTIES.forEach { name ->
            if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customOptionNames, name)) {
                // Deliberately not setMpvProperty: that caches the value in pendingMpvProperties and
                // replays it onto the next handle, which would carry the clamp into the next episode.
                // The freeze is per-session state; a fresh handle gets the preset from its pre-init
                // options instead.
                handle.takeIf { it != 0L }?.let { NativePlayerBridge.setMpvProperty(it, name, METERED_PAUSED_PREFETCH_SECS) }
            }
        }
    }

    /** Restores the preset's prefetch limits if the Metered pause clamp is engaged. Idempotent. */
    private fun releaseMeteredPrefetchFreeze() {
        if (!meteredPrefetchFrozen) return
        meteredPrefetchFrozen = false
        applyDesktopBufferPreset(PlayerSettingsRepository.uiState.value.desktopBufferPreset)
    }

    private fun disposePlayerHandle() {
        val current = synchronized(handleLock) {
            val value = handle
            handle = 0L
            value
        }
        keyboardPanelOpen = false
        lastSentControlsStructureKey = null
        hudLabelsSent = false
        lastSentMediaSessionKey = null
        lastSentMpvMediaTitle = null
        // The clamp lived on the handle being torn down; the next handle starts from its pre-init
        // preset options, so only the flag needs clearing.
        meteredPrefetchFrozen = false
        if (current != 0L) {
            val serialized = mayHostVapourSynth
            kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-Player-Dispose") {
                if (serialized) {
                    synchronized(nativeProcessLifecycleLock) {
                        runCatching { NativePlayerBridge.dispose(current) }
                    }
                } else {
                    runCatching { NativePlayerBridge.dispose(current) }
                }
            }
        }
    }

    /** Mirrors [handleFallbackAction]'s toggle: an ended or paused player plays, otherwise it pauses. */
    override fun toggleWouldPlay(): Boolean? {
        val current = handle
        if (current == 0L) return null
        return NativePlayerBridge.isEnded(current) || NativePlayerBridge.isPaused(current)
    }

    override fun play() {
        // Restore the full prefetch budget before resuming, so mpv refills immediately instead of
        // draining the frozen buffer until the next snapshot poll notices playback resumed.
        releaseMeteredPrefetchFreeze()
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, false) }
    }

    override fun pause() {
        handle.takeIf { it != 0L }?.let { NativePlayerBridge.setPaused(it, true) }
    }

    override fun seekTo(positionMs: Long) {
        // A seek can invalidate the cache the freeze was protecting; let the demuxer refill at the
        // preset rate. The snapshot poll re-freezes once the new position has a resumable buffer.
        releaseMeteredPrefetchFreeze()
        val current = handle.takeIf { it != 0L } ?: return
        if (liveCacheOnlySeeks()) {
            NativePlayerBridge.seekWithinCacheTo(current, positionMs)
        } else {
            NativePlayerBridge.seekTo(current, positionMs)
        }
    }

    override fun seekBy(offsetMs: Long) {
        releaseMeteredPrefetchFreeze()
        val current = handle.takeIf { it != 0L } ?: return
        if (liveCacheOnlySeeks()) {
            NativePlayerBridge.seekWithinCacheBy(current, offsetMs)
        } else {
            NativePlayerBridge.seekBy(current, offsetMs)
        }
    }

    // A live channel can only seek inside what mpv has cached; anything further fell through to a
    // connection that cannot seek and broke playback (see seekWithinCacheLocked in the bridge).
    private fun liveCacheOnlySeeks(): Boolean =
        com.nuvio.app.features.iptv.IptvPlaybackSession.isIptvUrl(pendingSource?.sourceUrl)

    override fun retry() {
        val pending = pendingSource ?: return
        attach(
            sourceUrl = pending.sourceUrl,
            mediaTitle = pending.mediaTitle,
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

    override fun selectAudioTrack(index: Int): Boolean {
        val current = handle.takeIf { it != 0L } ?: return false
        val trackId = resolveTrackId(index, decodeTracks { NativePlayerBridge.audioTracksJson(it) }) ?: return false
        val selected = NativePlayerBridge.selectAudioTrack(current, trackId)
        if (!selected) {
            showPresetPill(localizedString(Res.string.player_hud_audio_track), localizedString(Res.string.player_hud_stream_is_rate_limited_try_again))
        }
        return selected
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

    override val supportsSpeechSpots: Boolean get() = handle != 0L

    override fun decodeSpeechSpot(startSeconds: Double, durationSeconds: Double): FloatArray? {
        val current = handle.takeIf { it != 0L } ?: return null
        val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-autosync").apply { mkdirs() }
        val file = File.createTempFile("speech-", ".f32", directory)
        return try {
            val result = NativePlayerBridge.decodeSpeechSpot(current, startSeconds, durationSeconds, file.absolutePath)
            if (result != 0 || file.length() < 4) return null
            // ao=pcm with audio-format=float writes raw little-endian float32 samples.
            val samples = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(samples.remaining()).also(samples::get)
        } finally {
            file.delete()
        }
    }

    override fun sourceBytesPerSecond(): Double? =
        handle.takeIf { it != 0L }
            ?.let { NativePlayerBridge.speechSourceBytesPerSecond(it) }
            ?.takeIf { it > 0.0 }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        val current = handle
        // The Style tab applies a change from its click handler and the player screen applies the
        // same style again once it recomposes; the repeat would cost a second round of property
        // writes, a redraw and a paused refresh for nothing.
        if (current != 0L && current == subtitleStyleAppliedHandle && style == pendingSubtitleStyle) return
        pendingSubtitleStyle = style
        if (current != 0L) {
            applySubtitleStyle(current, style)
            subtitleStyleAppliedHandle = current
        }
    }

    private fun applyPendingSubtitleConfiguration(current: Long) {
        pendingSubtitleDelayMs?.let { delayMs -> NativePlayerBridge.setSubtitleDelayMs(current, delayMs) }
        pendingSubtitleStyle?.let { style -> applySubtitleStyle(current, style) }
    }

    private fun applySubtitleStyle(current: Long, style: SubtitleStyleState) {
        // Drop shadow, blur, and italic are driven purely through mpv properties (no native-signature
        // change). The shadow only renders in the "outline-and-shadow" border style that
        // applySubtitleStyle selects for a transparent background, and only when the offset is
        // non-zero. Set these before the native call so the redraw it triggers also flushes them
        // while paused; applySubtitleStyle does not touch sub-blur / sub-italic, so they persist.
        NativePlayerBridge.setMpvProperty(
            current,
            "sub-shadow-offset",
            if (style.shadowEnabled) subtitleShadowOffsetLabel(style.shadowOffset) else "0",
        )
        NativePlayerBridge.setMpvProperty(
            current,
            "sub-blur",
            style.blur.coerceIn(SUBTITLE_BLUR_MIN, SUBTITLE_BLUR_MAX).toString(),
        )
        NativePlayerBridge.setMpvProperty(current, "sub-italic", if (style.italic) "yes" else "no")
        // ASS/SSA tracks: the override level decides how much of everything else below reaches
        // them at all, and carries its own size factor. Both go through one native call because the
        // bridge has to know which subtitle track is selected before it can apply either.
        NativePlayerBridge.setSubtitleAssStyleMode(
            current,
            style.assStyleMode.mpvValue,
            style.toMpvSubtitleAssScale(),
        )
        NativePlayerBridge.applySubtitleStyle(
            handle = current,
            textColor = style.textColor.toMpvColorString(),
            backgroundColor = style.backgroundColor.toMpvColorString(),
            outlineColor = style.outlineColor.toMpvColorString(),
            outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
            bold = style.bold,
            fontSize = style.toMpvSubtitleFontSize(),
            subPos = effectiveSubtitlePosition(style),
            fontName = style.fontFamily,
        )
        // mpv's sub-shadow-color is an alias of sub-back-color. applySubtitleStyle writes the
        // user's (usually transparent) background to that property, so the shadow colour must be
        // applied afterwards or it is immediately overwritten and the offset appears to do
        // nothing. In outline-and-shadow mode sub-back-color is used for the shadow itself.
        // No forceVideoRedraw here: the bridge's paused subtitle nudge, scheduled by the
        // applySubtitleStyle call above, runs after this write on the same queue. The full redraw
        // hides and re-shows the video window, which flashed black on paused subtitle changes.
        if (style.shadowEnabled && style.backgroundColor.alpha <= 0f) {
            NativePlayerBridge.setMpvProperty(current, "sub-back-color", style.shadowColor.toMpvColorString())
        }
        reapplyCustomSubtitleOverrides(current)
    }

    /**
     * The user's subtitle position, or — while the HUD is showing and the subtitles would reach
     * into its chrome — the highest position whose bottom edge still sits on the clearance line
     * the overlay reported. mpv puts a text subtitle's bottom edge at `sub-pos`% of the height
     * less `sub-margin-y` (22 by default, in 720p-relative units, i.e. a fixed 22/720 of the
     * height at any window size), and multi-line text grows upward from that edge — so it is the
     * only thing that can collide with the controls, whatever the font size. Solving for the edge
     * rather than the block means a subtitle that already clears the chrome, or one only just
     * under it, moves not at all or by exactly the overlap.
     */
    private fun effectiveSubtitlePosition(style: SubtitleStyleState): Int {
        val base = style.toMpvSubtitlePosition()
        val clearance = hudSubtitleClearancePercent
        if (clearance <= 0.0) return base
        val ceiling = floor(clearance + 100.0 * MPV_DEFAULT_SUB_MARGIN_Y / 720.0).toInt().coerceIn(0, 150)
        return minOf(base, ceiling)
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

    /**
     * The selected video and audio tracks as mpv decoded them, for the player badges. Read from
     * the bridge's cached track JSON, so it is cheap to poll. [width]/[height]/[isHdr] come from
     * the caller, which already tracks the decoded frame.
     */
    fun playingMediaFacts(width: Int?, height: Int?, isHdr: Boolean): com.nuvio.app.features.player.PlayingMediaFacts? {
        if (handle == 0L) return null
        decodedWidth = width
        decodedHeight = height
        decodedIsHdr = isHdr
        val video = decodeTracks { NativePlayerBridge.videoTracksJson(it) }.firstOrNull { it.selected }
        val audio = decodeTracks { NativePlayerBridge.audioTracksJson(it) }.firstOrNull { it.selected }
        if (video == null && audio == null) return null
        return com.nuvio.app.features.player.PlayingMediaFacts(
            width = width ?: video?.width?.takeIf { it > 0 },
            height = height ?: video?.height?.takeIf { it > 0 },
            isHdr = isHdr,
            videoCodec = video?.codec?.takeIf(String::isNotBlank),
            dolbyVisionProfile = video?.dvProfile ?: 0,
            audioCodec = audio?.codec?.takeIf(String::isNotBlank),
            audioProfile = audio?.profile?.takeIf(String::isNotBlank),
            audioChannels = audio?.channelCount?.takeIf { it > 0 },
            audioLanguage = audio?.language?.takeIf(String::isNotBlank),
            videoProfile = video?.profile?.takeIf(String::isNotBlank),
            fps = video?.fps?.takeIf { it > 0.0 },
            videoBitrate = video?.bitrate?.takeIf { it > 0 },
            audioTitle = audio?.label?.takeIf(String::isNotBlank),
            audioSampleRate = audio?.sampleRate?.takeIf { it > 0 },
            audioBitrate = audio?.bitrate?.takeIf { it > 0 },
        )
    }

    // The decoded frame as PlayerEngine.desktop last reported it (it is the one that tracks it).
    @Volatile private var decodedWidth: Int? = null
    @Volatile private var decodedHeight: Int? = null
    @Volatile private var decodedIsHdr: Boolean = false

    override fun playingMediaFacts(): com.nuvio.app.features.player.PlayingMediaFacts? =
        playingMediaFacts(decodedWidth, decodedHeight, decodedIsHdr)

    /** Image badges for what is playing, shown in the title block; empty hides them. */
    fun setPlayerBadges(badges: List<com.nuvio.app.features.streams.StreamBadge>) {
        val current = handle.takeIf { it != 0L } ?: return
        val payload = badges.joinToString(prefix = "[", postfix = "]") { badge ->
            // The chip's fill and border as StreamBadgeChip draws them in the stream list.
            val fill = badgeFillCssColorOrNull(badge.tagColor, badge.tagStyle).orEmpty()
            val border = badge.borderColor.toBadgeCssColorOrNull().orEmpty()
            "{\"name\":${badge.name.toJsonString()},\"imageURL\":${badge.imageURL.toJsonString()}," +
                "\"backgroundColor\":${fill.toJsonString()},\"borderColor\":${border.toJsonString()}}"
        }
        NativePlayerBridge.runJavaScript(current, "window.nuvioSetPlayerBadges && window.nuvioSetPlayerBadges($payload)")
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
    val codec: String = "",
    val profile: String = "",
    val channelCount: Int = 0,
    val dvProfile: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val bitrate: Long = 0,
    val sampleRate: Int = 0,
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

/**
 * The two time-based demuxer limits the Metered preset clamps while paused. Both are listed because
 * `cache-secs` overrides `demuxer-readahead-secs` whenever the cache is enabled and the stream is
 * seekable — leaving either one at its preset value would let prefetch continue.
 */
private val METERED_PREFETCH_PROPERTIES = listOf("demuxer-readahead-secs", "cache-secs")

/**
 * Forward prefetch allowed while the Metered preset is paused. Deliberately a small floor rather
 * than 0: lowering these limits never discards packets mpv has already cached (the demuxer only
 * stops appending), so the buffer built before the pause survives it — but if the cache is somehow
 * near-empty, a hard 0 would mean nothing is ever fetched and the player could not resume. One
 * second is well above `cache-pause-wait` (0.25s) and, because the freeze only engages once there
 * is already a resumable buffer, in practice it fetches nothing at all.
 */
private const val METERED_PAUSED_PREFETCH_SECS = "1"

/** Minimum buffer ahead of the playhead before pausing is allowed to freeze prefetch. */
private const val METERED_FREEZE_MIN_BUFFER_MS = 3_000L

private data class DesktopBufferLimits(
    val readaheadSeconds: Int,
    val cacheSeconds: Int,
    val maxBytes: String,
    val maxBackBytes: String,
)

internal fun desktopBufferPresetMpvOptions(
    preset: DesktopBufferPreset,
    hostOs: DesktopHostOs,
    playbackSpeed: Float = 1f,
): List<Pair<String, String>> {
    val limits = when (hostOs) {
        DesktopHostOs.WINDOWS -> when (preset) {
            DesktopBufferPreset.Metered -> DesktopBufferLimits(10, 10, "32MiB", "8MiB")
            DesktopBufferPreset.LowData -> DesktopBufferLimits(15, 30, "64MiB", "16MiB")
            DesktopBufferPreset.Balanced -> DesktopBufferLimits(60, 120, "256MiB", "64MiB")
            DesktopBufferPreset.Resilient -> DesktopBufferLimits(180, 600, "1GiB", "128MiB")
        }
        DesktopHostOs.MACOS -> when (preset) {
            DesktopBufferPreset.Metered -> DesktopBufferLimits(8, 8, "24MiB", "8MiB")
            DesktopBufferPreset.LowData -> DesktopBufferLimits(10, 10, "32MiB", "8MiB")
            DesktopBufferPreset.Balanced -> DesktopBufferLimits(20, 20, "48MiB", "12MiB")
            // Preserve the previous macOS defaults for existing installations.
            DesktopBufferPreset.Resilient -> DesktopBufferLimits(30, 30, "64MiB", "16MiB")
        }
        DesktopHostOs.LINUX,
        DesktopHostOs.UNKNOWN,
        -> return emptyList()
    }
    val factor = playbackSpeed.coerceAtLeast(1f)
    return listOf(
        "demuxer-readahead-secs" to (limits.readaheadSeconds * factor).toString(),
        "cache-secs" to (limits.cacheSeconds * factor).toString(),
        "demuxer-max-bytes" to limits.maxBytes,
        "demuxer-max-back-bytes" to limits.maxBackBytes,
        // This is mpv's tiny demuxer/I/O ring buffer, not its media cache. Large values can turn
        // forward seeks into sequential reads. Keep it fixed and let the demuxer limits above own
        // actual resilience; 1 MiB is already 8x mpv's 128 KiB default.
        "stream-buffer-size" to "1MiB",
        // Media buffered before (re)starting playback. Scale content-time with playback speed so
        // wall-clock startup remains equally quick at faster rates.
        "cache-pause-wait" to (0.25f * factor).toString(),
    )
}

internal fun desktopCustomMpvOptionNames(options: String): Set<String> =
    options.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull { option ->
            option.indexOf('=')
                .takeIf { it > 0 }
                ?.let { separator -> option.substring(0, separator) }
        }
        .toSet()

/** The four HUD colour-panel steppers. Each carries its delta as the command value. */
private val DESKTOP_COLOR_GRADE_COMMANDS = setOf(
    "adjustDesktopColorContrast",
    "adjustDesktopColorBrightness",
    "adjustDesktopColorSaturation",
    "adjustDesktopColorGamma",
)

internal fun shouldApplyNuvioRuntimeMpvProperty(
    configMode: DesktopMpvConfigMode,
    customOptionNames: Set<String>,
    propertyName: String,
): Boolean = when (configMode) {
    DesktopMpvConfigMode.Full -> false
    DesktopMpvConfigMode.Replace -> propertyName !in customOptionNames
    DesktopMpvConfigMode.Off,
    DesktopMpvConfigMode.Add,
    -> true
}

// Subtitle drop-shadow tuning (mpv scaled pixels + #AARRGGBB). The colour must be applied after
// the subtitle background because mpv aliases sub-shadow-color to sub-back-color. Offset and colour
// are now user-controlled (SubtitleStyleState.shadowOffset / shadowColor); the old 1.5 px / #66000000
// hardcodes live on as the model defaults.
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

// mpv's sub-scale is a plain multiplier; the UI stores it as a percentage so it round-trips
// through the integer settings store. Clamped to the UI's own range so a corrupt persisted value
// cannot render subtitles invisibly small or absurdly large.
private fun SubtitleStyleState.toMpvSubtitleAssScale(): Double =
    assScalePercent.coerceIn(SUBTITLE_ASS_SCALE_MIN, SUBTITLE_ASS_SCALE_MAX) / 100.0

// mpv's default `sub-margin-y`; nothing in the player sets it, and it has no property getter on
// the bridge. Only effectiveSubtitlePosition depends on it, and only to a fraction of a percent.
private const val MPV_DEFAULT_SUB_MARGIN_Y = 22.0

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
    val mediaTitle: String = "",
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
private fun buildDesktopUserMpvOptions(initialPlaybackSpeed: Float): List<String> {
    val settings = PlayerSettingsRepository.uiState.value
    return buildList {
        add("@nuvio-config-mode=${settings.desktopMpvConfigMode.name.lowercase()}")
        // Resolved natively: only the bridge can ask DXGI what the adapter actually has, and the
        // trimmed pipeline has to be in place before mpv_initialize (the first frame is the one that
        // would abort). Windows-only marker — the macOS bridge forwards unknown entries to mpv.
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            add("@nuvio-low-vram=${settings.desktopLowVramMode.name.lowercase()}")
        }
        val useNuvioOptions = settings.desktopMpvConfigMode != DesktopMpvConfigMode.Full
        if (useNuvioOptions) {
            // These must be options, not post-create properties. Native creation does not return
            // until after loadfile has opened/probed the URL, and stream-buffer-size in particular
            // is captured by the stream when it opens. Applying the preset afterward left the
            // native 256 MiB fallback active and made ordinary forward seeks read/discard hundreds
            // of megabytes instead of issuing an HTTP range request.
            desktopBufferPresetMpvOptions(
                preset = settings.desktopBufferPreset,
                hostOs = DesktopHostOs.current,
                playbackSpeed = initialPlaybackSpeed,
            ).forEach { (name, value) -> add("$name=$value") }
            // Nuvio's stats overlay in place of the built-in one (same script name, so the
            // diagnostics binding still finds it). `scripts`, not `scripts-append`: mpv's option
            // API rejects the -append suffix. A `scripts=` line in the custom options box is
            // applied later and replaces this, which leaves no overlay; so would a failed export,
            // hence the built-in is only switched off when ours is there.
            DesktopMpvScripts.statsScriptPath?.let {
                add("load-stats-overlay=no")
                add("scripts=$it")
            }
        }
        // Let mpv select an embedded preferred-language subtitle as part of file loading. Waiting
        // for the Compose-side track poll is unnecessarily fragile for tracks already present in
        // the container, and can leave `sid=no` active if startup takes longer than that poll.
        // Per-title persisted selection still runs afterward and can override this default.
        val preferredSubtitleLanguages = primarySubtitleTargetsForSettings(settings)
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
            // Options are built before the player screen's own meta fetch can have finished, so an
            // "Original" preference relies on the detail screen having recorded it. When it has
            // not, the Compose-side track pass corrects the choice once tracks are published.
            originalLanguage = OriginalLanguageCache.current,
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
            settings.desktopMpvPropertyOverrides.forEach { (key, value) ->
                if (key !in DESKTOP_PROFILE_OWNED_OVERRIDE_KEYS) add("$key=$value")
            }
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
 * How long mpv waits on a silent connection to a debrid CDN before giving up, in place of mpv's
 * 60 s default. These hosts answer a range request in well under a second (TorBox store nodes
 * measured 0.1-0.6 s), so a minute of silence is a stalled node, not a slow one: on 2026-10-03
 * `store-046.wnam.tb-cdn.io` held five opens for the full 60 s each. Failing in 15 s lets the
 * TorBox node hop or failover start while the user is still waiting. Mid-playback it also brings
 * FFmpeg's own `reconnect=1` retry forward, since a timed-out read of a partly read file
 * reconnects at the same offset. Resolver and addon endpoints keep the default: a resolve can
 * legitimately take 10 s or more (Debridio once took 14 s waiting on a throttled TorBox call).
 */
internal const val DIRECT_MEDIA_NETWORK_TIMEOUT_SECONDS = 15

/**
 * mpv's diagnostics overlay prints the `filename` property, which is the last path segment of the
 * URL it opened and ignores `force-media-title`. A debrid CDN link ends in an opaque hash plus the
 * account token (`/dld/<hash>?token=...`), so that is what the overlay showed.
 *
 * Appending `#/<title>` gives mpv a readable basename. FFmpeg's http protocol drops the fragment
 * from the request line, so the server sees the original URL. Limited to direct-media hosts and to
 * non-manifest paths: an HLS/DASH manifest resolves its relative segment URLs against the base URL,
 * and a `/` inside the fragment would corrupt that base.
 */
internal fun withMpvFilenameHint(url: String, title: String): String {
    val name = title.trim().replace(Regex("[\\\\/\\s]+"), " ").trim()
    if (name.isEmpty() || '#' in url) return url
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return url
    if (!PlaybackRedirectResolver.isDirectMediaHost(url)) return url
    val path = url.substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return url
    return "$url#/${java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")}"
}

internal fun directMediaNetworkTimeoutOption(sourceUrl: String): String? =
    if (PlaybackRedirectResolver.isDirectMediaHost(sourceUrl)) {
        "network-timeout=$DIRECT_MEDIA_NETWORK_TIMEOUT_SECONDS"
    } else {
        null
    }

/**
 * Web (non-debrid) addon streams are served by streaming-site CDNs that reject libmpv's default
 * `User-Agent`. Stremio never hits this because its local streaming server makes the origin
 * request; we hand the URL to mpv directly, so supply a browser-like UA whenever the stream's
 * `proxyHeaders` didn't specify one. ffmpeg's http protocol only adds its own User-Agent when the
 * custom header block lacks one, so an addon-supplied UA (or this default) is sent exactly once.
 */
internal const val DESKTOP_PLAYBACK_FALLBACK_USER_AGENT =
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

private val controlsJsonLog = Logger.withTag("PlayerControlsJson")

private val audioPassthroughLog = Logger.withTag("PlayerAudioPassthrough")

private val episodeArtworkLog = Logger.withTag("PlayerEpisodeArtwork")

/**
 * Guards the hand-built controls payload, which is assembled by [toControlsJson] as a raw string
 * with a manual `append(',')` between every field and injected as
 * `window.playerControls(JSON.parse("…"))`.
 *
 * A single missed separator makes the whole document invalid, and the HUD's failure is total and
 * silent: `JSON.parse` throws inside the WebView, `window.playerControls` never runs, and the
 * overlay keeps whatever its initial render produced. What that looks like from the outside is
 * every player setting reading as its compile-time default — scale 0, no media title, failover off,
 * colour profile Neutral — while the settings screen and the playback engine show the real values,
 * because those read the repository and never touch this JSON. There is no `window.onerror` in
 * controls.js and no console bridge, so nothing is logged on the web side at all.
 *
 * That has now happened twice (`submitIntroSegmentPreviewLabel`, `themeAccentFill`), both times
 * costing a long hunt through the wrong layer. Parsing here turns it into one log line naming the
 * offending field. The payload is only rebuilt when the structure key actually changes, so this is
 * not on the per-frame path.
 */
internal fun controlsJsonIsWellFormed(payload: String): Boolean {
    val error = runCatching { Json.parseToJsonElement(payload) }.exceptionOrNull() ?: return true
    controlsJsonLog.e(error) {
        "Controls payload is not valid JSON, so the HUD would silently keep its defaults. " +
            "Almost certainly a missing append(',') in toControlsJson near: " +
            payloadExcerptAroundFailure(payload, error.message.orEmpty())
    }
    return false
}

/**
 * kotlinx reports the byte offset it gave up at, which is the cheapest possible pointer at the
 * field whose separator is missing — the name is a few characters to its left. Falls back to the
 * head of the payload when the message carries no offset.
 */
internal fun payloadExcerptAroundFailure(payload: String, message: String): String {
    val offset = Regex("offset (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: return payload.take(200)
    return payload.substring(
        (offset - 160).coerceAtLeast(0),
        (offset + 40).coerceAtMost(payload.length),
    )
}

// internal (not private) so ControlsJsonGuardTest can check whole payloads, not just samples.
internal fun PlayerControlsState.toControlsJson(
    appFullscreenKeyCode: Int,
    playerShortcutKeyCodes: Map<String, Int>,
    skipIntervalKeyLabel: String,
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
        appendJsonField("pauseOverlaySourceEnabled", pauseOverlaySourceEnabled)
        append(',')
        appendJsonArrayField("pauseOverlayCast", pauseOverlayCast) { appendCastItemJson(it) }
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
        appendJsonField("desktopColorContrast", desktopColorContrast)
        append(',')
        appendJsonField("desktopColorBrightness", desktopColorBrightness)
        append(',')
        appendJsonField("desktopColorSaturation", desktopColorSaturation)
        append(',')
        appendJsonField("desktopColorGamma", desktopColorGamma)
        append(',')
        appendJsonField("desktopAnimeModeLabel", desktopAnimeModeLabel)
        append(',')
        appendJsonField("desktopAnimeSvpEnabled", desktopAnimeSvpEnabled)
        append(',')
        appendJsonField("rtxVsrEnabled", rtxVsrEnabled)
        append(',')
        appendJsonField("rtxTrueHdrEnabled", rtxTrueHdrEnabled)
        append(',')
        appendJsonField("playbackInfoPanelEnabled", playbackInfoPanelEnabled)
        append(',')
        appendJsonField("activeSubtitleLabel", activeSubtitleLabel)
        append(',')
        appendJsonField("seekThumbnailsEnabled", seekThumbnailsEnabled)
        append(',')
        appendJsonField("seekrVttUrl", seekrVttUrl)
        append(',')
        appendJsonField("seekrScale", seekrScale)
        append(',')
        appendJsonField("seekStepSeconds", seekStepSeconds)
        append(',')
        append("\"iptv\":")
        iptv?.let { appendIptvStateJson(it) } ?: append("null")
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
        appendJsonField("submitIntroSegmentPreviewLabel", submitIntroSegmentPreviewLabel)
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
        appendJsonField("downloadSubtitleLabel", downloadSubtitleLabel)
        append(',')
        appendJsonField("autoSyncedSubtitleLabel", autoSyncedSubtitleLabel)
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
        appendJsonField("outlineWidthLabel", outlineWidthLabel)
        append(',')
        appendJsonField("shadowLabel", shadowLabel)
        append(',')
        appendJsonField("shadowOffsetLabel", shadowOffsetLabel)
        append(',')
        appendJsonField("shadowColorLabel", shadowColorLabel)
        append(',')
        appendJsonField("shadowIntensityLabel", shadowIntensityLabel)
        append(',')
        appendJsonField("blurLabel", blurLabel)
        append(',')
        appendJsonField("boldLabel", boldLabel)
        append(',')
        appendJsonField("italicLabel", italicLabel)
        append(',')
        appendJsonField("bottomOffsetLabel", bottomOffsetLabel)
        append(',')
        appendJsonField("assStyleModeLabel", assStyleModeLabel)
        append(',')
        appendJsonField("assScaleLabel", assScaleLabel)
        append(',')
        appendJsonField("assStyleModeValueLabel", assStyleModeValueLabel)
        append(',')
        // The level list itself, so the panel's dropdown is built from the enum rather than from a
        // copy of it kept in the overlay that could drift out of order.
        appendJsonArrayField("subtitleAssStyleModes", SubtitleAssStyleMode.entries.toList()) { mode ->
            append('{')
            appendJsonField("value", mode.name)
            append(',')
            appendJsonField("label", mode.label)
            append('}')
        }
        append(',')
        appendJsonField("colorLabel", colorLabel)
        append(',')
        appendJsonField("textOpacityLabel", textOpacityLabel)
        append(',')
        appendJsonField("outlineColorLabel", outlineColorLabel)
        append(',')
        appendJsonField("backgroundColorLabel", backgroundColorLabel)
        append(',')
        appendJsonField("resetDefaultsLabel", resetDefaultsLabel)
        append(',')
        appendJsonField("onLabel", onLabel)
        append(',')
        appendJsonField("offLabel", offLabel)
        append(',')
        appendJsonField("posterHighlightMode", posterHighlightMode)
        append(',')
        appendJsonField("themeAccentColor", themeAccentColor)
        append(',')
        appendJsonField("themeAccentStrongColor", themeAccentStrongColor)
        append(',')
        appendJsonField("themeAccentFill", themeAccentFill)
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
        appendJsonField("minimalHudEnabled", minimalHudEnabled)
        append(',')
        appendJsonField("minimalHudPillsEnabled", minimalHudPillsEnabled)
        append(',')
        appendJsonField("ultraHudEnabled", ultraHudEnabled)
        append(',')
        appendJsonField("officialHudEnabled", officialHudEnabled)
        append(',')
        appendJsonField("seekHandleEnabled", seekHandleEnabled)
        append(',')
        appendJsonField("hudVignetteEnabled", hudVignetteEnabled)
        append(',')
        appendJsonField("hiddenHudButtons", hiddenHudButtons)
        append(',')
        appendJsonField("hudButtonOrder", hudButtonOrder)
        append(',')
        appendJsonField("alwaysShowClock", alwaysShowClock)
        append(',')
        appendJsonField("playbackSpeedFineIncrementsEnabled", playbackSpeedFineIncrementsEnabled)
        append(',')
        appendJsonField("playbackSpeedToggleLow", playbackSpeedToggleLow)
        append(',')
        appendJsonField("playbackSpeedToggleHigh", playbackSpeedToggleHigh)
        append(',')
        appendJsonField("appFullscreenKeyCode", appFullscreenKeyCode)
        append(',')
        appendJsonMapField("playerShortcutKeyCodes", playerShortcutKeyCodes)
        append(',')
        appendJsonField("uiScalePercent", uiScalePercent)
        append(',')
        appendJsonField("controlIconScalePercent", controlIconScalePercent)
        append(',')
        appendJsonField("uiFontFamily", uiFontFamily)
        append(',')
        appendJsonField("sourceNotchPosition", sourceNotchPosition)
        append(',')
        appendJsonField("sourceNotchHoverEnabled", sourceNotchHoverEnabled)
        append(',')
        appendJsonField("notificationPosition", notificationPosition)
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
        appendJsonField("skipKeyAction", skipKeyAction)
        append(',')
        appendJsonField("skipIntervalKeyLabel", skipIntervalKeyLabel)
        append(',')
        appendJsonField("skipSubmitToastVisible", skipSubmitToast.visible)
        append(',')
        appendJsonField("skipSubmitToastPhase", skipSubmitToast.phase)
        append(',')
        appendJsonField("skipSubmitToastTitle", skipSubmitToast.title)
        append(',')
        appendJsonField("skipSubmitToastDetail", skipSubmitToast.detail)
        append(',')
        appendJsonField("skipSubmitToastHint", skipSubmitToast.hint)
        append(',')
        appendJsonField("skipSubmitToastAccepted", skipSubmitToast.accepted)
        append(',')
        appendJsonField("skipSubmitToastKey", skipSubmitToast.key)
        append(',')
        appendJsonField("skipSubmitToastDismissible", skipSubmitToastDismissible)
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
        appendJsonField("playlistPeekTitle", playlistPeekTitle)
        append(',')
        appendJsonArrayField("playlistPeekItems", playlistPeekItems) { appendPlaylistPeekItemJson(it) }
        append(',')
        appendJsonPartyField("party", party)
        append(',')
        appendStreamInfoField(streamInfo)
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
        appendJsonArrayField("sourceSortOptions", sourceSortOptions) { appendFilterItemJson(it) }
        append(',')
        appendJsonField("sourceSortLabel", sourceSortLabel)
        append(',')
        appendJsonField("sourceCachedFirst", sourceCachedFirst)
        append(',')
        appendJsonArrayField("episodeItems", episodeItems) { appendEpisodeItemJson(it) }
        append(',')
        appendJsonField("episodeFallbackThumbnail", episodeFallbackThumbnail)
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
        appendJsonField("audioTrackFilterActive", audioTrackFilterActive)
        append(',')
        appendJsonArrayField("audioTrackItems", audioTrackItems) { appendAudioTrackItemJson(it) }
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
        appendJsonArrayField("subtitleBackgroundColorSwatches", SubtitleBackgroundColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonArrayField("subtitleShadowColorSwatches", SubtitleShadowColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonField("closeModalsToken", closeModalsToken)
        append(',')
        appendJsonField("openSourcesToken", openSourcesToken)
        append(',')
        appendJsonField("sourcesPanelOpen", sourcesPanelOpen)
        append(',')
        appendJsonField("heroTrailerMode", heroTrailer.enabled)
        append(',')
        appendJsonField("heroTrailerBackgroundColor", heroTrailer.backgroundColor)
        append(',')
        appendJsonField("heroTrailerLogoUrl", heroTrailer.logoUrl)
        append(',')
        appendJsonField("heroTrailerTitle", heroTrailer.title)
        append(',')
        appendJsonField("heroTrailerMeta", heroTrailer.meta)
        append(',')
        appendJsonField("heroTrailerDescription", heroTrailer.description)
        append(',')
        appendJsonField("heroTrailerMuted", heroTrailer.muted)
        append(',')
        appendJsonField("heroTrailerVolume", heroTrailer.volume)
        append(',')
        appendJsonField("heroTrailerNavDismissEdge", heroTrailer.navDismissEdge)
        append(',')
        appendJsonField("heroTrailerNavDismissBandFraction", heroTrailer.navDismissBandFraction.toDouble())
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
        heroTrailer = heroTrailer.copy(volume = 0),
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

private fun StringBuilder.appendJsonPartyField(name: String, party: PlayerControlPartyState) {
    append('"').append(name).append("\":{")
    appendJsonField("available", party.available)
    append(',')
    appendJsonField("active", party.active)
    append(',')
    appendJsonField("openPanelToken", party.openPanelToken)
    append(',')
    appendJsonField("busy", party.busy)
    append(',')
    appendJsonField("connected", party.connected)
    append(',')
    appendJsonField("isHost", party.isHost)
    append(',')
    appendJsonField("canControl", party.canControl)
    append(',')
    appendJsonField("hostOnly", party.hostOnly)
    append(',')
    appendJsonField("sharing", party.sharing)
    append(',')
    appendJsonField("canShareStream", party.canShareStream)
    append(',')
    appendJsonField("sameMedia", party.sameMedia)
    append(',')
    appendJsonField("code", party.code)
    append(',')
    appendJsonField("pillMessage", party.pillMessage)
    append(',')
    appendJsonField("behindTitle", party.behindTitle)
    append(',')
    appendJsonField("elsewhereTitle", party.elsewhereTitle)
    append(',')
    appendJsonField("startNote", party.startNote)
    append(',')
    appendJsonArrayField("members", party.members) { appendPartyMemberJson(it) }
    append(',')
    appendJsonField("chatUnread", party.chatUnread)
    append(',')
    appendJsonArrayField("chatMessages", party.chatMessages) { appendPartyChatMessageJson(it) }
    append(',')
    append("\"labels\":{")
    party.labels.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) append(',')
        appendJsonField(key, value)
    }
    append("}}")
}

private fun StringBuilder.appendPartyChatMessageJson(message: com.nuvio.app.features.player.PlayerControlChatMessage) {
    append('{')
    appendJsonField("key", message.key)
    append(',')
    appendJsonField("name", message.name)
    append(',')
    appendJsonField("initials", message.initials)
    append(',')
    appendJsonField("color", message.color)
    append(',')
    appendJsonField("avatarUrl", message.avatarUrl)
    append(',')
    appendJsonField("body", message.body)
    append(',')
    appendJsonField("time", message.time)
    append(',')
    appendJsonField("mine", message.mine)
    append(',')
    appendJsonField("sending", message.sending)
    append(',')
    appendJsonField("failed", message.failed)
    append(',')
    appendJsonField("startsGroup", message.startsGroup)
    append('}')
}

private fun StringBuilder.appendStreamInfoField(info: com.nuvio.app.features.player.PlayerControlStreamInfo?) {
    append("\"streamInfo\":")
    if (info == null) {
        append("null")
        return
    }
    append('{')
    appendJsonField("title", info.title)
    append(',')
    appendJsonField("closeLabel", info.closeLabel)
    append(',')
    appendJsonArrayField("sections", info.sections) { section ->
        append('{')
        appendJsonField("title", section.title)
        append(',')
        appendJsonArrayField("rows", section.rows) { row ->
            append('{')
            appendJsonField("label", row.label)
            append(',')
            appendJsonField("value", row.value)
            append('}')
        }
        append('}')
    }
    append('}')
}

private fun StringBuilder.appendPartyMemberJson(member: PlayerControlPartyMember) {
    append('{')
    appendJsonField("index", member.index)
    append(',')
    appendJsonField("name", member.name)
    append(',')
    appendJsonField("initials", member.initials)
    append(',')
    appendJsonField("color", member.color)
    append(',')
    appendJsonField("avatarUrl", member.avatarUrl)
    append(',')
    appendJsonField("status", member.status)
    append(',')
    appendJsonField("presence", member.presence)
    append(',')
    appendJsonField("isHost", member.isHost)
    append(',')
    appendJsonField("isSelf", member.isSelf)
    append(',')
    appendJsonField("canRemove", member.canRemove)
    append('}')
}

private fun StringBuilder.appendPlaylistPeekItemJson(item: PlayerControlPlaylistItem) {
    append('{')
    appendJsonField("position", item.position)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("subtitle", item.subtitle)
    append(',')
    appendJsonField("state", item.state)
    append('}')
}

private fun StringBuilder.appendIptvStateJson(iptv: com.nuvio.app.features.player.PlayerControlIptvState) {
    append('{')
    appendJsonField("channelLabel", iptv.channelLabel)
    append(',')
    appendJsonField("nowTitle", iptv.nowTitle)
    append(',')
    appendJsonField("nowTimeLabel", iptv.nowTimeLabel)
    append(',')
    appendJsonField("nowProgress", iptv.nowProgress.toDouble())
    append(',')
    appendJsonField("nextLabel", iptv.nextLabel)
    append(',')
    appendJsonField("liveLabel", iptv.liveLabel)
    append(',')
    appendJsonField("goLiveLabel", iptv.goLiveLabel)
    append(',')
    appendJsonField("previousChannelLabel", iptv.previousChannelLabel)
    append(',')
    appendJsonField("nextChannelLabel", iptv.nextChannelLabel)
    append(',')
    appendJsonField("exitLabel", iptv.exitLabel)
    append(',')
    appendJsonField("rewindWindowMs", iptv.rewindWindowMs)
    append(',')
    appendJsonField("channelsLabel", iptv.channelsLabel)
    append(',')
    appendJsonField("groupsLabel", iptv.groupsLabel)
    append(',')
    appendJsonField("searchLabel", iptv.searchLabel)
    append(',')
    appendJsonField("channelsNote", iptv.channelsNote)
    append(',')
    appendJsonArrayField("groups", iptv.groups) { group ->
        append('{')
        appendJsonField("index", group.index.toLong())
        append(',')
        appendJsonField("name", group.name)
        append(',')
        appendJsonField("count", group.count.toLong())
        append(',')
        appendJsonField("selected", group.selected)
        append('}')
    }
    append(',')
    appendJsonArrayField("channels", iptv.channels) { channel ->
        append('{')
        appendJsonField("uid", channel.uid.toLong())
        append(',')
        appendJsonField("num", channel.num.toLong())
        append(',')
        appendJsonField("name", channel.name)
        append(',')
        appendJsonField("logo", channel.logo)
        append(',')
        appendJsonField("nowTitle", channel.nowTitle)
        append(',')
        appendJsonField("playing", channel.playing)
        append('}')
    }
    append('}')
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
    // Omitted entirely when scoring's badge is off, so the HUD can simply test for presence.
    item.score?.let {
        append(',')
        appendJsonField("score", it)
        append(',')
        appendJsonField("scoreRejected", item.scoreRejected)
    }
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
    if (item.fillerKind.isNotEmpty()) {
        append(',')
        appendJsonField("fillerKind", item.fillerKind)
        append(',')
        appendJsonField("fillerLabel", item.fillerLabel)
    }
    append('}')
}

private fun StringBuilder.appendCastItemJson(item: PlayerControlCastItem) {
    append('{')
    appendJsonField("name", item.name)
    append(',')
    appendJsonField("role", item.role)
    append(',')
    appendJsonField("photo", item.photo)
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
    append(',')
    appendJsonField("isDownloading", item.isDownloading)
    append(',')
    appendJsonField("isAutoSynced", item.isAutoSynced)
    append('}')
}

private fun StringBuilder.appendAudioTrackItemJson(item: PlayerControlAudioTrackItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("languageLabel", item.languageLabel)
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
    appendJsonField("outlineWidth", style.outlineWidth)
    append(',')
    appendJsonField("shadowEnabled", style.shadowEnabled)
    append(',')
    appendJsonField("shadowColor", style.shadowColor.toStorageHexString())
    append(',')
    appendJsonField("shadowOffset", style.shadowOffset)
    append(',')
    appendJsonField("blur", style.blur)
    append(',')
    appendJsonField("bold", style.bold)
    append(',')
    appendJsonField("italic", style.italic)
    append(',')
    appendJsonField("fontSizeSp", style.fontSizeSp)
    append(',')
    appendJsonField("bottomOffset", style.bottomOffset)
    append(',')
    appendJsonField("fontFamily", style.fontFamily)
    append(',')
    // The enum name, not its label: the panel resolves the display name through the mode list it
    // was sent, and `assStyleModeValueLabel` at the top level carries the label for the menu tick.
    appendJsonField("assStyleMode", style.assStyleMode.name)
    append(',')
    appendJsonField("assScalePercent", style.assScalePercent)
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
