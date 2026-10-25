package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.stream_info_audio_track
import nuvio.composeapp.generated.resources.stream_info_bitrate
import nuvio.composeapp.generated.resources.stream_info_channels
import nuvio.composeapp.generated.resources.stream_info_codec
import nuvio.composeapp.generated.resources.stream_info_dynamic_range
import nuvio.composeapp.generated.resources.stream_info_file
import nuvio.composeapp.generated.resources.stream_info_frame_rate
import nuvio.composeapp.generated.resources.stream_info_language
import nuvio.composeapp.generated.resources.stream_info_name
import nuvio.composeapp.generated.resources.stream_info_off
import nuvio.composeapp.generated.resources.stream_info_provider
import nuvio.composeapp.generated.resources.stream_info_resolution
import nuvio.composeapp.generated.resources.stream_info_sample_rate
import nuvio.composeapp.generated.resources.stream_info_section_audio
import nuvio.composeapp.generated.resources.stream_info_section_source
import nuvio.composeapp.generated.resources.stream_info_section_subtitle
import nuvio.composeapp.generated.resources.stream_info_section_video
import nuvio.composeapp.generated.resources.stream_info_source
import nuvio.composeapp.generated.resources.stream_info_subtitle_source_addon
import nuvio.composeapp.generated.resources.stream_info_subtitle_source_embedded
import nuvio.composeapp.generated.resources.stream_info_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

data class PlayerControlStreamInfoRow(val label: String, val value: String)

data class PlayerControlStreamInfoSection(val title: String, val rows: List<PlayerControlStreamInfoRow>)

/**
 * The HUD's stream info panel (upstream d3786272e, rebuilt for the HTML HUD): what is playing, as
 * mpv decodes it. Built only while the panel is open ([PlayerScreenRuntime.showStreamInfo]), so
 * the controls JSON pushed on every tick carries nothing extra the rest of the time.
 */
data class PlayerControlStreamInfo(
    val title: String = "",
    val closeLabel: String = "",
    val sections: List<PlayerControlStreamInfoSection> = emptyList(),
)

@Composable
internal fun PlayerScreenRuntime.rememberStreamInfoState(): PlayerControlStreamInfo? {
    if (!showStreamInfo) return null
    var facts by remember { mutableStateOf<PlayingMediaFacts?>(null) }
    LaunchedEffect(playerController, activeSourceUrl) {
        // Bitrate and the audio track can change while the panel is open.
        while (true) {
            facts = playerController?.playingMediaFacts()
            delay(STREAM_INFO_REFRESH_MS)
        }
    }
    val current = facts ?: PlayingMediaFacts()
    val subtitleLabel = activePlaybackSubtitleLabel()
    val subtitleSource = if (useCustomSubtitles) {
        stringResource(Res.string.stream_info_subtitle_source_addon)
    } else {
        stringResource(Res.string.stream_info_subtitle_source_embedded)
    }
    val off = stringResource(Res.string.stream_info_off)

    val source = listOfNotNull(
        row(stringResource(Res.string.stream_info_name), activeStreamTitle),
        row(stringResource(Res.string.stream_info_provider), activeProviderName),
        row(stringResource(Res.string.stream_info_file), activeStreamFilename),
    )
    val video = listOfNotNull(
        row(stringResource(Res.string.stream_info_codec), joinCodec(videoCodecLabel(current.videoCodec), current.videoProfile)),
        row(stringResource(Res.string.stream_info_resolution), resolutionLabel(current.width, current.height)),
        row(stringResource(Res.string.stream_info_frame_rate), current.fps?.let(::formatFps)),
        row(stringResource(Res.string.stream_info_dynamic_range), dynamicRangeLabel(current)),
        row(stringResource(Res.string.stream_info_bitrate), current.videoBitrate?.let(::formatBitrate)),
    )
    val audio = listOfNotNull(
        row(stringResource(Res.string.stream_info_audio_track), current.audioTitle),
        row(stringResource(Res.string.stream_info_codec), joinCodec(audioCodecLabel(current.audioCodec), current.audioProfile)),
        row(stringResource(Res.string.stream_info_channels), current.audioChannels?.let(::channelsLabel)),
        row(stringResource(Res.string.stream_info_sample_rate), current.audioSampleRate?.let(::formatSampleRate)),
        row(stringResource(Res.string.stream_info_bitrate), current.audioBitrate?.let(::formatBitrate)),
        row(stringResource(Res.string.stream_info_language), current.audioLanguage),
    )
    val subtitle = if (subtitleLabel.isBlank()) {
        listOf(PlayerControlStreamInfoRow(stringResource(Res.string.stream_info_name), off))
    } else {
        listOfNotNull(
            row(stringResource(Res.string.stream_info_name), subtitleLabel),
            row(stringResource(Res.string.stream_info_source), subtitleSource),
        )
    }
    return PlayerControlStreamInfo(
        title = stringResource(Res.string.stream_info_title),
        closeLabel = stringResource(Res.string.action_close),
        sections = listOf(
            PlayerControlStreamInfoSection(stringResource(Res.string.stream_info_section_source), source),
            PlayerControlStreamInfoSection(stringResource(Res.string.stream_info_section_video), video),
            PlayerControlStreamInfoSection(stringResource(Res.string.stream_info_section_audio), audio),
            PlayerControlStreamInfoSection(stringResource(Res.string.stream_info_section_subtitle), subtitle),
        ).filter { it.rows.isNotEmpty() },
    )
}

private fun row(label: String, value: String?): PlayerControlStreamInfoRow? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { PlayerControlStreamInfoRow(label, it) }

private fun joinCodec(codec: String?, profile: String?): String? {
    val name = codec?.takeIf { it.isNotBlank() } ?: return profile?.takeIf { it.isNotBlank() }
    val detail = profile?.trim()?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
    return if (detail != null) "$name ($detail)" else name
}

internal fun videoCodecLabel(codec: String?): String? = when (codec?.trim()?.lowercase()) {
    null, "" -> null
    "hevc", "h265" -> "HEVC (H.265)"
    "h264", "avc" -> "AVC (H.264)"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "mpeg2video" -> "MPEG-2"
    else -> codec.uppercase()
}

internal fun audioCodecLabel(codec: String?): String? = when (codec?.trim()?.lowercase()) {
    null, "" -> null
    "eac3" -> "E-AC-3"
    "ac3" -> "AC-3"
    "truehd" -> "TrueHD"
    "dts" -> "DTS"
    "aac" -> "AAC"
    "opus" -> "Opus"
    "flac" -> "FLAC"
    "mp3" -> "MP3"
    "vorbis" -> "Vorbis"
    else -> codec.uppercase()
}

internal fun resolutionLabel(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val tier = when {
        width >= 3200 || height >= 1800 -> "4K"
        width >= 2400 || height >= 1400 -> "1440p"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        else -> "${height}p"
    }
    return "$width × $height ($tier)"
}

internal fun dynamicRangeLabel(facts: PlayingMediaFacts): String? = when {
    facts.dolbyVisionProfile > 0 && facts.isHdr && facts.dolbyVisionProfile != 5 ->
        "Dolby Vision (profile ${facts.dolbyVisionProfile}) + HDR10"
    facts.dolbyVisionProfile > 0 -> "Dolby Vision (profile ${facts.dolbyVisionProfile})"
    facts.isHdr -> "HDR"
    facts.width != null -> "SDR"
    else -> null
}

internal fun formatFps(fps: Double): String {
    val rounded = (fps * 1000).roundToInt() / 1000.0
    val text = rounded.toString().trimEnd('0').trimEnd('.')
    return "$text fps"
}

internal fun formatBitrate(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000 -> "${(bitsPerSecond / 100_000) / 10.0} Mb/s"
    else -> "${bitsPerSecond / 1000} kb/s"
}

internal fun formatSampleRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "${(hz / 100) / 10.0} kHz"

internal fun channelsLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo (2.0)"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels channels"
}

private const val STREAM_INFO_REFRESH_MS = 2_000L
