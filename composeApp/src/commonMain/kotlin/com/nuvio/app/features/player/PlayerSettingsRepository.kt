package com.nuvio.app.features.player

import com.nuvio.app.core.ui.frameBudgetSetProbesEnabled
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.player.skip.SkipAutoAcceptMode
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.STREAM_PREFETCH_DEFAULT_CACHE_MINUTES
import com.nuvio.app.features.streams.STREAM_PREFETCH_CACHE_MINUTE_VALUES
import com.nuvio.app.features.streams.StreamPrefetchCache
import com.nuvio.app.features.streams.StreamPrefetchService
import com.nuvio.app.features.streams.StreamPrefetchScope
import com.nuvio.app.features.streams.StreamAutoPlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

val STREAM_AUTO_PLAY_TIMEOUT_VALUES: List<Int> = listOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, Int.MAX_VALUE
)

/** Seek-button / arrow-key jump distance, in seconds. */
const val DefaultSeekStepSeconds = 10
val SEEK_STEP_SECONDS_RANGE: IntRange = 1..60

/**
 * Range of the [DesktopColorProfile.Custom] equalizer offsets. mpv accepts +/-100 on contrast,
 * brightness, saturation and gamma, but the shipped presets live inside +/-15 and anything past 50
 * destroys the image rather than grading it, so the sliders stop there.
 */
val DESKTOP_COLOR_OFFSET_RANGE: IntRange = -50..50

/** Allowed wait durations (seconds) before failover gives up on a stalling stream and tries the next. */
val STREAM_FAILOVER_TIMEOUT_VALUES: List<Int> = listOf(5, 10, 15, 20, 25, 30, 45, 60)
const val STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS = 10

/**
 * Allowed wait durations (seconds) before the TV-mode home hero swaps to the focused
 * item's trailer.
 */
val HERO_TV_TRAILER_DELAY_VALUES: List<Int> = listOf(1, 2, 3, 5, 8, 10, 15)

/** Snaps [value] to the nearest allowed delay in [HERO_TV_TRAILER_DELAY_VALUES]. */
fun snapToHeroTvTrailerDelay(value: Int): Int =
    HERO_TV_TRAILER_DELAY_VALUES.minByOrNull { abs(it - value) } ?: 5

/**
 * Snaps [value] to the nearest allowed timeout value in [STREAM_AUTO_PLAY_TIMEOUT_VALUES].
 * Ties break to the lower value. Negative values snap to 0.
 */
fun snapToAllowedTimeout(value: Int): Int {
    if (value <= 0) return 0
    var bestValue = STREAM_AUTO_PLAY_TIMEOUT_VALUES[0]
    var bestDistance = Long.MAX_VALUE
    for (allowed in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
        val distance = abs(value.toLong() - allowed.toLong())
        if (distance < bestDistance || (distance == bestDistance && allowed < bestValue)) {
            bestDistance = distance
            bestValue = allowed
        }
    }
    return bestValue
}

data class PlayerSettingsUiState(
    val showLoadingOverlay: Boolean = true,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    val defaultPlaybackSpeed: Float = 1f,
    val mouseMoveRevealsControlsEnabled: Boolean = true,
    val desktopLegacyHudEnabled: Boolean = false,
    val desktopMinimalHudEnabled: Boolean = false,
    val desktopUltraHudEnabled: Boolean = false,
    val desktopOfficialHudEnabled: Boolean = false,
    // Minimal layout only: each control group sits on its own translucent pill.
    val desktopMinimalHudPillsEnabled: Boolean = false,
    // Draw the round handle on the seek bar; off leaves the coloured fill edge as the marker.
    val desktopSeekHandleEnabled: Boolean = true,
    // The dark top/bottom edge gradients that fade in with the HUD so its controls stay legible.
    val desktopHudVignetteEnabled: Boolean = true,
    val desktopAlwaysShowClockEnabled: Boolean = false,
    // Adds the playing source (release name + provider) as the bottom row of the paused metadata
    // overlay. Off by default: the raw release strings are noisy, and only some viewers want them.
    val desktopPauseOverlaySourceEnabled: Boolean = false,
    // When true, the desktop speed button / speed keyboard shortcuts step by 0.1 instead of
    // jumping between the coarse preset stages (1, 1.25, 1.5, 2, 3, 4).
    val desktopPlaybackSpeedFineIncrementsEnabled: Boolean = false,
    // The two speeds the "Toggle playback speed" shortcut flips between. Both are user-set, so the
    // pair can be 1.2 / 1.6 just as well as the 1x / 2x default.
    val playbackSpeedToggleLow: Float = 1f,
    val playbackSpeedToggleHigh: Float = 2f,
    // When true, mpv writes its full verbose log to logs/mpv-verbose.log (via the mpv --log-file
    // option) for troubleshooting, instead of the bridge's normal warnings-only capture.
    val desktopVerboseMpvLoggingEnabled: Boolean = false,
    // Extra user UI-scale for the desktop/legacy player HUD, in percent (-50..50); 0 = unchanged.
    val desktopUiScalePercent: Int = 0,
    // Extra scale for just the bottom control row's buttons (transport + action icons), in percent
    // (-50..50) on top of desktopUiScalePercent; 0 = unchanged.
    val desktopControlIconScalePercent: Int = 0,
    // How far the seek buttons / arrow-key shortcuts jump, in seconds.
    val seekStepSeconds: Int = DefaultSeekStepSeconds,
    val desktopSourceNotchPosition: DesktopSourceNotchPosition = DesktopSourceNotchPosition.Right,
    // When false the notch only opens Sources on a click, so a pointer crossing that screen edge on
    // its way to another monitor no longer pulls the panel open.
    val desktopSourceNotchHoverEnabled: Boolean = true,
    // Where the transient player pills (playback speed, volume, aspect ratio) are drawn.
    val desktopPlayerNotificationPosition: DesktopPlayerNotificationPosition =
        DesktopPlayerNotificationPosition.Center,
    val externalPlayerEnabled: Boolean = false,
    val externalPlayerForwardSubtitles: Boolean = false,
    val externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId(),
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val dualSubtitlesEnabled: Boolean = false,
    val preferredSubtitleTrackKind: SubtitleTrackKind = SubtitleTrackKind.DEFAULT,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    // Automatic selection reaches for a matching addon subtitle before the video's own tracks;
    // embedded tracks are only auto-selected when no addon subtitle matches. Off keeps the
    // historical order (built-in wins, addons fill the gap).
    val preferAddonSubtitles: Boolean = false,
    // Track kinds ruled out by name — signs/songs/karaoke/forced subtitles, commentary and
    // audio-description tracks. Rejected tracks are hidden from the player's lists and never
    // selected automatically. See PlayerTrackRejectKeywords.kt.
    val rejectedSubtitleKeywords: Set<SubtitleRejectKeyword> = emptySet(),
    val rejectedAudioKeywords: Set<AudioRejectKeyword> = emptySet(),
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val streamPrefetchScope: StreamPrefetchScope = StreamPrefetchScope.OFF,
    val streamPrefetchCacheMinutes: Int = STREAM_PREFETCH_DEFAULT_CACHE_MINUTES,
    val streamPrefetchResolveLinks: Boolean = true,
    val decoderPriority: Int = 1,
    val nvidiaRtxSuperResolutionEnabled: Boolean = false,
    val nvidiaRtxHdrEnabled: Boolean = false,
    val mapDV7ToHevc: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlaySelectedPlugins: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val skipIntroEnabled: Boolean = true,
    val skipAutoAcceptMode: SkipAutoAcceptMode = SkipAutoAcceptMode.MANUAL,
    val skipMovieCreditsToPostCredits: Boolean = false,
    val stripSdhSubtitles: Boolean = false,
    val animeSkipEnabled: Boolean = false,
    val animeSkipClientId: String = "",
    val introDbApiKey: String = "",
    val seekrApiKey: String = "",
    val skipDbApiKey: String = "",
    val introSubmitEnabled: Boolean = false,
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    // Whether MANUAL stream selection also governs next-episode transitions. The in-player
    // next-episode path has always substituted FIRST_STREAM for MANUAL so a transition can never
    // stall on a picker; this opts out of that substitution. Off by default: it changes what a
    // default install does at every episode boundary.
    val streamAutoPlayManualNextEpisode: Boolean = false,
    val streamAutoPlayPreferBingeGroup: Boolean = true,
    val streamAutoPlayReuseBingeGroup: Boolean = false,
    // If a stream fails (playback error or never starts within the timeout), automatically try the
    // next stream in the source list instead of exiting. Opt-in.
    val streamFailoverEnabled: Boolean = false,
    val streamFailoverTimeoutSeconds: Int = STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val useLibass: Boolean = false,
    val libassRenderType: String = "CUES",
    val iosVideoOutputPreset: IosVideoOutputPreset = IosVideoOutputPreset.NativeEdr,
    val iosToneMappingMode: IosToneMappingMode = IosToneMappingMode.Auto,
    val iosTargetPrimaries: IosTargetPrimaries = IosTargetPrimaries.Auto,
    val iosTargetTransfer: IosTargetTransfer = IosTargetTransfer.Auto,
    val iosHardwareDecoderMode: IosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox,
    val iosAudioOutputMode: IosAudioOutputMode = IosAudioOutputMode.Auto,
    val iosExtendedDynamicRangeEnabled: Boolean = true,
    val iosTargetColorspaceHintEnabled: Boolean = true,
    val iosHdrComputePeakEnabled: Boolean = true,
    val iosDebandEnabled: Boolean = false,
    val iosInterpolationEnabled: Boolean = false,
    val iosBrightness: Int = 0,
    val iosContrast: Int = 0,
    val iosSaturation: Int = 0,
    val iosGamma: Int = 0,
    val desktopHdrMode: DesktopHdrMode = DesktopHdrMode.Auto,
    val desktopColorProfile: DesktopColorProfile = DesktopColorProfile.Neutral,
    /**
     * mpv equalizer offsets for [DesktopColorProfile.Custom]. Ignored by every other profile, so
     * they survive a trip through the presets and come back unchanged.
     */
    val desktopColorContrast: Int = 0,
    val desktopColorBrightness: Int = 0,
    val desktopColorSaturation: Int = 0,
    val desktopColorGamma: Int = 0,
    val desktopBufferPreset: DesktopBufferPreset = DesktopBufferPreset.Balanced,
    val desktopRendererApi: DesktopRendererApi = DesktopRendererApi.D3D11,
    /** Desktop diagnostics: frame-budget telemetry. Off unless someone is investigating. */
    val desktopPerformanceLoggingEnabled: Boolean = false,
    val desktopLowVramMode: DesktopLowVramMode = DesktopLowVramMode.Auto,
    val desktopAnimeMode: DesktopAnimeMode = DesktopAnimeMode.Off,
    val desktopAnimeModeAutoEnabled: Boolean = false,
    // Opts Western cartoons back into anime detection — the behaviour before a bare "Animation"
    // genre stopped being enough on its own. Off by default; see [classifyAnimeContent].
    val desktopAnimeTreatAnimationAsAnime: Boolean = false,
    // The anime shader chains — built-in Anime4K presets and auto-applied custom shaders alike —
    // are upscalers, so on an already-4K source they cost the most and buy the least, enough to
    // lock up a modest GPU. On by default; off restores unconditional applying. A session force
    // (F10) bypasses it either way.
    val desktopAnimeSkipUltraHdEnabled: Boolean = true,
    val desktopAnimeSvpEnabled: Boolean = false,
    // Draws SVP's own method/frame-rate overlay on the filtered video (DEBUG_OVERLAY in svp.conf).
    val desktopAnimeSvpDebugOverlayEnabled: Boolean = false,
    // Opt-in summary of what the pipeline is actually doing, shown when playback starts.
    val desktopPlaybackInfoPanelEnabled: Boolean = false,
    // In-memory only (never persisted): F10/shader-menu force for the current playback session.
    // Non-null bypasses the auto-detect gate entirely; cleared when the player disposes.
    val desktopAnimeSessionOverride: DesktopAnimeSessionOverride? = null,
    // In-memory only: F7 turned SVP on this session, so it applies even to undetected content.
    val desktopAnimeSvpSessionForced: Boolean = false,
    val desktopCustomShaderPaths: String = "",
    val desktopCustomShaderSelectedPath: String = "",
    // Bitstream/passthrough of compressed audio (AC3/DTS/E-AC3/TrueHD/DTS-HD) to a receiver.
    val desktopAudioPassthroughEnabled: Boolean = false,
    // Seek-bar hover previews; see DesktopSeekThumbnailMode for why Local exists.
    val desktopSeekThumbnailMode: DesktopSeekThumbnailMode = DesktopSeekThumbnailMode.Streaming,
    val desktopRateLimitRecoveryMode: DesktopRateLimitRecoveryMode = DesktopRateLimitRecoveryMode.PreferFailover,
    val desktopRateLimitReconnectFirstDelaySeconds: Int = RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS,
    val desktopRateLimitReconnectSecondDelaySeconds: Int = RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS,
    val desktopMpvConfigMode: DesktopMpvConfigMode = DesktopMpvConfigMode.Off,
    // Free-form mpv options, one `key=value` per line, applied just before mpv_initialize so a
    // power user can override any of Nuvio's built-in options.
    val desktopCustomMpvOptions: String = "",
    // Curated mpv property overrides chosen from the in-player "Advanced (mpv)" menu (e.g.
    // deband=yes). Applied both at mpv init and at runtime; the raw options box above still wins.
    val desktopMpvPropertyOverrides: Map<String, String> = emptyMap(),
    val heroTvTrailerEnabled: Boolean = false,
    val heroTvTrailerDelaySeconds: Int = 5,
    val heroTvTrailerSoundEnabled: Boolean = false,
    val heroTvTrailerFullscreen: Boolean = false,
    val heroTvTrailerSearchEnabled: Boolean = true,
) {
    val desktopHudLayout: DesktopHudLayout
        get() = when {
            desktopOfficialHudEnabled -> DesktopHudLayout.Official
            desktopUltraHudEnabled -> DesktopHudLayout.Ultra
            desktopMinimalHudEnabled -> DesktopHudLayout.Minimal
            desktopLegacyHudEnabled -> DesktopHudLayout.Legacy
            else -> DesktopHudLayout.Standard
        }
}

object PlayerSettingsRepository {
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var showLoadingOverlay = true
    private var resizeMode = PlayerResizeMode.Fit
    private var defaultPlaybackSpeed = 1f
    private var mouseMoveRevealsControlsEnabled = true
    private var desktopLegacyHudEnabled = false
    private var desktopMinimalHudEnabled = false
    private var desktopUltraHudEnabled = false
    private var desktopOfficialHudEnabled = false
    private var desktopMinimalHudPillsEnabled = false
    private var desktopSeekHandleEnabled = true
    private var desktopHudVignetteEnabled = true
    private var desktopAlwaysShowClockEnabled = false
    private var desktopPauseOverlaySourceEnabled = false
    private var desktopPlaybackSpeedFineIncrementsEnabled = false
    private var playbackSpeedToggleLow = 1f
    private var playbackSpeedToggleHigh = 2f
    private var desktopVerboseMpvLoggingEnabled = false
    private var desktopUiScalePercent = 0
    private var desktopControlIconScalePercent = 0
    private var seekStepSeconds = DefaultSeekStepSeconds
    private var desktopSourceNotchPosition = DesktopSourceNotchPosition.Right
    private var desktopSourceNotchHoverEnabled = true
    private var desktopPlayerNotificationPosition = DesktopPlayerNotificationPosition.Center
    private var externalPlayerEnabled = false
    private var externalPlayerForwardSubtitles = false
    private var externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId()
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var dualSubtitlesEnabled = false
    private var preferredSubtitleTrackKind = SubtitleTrackKind.DEFAULT
    private var subtitleStyle = SubtitleStyleState.DEFAULT
    private var addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
    private var preferAddonSubtitles = false
    private var rejectedSubtitleKeywords: Set<SubtitleRejectKeyword> = emptySet()
    private var rejectedAudioKeywords: Set<AudioRejectKeyword> = emptySet()
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24
    private var streamPrefetchScope = StreamPrefetchScope.OFF
    private var streamPrefetchCacheMinutes = STREAM_PREFETCH_DEFAULT_CACHE_MINUTES
    private var streamPrefetchResolveLinks = true
    private var decoderPriority = 1
    private var nvidiaRtxSuperResolutionEnabled = false
    private var nvidiaRtxHdrEnabled = false
    private var mapDV7ToHevc = false
    private var tunnelingEnabled = false
    private var streamAutoPlayMode = StreamAutoPlayMode.MANUAL
    private var streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
    private var streamAutoPlaySelectedAddons: Set<String> = emptySet()
    private var streamAutoPlaySelectedPlugins: Set<String> = emptySet()
    private var streamAutoPlayRegex = ""
    private var streamAutoPlayTimeoutSeconds = 3
    private var skipIntroEnabled = true
    private var skipAutoAcceptMode = SkipAutoAcceptMode.MANUAL
    private var skipMovieCreditsToPostCredits = false
    private var stripSdhSubtitles = false
    private var animeSkipEnabled = false
    private var animeSkipClientId = ""
    private var introDbApiKey = ""
    private var seekrApiKey = ""
    private var skipDbApiKey = ""
    private var introSubmitEnabled = false
    private var streamAutoPlayNextEpisodeEnabled = false
    private var streamAutoPlayManualNextEpisode = false
    private var streamAutoPlayPreferBingeGroup = true
    private var streamAutoPlayReuseBingeGroup = false
    private var streamFailoverEnabled = false
    private var streamFailoverTimeoutSeconds = STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS
    private var nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    private var nextEpisodeThresholdPercent = 99f
    private var nextEpisodeThresholdMinutesBeforeEnd = 2f
    private var useLibass = false
    private var libassRenderType = "CUES"
    private var iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
    private var iosToneMappingMode = IosToneMappingMode.Auto
    private var iosTargetPrimaries = IosTargetPrimaries.Auto
    private var iosTargetTransfer = IosTargetTransfer.Auto
    private var iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
    private var iosAudioOutputMode = IosAudioOutputMode.Auto
    private var iosExtendedDynamicRangeEnabled = true
    private var iosTargetColorspaceHintEnabled = true
    private var iosHdrComputePeakEnabled = true
    private var iosDebandEnabled = false
    private var iosInterpolationEnabled = false
    private var iosBrightness = 0
    private var iosContrast = 0
    private var iosSaturation = 0
    private var iosGamma = 0
    private var desktopHdrMode = DesktopHdrMode.Auto
    private var desktopColorProfile = DesktopColorProfile.Neutral
    private var desktopColorContrast = 0
    private var desktopColorBrightness = 0
    private var desktopColorSaturation = 0
    private var desktopColorGamma = 0
    private var desktopBufferPreset = DesktopBufferPreset.Balanced
    private var desktopRendererApi = DesktopRendererApi.D3D11
    private var desktopPerformanceLoggingEnabled = false
    private var desktopLowVramMode = DesktopLowVramMode.Auto
    private var desktopAnimeMode = DesktopAnimeMode.Off
    private var desktopAnimeModeAutoEnabled = false
    private var desktopAnimeTreatAnimationAsAnime = false
    private var desktopAnimeSkipUltraHdEnabled = true
    private var desktopAnimeSvpEnabled = false
    private var desktopAnimeSvpDebugOverlayEnabled = false
    private var desktopPlaybackInfoPanelEnabled = false
    // Session-only state; deliberately has no PlayerSettingsStorage backing.
    private var desktopAnimeSessionOverride: DesktopAnimeSessionOverride? = null
    private var desktopAnimeSvpSessionForced = false
    private var desktopCustomShaderPaths = ""
    private var desktopCustomShaderSelectedPath = ""
    private var desktopAudioPassthroughEnabled = false
    private var desktopSeekThumbnailMode = DesktopSeekThumbnailMode.Streaming
    private var desktopRateLimitRecoveryMode = DesktopRateLimitRecoveryMode.PreferFailover
    private var desktopRateLimitReconnectFirstDelaySeconds = RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS
    private var desktopRateLimitReconnectSecondDelaySeconds = RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS
    private var desktopMpvConfigMode = DesktopMpvConfigMode.Off
    private var desktopCustomMpvOptions = ""
    private var desktopMpvPropertyOverrides: Map<String, String> = emptyMap()
    private var heroTvTrailerEnabled = false
    private var heroTvTrailerDelaySeconds = 5
    private var heroTvTrailerSoundEnabled = false
    private var heroTvTrailerFullscreen = false
    private var heroTvTrailerSearchEnabled = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        // A different profile means different addons, different debrid and a different prefetch
        // setting; nothing speculatively gathered for the old one is answering for this one.
        StreamPrefetchCache.clear()
        StreamPrefetchService.reset()
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        showLoadingOverlay = true
        resizeMode = PlayerResizeMode.Fit
        defaultPlaybackSpeed = 1f
        mouseMoveRevealsControlsEnabled = true
        desktopLegacyHudEnabled = false
        desktopMinimalHudEnabled = false
        desktopUltraHudEnabled = false
        desktopOfficialHudEnabled = false
        desktopMinimalHudPillsEnabled = false
        desktopSeekHandleEnabled = true
        desktopHudVignetteEnabled = true
        desktopAlwaysShowClockEnabled = false
        desktopPauseOverlaySourceEnabled = false
        desktopPlaybackSpeedFineIncrementsEnabled = false
        playbackSpeedToggleLow = 1f
        playbackSpeedToggleHigh = 2f
        desktopVerboseMpvLoggingEnabled = false
        desktopUiScalePercent = 0
        desktopControlIconScalePercent = 0
        seekStepSeconds = DefaultSeekStepSeconds
        desktopSourceNotchPosition = DesktopSourceNotchPosition.Right
        desktopSourceNotchHoverEnabled = true
        desktopPlayerNotificationPosition = DesktopPlayerNotificationPosition.Center
        externalPlayerEnabled = false
        externalPlayerForwardSubtitles = false
        externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage = AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage = null
        preferredSubtitleLanguage = SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage = null
        dualSubtitlesEnabled = false
        preferredSubtitleTrackKind = SubtitleTrackKind.DEFAULT
        subtitleStyle = SubtitleStyleState.DEFAULT
        addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
        preferAddonSubtitles = false
        rejectedSubtitleKeywords = emptySet()
        rejectedAudioKeywords = emptySet()
        streamReuseLastLinkEnabled = false
        streamReuseLastLinkCacheHours = 24
        streamPrefetchScope = StreamPrefetchScope.OFF
        streamPrefetchCacheMinutes = STREAM_PREFETCH_DEFAULT_CACHE_MINUTES
        streamPrefetchResolveLinks = true
        decoderPriority = 1
        nvidiaRtxSuperResolutionEnabled = false
        nvidiaRtxHdrEnabled = false
        mapDV7ToHevc = false
        tunnelingEnabled = false
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = emptySet()
        streamAutoPlaySelectedPlugins = emptySet()
        streamAutoPlayRegex = ""
        streamAutoPlayTimeoutSeconds = 3
        skipIntroEnabled = true
        skipAutoAcceptMode = SkipAutoAcceptMode.MANUAL
        skipMovieCreditsToPostCredits = false
        stripSdhSubtitles = false
        animeSkipEnabled = false
        animeSkipClientId = ""
        introDbApiKey = ""
        seekrApiKey = ""
        skipDbApiKey = ""
        introSubmitEnabled = false
        streamAutoPlayNextEpisodeEnabled = false
        streamAutoPlayManualNextEpisode = false
        streamAutoPlayPreferBingeGroup = true
        streamAutoPlayReuseBingeGroup = false
        streamFailoverEnabled = false
        streamFailoverTimeoutSeconds = STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS
        nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = 99f
        nextEpisodeThresholdMinutesBeforeEnd = 2f
        useLibass = false
        libassRenderType = "CUES"
        iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = IosToneMappingMode.Auto
        iosTargetPrimaries = IosTargetPrimaries.Auto
        iosTargetTransfer = IosTargetTransfer.Auto
        iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = IosAudioOutputMode.Auto
        iosExtendedDynamicRangeEnabled = true
        iosTargetColorspaceHintEnabled = true
        iosHdrComputePeakEnabled = true
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        desktopHdrMode = DesktopHdrMode.Auto
        desktopColorProfile = DesktopColorProfile.Neutral
        desktopColorContrast = 0
        desktopColorBrightness = 0
        desktopColorSaturation = 0
        desktopColorGamma = 0
        desktopBufferPreset = DesktopBufferPreset.Balanced
        desktopRendererApi = DesktopRendererApi.D3D11
        desktopPerformanceLoggingEnabled = false
        desktopLowVramMode = DesktopLowVramMode.Auto
        desktopAnimeMode = DesktopAnimeMode.Off
        desktopAnimeModeAutoEnabled = false
        desktopAnimeTreatAnimationAsAnime = false
        desktopAnimeSkipUltraHdEnabled = true
        desktopAnimeSvpEnabled = false
        desktopAnimeSvpDebugOverlayEnabled = false
        desktopPlaybackInfoPanelEnabled = false
        desktopAnimeSessionOverride = null
        desktopAnimeSvpSessionForced = false
        desktopCustomShaderPaths = ""
        desktopCustomShaderSelectedPath = ""
        desktopAudioPassthroughEnabled = false
        desktopSeekThumbnailMode = DesktopSeekThumbnailMode.Streaming
        desktopRateLimitRecoveryMode = DesktopRateLimitRecoveryMode.PreferFailover
        desktopRateLimitReconnectFirstDelaySeconds = RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS
        desktopRateLimitReconnectSecondDelaySeconds = RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS
        desktopMpvConfigMode = DesktopMpvConfigMode.Off
        desktopCustomMpvOptions = ""
        desktopMpvPropertyOverrides = emptyMap()
        heroTvTrailerEnabled = false
        heroTvTrailerDelaySeconds = 5
        heroTvTrailerSoundEnabled = false
        heroTvTrailerFullscreen = false
        heroTvTrailerSearchEnabled = true
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        showLoadingOverlay = PlayerSettingsStorage.loadShowLoadingOverlay() ?: true
        resizeMode = PlayerSettingsStorage.loadResizeMode()
            ?.let { runCatching { PlayerResizeMode.valueOf(it) }.getOrNull() }
            ?: PlayerResizeMode.Fit
        defaultPlaybackSpeed = PlayerSettingsStorage.loadDefaultPlaybackSpeed() ?: 1f
        mouseMoveRevealsControlsEnabled = PlayerSettingsStorage.loadMouseMoveRevealsControlsEnabled() ?: true
        desktopLegacyHudEnabled = PlayerSettingsStorage.loadDesktopLegacyHudEnabled() ?: false
        desktopMinimalHudEnabled = PlayerSettingsStorage.loadDesktopMinimalHudEnabled() ?: false
        desktopUltraHudEnabled = PlayerSettingsStorage.loadDesktopUltraHudEnabled() ?: false
        desktopOfficialHudEnabled = PlayerSettingsStorage.loadDesktopOfficialHudEnabled() ?: false
        desktopMinimalHudPillsEnabled = PlayerSettingsStorage.loadDesktopMinimalHudPillsEnabled() ?: false
        desktopSeekHandleEnabled = PlayerSettingsStorage.loadDesktopSeekHandleEnabled() ?: true
        desktopHudVignetteEnabled = PlayerSettingsStorage.loadDesktopHudVignetteEnabled() ?: true
        // Four stored flags, one layout: official beats ultra beats minimal beats legacy if several
        // were ever set.
        if (desktopOfficialHudEnabled) desktopUltraHudEnabled = false
        if (desktopOfficialHudEnabled || desktopUltraHudEnabled) desktopMinimalHudEnabled = false
        if (desktopOfficialHudEnabled || desktopMinimalHudEnabled || desktopUltraHudEnabled) {
            desktopLegacyHudEnabled = false
        }
        desktopAlwaysShowClockEnabled = PlayerSettingsStorage.loadDesktopAlwaysShowClockEnabled() ?: false
        desktopPauseOverlaySourceEnabled = PlayerSettingsStorage.loadDesktopPauseOverlaySourceEnabled() ?: false
        desktopPlaybackSpeedFineIncrementsEnabled =
            PlayerSettingsStorage.loadDesktopPlaybackSpeedFineIncrementsEnabled() ?: false
        val toggleRange = normalizePlaybackSpeedToggleRange(
            low = PlayerSettingsStorage.loadPlaybackSpeedToggleLow() ?: 1f,
            high = PlayerSettingsStorage.loadPlaybackSpeedToggleHigh() ?: 2f,
        )
        playbackSpeedToggleLow = toggleRange.first
        playbackSpeedToggleHigh = toggleRange.second
        desktopVerboseMpvLoggingEnabled = PlayerSettingsStorage.loadDesktopVerboseMpvLoggingEnabled() ?: false
        desktopUiScalePercent = (PlayerSettingsStorage.loadDesktopUiScalePercent() ?: 0).coerceIn(-50, 50)
        desktopControlIconScalePercent =
            (PlayerSettingsStorage.loadDesktopControlIconScalePercent() ?: 0).coerceIn(-50, 50)
        seekStepSeconds = (PlayerSettingsStorage.loadSeekStepSeconds() ?: DefaultSeekStepSeconds)
            .coerceIn(SEEK_STEP_SECONDS_RANGE.first, SEEK_STEP_SECONDS_RANGE.last)
        desktopSourceNotchPosition = DesktopSourceNotchPosition.fromStorage(
            PlayerSettingsStorage.loadDesktopSourceNotchPosition(),
        )
        desktopSourceNotchHoverEnabled =
            PlayerSettingsStorage.loadDesktopSourceNotchHoverEnabled() ?: true
        desktopPlayerNotificationPosition = DesktopPlayerNotificationPosition.fromStorage(
            PlayerSettingsStorage.loadDesktopPlayerNotificationPosition(),
        )
        externalPlayerEnabled = PlayerSettingsStorage.loadExternalPlayerEnabled() ?: false
        externalPlayerForwardSubtitles = PlayerSettingsStorage.loadExternalPlayerForwardSubtitles() ?: false
        externalPlayerId = PlayerSettingsStorage.loadExternalPlayerId()
            ?: ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredAudioLanguage())
                ?: AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredAudioLanguage())
        preferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredSubtitleLanguage())
                ?: SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredSubtitleLanguage())
        dualSubtitlesEnabled = PlayerSettingsStorage.loadDualSubtitlesEnabled() ?: false
        preferredSubtitleTrackKind = loadOrMigrateSubtitleTrackKind()
        subtitleStyle = SubtitleStyleState(
            textColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleTextColor())
                ?: SubtitleStyleState.DEFAULT.textColor,
            backgroundColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleBackgroundColor())
                ?: SubtitleStyleState.DEFAULT.backgroundColor,
            outlineColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleOutlineColor())
                ?: SubtitleStyleState.DEFAULT.outlineColor,
            outlineEnabled = PlayerSettingsStorage.loadSubtitleOutlineEnabled()
                ?: SubtitleStyleState.DEFAULT.outlineEnabled,
            outlineWidth = PlayerSettingsStorage.loadSubtitleOutlineWidth()
                ?: SubtitleStyleState.DEFAULT.outlineWidth,
            shadowEnabled = PlayerSettingsStorage.loadSubtitleShadowEnabled()
                ?: SubtitleStyleState.DEFAULT.shadowEnabled,
            shadowColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleShadowColor())
                ?: SubtitleStyleState.DEFAULT.shadowColor,
            shadowOffset = PlayerSettingsStorage.loadSubtitleShadowOffset()
                ?: SubtitleStyleState.DEFAULT.shadowOffset,
            blur = PlayerSettingsStorage.loadSubtitleBlur()
                ?: SubtitleStyleState.DEFAULT.blur,
            bold = PlayerSettingsStorage.loadSubtitleBold()
                ?: SubtitleStyleState.DEFAULT.bold,
            italic = PlayerSettingsStorage.loadSubtitleItalic()
                ?: SubtitleStyleState.DEFAULT.italic,
            fontSizeSp = PlayerSettingsStorage.loadSubtitleFontSizeSp()
                ?: SubtitleStyleState.DEFAULT.fontSizeSp,
            bottomOffset = PlayerSettingsStorage.loadSubtitleBottomOffset()
                ?: SubtitleStyleState.DEFAULT.bottomOffset,
            fontFamily = PlayerSettingsStorage.loadSubtitleFontFamily()
                ?: SubtitleStyleState.DEFAULT.fontFamily,
            assStyleMode = PlayerSettingsStorage.loadSubtitleAssStyleMode()
                ?.let { stored -> runCatching { SubtitleAssStyleMode.valueOf(stored) }.getOrNull() }
                ?: SubtitleStyleState.DEFAULT.assStyleMode,
            assScalePercent = PlayerSettingsStorage.loadSubtitleAssScalePercent()
                ?.coerceIn(SUBTITLE_ASS_SCALE_MIN, SUBTITLE_ASS_SCALE_MAX)
                ?: SubtitleStyleState.DEFAULT.assScalePercent,
            showOnlyPreferredLanguages = PlayerSettingsStorage.loadSubtitleShowOnlyPreferredLanguages()
                ?: SubtitleStyleState.DEFAULT.showOnlyPreferredLanguages,
        )
        addonSubtitleStartupMode = PlayerSettingsStorage.loadAddonSubtitleStartupMode()
            ?.let { runCatching { AddonSubtitleStartupMode.valueOf(it) }.getOrNull() }
            ?: AddonSubtitleStartupMode.ALL_SUBTITLES
        preferAddonSubtitles = PlayerSettingsStorage.loadPreferAddonSubtitles() ?: false
        rejectedSubtitleKeywords =
            parseSubtitleRejectKeywords(PlayerSettingsStorage.loadRejectedSubtitleKeywords())
        rejectedAudioKeywords =
            parseAudioRejectKeywords(PlayerSettingsStorage.loadRejectedAudioKeywords())
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
        streamPrefetchScope = PlayerSettingsStorage.loadStreamPrefetchScope()
            ?.let { runCatching { StreamPrefetchScope.valueOf(it) }.getOrNull() }
            ?: StreamPrefetchScope.OFF
        streamPrefetchCacheMinutes = PlayerSettingsStorage.loadStreamPrefetchCacheMinutes()
            ?.takeIf { it in STREAM_PREFETCH_CACHE_MINUTE_VALUES }
            ?: STREAM_PREFETCH_DEFAULT_CACHE_MINUTES
        streamPrefetchResolveLinks = PlayerSettingsStorage.loadStreamPrefetchResolveLinks() ?: true
        streamReuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours() ?: 24
        decoderPriority = PlayerSettingsStorage.loadDecoderPriority() ?: 1
        nvidiaRtxSuperResolutionEnabled = PlayerSettingsStorage.loadNvidiaRtxSuperResolutionEnabled() ?: false
        nvidiaRtxHdrEnabled = PlayerSettingsStorage.loadNvidiaRtxHdrEnabled() ?: false
        nvidiaRtxHdrEnabled = PlayerSettingsStorage.loadNvidiaRtxHdrEnabled() ?: false
        mapDV7ToHevc = PlayerSettingsStorage.loadMapDV7ToHevc() ?: false
        tunnelingEnabled = PlayerSettingsStorage.loadTunnelingEnabled() ?: false
        streamAutoPlayMode = PlayerSettingsStorage.loadStreamAutoPlayMode()
            ?.let { runCatching { StreamAutoPlayMode.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = PlayerSettingsStorage.loadStreamAutoPlaySource()
            ?.let { runCatching { StreamAutoPlaySource.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = PlayerSettingsStorage.loadStreamAutoPlaySelectedAddons() ?: emptySet()
        streamAutoPlaySelectedPlugins = PlayerSettingsStorage.loadStreamAutoPlaySelectedPlugins() ?: emptySet()
        if (!AppFeaturePolicy.pluginsEnabled) {
            val normalizedSource = normalizeStreamAutoPlaySource(streamAutoPlaySource)
            if (normalizedSource != streamAutoPlaySource) {
                streamAutoPlaySource = normalizedSource
                PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
            }
            if (streamAutoPlaySelectedPlugins.isNotEmpty()) {
                streamAutoPlaySelectedPlugins = emptySet()
                PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(emptySet())
            }
        }
        streamAutoPlayRegex = PlayerSettingsStorage.loadStreamAutoPlayRegex() ?: ""
        streamAutoPlayTimeoutSeconds = PlayerSettingsStorage.loadStreamAutoPlayTimeoutSeconds() ?: 3
        // Legacy migration: 11 was the old sentinel for "unlimited"
        if (streamAutoPlayTimeoutSeconds == 11) {
            streamAutoPlayTimeoutSeconds = Int.MAX_VALUE
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        } else if (streamAutoPlayTimeoutSeconds !in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
            streamAutoPlayTimeoutSeconds = snapToAllowedTimeout(streamAutoPlayTimeoutSeconds)
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        }
        skipIntroEnabled = PlayerSettingsStorage.loadSkipIntroEnabled() ?: true
        skipAutoAcceptMode = PlayerSettingsStorage.loadSkipAutoAcceptMode()
            ?.let { runCatching { SkipAutoAcceptMode.valueOf(it) }.getOrNull() }
            ?: SkipAutoAcceptMode.MANUAL
        skipMovieCreditsToPostCredits = PlayerSettingsStorage.loadSkipMovieCreditsToPostCredits() ?: false
        stripSdhSubtitles = PlayerSettingsStorage.loadStripSdhSubtitles() ?: false
        animeSkipEnabled = PlayerSettingsStorage.loadAnimeSkipEnabled() ?: false
        animeSkipClientId = PlayerSettingsStorage.loadAnimeSkipClientId() ?: ""
        introDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey() ?: ""
        seekrApiKey = PlayerSettingsStorage.loadSeekrApiKey() ?: ""
        skipDbApiKey = PlayerSettingsStorage.loadSkipDbApiKey() ?: ""
        introSubmitEnabled = PlayerSettingsStorage.loadIntroSubmitEnabled() ?: false
        streamAutoPlayNextEpisodeEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeEnabled() ?: false
        streamAutoPlayManualNextEpisode = PlayerSettingsStorage.loadStreamAutoPlayManualNextEpisode() ?: false
        streamAutoPlayPreferBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayPreferBingeGroup() ?: true
        streamAutoPlayReuseBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayReuseBingeGroup() ?: false
        streamFailoverEnabled = PlayerSettingsStorage.loadStreamFailoverEnabled() ?: false
        streamFailoverTimeoutSeconds =
            (PlayerSettingsStorage.loadStreamFailoverTimeoutSeconds() ?: STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS)
                .let { if (it in STREAM_FAILOVER_TIMEOUT_VALUES) it else STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS }
        nextEpisodeThresholdMode = PlayerSettingsStorage.loadNextEpisodeThresholdMode()
            ?.let { runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrNull() }
            ?: NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = PlayerSettingsStorage.loadNextEpisodeThresholdPercent() ?: 99f
        nextEpisodeThresholdMinutesBeforeEnd = PlayerSettingsStorage.loadNextEpisodeThresholdMinutesBeforeEnd() ?: 2f
        useLibass = PlayerSettingsStorage.loadUseLibass() ?: false
        libassRenderType = PlayerSettingsStorage.loadLibassRenderType() ?: "CUES"
        iosVideoOutputPreset = PlayerSettingsStorage.loadIosVideoOutputPreset()
            ?.let { runCatching { IosVideoOutputPreset.valueOf(it) }.getOrNull() }
            ?: IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = PlayerSettingsStorage.loadIosToneMappingMode()
            ?.let { runCatching { IosToneMappingMode.valueOf(it) }.getOrNull() }
            ?: IosToneMappingMode.Auto
        iosTargetPrimaries = PlayerSettingsStorage.loadIosTargetPrimaries()
            ?.let { runCatching { IosTargetPrimaries.valueOf(it) }.getOrNull() }
            ?: IosTargetPrimaries.Auto
        iosTargetTransfer = PlayerSettingsStorage.loadIosTargetTransfer()
            ?.let { runCatching { IosTargetTransfer.valueOf(it) }.getOrNull() }
            ?: IosTargetTransfer.Auto
        iosHardwareDecoderMode = PlayerSettingsStorage.loadIosHardwareDecoderMode()
            ?.let { runCatching { IosHardwareDecoderMode.valueOf(it) }.getOrNull() }
            ?: IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = PlayerSettingsStorage.loadIosAudioOutputMode()
            ?.let { runCatching { IosAudioOutputMode.valueOf(it) }.getOrNull() }
            ?: IosAudioOutputMode.Auto
        iosExtendedDynamicRangeEnabled = PlayerSettingsStorage.loadIosExtendedDynamicRangeEnabled() ?: true
        iosTargetColorspaceHintEnabled = PlayerSettingsStorage.loadIosTargetColorspaceHintEnabled() ?: true
        iosHdrComputePeakEnabled = PlayerSettingsStorage.loadIosHdrComputePeakEnabled() ?: true
        iosDebandEnabled = PlayerSettingsStorage.loadIosDebandEnabled() ?: false
        iosInterpolationEnabled = PlayerSettingsStorage.loadIosInterpolationEnabled() ?: false
        iosBrightness = PlayerSettingsStorage.loadIosBrightness() ?: 0
        iosContrast = PlayerSettingsStorage.loadIosContrast() ?: 0
        iosSaturation = PlayerSettingsStorage.loadIosSaturation() ?: 0
        iosGamma = PlayerSettingsStorage.loadIosGamma() ?: 0
        desktopHdrMode = PlayerSettingsStorage.loadDesktopHdrMode()
            ?.let { runCatching { DesktopHdrMode.valueOf(it) }.getOrNull() }
            ?: DesktopHdrMode.Auto
        desktopColorProfile = PlayerSettingsStorage.loadDesktopColorProfile()
            ?.let { runCatching { DesktopColorProfile.valueOf(it) }.getOrNull() }
            ?: DesktopColorProfile.Neutral
        desktopColorContrast = PlayerSettingsStorage.loadDesktopColorContrast() ?: 0
        desktopColorBrightness = PlayerSettingsStorage.loadDesktopColorBrightness() ?: 0
        desktopColorSaturation = PlayerSettingsStorage.loadDesktopColorSaturation() ?: 0
        desktopColorGamma = PlayerSettingsStorage.loadDesktopColorGamma() ?: 0
        desktopBufferPreset = PlayerSettingsStorage.loadDesktopBufferPreset()
            ?.let { runCatching { DesktopBufferPreset.valueOf(it) }.getOrNull() }
            ?: DesktopBufferPreset.Balanced
        PlayerSettingsStorage.saveDesktopBufferPreset(desktopBufferPreset.name)
        desktopRendererApi = PlayerSettingsStorage.loadDesktopRendererApi()
            ?.let { runCatching { DesktopRendererApi.valueOf(it) }.getOrNull() }
            ?: DesktopRendererApi.D3D11
        desktopPerformanceLoggingEnabled = PlayerSettingsStorage.loadDesktopPerformanceLogging() ?: false
        desktopLowVramMode = PlayerSettingsStorage.loadDesktopLowVramMode()
            ?.let { runCatching { DesktopLowVramMode.valueOf(it) }.getOrNull() }
            ?: DesktopLowVramMode.Auto
        val storedAnimeMode = PlayerSettingsStorage.loadDesktopAnimeMode()
        if (storedAnimeMode == "Auto") {
            // Migrate: old "Auto" = Optimized preset + auto-detect on.
            desktopAnimeMode = DesktopAnimeMode.Optimized
            desktopAnimeModeAutoEnabled = true
            PlayerSettingsStorage.saveDesktopAnimeMode(DesktopAnimeMode.Optimized.name)
            PlayerSettingsStorage.saveDesktopAnimeModeAutoEnabled(true)
        } else {
            desktopAnimeMode = storedAnimeMode
                ?.let { runCatching { DesktopAnimeMode.valueOf(it) }.getOrNull() }
                ?: DesktopAnimeMode.Off
            desktopAnimeModeAutoEnabled = PlayerSettingsStorage.loadDesktopAnimeModeAutoEnabled() ?: false
        }
        desktopAnimeTreatAnimationAsAnime =
            PlayerSettingsStorage.loadDesktopAnimeTreatAnimationAsAnime() ?: false
        desktopAnimeSkipUltraHdEnabled =
            PlayerSettingsStorage.loadDesktopAnimeSkipUltraHdEnabled() ?: true
        desktopAnimeSvpEnabled = PlayerSettingsStorage.loadDesktopAnimeSvpEnabled() ?: false
        desktopAnimeSvpDebugOverlayEnabled =
            PlayerSettingsStorage.loadDesktopAnimeSvpDebugOverlayEnabled() ?: false
        desktopPlaybackInfoPanelEnabled =
            PlayerSettingsStorage.loadDesktopPlaybackInfoPanelEnabled() ?: false
        desktopCustomShaderPaths = PlayerSettingsStorage.loadDesktopCustomShaderPaths().orEmpty()
        desktopCustomShaderSelectedPath = PlayerSettingsStorage.loadDesktopCustomShaderSelectedPath().orEmpty()
        val legacyCustomShadersEnabled = PlayerSettingsStorage.loadDesktopCustomShadersEnabled() ?: false
        if (legacyCustomShadersEnabled &&
            desktopCustomShaderPaths.isNotBlank() &&
            desktopAnimeMode != DesktopAnimeMode.CustomShader
        ) {
            desktopAnimeMode = DesktopAnimeMode.CustomShader
            PlayerSettingsStorage.saveDesktopAnimeMode(DesktopAnimeMode.CustomShader.name)
        }
        desktopAudioPassthroughEnabled = PlayerSettingsStorage.loadDesktopAudioPassthroughEnabled() ?: false
        desktopSeekThumbnailMode = PlayerSettingsStorage.loadDesktopSeekThumbnailMode()
            ?.let { runCatching { DesktopSeekThumbnailMode.valueOf(it) }.getOrNull() }
            // Before the mode existed this was an On/Off switch: Off stays Off, On meant every source.
            ?: when (PlayerSettingsStorage.loadDesktopSeekThumbnailsEnabled()) {
                false -> DesktopSeekThumbnailMode.Off
                else -> DesktopSeekThumbnailMode.Streaming
            }
        desktopRateLimitRecoveryMode = PlayerSettingsStorage.loadDesktopRateLimitRecoveryMode()
            ?.let { runCatching { DesktopRateLimitRecoveryMode.valueOf(it) }.getOrNull() }
            ?: DesktopRateLimitRecoveryMode.PreferFailover
        desktopRateLimitReconnectFirstDelaySeconds =
            PlayerSettingsStorage.loadDesktopRateLimitReconnectFirstDelaySeconds()
                ?.takeIf { it in RATE_LIMIT_RECONNECT_DELAY_VALUES }
                ?: RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS
        desktopRateLimitReconnectSecondDelaySeconds =
            PlayerSettingsStorage.loadDesktopRateLimitReconnectSecondDelaySeconds()
                ?.takeIf { it in RATE_LIMIT_RECONNECT_DELAY_VALUES }
                ?: RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS
        desktopCustomMpvOptions = PlayerSettingsStorage.loadDesktopCustomMpvOptions().orEmpty()
        desktopMpvConfigMode = PlayerSettingsStorage.loadDesktopMpvConfigMode()
            ?.let { runCatching { DesktopMpvConfigMode.valueOf(it) }.getOrNull() }
            // Preserve pre-mode installations which already relied on raw overrides.
            ?: if (desktopCustomMpvOptions.isBlank()) DesktopMpvConfigMode.Off else DesktopMpvConfigMode.Replace
        desktopMpvPropertyOverrides = parseMpvPropertyOverrides(PlayerSettingsStorage.loadDesktopMpvPropertyOverrides())
        heroTvTrailerEnabled = PlayerSettingsStorage.loadHeroTvTrailerEnabled() ?: false
        heroTvTrailerDelaySeconds = PlayerSettingsStorage.loadHeroTvTrailerDelaySeconds()
            ?.let(::snapToHeroTvTrailerDelay) ?: 5
        heroTvTrailerSoundEnabled = PlayerSettingsStorage.loadHeroTvTrailerSoundEnabled() ?: false
        heroTvTrailerFullscreen = PlayerSettingsStorage.loadHeroTvTrailerFullscreen() ?: false
        heroTvTrailerSearchEnabled = PlayerSettingsStorage.loadHeroTvTrailerSearchEnabled() ?: true
        publish()
    }

    fun setShowLoadingOverlay(enabled: Boolean) {
        ensureLoaded()
        if (showLoadingOverlay == enabled) return
        showLoadingOverlay = enabled
        publish()
        PlayerSettingsStorage.saveShowLoadingOverlay(enabled)
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        ensureLoaded()
        if (resizeMode == mode) return
        resizeMode = mode
        publish()
        PlayerSettingsStorage.saveResizeMode(mode.name)
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        ensureLoaded()
        val normalized = speed.coerceIn(0.25f, 4f)
        if (defaultPlaybackSpeed == normalized) return
        defaultPlaybackSpeed = normalized
        publish()
        PlayerSettingsStorage.saveDefaultPlaybackSpeed(normalized)
    }

    fun setMouseMoveRevealsControlsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (mouseMoveRevealsControlsEnabled == enabled) return
        mouseMoveRevealsControlsEnabled = enabled
        publish()
        PlayerSettingsStorage.saveMouseMoveRevealsControlsEnabled(enabled)
    }

    fun setDesktopMinimalHudPillsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopMinimalHudPillsEnabled == enabled) return
        desktopMinimalHudPillsEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopMinimalHudPillsEnabled(enabled)
    }

    fun setDesktopSeekHandleEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopSeekHandleEnabled == enabled) return
        desktopSeekHandleEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopSeekHandleEnabled(enabled)
    }

    fun setDesktopHudVignetteEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopHudVignetteEnabled == enabled) return
        desktopHudVignetteEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopHudVignetteEnabled(enabled)
    }

    fun setDesktopHudLayout(layout: DesktopHudLayout) {
        ensureLoaded()
        val legacy = layout == DesktopHudLayout.Legacy
        val minimal = layout == DesktopHudLayout.Minimal
        val ultra = layout == DesktopHudLayout.Ultra
        val official = layout == DesktopHudLayout.Official
        if (
            desktopLegacyHudEnabled == legacy &&
            desktopMinimalHudEnabled == minimal &&
            desktopUltraHudEnabled == ultra &&
            desktopOfficialHudEnabled == official
        ) {
            return
        }
        desktopLegacyHudEnabled = legacy
        desktopMinimalHudEnabled = minimal
        desktopUltraHudEnabled = ultra
        desktopOfficialHudEnabled = official
        publish()
        PlayerSettingsStorage.saveDesktopLegacyHudEnabled(legacy)
        PlayerSettingsStorage.saveDesktopMinimalHudEnabled(minimal)
        PlayerSettingsStorage.saveDesktopUltraHudEnabled(ultra)
        PlayerSettingsStorage.saveDesktopOfficialHudEnabled(official)
    }

    fun setDesktopAlwaysShowClockEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAlwaysShowClockEnabled == enabled) return
        desktopAlwaysShowClockEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAlwaysShowClockEnabled(enabled)
    }

    fun setDesktopPauseOverlaySourceEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopPauseOverlaySourceEnabled == enabled) return
        desktopPauseOverlaySourceEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopPauseOverlaySourceEnabled(enabled)
    }

    fun setDesktopPlaybackSpeedFineIncrementsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopPlaybackSpeedFineIncrementsEnabled == enabled) return
        desktopPlaybackSpeedFineIncrementsEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopPlaybackSpeedFineIncrementsEnabled(enabled)
    }

    /**
     * The two ends of the speed-toggle range. Written together because they are one control: the
     * shortcut needs low < high to have anything to flip between, so the pair is normalised here
     * rather than trusting either caller or a hand-edited prefs file.
     */
    fun setPlaybackSpeedToggleRange(low: Float, high: Float) {
        ensureLoaded()
        val (nextLow, nextHigh) = normalizePlaybackSpeedToggleRange(low, high)
        if (playbackSpeedToggleLow == nextLow && playbackSpeedToggleHigh == nextHigh) return
        playbackSpeedToggleLow = nextLow
        playbackSpeedToggleHigh = nextHigh
        publish()
        PlayerSettingsStorage.savePlaybackSpeedToggleLow(nextLow)
        PlayerSettingsStorage.savePlaybackSpeedToggleHigh(nextHigh)
    }

    fun setDesktopVerboseMpvLoggingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopVerboseMpvLoggingEnabled == enabled) return
        desktopVerboseMpvLoggingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopVerboseMpvLoggingEnabled(enabled)
    }

    fun setDesktopUiScalePercent(percent: Int) {
        ensureLoaded()
        val clamped = percent.coerceIn(-50, 50)
        if (desktopUiScalePercent == clamped) return
        desktopUiScalePercent = clamped
        publish()
        PlayerSettingsStorage.saveDesktopUiScalePercent(clamped)
    }

    fun setDesktopControlIconScalePercent(percent: Int) {
        ensureLoaded()
        val clamped = percent.coerceIn(-50, 50)
        if (desktopControlIconScalePercent == clamped) return
        desktopControlIconScalePercent = clamped
        publish()
        PlayerSettingsStorage.saveDesktopControlIconScalePercent(clamped)
    }

    fun setSeekStepSeconds(seconds: Int) {
        ensureLoaded()
        val clamped = seconds.coerceIn(SEEK_STEP_SECONDS_RANGE.first, SEEK_STEP_SECONDS_RANGE.last)
        if (seekStepSeconds == clamped) return
        seekStepSeconds = clamped
        publish()
        PlayerSettingsStorage.saveSeekStepSeconds(clamped)
    }

    fun setDesktopSourceNotchPosition(position: DesktopSourceNotchPosition) {
        ensureLoaded()
        if (desktopSourceNotchPosition == position) return
        desktopSourceNotchPosition = position
        publish()
        PlayerSettingsStorage.saveDesktopSourceNotchPosition(position.name)
    }

    fun setDesktopSourceNotchHoverEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopSourceNotchHoverEnabled == enabled) return
        desktopSourceNotchHoverEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopSourceNotchHoverEnabled(enabled)
    }

    fun setDesktopPlayerNotificationPosition(position: DesktopPlayerNotificationPosition) {
        ensureLoaded()
        if (desktopPlayerNotificationPosition == position) return
        desktopPlayerNotificationPosition = position
        publish()
        PlayerSettingsStorage.saveDesktopPlayerNotificationPosition(position.name)
    }

    fun setExternalPlayerEnabled(enabled: Boolean) {
        ensureLoaded()
        if (enabled && externalPlayerId.isNullOrBlank()) {
            externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
                ?: ExternalPlayerPlatform.availablePlayers().firstOrNull()?.id
            PlayerSettingsStorage.saveExternalPlayerId(externalPlayerId)
        }
        if (externalPlayerEnabled == enabled) {
            publish()
            return
        }
        externalPlayerEnabled = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerEnabled(enabled)
    }

    fun setExternalPlayerId(playerId: String?) {
        ensureLoaded()
        val normalized = playerId?.takeIf { it.isNotBlank() }
        if (externalPlayerId == normalized) return
        externalPlayerId = normalized
        publish()
        PlayerSettingsStorage.saveExternalPlayerId(normalized)
    }

    fun setExternalPlayerForwardSubtitles(enabled: Boolean) {
        ensureLoaded()
        if (externalPlayerForwardSubtitles == enabled) return
        externalPlayerForwardSubtitles = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerForwardSubtitles(enabled)
    }

    fun setPreferredAudioLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: AudioLanguageOption.DEVICE
        if (preferredAudioLanguage == normalized) return
        preferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredAudioLanguage(normalized)
    }

    fun setSecondaryPreferredAudioLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredAudioLanguage == normalized) return
        secondaryPreferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredAudioLanguage(normalized)
    }

    fun setPreferredSubtitleLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: SubtitleLanguageOption.NONE
        if (preferredSubtitleLanguage == normalized) return
        preferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredSubtitleLanguage(normalized)
    }

    fun setSecondaryPreferredSubtitleLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredSubtitleLanguage == normalized) return
        secondaryPreferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(normalized)
    }

    fun setDualSubtitlesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (dualSubtitlesEnabled == enabled) return
        dualSubtitlesEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDualSubtitlesEnabled(enabled)
    }

    fun setPreferredSubtitleTrackKind(kind: SubtitleTrackKind) {
        ensureLoaded()
        if (preferredSubtitleTrackKind == kind) return
        preferredSubtitleTrackKind = kind
        publish()
        PlayerSettingsStorage.savePreferredSubtitleTrackKind(kind.storageValue)
    }

    /**
     * The track kind, or its two predecessors folded into one. Until 1.14 "forced" was a language
     * — a value of the preferred (or secondary) subtitle language dropdown, plus a "Use Forced
     * Subtitles" switch that substituted it — and SDH was a separate switch, so a store written by
     * an older build has no kind but may have any of those three.
     *
     * A forced *language* leaves the language slot with nothing in it. The secondary was what
     * supplied the real language in that configuration, so it is promoted; failing that the
     * device language is the least surprising stand-in, since nobody who picked Forced wanted
     * subtitles off. The migrated values are written back so this runs once.
     */
    private fun loadOrMigrateSubtitleTrackKind(): SubtitleTrackKind {
        SubtitleTrackKind.fromStorage(PlayerSettingsStorage.loadPreferredSubtitleTrackKind())
            ?.let { return it }

        val forcedLanguage = preferredSubtitleLanguage == SubtitleLanguageOption.FORCED
        val forcedSecondary = secondaryPreferredSubtitleLanguage == SubtitleLanguageOption.FORCED
        val kind = when {
            forcedLanguage || forcedSecondary ||
                PlayerSettingsStorage.loadLegacySubtitleUseForcedSubtitles() == true -> SubtitleTrackKind.FORCED
            PlayerSettingsStorage.loadLegacyPreferHearingImpairedSubtitles() == true -> SubtitleTrackKind.SDH
            else -> SubtitleTrackKind.DEFAULT
        }
        if (forcedLanguage) {
            preferredSubtitleLanguage = secondaryPreferredSubtitleLanguage
                ?.takeUnless { it == SubtitleLanguageOption.FORCED }
                ?: SubtitleLanguageOption.DEVICE
            secondaryPreferredSubtitleLanguage = null
            PlayerSettingsStorage.savePreferredSubtitleLanguage(preferredSubtitleLanguage)
            PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(null)
        } else if (forcedSecondary) {
            secondaryPreferredSubtitleLanguage = null
            PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(null)
        }
        PlayerSettingsStorage.savePreferredSubtitleTrackKind(kind.storageValue)
        return kind
    }

    fun setSubtitleStyle(style: SubtitleStyleState) {
        ensureLoaded()
        if (subtitleStyle == style) return
        subtitleStyle = style
        publish()
        PlayerSettingsStorage.saveSubtitleTextColor(style.textColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleBackgroundColor(style.backgroundColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineColor(style.outlineColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineEnabled(style.outlineEnabled)
        PlayerSettingsStorage.saveSubtitleShadowEnabled(style.shadowEnabled)
        PlayerSettingsStorage.saveSubtitleShadowColor(style.shadowColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleShadowOffset(style.shadowOffset)
        PlayerSettingsStorage.saveSubtitleBlur(style.blur)
        PlayerSettingsStorage.saveSubtitleOutlineWidth(style.outlineWidth)
        PlayerSettingsStorage.saveSubtitleBold(style.bold)
        PlayerSettingsStorage.saveSubtitleItalic(style.italic)
        PlayerSettingsStorage.saveSubtitleFontSizeSp(style.fontSizeSp)
        PlayerSettingsStorage.saveSubtitleBottomOffset(style.bottomOffset)
        PlayerSettingsStorage.saveSubtitleFontFamily(style.fontFamily)
        PlayerSettingsStorage.saveSubtitleAssStyleMode(style.assStyleMode.name)
        PlayerSettingsStorage.saveSubtitleAssScalePercent(style.assScalePercent)
        PlayerSettingsStorage.saveSubtitleShowOnlyPreferredLanguages(style.showOnlyPreferredLanguages)
    }

    fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        ensureLoaded()
        if (addonSubtitleStartupMode == mode) return
        addonSubtitleStartupMode = mode
        publish()
        PlayerSettingsStorage.saveAddonSubtitleStartupMode(mode.name)
    }

    fun setPreferAddonSubtitles(enabled: Boolean) {
        ensureLoaded()
        if (preferAddonSubtitles == enabled) return
        preferAddonSubtitles = enabled
        publish()
        PlayerSettingsStorage.savePreferAddonSubtitles(enabled)
    }

    fun setRejectedSubtitleKeywords(keywords: Set<SubtitleRejectKeyword>) {
        ensureLoaded()
        if (rejectedSubtitleKeywords == keywords) return
        rejectedSubtitleKeywords = keywords
        publish()
        PlayerSettingsStorage.saveRejectedSubtitleKeywords(keywords.map { it.storageValue }.toSet())
    }

    fun setRejectedAudioKeywords(keywords: Set<AudioRejectKeyword>) {
        ensureLoaded()
        if (rejectedAudioKeywords == keywords) return
        rejectedAudioKeywords = keywords
        publish()
        PlayerSettingsStorage.saveRejectedAudioKeywords(keywords.map { it.storageValue }.toSet())
    }

    fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamReuseLastLinkEnabled == enabled) return
        streamReuseLastLinkEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkEnabled(enabled)
    }

    fun setStreamReuseLastLinkCacheHours(hours: Int) {
        ensureLoaded()
        if (streamReuseLastLinkCacheHours == hours) return
        streamReuseLastLinkCacheHours = hours
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkCacheHours(hours)
    }

    fun setStreamPrefetchScope(scope: StreamPrefetchScope) {
        ensureLoaded()
        if (streamPrefetchScope == scope) return
        streamPrefetchScope = scope
        publish()
        PlayerSettingsStorage.saveStreamPrefetchScope(scope.name)
        // Turning prefetching down must not leave results the user has opted out of still being
        // served on their next play.
        if (!scope.isEnabled) StreamPrefetchCache.clear()
    }

    fun setStreamPrefetchCacheMinutes(minutes: Int) {
        ensureLoaded()
        if (streamPrefetchCacheMinutes == minutes) return
        streamPrefetchCacheMinutes = minutes
        publish()
        PlayerSettingsStorage.saveStreamPrefetchCacheMinutes(minutes)
    }

    fun setStreamPrefetchResolveLinks(enabled: Boolean) {
        ensureLoaded()
        if (streamPrefetchResolveLinks == enabled) return
        streamPrefetchResolveLinks = enabled
        publish()
        PlayerSettingsStorage.saveStreamPrefetchResolveLinks(enabled)
    }

    fun setDecoderPriority(priority: Int) {
        ensureLoaded()
        if (decoderPriority == priority) return
        decoderPriority = priority
        publish()
        PlayerSettingsStorage.saveDecoderPriority(priority)
    }

    fun setNvidiaRtxSuperResolutionEnabled(enabled: Boolean) {
        ensureLoaded()
        if (nvidiaRtxSuperResolutionEnabled == enabled) return
        nvidiaRtxSuperResolutionEnabled = enabled
        publish()
        PlayerSettingsStorage.saveNvidiaRtxSuperResolutionEnabled(enabled)
    }

    fun setNvidiaRtxHdrEnabled(enabled: Boolean) {
        ensureLoaded()
        if (nvidiaRtxHdrEnabled == enabled) return
        nvidiaRtxHdrEnabled = enabled
        publish()
        PlayerSettingsStorage.saveNvidiaRtxHdrEnabled(enabled)
    }

    fun setMapDV7ToHevc(enabled: Boolean) {
        ensureLoaded()
        if (mapDV7ToHevc == enabled) return
        mapDV7ToHevc = enabled
        publish()
        PlayerSettingsStorage.saveMapDV7ToHevc(enabled)
    }

    fun setTunnelingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tunnelingEnabled == enabled) return
        tunnelingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTunnelingEnabled(enabled)
    }

    fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        ensureLoaded()
        if (streamAutoPlayMode == mode) return
        streamAutoPlayMode = mode
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayMode(mode.name)
    }

    fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        ensureLoaded()
        val normalizedSource = normalizeStreamAutoPlaySource(source)
        if (streamAutoPlaySource == normalizedSource) return
        streamAutoPlaySource = normalizedSource
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
    }

    fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        ensureLoaded()
        if (streamAutoPlaySelectedAddons == addons) return
        streamAutoPlaySelectedAddons = addons
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(addons)
    }

    fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        ensureLoaded()
        val normalizedPlugins = if (AppFeaturePolicy.pluginsEnabled) plugins else emptySet()
        if (streamAutoPlaySelectedPlugins == normalizedPlugins) return
        streamAutoPlaySelectedPlugins = normalizedPlugins
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(normalizedPlugins)
    }

    fun setStreamAutoPlayRegex(regex: String) {
        ensureLoaded()
        if (streamAutoPlayRegex == regex) return
        streamAutoPlayRegex = regex
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayRegex(regex)
    }

    fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        ensureLoaded()
        if (streamAutoPlayTimeoutSeconds == seconds) return
        streamAutoPlayTimeoutSeconds = seconds
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(seconds)
    }

    fun setSkipIntroEnabled(enabled: Boolean) {
        ensureLoaded()
        if (skipIntroEnabled == enabled) return
        skipIntroEnabled = enabled
        publish()
        PlayerSettingsStorage.saveSkipIntroEnabled(enabled)
    }

    fun setSkipAutoAcceptMode(mode: SkipAutoAcceptMode) {
        ensureLoaded()
        if (skipAutoAcceptMode == mode) return
        skipAutoAcceptMode = mode
        publish()
        PlayerSettingsStorage.saveSkipAutoAcceptMode(mode.name)
    }

    fun setSkipMovieCreditsToPostCredits(enabled: Boolean) {
        ensureLoaded()
        if (skipMovieCreditsToPostCredits == enabled) return
        skipMovieCreditsToPostCredits = enabled
        publish()
        PlayerSettingsStorage.saveSkipMovieCreditsToPostCredits(enabled)
    }

    fun setStripSdhSubtitles(enabled: Boolean) {
        ensureLoaded()
        if (stripSdhSubtitles == enabled) return
        stripSdhSubtitles = enabled
        publish()
        PlayerSettingsStorage.saveStripSdhSubtitles(enabled)
    }

    fun setAnimeSkipEnabled(enabled: Boolean) {
        ensureLoaded()
        if (animeSkipEnabled == enabled) return
        animeSkipEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAnimeSkipEnabled(enabled)
    }

    fun setAnimeSkipClientId(clientId: String) {
        ensureLoaded()
        if (animeSkipClientId == clientId) return
        animeSkipClientId = clientId
        publish()
        PlayerSettingsStorage.saveAnimeSkipClientId(clientId)
    }

    fun setIntroDbApiKey(apiKey: String) {
        ensureLoaded()
        if (introDbApiKey == apiKey) return
        introDbApiKey = apiKey
        publish()
        PlayerSettingsStorage.saveIntroDbApiKey(apiKey)
    }

    fun setSeekrApiKey(apiKey: String) {
        ensureLoaded()
        val normalized = apiKey.trim()
        if (seekrApiKey == normalized) return
        seekrApiKey = normalized
        publish()
        PlayerSettingsStorage.saveSeekrApiKey(normalized)
    }

    fun setSkipDbApiKey(apiKey: String) {
        ensureLoaded()
        if (skipDbApiKey == apiKey) return
        skipDbApiKey = apiKey
        publish()
        PlayerSettingsStorage.saveSkipDbApiKey(apiKey)
    }

    fun setIntroSubmitEnabled(enabled: Boolean) {
        ensureLoaded()
        if (introSubmitEnabled == enabled) return
        introSubmitEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIntroSubmitEnabled(enabled)
    }

    fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayNextEpisodeEnabled == enabled) return
        streamAutoPlayNextEpisodeEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    fun setStreamFailoverEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamFailoverEnabled == enabled) return
        streamFailoverEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamFailoverEnabled(enabled)
    }

    fun setStreamFailoverTimeoutSeconds(seconds: Int) {
        ensureLoaded()
        val snapped = if (seconds in STREAM_FAILOVER_TIMEOUT_VALUES) seconds else STREAM_FAILOVER_DEFAULT_TIMEOUT_SECONDS
        if (streamFailoverTimeoutSeconds == snapped) return
        streamFailoverTimeoutSeconds = snapped
        publish()
        PlayerSettingsStorage.saveStreamFailoverTimeoutSeconds(snapped)
    }

    fun setStreamAutoPlayManualNextEpisode(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayManualNextEpisode == enabled) return
        streamAutoPlayManualNextEpisode = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayManualNextEpisode(enabled)
    }

    fun setStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayPreferBingeGroup == enabled) return
        streamAutoPlayPreferBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayPreferBingeGroup(enabled)
    }

    fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayReuseBingeGroup == enabled) return
        streamAutoPlayReuseBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayReuseBingeGroup(enabled)
    }

    fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        ensureLoaded()
        if (nextEpisodeThresholdMode == mode) return
        nextEpisodeThresholdMode = mode
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMode(mode.name)
    }

    fun setNextEpisodeThresholdPercent(percent: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdPercent == percent) return
        nextEpisodeThresholdPercent = percent
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdPercent(percent)
    }

    fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdMinutesBeforeEnd == minutes) return
        nextEpisodeThresholdMinutesBeforeEnd = minutes
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    fun setUseLibass(enabled: Boolean) {
        ensureLoaded()
        if (useLibass == enabled) return
        useLibass = enabled
        publish()
        PlayerSettingsStorage.saveUseLibass(enabled)
    }

    fun setLibassRenderType(renderType: String) {
        ensureLoaded()
        if (libassRenderType == renderType) return
        libassRenderType = renderType
        publish()
        PlayerSettingsStorage.saveLibassRenderType(renderType)
    }

    fun setIosVideoOutputPreset(preset: IosVideoOutputPreset) {
        ensureLoaded()
        iosVideoOutputPreset = preset
        when (preset) {
            IosVideoOutputPreset.NativeEdr -> {
                iosExtendedDynamicRangeEnabled = true
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.SdrToneMapped -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = false
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Bt2390
                iosTargetPrimaries = IosTargetPrimaries.Bt709
                iosTargetTransfer = IosTargetTransfer.Srgb
            }
            IosVideoOutputPreset.Compatibility -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = false
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.Custom -> Unit
        }
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosToneMappingMode(mode: IosToneMappingMode) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosToneMappingMode = mode
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetPrimaries(primaries: IosTargetPrimaries) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetPrimaries = primaries
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetTransfer(transfer: IosTargetTransfer) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetTransfer = transfer
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHardwareDecoderMode(mode: IosHardwareDecoderMode) {
        ensureLoaded()
        iosHardwareDecoderMode = mode
        publish()
        PlayerSettingsStorage.saveIosHardwareDecoderMode(mode.name)
    }

    fun setIosAudioOutputMode(mode: IosAudioOutputMode) {
        ensureLoaded()
        iosAudioOutputMode = mode
        publish()
        PlayerSettingsStorage.saveIosAudioOutputMode(mode.name)
    }

    fun setIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosExtendedDynamicRangeEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetColorspaceHintEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetColorspaceHintEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHdrComputePeakEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosHdrComputePeakEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosDebandEnabled(enabled: Boolean) {
        ensureLoaded()
        iosDebandEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosDebandEnabled(enabled)
    }

    fun setIosInterpolationEnabled(enabled: Boolean) {
        ensureLoaded()
        iosInterpolationEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosInterpolationEnabled(enabled)
    }

    fun setIosBrightness(value: Int) {
        ensureLoaded()
        iosBrightness = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosBrightness(iosBrightness)
    }

    fun setIosContrast(value: Int) {
        ensureLoaded()
        iosContrast = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosContrast(iosContrast)
    }

    fun setIosSaturation(value: Int) {
        ensureLoaded()
        iosSaturation = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosSaturation(iosSaturation)
    }

    fun setIosGamma(value: Int) {
        ensureLoaded()
        iosGamma = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosGamma(iosGamma)
    }

    fun resetIosVideoOutputTuning() {
        ensureLoaded()
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        publish()
        PlayerSettingsStorage.saveIosBrightness(0)
        PlayerSettingsStorage.saveIosContrast(0)
        PlayerSettingsStorage.saveIosSaturation(0)
        PlayerSettingsStorage.saveIosGamma(0)
        PlayerSettingsStorage.saveIosDebandEnabled(false)
        PlayerSettingsStorage.saveIosInterpolationEnabled(false)
    }

    private fun saveIosVideoOutputSettings() {
        PlayerSettingsStorage.saveIosVideoOutputPreset(iosVideoOutputPreset.name)
        PlayerSettingsStorage.saveIosToneMappingMode(iosToneMappingMode.name)
        PlayerSettingsStorage.saveIosTargetPrimaries(iosTargetPrimaries.name)
        PlayerSettingsStorage.saveIosTargetTransfer(iosTargetTransfer.name)
        PlayerSettingsStorage.saveIosExtendedDynamicRangeEnabled(iosExtendedDynamicRangeEnabled)
        PlayerSettingsStorage.saveIosTargetColorspaceHintEnabled(iosTargetColorspaceHintEnabled)
        PlayerSettingsStorage.saveIosHdrComputePeakEnabled(iosHdrComputePeakEnabled)
    }

    private fun publish() {
        _uiState.value = PlayerSettingsUiState(
            showLoadingOverlay = showLoadingOverlay,
            resizeMode = resizeMode,
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            mouseMoveRevealsControlsEnabled = mouseMoveRevealsControlsEnabled,
            desktopLegacyHudEnabled = desktopLegacyHudEnabled,
            desktopMinimalHudEnabled = desktopMinimalHudEnabled,
            desktopUltraHudEnabled = desktopUltraHudEnabled,
            desktopOfficialHudEnabled = desktopOfficialHudEnabled,
            desktopMinimalHudPillsEnabled = desktopMinimalHudPillsEnabled,
            desktopSeekHandleEnabled = desktopSeekHandleEnabled,
            desktopHudVignetteEnabled = desktopHudVignetteEnabled,
            desktopAlwaysShowClockEnabled = desktopAlwaysShowClockEnabled,
            desktopPauseOverlaySourceEnabled = desktopPauseOverlaySourceEnabled,
            desktopPlaybackSpeedFineIncrementsEnabled = desktopPlaybackSpeedFineIncrementsEnabled,
            playbackSpeedToggleLow = playbackSpeedToggleLow,
            playbackSpeedToggleHigh = playbackSpeedToggleHigh,
            desktopVerboseMpvLoggingEnabled = desktopVerboseMpvLoggingEnabled,
            desktopUiScalePercent = desktopUiScalePercent,
            desktopControlIconScalePercent = desktopControlIconScalePercent,
            seekStepSeconds = seekStepSeconds,
            desktopSourceNotchPosition = desktopSourceNotchPosition,
            desktopSourceNotchHoverEnabled = desktopSourceNotchHoverEnabled,
            desktopPlayerNotificationPosition = desktopPlayerNotificationPosition,
            externalPlayerEnabled = externalPlayerEnabled,
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            externalPlayerId = externalPlayerId,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            dualSubtitlesEnabled = dualSubtitlesEnabled,
            preferredSubtitleTrackKind = preferredSubtitleTrackKind,
            subtitleStyle = subtitleStyle,
            addonSubtitleStartupMode = addonSubtitleStartupMode,
            preferAddonSubtitles = preferAddonSubtitles,
            rejectedSubtitleKeywords = rejectedSubtitleKeywords,
            rejectedAudioKeywords = rejectedAudioKeywords,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamPrefetchScope = streamPrefetchScope,
            streamPrefetchCacheMinutes = streamPrefetchCacheMinutes,
            streamPrefetchResolveLinks = streamPrefetchResolveLinks,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
            streamAutoPlayMode = streamAutoPlayMode,
            streamAutoPlaySource = streamAutoPlaySource,
            streamAutoPlaySelectedAddons = streamAutoPlaySelectedAddons,
            streamAutoPlaySelectedPlugins = streamAutoPlaySelectedPlugins,
            streamAutoPlayRegex = streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds,
            skipIntroEnabled = skipIntroEnabled,
            skipAutoAcceptMode = skipAutoAcceptMode,
            skipMovieCreditsToPostCredits = skipMovieCreditsToPostCredits,
            stripSdhSubtitles = stripSdhSubtitles,
            animeSkipEnabled = animeSkipEnabled,
            animeSkipClientId = animeSkipClientId,
            introDbApiKey = introDbApiKey,
            seekrApiKey = seekrApiKey,
            skipDbApiKey = skipDbApiKey,
            introSubmitEnabled = introSubmitEnabled,
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayManualNextEpisode = streamAutoPlayManualNextEpisode,
            streamAutoPlayPreferBingeGroup = streamAutoPlayPreferBingeGroup,
            streamAutoPlayReuseBingeGroup = streamAutoPlayReuseBingeGroup,
            streamFailoverEnabled = streamFailoverEnabled,
            streamFailoverTimeoutSeconds = streamFailoverTimeoutSeconds,
            nextEpisodeThresholdMode = nextEpisodeThresholdMode,
            nextEpisodeThresholdPercent = nextEpisodeThresholdPercent,
            nextEpisodeThresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEnd,
            useLibass = useLibass,
            libassRenderType = libassRenderType,
            iosVideoOutputPreset = iosVideoOutputPreset,
            iosToneMappingMode = iosToneMappingMode,
            iosTargetPrimaries = iosTargetPrimaries,
            iosTargetTransfer = iosTargetTransfer,
            iosHardwareDecoderMode = iosHardwareDecoderMode,
            iosAudioOutputMode = iosAudioOutputMode,
            iosExtendedDynamicRangeEnabled = iosExtendedDynamicRangeEnabled,
            iosTargetColorspaceHintEnabled = iosTargetColorspaceHintEnabled,
            iosHdrComputePeakEnabled = iosHdrComputePeakEnabled,
            iosDebandEnabled = iosDebandEnabled,
            iosInterpolationEnabled = iosInterpolationEnabled,
            iosBrightness = iosBrightness,
            iosContrast = iosContrast,
            iosSaturation = iosSaturation,
            iosGamma = iosGamma,
            desktopHdrMode = desktopHdrMode,
            desktopColorProfile = desktopColorProfile,
            desktopColorContrast = desktopColorContrast,
            desktopColorBrightness = desktopColorBrightness,
            desktopColorSaturation = desktopColorSaturation,
            desktopColorGamma = desktopColorGamma,
            desktopBufferPreset = desktopBufferPreset,
            desktopRendererApi = desktopRendererApi,
            desktopPerformanceLoggingEnabled = desktopPerformanceLoggingEnabled,
            desktopLowVramMode = desktopLowVramMode,
            desktopAnimeMode = desktopAnimeMode,
            desktopAnimeModeAutoEnabled = desktopAnimeModeAutoEnabled,
            desktopAnimeTreatAnimationAsAnime = desktopAnimeTreatAnimationAsAnime,
            desktopAnimeSkipUltraHdEnabled = desktopAnimeSkipUltraHdEnabled,
            desktopAnimeSvpEnabled = desktopAnimeSvpEnabled,
            desktopAnimeSvpDebugOverlayEnabled = desktopAnimeSvpDebugOverlayEnabled,
            desktopPlaybackInfoPanelEnabled = desktopPlaybackInfoPanelEnabled,
            desktopAnimeSessionOverride = desktopAnimeSessionOverride,
            desktopAnimeSvpSessionForced = desktopAnimeSvpSessionForced,
            desktopCustomShaderPaths = desktopCustomShaderPaths,
            desktopCustomShaderSelectedPath = desktopCustomShaderSelectedPath,
            desktopAudioPassthroughEnabled = desktopAudioPassthroughEnabled,
            desktopSeekThumbnailMode = desktopSeekThumbnailMode,
            desktopRateLimitRecoveryMode = desktopRateLimitRecoveryMode,
            desktopRateLimitReconnectFirstDelaySeconds = desktopRateLimitReconnectFirstDelaySeconds,
            desktopRateLimitReconnectSecondDelaySeconds = desktopRateLimitReconnectSecondDelaySeconds,
            desktopMpvConfigMode = desktopMpvConfigMode,
            desktopCustomMpvOptions = desktopCustomMpvOptions,
            desktopMpvPropertyOverrides = desktopMpvPropertyOverrides,
            heroTvTrailerEnabled = heroTvTrailerEnabled,
            heroTvTrailerDelaySeconds = heroTvTrailerDelaySeconds,
            heroTvTrailerSoundEnabled = heroTvTrailerSoundEnabled,
            heroTvTrailerFullscreen = heroTvTrailerFullscreen,
            heroTvTrailerSearchEnabled = heroTvTrailerSearchEnabled,
        )
    }

    fun setDesktopHdrMode(mode: DesktopHdrMode) {
        ensureLoaded()
        if (desktopHdrMode == mode) return
        desktopHdrMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopHdrMode(mode.name)
    }

    fun setDesktopColorProfile(profile: DesktopColorProfile) {
        ensureLoaded()
        if (desktopColorProfile == profile) return
        desktopColorProfile = profile
        publish()
        PlayerSettingsStorage.saveDesktopColorProfile(profile.name)
    }

    /**
     * The equalizer offsets behind [DesktopColorProfile.Custom]. Clamped to the same +/-50 the iOS
     * tuning sliders use: mpv accepts +/-100, but past 50 the image is destroyed rather than graded.
     */
    fun setDesktopColorContrast(value: Int) {
        ensureLoaded()
        val clamped = value.coerceIn(DESKTOP_COLOR_OFFSET_RANGE.first, DESKTOP_COLOR_OFFSET_RANGE.last)
        if (desktopColorContrast == clamped) return
        desktopColorContrast = clamped
        publish()
        PlayerSettingsStorage.saveDesktopColorContrast(clamped)
    }

    fun setDesktopColorBrightness(value: Int) {
        ensureLoaded()
        val clamped = value.coerceIn(DESKTOP_COLOR_OFFSET_RANGE.first, DESKTOP_COLOR_OFFSET_RANGE.last)
        if (desktopColorBrightness == clamped) return
        desktopColorBrightness = clamped
        publish()
        PlayerSettingsStorage.saveDesktopColorBrightness(clamped)
    }

    fun setDesktopColorSaturation(value: Int) {
        ensureLoaded()
        val clamped = value.coerceIn(DESKTOP_COLOR_OFFSET_RANGE.first, DESKTOP_COLOR_OFFSET_RANGE.last)
        if (desktopColorSaturation == clamped) return
        desktopColorSaturation = clamped
        publish()
        PlayerSettingsStorage.saveDesktopColorSaturation(clamped)
    }

    fun setDesktopColorGamma(value: Int) {
        ensureLoaded()
        val clamped = value.coerceIn(DESKTOP_COLOR_OFFSET_RANGE.first, DESKTOP_COLOR_OFFSET_RANGE.last)
        if (desktopColorGamma == clamped) return
        desktopColorGamma = clamped
        publish()
        PlayerSettingsStorage.saveDesktopColorGamma(clamped)
    }

    /** Returns the Custom grade to neutral without leaving the Custom profile. */
    fun resetDesktopColorTuning() {
        ensureLoaded()
        desktopColorContrast = 0
        desktopColorBrightness = 0
        desktopColorSaturation = 0
        desktopColorGamma = 0
        publish()
        PlayerSettingsStorage.saveDesktopColorContrast(0)
        PlayerSettingsStorage.saveDesktopColorBrightness(0)
        PlayerSettingsStorage.saveDesktopColorSaturation(0)
        PlayerSettingsStorage.saveDesktopColorGamma(0)
    }

    fun setDesktopBufferPreset(preset: DesktopBufferPreset) {
        ensureLoaded()
        if (desktopBufferPreset == preset) return
        desktopBufferPreset = preset
        publish()
        PlayerSettingsStorage.saveDesktopBufferPreset(preset.name)
    }

    /**
     * Frame-budget telemetry. Applies immediately — the probes and the frame-clock loops read the
     * flag as snapshot state — so no restart is needed, unlike the renderer below.
     */
    fun setDesktopPerformanceLogging(enabled: Boolean) {
        ensureLoaded()
        if (desktopPerformanceLoggingEnabled == enabled) return
        desktopPerformanceLoggingEnabled = enabled
        frameBudgetSetProbesEnabled(enabled)
        publish()
        PlayerSettingsStorage.saveDesktopPerformanceLogging(enabled)
    }

    fun setDesktopRendererApi(api: DesktopRendererApi) {
        ensureLoaded()
        if (desktopRendererApi == api) return
        desktopRendererApi = api
        publish()
        PlayerSettingsStorage.saveDesktopRendererApi(api.name)
    }

    /** Takes effect on the next playback: the pipeline it trims is set up before mpv initialises. */
    fun setDesktopLowVramMode(mode: DesktopLowVramMode) {
        ensureLoaded()
        if (desktopLowVramMode == mode) return
        desktopLowVramMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopLowVramMode(mode.name)
    }

    fun setDesktopAnimeMode(mode: DesktopAnimeMode) {
        ensureLoaded()
        if (desktopAnimeMode == mode) return
        desktopAnimeMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopAnimeMode(mode.name)
    }

    fun setDesktopAnimeModeAutoEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAnimeModeAutoEnabled == enabled) return
        desktopAnimeModeAutoEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAnimeModeAutoEnabled(enabled)
    }

    fun setDesktopAnimeTreatAnimationAsAnime(enabled: Boolean) {
        ensureLoaded()
        if (desktopAnimeTreatAnimationAsAnime == enabled) return
        desktopAnimeTreatAnimationAsAnime = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAnimeTreatAnimationAsAnime(enabled)
    }

    fun setDesktopAnimeSkipUltraHdEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAnimeSkipUltraHdEnabled == enabled) return
        desktopAnimeSkipUltraHdEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAnimeSkipUltraHdEnabled(enabled)
    }

    fun setDesktopAnimeSvpEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAnimeSvpEnabled == enabled) return
        desktopAnimeSvpEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAnimeSvpEnabled(enabled)
    }

    fun setDesktopPlaybackInfoPanelEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopPlaybackInfoPanelEnabled == enabled) return
        desktopPlaybackInfoPanelEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopPlaybackInfoPanelEnabled(enabled)
    }

    fun setDesktopAnimeSvpDebugOverlayEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAnimeSvpDebugOverlayEnabled == enabled) return
        desktopAnimeSvpDebugOverlayEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAnimeSvpDebugOverlayEnabled(enabled)
    }

    // Session-only anime state below: published through uiState but deliberately never persisted.
    // The desktop player clears it when the playback surface disposes.

    fun setDesktopAnimeSessionOverride(override: DesktopAnimeSessionOverride) {
        ensureLoaded()
        if (desktopAnimeSessionOverride == override) return
        desktopAnimeSessionOverride = override
        publish()
    }

    fun setDesktopAnimeSvpSessionForced(forced: Boolean) {
        ensureLoaded()
        if (desktopAnimeSvpSessionForced == forced) return
        desktopAnimeSvpSessionForced = forced
        publish()
    }

    fun clearDesktopAnimeSessionState() {
        if (desktopAnimeSessionOverride == null && !desktopAnimeSvpSessionForced) return
        desktopAnimeSessionOverride = null
        desktopAnimeSvpSessionForced = false
        publish()
    }

    fun setDesktopCustomShaderPaths(paths: String) {
        ensureLoaded()
        val normalized = paths.trim()
        if (desktopCustomShaderPaths == normalized) return
        desktopCustomShaderPaths = normalized
        publish()
        PlayerSettingsStorage.saveDesktopCustomShaderPaths(normalized)
    }

    fun setDesktopCustomShaderSelectedPath(path: String) {
        ensureLoaded()
        val normalized = path.trim()
        if (desktopCustomShaderSelectedPath == normalized) return
        desktopCustomShaderSelectedPath = normalized
        publish()
        PlayerSettingsStorage.saveDesktopCustomShaderSelectedPath(normalized)
    }

    fun setDesktopAudioPassthroughEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopAudioPassthroughEnabled == enabled) return
        desktopAudioPassthroughEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopAudioPassthroughEnabled(enabled)
    }

    fun setDesktopSeekThumbnailMode(mode: DesktopSeekThumbnailMode) {
        ensureLoaded()
        if (desktopSeekThumbnailMode == mode) return
        desktopSeekThumbnailMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopSeekThumbnailMode(mode.name)
    }

    fun setDesktopRateLimitRecoveryMode(mode: DesktopRateLimitRecoveryMode) {
        ensureLoaded()
        if (desktopRateLimitRecoveryMode == mode) return
        desktopRateLimitRecoveryMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopRateLimitRecoveryMode(mode.name)
    }

    fun setDesktopRateLimitReconnectFirstDelaySeconds(seconds: Int) {
        ensureLoaded()
        val snapped = seconds.takeIf { it in RATE_LIMIT_RECONNECT_DELAY_VALUES }
            ?: RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS
        if (desktopRateLimitReconnectFirstDelaySeconds == snapped) return
        desktopRateLimitReconnectFirstDelaySeconds = snapped
        publish()
        PlayerSettingsStorage.saveDesktopRateLimitReconnectFirstDelaySeconds(snapped)
    }

    fun setDesktopRateLimitReconnectSecondDelaySeconds(seconds: Int) {
        ensureLoaded()
        val snapped = seconds.takeIf { it in RATE_LIMIT_RECONNECT_DELAY_VALUES }
            ?: RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS
        if (desktopRateLimitReconnectSecondDelaySeconds == snapped) return
        desktopRateLimitReconnectSecondDelaySeconds = snapped
        publish()
        PlayerSettingsStorage.saveDesktopRateLimitReconnectSecondDelaySeconds(snapped)
    }

    fun setDesktopCustomMpvOptions(options: String) {
        ensureLoaded()
        if (desktopCustomMpvOptions == options) return
        desktopCustomMpvOptions = options
        publish()
        PlayerSettingsStorage.saveDesktopCustomMpvOptions(options)
    }

    fun setDesktopMpvConfigMode(mode: DesktopMpvConfigMode) {
        ensureLoaded()
        if (desktopMpvConfigMode == mode) return
        desktopMpvConfigMode = mode
        publish()
        PlayerSettingsStorage.saveDesktopMpvConfigMode(mode.name)
    }

    /** Sets (or, when [value] is null, clears) a single curated mpv property override. */
    fun setDesktopMpvPropertyOverride(key: String, value: String?) {
        ensureLoaded()
        val next = desktopMpvPropertyOverrides.toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        if (next == desktopMpvPropertyOverrides) return
        desktopMpvPropertyOverrides = next
        publish()
        PlayerSettingsStorage.saveDesktopMpvPropertyOverrides(serializeMpvPropertyOverrides(next))
    }

    private fun parseMpvPropertyOverrides(raw: String?): Map<String, String> =
        raw?.lineSequence()
            ?.mapNotNull { line ->
                val trimmed = line.trim()
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                trimmed.substring(0, separator).trim() to trimmed.substring(separator + 1).trim()
            }
            ?.filter { it.first.isNotEmpty() }
            ?.toMap()
            .orEmpty()

    private fun serializeMpvPropertyOverrides(overrides: Map<String, String>): String =
        overrides.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun setHeroTvTrailerEnabled(enabled: Boolean) {
        ensureLoaded()
        if (heroTvTrailerEnabled == enabled) return
        heroTvTrailerEnabled = enabled
        publish()
        PlayerSettingsStorage.saveHeroTvTrailerEnabled(enabled)
    }

    fun setHeroTvTrailerDelaySeconds(seconds: Int) {
        ensureLoaded()
        val normalized = snapToHeroTvTrailerDelay(seconds)
        if (heroTvTrailerDelaySeconds == normalized) return
        heroTvTrailerDelaySeconds = normalized
        publish()
        PlayerSettingsStorage.saveHeroTvTrailerDelaySeconds(normalized)
    }

    fun setHeroTvTrailerSoundEnabled(enabled: Boolean) {
        ensureLoaded()
        if (heroTvTrailerSoundEnabled == enabled) return
        heroTvTrailerSoundEnabled = enabled
        publish()
        PlayerSettingsStorage.saveHeroTvTrailerSoundEnabled(enabled)
    }

    fun setHeroTvTrailerFullscreen(enabled: Boolean) {
        ensureLoaded()
        if (heroTvTrailerFullscreen == enabled) return
        heroTvTrailerFullscreen = enabled
        publish()
        PlayerSettingsStorage.saveHeroTvTrailerFullscreen(enabled)
    }

    fun setHeroTvTrailerSearchEnabled(enabled: Boolean) {
        ensureLoaded()
        if (heroTvTrailerSearchEnabled == enabled) return
        heroTvTrailerSearchEnabled = enabled
        publish()
        PlayerSettingsStorage.saveHeroTvTrailerSearchEnabled(enabled)
    }

    private fun normalizeStreamAutoPlaySource(source: StreamAutoPlaySource): StreamAutoPlaySource {
        return if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.ALL_SOURCES
        } else {
            source
        }
    }
}
