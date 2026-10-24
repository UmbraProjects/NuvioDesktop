package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_ios_hardware_decoder_off
import nuvio.composeapp.generated.resources.player_ios_preset_compatibility_desc
import nuvio.composeapp.generated.resources.player_ios_preset_compatibility_label
import nuvio.composeapp.generated.resources.player_ios_preset_custom_desc
import nuvio.composeapp.generated.resources.player_ios_preset_custom_label
import nuvio.composeapp.generated.resources.player_ios_preset_native_edr_desc
import nuvio.composeapp.generated.resources.player_ios_preset_native_edr_label
import nuvio.composeapp.generated.resources.player_ios_preset_sdr_tone_mapped_desc
import nuvio.composeapp.generated.resources.player_ios_preset_sdr_tone_mapped_label
import org.jetbrains.compose.resources.stringResource

@Serializable
data class PlayerRoute(
    val launchId: Long,
)

enum class PlayerSourceAffinity {
    Local,
    Stream;

    companion object {
        fun fromInitialStreamType(streamType: String?): PlayerSourceAffinity =
            if (streamType.equals("local", ignoreCase = true)) Local else Stream
    }
}

data class PlayerLaunch(
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val sourceAffinity: PlayerSourceAffinity = PlayerSourceAffinity.fromInitialStreamType(streamType),
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    // Canonical metadata year, or a best-effort filename parse for metadata-less direct playback.
    // It disambiguates artwork lookup and library download names.
    val releaseYear: Int? = null,
    val streamTitle: String,
    val streamFilename: String? = null,
    val streamSubtitle: String? = null,
    val sourceIdentityKey: String? = null,
    val bingeGroup: String? = null,
    val pauseDescription: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val contentType: String? = null,
    val videoId: String? = null,
    val parentMetaId: String,
    val parentMetaType: String,
    val watchProgressSource: String? = null,
    val torrentInfoHash: String? = null,
    val torrentFileIdx: Int? = null,
    val torrentFilename: String? = null,
    val torrentTrackers: List<String> = emptyList(),
    val initialPositionMs: Long = 0L,
    val initialProgressFraction: Float? = null,
    val disableProgressTracking: Boolean = false,
    val autoPlayMode: PlayerAutoPlayMode = PlayerAutoPlayMode.NextEpisode,
)

enum class PlayerAutoPlayMode {
    NextEpisode,
    RandomEpisode,
    /**
     * Playing an entry of a playlist (see `PlaylistPlaybackSession`). Next-episode binge is off: the
     * end of the file, or the up-next card, moves on to the playlist's next entry instead.
     */
    Playlist,
}

object PlayerLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, PlayerLaunch>()

    fun put(launch: PlayerLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): PlayerLaunch? = launches[launchId]

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }
}

enum class PlayerResizeMode {
    Fit,
    Fill,
    Zoom,
}

enum class IosVideoOutputPreset(
    val label: String,
    val description: String,
) {
    NativeEdr(
        label = "Native EDR",
        description = "Best for HDR-capable iPhones and iPads.",
    ),
    SdrToneMapped(
        label = "SDR tone mapped",
        description = "More predictable whites and blacks on SDR-style output.",
    ),
    Compatibility(
        label = "Compatibility",
        description = "Closest to the older iOS MPV behavior.",
    ),
    Custom(
        label = "Custom",
        description = "Use your advanced values below.",
    ),
}

enum class IosToneMappingMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt2390("bt.2390", "BT.2390"),
    Mobius("mobius", "Mobius"),
    Reinhard("reinhard", "Reinhard"),
    Hable("hable", "Hable"),
    Gamma("gamma", "Gamma"),
    Clip("clip", "Clip"),
}

enum class IosTargetPrimaries(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt709("bt.709", "BT.709"),
    DisplayP3("display-p3", "Display P3"),
    Bt2020("bt.2020", "BT.2020"),
}

enum class IosTargetTransfer(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Srgb("srgb", "sRGB"),
    Bt1886("bt.1886", "BT.1886"),
    Gamma22("gamma2.2", "Gamma 2.2"),
    Gamma24("gamma2.4", "Gamma 2.4"),
    Pq("pq", "PQ"),
    Hlg("hlg", "HLG"),
}

enum class IosHardwareDecoderMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    VideoToolbox("videotoolbox", "VideoToolbox"),
    Off("no", "Off"),
}

enum class IosAudioOutputMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("avfoundation,audiounit,", "Auto"),
    AvFoundation("avfoundation", "AVFoundation"),
    AudioUnit("audiounit", "AudioUnit"),
}

enum class DesktopHdrMode(val label: String, val description: String) {
    Auto("Auto", "Let the OS decide between passthrough and tonemapping based on your display."),
    AlwaysTonemap("Always Tonemap", "Force HDR content to be tonemapped to SDR, even on HDR displays."),
    AlwaysPassthrough("Always Passthrough", "Always attempt HDR passthrough, even on SDR displays."),
}

enum class DesktopColorProfile(val label: String, val description: String) {
    Neutral("Neutral", "No color adjustments applied. Accurate to the source."),
    Cinematic("Cinematic", "Slightly deeper contrast with richer colors for a cinematic look."),
    Vivid("Vivid", "Boosted contrast and saturation for a punchier image."),
    Custom("Custom", "Your own contrast, brightness, saturation and gamma offsets."),
}

/**
 * Trims the gpu-next rendering pipeline down to what a small video-memory budget can actually
 * allocate.
 *
 * libplacebo sizes its intermediate render targets from the *source* resolution, not the window: one
 * RGBA16F surface for a 4K frame is ~63 MB, and the baseline pipeline (deband, linear/sigmoid light,
 * the separable scalers, HDR peak detection) wants several of them at once. When the driver refuses
 * one of those allocations libplacebo disables FBOs mid-frame, skips the main scaler, and then trips
 * an internal size assertion that aborts the process — the crash cannot be caught or recovered from
 * after the fact, so the only fix is to never ask for the memory. Reported on an Intel UHD 630 with a
 * 2 GB shared pool playing 4K ("Failed creating FBO texture! Disabling advanced rendering..").
 *
 * [Auto] resolves natively (see the Windows player bridge) against the adapter's dedicated video
 * memory. It deliberately does not look at the source resolution: mpv's options have to be set before
 * the file is loaded, and the first rendered frame is already the one that crashes.
 *
 * Anime4K and custom GLSL chains are left alone in every mode — they are an explicit, visible user
 * choice, and silently dropping them would be a worse surprise than the quality loss here.
 */
enum class DesktopLowVramMode(val label: String, val description: String) {
    Off("Off", "Always use the full rendering pipeline, whatever the GPU reports."),
    Auto("Auto", "Trim the pipeline on integrated graphics and GPUs with under 2 GB of video memory."),
    On("On", "Always trim: bilinear scaling, no debanding, dithering or HDR peak detection."),
}

enum class DesktopSourceNotchPosition {
    Right,
    Left,
    Hidden,
    ;

    companion object {
        fun fromStorage(value: String?): DesktopSourceNotchPosition =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Right
    }
}

/**
 * Where the transient in-player pills (playback speed, volume, aspect ratio, UI scale...) appear.
 *
 * [TopCenter] lifts them out of the middle of the picture. It deliberately stays centred rather
 * than tucking into a corner: the top-left corner already carries the title/episode block and the
 * top-right one the header actions and playback info panel.
 */
enum class DesktopPlayerNotificationPosition(val webValue: String) {
    Center("center"),
    TopCenter("top-center"),
    ;

    companion object {
        fun fromStorage(value: String?): DesktopPlayerNotificationPosition =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Center
    }
}

/**
 * How the desktop HUD's bottom control bar is composed. Persisted as three booleans
 * (`desktop_legacy_hud_enabled` / `desktop_minimal_hud_enabled` / `desktop_ultra_hud_enabled`,
 * plus `desktop_official_hud_enabled`) so the pre-existing legacy toggle keeps its stored value; the repository guarantees at most one
 * of them is set.
 *
 * [Minimal] is the single-row layout: transport, volume and time on the left, tools on the right,
 * with the seek bar spanning the full width above them.
 *
 * [Ultra] strips that down to the seek bar plus two buttons: a gear that opens a frosted-glass
 * options popover (volume and every tool icon) and a fullscreen toggle. No transport buttons --
 * the video surface itself is the play/pause control.
 *
 * [Official] is the bottom bar of the official Nuvio Desktop player (NuvioMedia/NuvioDesktop,
 * which shipped it after this fork split off): an accent play button leading a row of round
 * icon-only tools, and the volume slider plus a "position / duration" readout on the right,
 * under a thick edge-to-edge seek bar. Only the bottom bar is ported; the header and side chrome
 * stay this fork's.
 */
enum class DesktopHudLayout {
    Standard,
    Legacy,
    Minimal,
    Ultra,
    Official,
}

enum class DesktopBufferPreset(val label: String, val description: String) {
    Metered(
        "Metered",
        "Smallest buffer, and pausing stops the download instead of filling the buffer. " +
            "For capped or metered connections; high-bitrate files may stall.",
    ),
    LowData("Low Data", "Minimizes network and memory use with a short playback buffer."),
    Balanced("Balanced", "Keeps a moderate buffer for reliable playback without excessive read-ahead."),
    Resilient("Resilient", "Uses a large buffer for unstable or high-latency connections."),
}

/**
 * Seek-bar hover previews come from a second libmpv instance that opens the same stream and issues
 * one byte-range request per hovered position. Against a debrid CDN those opens are exactly what
 * gets rate-limited, so previews can be limited to sources the user controls.
 */
enum class DesktopSeekThumbnailMode(val label: String, val description: String) {
    Off("Off", "No preview frames. The hover card still shows the time and chapter."),
    Local(
        "Local",
        "Previews only for files on this PC and servers on your home network. Debrid and other internet streams, and torrents, get none.",
    ),
    LocalAndSeekr(
        "Local + Seekr",
        "Local previews for files on this PC and your home network. Debrid streams and torrents use Seekr's ready-made thumbnails when it has the title and show none otherwise, so the stream's host is never asked. Needs a Seekr key under Integrations.",
    ),
    Streaming(
        "Streaming",
        "Previews for every source. With a Seekr key, streams use Seekr's thumbnails first. Otherwise each hovered position is another request to the stream's host, which can get you rate-limited by debrid providers.",
    ),
}

/**
 * What the desktop player does when a stream that was already playing gets an HTTP 429 (almost
 * always a seek whose range request the host throttled). TorBox's CDN answers that with a per-IP
 * ban on one `nexus-N` node lasting over an hour (measured 2026-09-26), so for TorBox "reconnect"
 * means reopening the same link through another node; other hosts are reopened after a wait.
 */
enum class DesktopRateLimitRecoveryMode(val label: String, val description: String) {
    Off(
        "Off",
        "Never reconnect. A rate limit fails over to another source if Stream Failover is on, otherwise playback stops.",
    ),
    PreferFailover(
        "Prefer Failover",
        "Fail over to another source when Stream Failover is on; reconnect only when it is off.",
    ),
    PreferReconnect(
        "Prefer Reconnect",
        "Reconnect to the same stream first (TorBox links switch server instantly), then fail over.",
    ),
}

/** Choices for the two reconnect waits used on hosts other than TorBox. */
val RATE_LIMIT_RECONNECT_DELAY_VALUES: List<Int> = listOf(3, 5, 10, 20, 30, 60, 120)
const val RATE_LIMIT_RECONNECT_FIRST_DEFAULT_SECONDS = 5
const val RATE_LIMIT_RECONNECT_SECOND_DEFAULT_SECONDS = 20

enum class DesktopMpvConfigMode(val label: String, val description: String) {
    Off("Off", "Use Nuvio's mpv configuration and ignore the custom options below."),
    Add("Add", "Add custom options only when Nuvio has not already configured that option."),
    Replace("Replace", "Custom options override matching Nuvio options; unspecified options keep Nuvio defaults."),
    Full("Full", "Use only the custom configuration plus the options required to embed mpv in Nuvio."),
}

/**
 * Graphics backend the desktop app UI (Compose/Skiko) renders with. Not the video player —
 * mpv always uses Direct3D 11. Applied to `skiko.renderApi` at startup, so a change only takes
 * effect after an app restart. Direct3D is the default: on some drivers a monitor-covering
 * OpenGL window is flipped straight to the display, bypassing DWM, so mpv's child swapchain and
 * the WebView2 HUD never reach the screen — fullscreen playback goes black with audio (1.15.0
 * reports, confirmed fixed by Direct3D). Which systems do this depends on driver settings, MPO and
 * HDR rather than the GPU model, so it cannot be predicted. OpenGL renders the UI slightly better
 * and stays selectable as an opt-in.
 */
enum class DesktopRendererApi(val label: String, val description: String, val skikoRenderApi: String) {
    OpenGL(
        "OpenGL",
        "More polished UI. If fullscreen playback shows a black screen, switch back to Direct3D 11.",
        "OPENGL",
    ),
    D3D11(
        "Direct3D 11",
        "Most compatible. Recommended for most systems.",
        "DIRECT3D",
    ),
}

/**
 * Desktop anime enhancement mode. Ports Stremio-Kai's Anime4K shader pipeline plus anime-tuned
 * scaling/deband. The persisted preset only auto-applies to detected anime (and only while
 * "Auto-apply to Anime" is on); CustomShader points at a user-provided shader from the desktop
 * shader library. F10 cycles a session-scoped force instead — see [DesktopAnimeSessionOverride].
 */
enum class DesktopAnimeMode(val label: String, val description: String) {
    Off("Off", "Never apply anime enhancements."),
    Optimized("Optimized", "Anime4K Optimized — razor-sharp edges with the lightest GPU load."),
    Fast("Fast", "Anime4K Eye-Candy (Fast) — stronger restore and line-thinning."),
    Hq("HQ", "Anime4K Eye-Candy (HQ) — maximum quality, heaviest GPU load."),
    ModeAFast("Mode A (Fast)", "Anime4K Mode A — best for blurry/compressed sources. Balanced speed and quality."),
    ModeAHq("Mode A (HQ)", "Anime4K Mode A — best for blurry/compressed sources. Highest quality, heavier GPU load."),
    ModeBFast("Mode B (Fast)", "Anime4K Mode B — best for already-clean or soft sources. Balanced speed and quality."),
    ModeBHq("Mode B (HQ)", "Anime4K Mode B — best for already-clean or soft sources. Highest quality, heavier GPU load."),
    ModeCFast("Mode C (Fast)", "Anime4K Mode C — best for noisy or heavily compressed sources. Balanced speed and quality."),
    ModeCHq("Mode C (HQ)", "Anime4K Mode C — best for noisy or heavily compressed sources. Highest quality, heavier GPU load."),
    CustomShader("Custom Shader", "Use the selected user shader from the desktop shader library."),
}

/**
 * An in-player force of the anime enhancement preset (F10 cycle / shader context menu). Lives only
 * in memory for the current playback session: it survives episode changes (binge-watching an
 * undetected anime shouldn't need re-forcing every episode) and is cleared when the player closes,
 * at which point behaviour falls back to the persisted "Auto-apply to Anime" gate.
 */
data class DesktopAnimeSessionOverride(
    val mode: DesktopAnimeMode,
    val customShaderPath: String = "",
    // Display label for pills/HUD: the preset label, or the shader file name for CustomShader.
    val label: String = mode.label,
)

/**
 * Heuristic anime detection from metadata. Nuvio Desktop has no online anime database (unlike
 * Stremio-Kai), so this reads the genre tags exposed by addons and TMDB, disambiguated by the
 * title's provenance.
 *
 * An explicit "Anime" genre is decisive. A bare "Animation" tag is not: TMDB and Trakt use it for
 * Western cartoons too, which is how South Park and Spider-Verse ended up having the anime shader
 * chain and SVP interpolation applied to them automatically. [com.nuvio.app.features.collection]
 * already refuses animation-alone for the same reason; this is that rule brought to the player.
 *
 * Deliberately lenient about missing metadata: animation is rejected only when [originalLanguage]
 * or [originCountries] *positively* say the title is not Japanese. An unknown provenance still
 * counts as anime, preserving the previous behaviour wherever the metadata is too thin to judge —
 * quietly losing detection on a real anime with a sparse addon meta would be a worse failure than
 * the over-match this fixes.
 *
 * Titles carrying an anime-native id (kitsu/mal/anilist/anidb) never reach here: [AnimeContentCache]
 * answers those from the id itself. The in-player F10 toggle overrides whatever this decides.
 *
 * [treatAnimationAsAnime] is the "Include Western Animation" preference, which opts the rejected
 * case back in for people who want the enhancement layer on Western cartoons too — the behaviour
 * this function had before provenance was consulted at all.
 */
fun isAnimeFromGenres(
    genres: List<String>,
    originalLanguage: String? = null,
    originCountries: Iterable<String> = emptyList(),
    treatAnimationAsAnime: Boolean = false,
): Boolean = classifyAnimeContent(genres, originalLanguage, originCountries)
    .isAnime(treatAnimationAsAnime)

/**
 * What the metadata says a title is, before any preference is applied.
 *
 * Kept separate from the yes/no answer so [AnimeContentCache] can store a *fact* and let the
 * "Include Western Animation" preference decide at read time — caching the verdict instead would
 * leave every title already seen this session answering with the old setting.
 */
enum class AnimeContentKind {
    /** An explicit anime tag, or animation whose provenance is not positively non-Japanese. */
    Anime,

    /** Animation positively identified as non-Japanese — a Western cartoon. */
    WesternAnimation,

    /** Not animation at all. */
    NotAnimation,
    ;

    /** [treatAnimationAsAnime] is the user preference that opts Western animation back in. */
    fun isAnime(treatAnimationAsAnime: Boolean): Boolean = when (this) {
        Anime -> true
        WesternAnimation -> treatAnimationAsAnime
        NotAnimation -> false
    }
}

/** See [isAnimeFromGenres] for the rule and why provenance is read leniently. */
fun classifyAnimeContent(
    genres: List<String>,
    originalLanguage: String? = null,
    originCountries: Iterable<String> = emptyList(),
): AnimeContentKind {
    val normalized = genres.map { it.trim().lowercase() }
    if (normalized.none { it == "anime" || it == "animation" }) return AnimeContentKind.NotAnimation
    if (normalized.any { it == "anime" }) return AnimeContentKind.Anime
    return if (hasNonJapaneseProvenance(originalLanguage, originCountries)) {
        AnimeContentKind.WesternAnimation
    } else {
        AnimeContentKind.Anime
    }
}

private fun hasNonJapaneseProvenance(
    originalLanguage: String?,
    originCountries: Iterable<String>,
): Boolean {
    val language = originalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    // MetaDetails.country is a comma-joined string of ISO codes ("JP, US"), not a list, so each
    // entry is split rather than compared whole — otherwise a co-production never matches Japan.
    val countries = originCountries
        .flatMap { it.split(',', '/', '|') }
        .mapNotNull { it.trim().lowercase().takeIf(String::isNotBlank) }
    if (language == null && countries.isEmpty()) return false
    if (language in JAPANESE_LANGUAGE_CODES) return false
    if (countries.any { it in JAPAN_COUNTRY_CODES }) return false
    return true
}

private val JAPANESE_LANGUAGE_CODES = setOf("ja", "jpn", "ja-jp")
private val JAPAN_COUNTRY_CODES = setOf("jp", "jpn", "japan")

@Composable
fun IosVideoOutputPreset.localizedLabel(): String = when (this) {
    IosVideoOutputPreset.NativeEdr -> stringResource(Res.string.player_ios_preset_native_edr_label)
    IosVideoOutputPreset.SdrToneMapped -> stringResource(Res.string.player_ios_preset_sdr_tone_mapped_label)
    IosVideoOutputPreset.Compatibility -> stringResource(Res.string.player_ios_preset_compatibility_label)
    IosVideoOutputPreset.Custom -> stringResource(Res.string.player_ios_preset_custom_label)
}

@Composable
fun IosVideoOutputPreset.localizedDescription(): String = when (this) {
    IosVideoOutputPreset.NativeEdr -> stringResource(Res.string.player_ios_preset_native_edr_desc)
    IosVideoOutputPreset.SdrToneMapped -> stringResource(Res.string.player_ios_preset_sdr_tone_mapped_desc)
    IosVideoOutputPreset.Compatibility -> stringResource(Res.string.player_ios_preset_compatibility_desc)
    IosVideoOutputPreset.Custom -> stringResource(Res.string.player_ios_preset_custom_desc)
}

@Composable
fun IosHardwareDecoderMode.localizedLabel(): String = when (this) {
    IosHardwareDecoderMode.Off -> stringResource(Res.string.player_ios_hardware_decoder_off)
    else -> label
}

data class PlayerPlaybackSnapshot(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)
