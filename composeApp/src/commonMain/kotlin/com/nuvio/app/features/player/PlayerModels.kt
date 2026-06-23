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

data class PlayerLaunch(
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val bingeGroup: String? = null,
    val pauseDescription: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val contentType: String? = null,
    val videoId: String? = null,
    val parentMetaId: String,
    val parentMetaType: String,
    val torrentInfoHash: String? = null,
    val torrentFileIdx: Int? = null,
    val torrentFilename: String? = null,
    val torrentTrackers: List<String> = emptyList(),
    val initialPositionMs: Long = 0L,
    val initialProgressFraction: Float? = null,
    val disableProgressTracking: Boolean = false,
)

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
}

enum class DesktopBufferPreset(val label: String, val description: String) {
    LowData("Low Data", "Minimizes network and memory use with a short playback buffer."),
    Balanced("Balanced", "Keeps a moderate buffer for reliable playback without excessive read-ahead."),
    Resilient("Resilient", "Uses a large buffer for unstable or high-latency connections."),
}

/**
 * Desktop anime enhancement mode. Ports Stremio-Kai's Anime4K shader pipeline plus anime-tuned
 * scaling/deband. `Auto` applies the Optimized preset only when the title is detected as anime;
 * the named presets force that preset regardless of detection. Cycled in-player with F10.
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
}

/**
 * Heuristic anime detection from metadata genres. Nuvio Desktop has no online anime database
 * (unlike Stremio-Kai), so this matches the "Anime" / "Animation" genre tags exposed by addons
 * and TMDB. It over-matches Western animation; the in-player F10 toggle overrides per session.
 */
fun isAnimeFromGenres(genres: List<String>): Boolean =
    genres.any { genre ->
        val normalized = genre.trim().lowercase()
        normalized == "anime" || normalized == "animation"
    }

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
