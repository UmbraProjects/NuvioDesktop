package com.nuvio.app.features.player

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

val STREAM_AUTO_PLAY_TIMEOUT_VALUES: List<Int> = listOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, Int.MAX_VALUE
)

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
    val desktopAlwaysShowClockEnabled: Boolean = false,
    // Adds the playing source (release name + provider) as the bottom row of the paused metadata
    // overlay. Off by default: the raw release strings are noisy, and only some viewers want them.
    val desktopPauseOverlaySourceEnabled: Boolean = false,
    // When true, the desktop speed button / speed keyboard shortcuts step by 0.1 instead of
    // jumping between the coarse preset stages (1, 1.25, 1.5, 2, 3, 4).
    val desktopPlaybackSpeedFineIncrementsEnabled: Boolean = false,
    // When true, mpv writes its full verbose log to logs/mpv-verbose.log (via the mpv --log-file
    // option) for troubleshooting, instead of the bridge's normal warnings-only capture.
    val desktopVerboseMpvLoggingEnabled: Boolean = false,
    // Extra user UI-scale for the desktop/legacy player HUD, in percent (-50..50); 0 = unchanged.
    val desktopUiScalePercent: Int = 0,
    val desktopSourceNotchPosition: DesktopSourceNotchPosition = DesktopSourceNotchPosition.Right,
    val externalPlayerEnabled: Boolean = false,
    val externalPlayerForwardSubtitles: Boolean = false,
    val externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId(),
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val dualSubtitlesEnabled: Boolean = false,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    // Track kinds ruled out by name — signs/songs/karaoke/forced subtitles, commentary and
    // audio-description tracks. Rejected tracks are hidden from the player's lists and never
    // selected automatically. See PlayerTrackRejectKeywords.kt.
    val rejectedSubtitleKeywords: Set<SubtitleRejectKeyword> = emptySet(),
    val rejectedAudioKeywords: Set<AudioRejectKeyword> = emptySet(),
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
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
    val animeSkipEnabled: Boolean = false,
    val animeSkipClientId: String = "",
    val introDbApiKey: String = "",
    val skipDbApiKey: String = "",
    val introSubmitEnabled: Boolean = false,
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroup: Boolean = true,
    val streamAutoPlayReuseBingeGroup: Boolean = true,
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
    val desktopBufferPreset: DesktopBufferPreset = DesktopBufferPreset.Balanced,
    val desktopRendererApi: DesktopRendererApi = DesktopRendererApi.OpenGL,
    val desktopAnimeMode: DesktopAnimeMode = DesktopAnimeMode.Off,
    val desktopAnimeModeAutoEnabled: Boolean = false,
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
)

object PlayerSettingsRepository {
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var showLoadingOverlay = true
    private var resizeMode = PlayerResizeMode.Fit
    private var defaultPlaybackSpeed = 1f
    private var mouseMoveRevealsControlsEnabled = true
    private var desktopLegacyHudEnabled = false
    private var desktopAlwaysShowClockEnabled = false
    private var desktopPauseOverlaySourceEnabled = false
    private var desktopPlaybackSpeedFineIncrementsEnabled = false
    private var desktopVerboseMpvLoggingEnabled = false
    private var desktopUiScalePercent = 0
    private var desktopSourceNotchPosition = DesktopSourceNotchPosition.Right
    private var externalPlayerEnabled = false
    private var externalPlayerForwardSubtitles = false
    private var externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId()
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var dualSubtitlesEnabled = false
    private var subtitleStyle = SubtitleStyleState.DEFAULT
    private var addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
    private var rejectedSubtitleKeywords: Set<SubtitleRejectKeyword> = emptySet()
    private var rejectedAudioKeywords: Set<AudioRejectKeyword> = emptySet()
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24
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
    private var animeSkipEnabled = false
    private var animeSkipClientId = ""
    private var introDbApiKey = ""
    private var skipDbApiKey = ""
    private var introSubmitEnabled = false
    private var streamAutoPlayNextEpisodeEnabled = false
    private var streamAutoPlayPreferBingeGroup = true
    private var streamAutoPlayReuseBingeGroup = true
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
    private var desktopBufferPreset = DesktopBufferPreset.Balanced
    private var desktopRendererApi = DesktopRendererApi.OpenGL
    private var desktopAnimeMode = DesktopAnimeMode.Off
    private var desktopAnimeModeAutoEnabled = false
    private var desktopAnimeSvpEnabled = false
    private var desktopAnimeSvpDebugOverlayEnabled = false
    private var desktopPlaybackInfoPanelEnabled = false
    // Session-only state; deliberately has no PlayerSettingsStorage backing.
    private var desktopAnimeSessionOverride: DesktopAnimeSessionOverride? = null
    private var desktopAnimeSvpSessionForced = false
    private var desktopCustomShaderPaths = ""
    private var desktopCustomShaderSelectedPath = ""
    private var desktopAudioPassthroughEnabled = false
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
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        showLoadingOverlay = true
        resizeMode = PlayerResizeMode.Fit
        defaultPlaybackSpeed = 1f
        mouseMoveRevealsControlsEnabled = true
        desktopLegacyHudEnabled = false
        desktopAlwaysShowClockEnabled = false
        desktopPauseOverlaySourceEnabled = false
        desktopPlaybackSpeedFineIncrementsEnabled = false
        desktopVerboseMpvLoggingEnabled = false
        desktopUiScalePercent = 0
        desktopSourceNotchPosition = DesktopSourceNotchPosition.Right
        externalPlayerEnabled = false
        externalPlayerForwardSubtitles = false
        externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage = AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage = null
        preferredSubtitleLanguage = SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage = null
        dualSubtitlesEnabled = false
        subtitleStyle = SubtitleStyleState.DEFAULT
        addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
        rejectedSubtitleKeywords = emptySet()
        rejectedAudioKeywords = emptySet()
        streamReuseLastLinkEnabled = false
        streamReuseLastLinkCacheHours = 24
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
        animeSkipEnabled = false
        animeSkipClientId = ""
        introDbApiKey = ""
        skipDbApiKey = ""
        introSubmitEnabled = false
        streamAutoPlayNextEpisodeEnabled = false
        streamAutoPlayPreferBingeGroup = true
        streamAutoPlayReuseBingeGroup = true
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
        desktopBufferPreset = DesktopBufferPreset.Balanced
        desktopRendererApi = DesktopRendererApi.OpenGL
        desktopAnimeMode = DesktopAnimeMode.Off
        desktopAnimeModeAutoEnabled = false
        desktopAnimeSvpEnabled = false
        desktopAnimeSvpDebugOverlayEnabled = false
        desktopPlaybackInfoPanelEnabled = false
        desktopAnimeSessionOverride = null
        desktopAnimeSvpSessionForced = false
        desktopCustomShaderPaths = ""
        desktopCustomShaderSelectedPath = ""
        desktopAudioPassthroughEnabled = false
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
        desktopAlwaysShowClockEnabled = PlayerSettingsStorage.loadDesktopAlwaysShowClockEnabled() ?: false
        desktopPauseOverlaySourceEnabled = PlayerSettingsStorage.loadDesktopPauseOverlaySourceEnabled() ?: false
        desktopPlaybackSpeedFineIncrementsEnabled =
            PlayerSettingsStorage.loadDesktopPlaybackSpeedFineIncrementsEnabled() ?: false
        desktopVerboseMpvLoggingEnabled = PlayerSettingsStorage.loadDesktopVerboseMpvLoggingEnabled() ?: false
        desktopUiScalePercent = (PlayerSettingsStorage.loadDesktopUiScalePercent() ?: 0).coerceIn(-50, 50)
        desktopSourceNotchPosition = DesktopSourceNotchPosition.fromStorage(
            PlayerSettingsStorage.loadDesktopSourceNotchPosition(),
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
            useForcedSubtitles = PlayerSettingsStorage.loadSubtitleUseForcedSubtitles()
                ?: SubtitleStyleState.DEFAULT.useForcedSubtitles,
            showOnlyPreferredLanguages = PlayerSettingsStorage.loadSubtitleShowOnlyPreferredLanguages()
                ?: SubtitleStyleState.DEFAULT.showOnlyPreferredLanguages,
        )
        addonSubtitleStartupMode = PlayerSettingsStorage.loadAddonSubtitleStartupMode()
            ?.let { runCatching { AddonSubtitleStartupMode.valueOf(it) }.getOrNull() }
            ?: AddonSubtitleStartupMode.ALL_SUBTITLES
        rejectedSubtitleKeywords =
            parseSubtitleRejectKeywords(PlayerSettingsStorage.loadRejectedSubtitleKeywords())
        rejectedAudioKeywords =
            parseAudioRejectKeywords(PlayerSettingsStorage.loadRejectedAudioKeywords())
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
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
        animeSkipEnabled = PlayerSettingsStorage.loadAnimeSkipEnabled() ?: false
        animeSkipClientId = PlayerSettingsStorage.loadAnimeSkipClientId() ?: ""
        introDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey() ?: ""
        skipDbApiKey = PlayerSettingsStorage.loadSkipDbApiKey() ?: ""
        introSubmitEnabled = PlayerSettingsStorage.loadIntroSubmitEnabled() ?: false
        streamAutoPlayNextEpisodeEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeEnabled() ?: false
        streamAutoPlayPreferBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayPreferBingeGroup() ?: true
        streamAutoPlayReuseBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayReuseBingeGroup() ?: true
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
        desktopBufferPreset = PlayerSettingsStorage.loadDesktopBufferPreset()
            ?.let { runCatching { DesktopBufferPreset.valueOf(it) }.getOrNull() }
            ?: DesktopBufferPreset.Balanced
        PlayerSettingsStorage.saveDesktopBufferPreset(desktopBufferPreset.name)
        desktopRendererApi = PlayerSettingsStorage.loadDesktopRendererApi()
            ?.let { runCatching { DesktopRendererApi.valueOf(it) }.getOrNull() }
            ?: DesktopRendererApi.OpenGL
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

    fun setDesktopLegacyHudEnabled(enabled: Boolean) {
        ensureLoaded()
        if (desktopLegacyHudEnabled == enabled) return
        desktopLegacyHudEnabled = enabled
        publish()
        PlayerSettingsStorage.saveDesktopLegacyHudEnabled(enabled)
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

    fun setDesktopSourceNotchPosition(position: DesktopSourceNotchPosition) {
        ensureLoaded()
        if (desktopSourceNotchPosition == position) return
        desktopSourceNotchPosition = position
        publish()
        PlayerSettingsStorage.saveDesktopSourceNotchPosition(position.name)
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
        PlayerSettingsStorage.saveSubtitleUseForcedSubtitles(style.useForcedSubtitles)
        PlayerSettingsStorage.saveSubtitleShowOnlyPreferredLanguages(style.showOnlyPreferredLanguages)
    }

    fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        ensureLoaded()
        if (addonSubtitleStartupMode == mode) return
        addonSubtitleStartupMode = mode
        publish()
        PlayerSettingsStorage.saveAddonSubtitleStartupMode(mode.name)
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
            desktopAlwaysShowClockEnabled = desktopAlwaysShowClockEnabled,
            desktopPauseOverlaySourceEnabled = desktopPauseOverlaySourceEnabled,
            desktopPlaybackSpeedFineIncrementsEnabled = desktopPlaybackSpeedFineIncrementsEnabled,
            desktopVerboseMpvLoggingEnabled = desktopVerboseMpvLoggingEnabled,
            desktopUiScalePercent = desktopUiScalePercent,
            desktopSourceNotchPosition = desktopSourceNotchPosition,
            externalPlayerEnabled = externalPlayerEnabled,
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            externalPlayerId = externalPlayerId,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            dualSubtitlesEnabled = dualSubtitlesEnabled,
            subtitleStyle = subtitleStyle,
            addonSubtitleStartupMode = addonSubtitleStartupMode,
            rejectedSubtitleKeywords = rejectedSubtitleKeywords,
            rejectedAudioKeywords = rejectedAudioKeywords,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
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
            animeSkipEnabled = animeSkipEnabled,
            animeSkipClientId = animeSkipClientId,
            introDbApiKey = introDbApiKey,
            skipDbApiKey = skipDbApiKey,
            introSubmitEnabled = introSubmitEnabled,
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
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
            desktopBufferPreset = desktopBufferPreset,
            desktopRendererApi = desktopRendererApi,
            desktopAnimeMode = desktopAnimeMode,
            desktopAnimeModeAutoEnabled = desktopAnimeModeAutoEnabled,
            desktopAnimeSvpEnabled = desktopAnimeSvpEnabled,
            desktopAnimeSvpDebugOverlayEnabled = desktopAnimeSvpDebugOverlayEnabled,
            desktopPlaybackInfoPanelEnabled = desktopPlaybackInfoPanelEnabled,
            desktopAnimeSessionOverride = desktopAnimeSessionOverride,
            desktopAnimeSvpSessionForced = desktopAnimeSvpSessionForced,
            desktopCustomShaderPaths = desktopCustomShaderPaths,
            desktopCustomShaderSelectedPath = desktopCustomShaderSelectedPath,
            desktopAudioPassthroughEnabled = desktopAudioPassthroughEnabled,
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

    fun setDesktopBufferPreset(preset: DesktopBufferPreset) {
        ensureLoaded()
        if (desktopBufferPreset == preset) return
        desktopBufferPreset = preset
        publish()
        PlayerSettingsStorage.saveDesktopBufferPreset(preset.name)
    }

    fun setDesktopRendererApi(api: DesktopRendererApi) {
        ensureLoaded()
        if (desktopRendererApi == api) return
        desktopRendererApi = api
        publish()
        PlayerSettingsStorage.saveDesktopRendererApi(api.name)
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
