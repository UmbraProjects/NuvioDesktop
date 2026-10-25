package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamBadgeRules

/**
 * What is actually playing, as the player decoded it: the selected video and audio tracks plus
 * the decoded frame size. mpv's own names (`hevc`, `eac3`, `truehd`, ...), not a source's claims.
 */
data class PlayingMediaFacts(
    val width: Int? = null,
    val height: Int? = null,
    val isHdr: Boolean = false,
    val videoCodec: String? = null,
    val dolbyVisionProfile: Int = 0,
    val audioCodec: String? = null,
    /** mpv's `codec-profile`, e.g. "DTS-HD MA" or "Dolby TrueHD + Dolby Atmos". */
    val audioProfile: String? = null,
    val audioChannels: Int? = null,
    val audioLanguage: String? = null,
    // For the stream info panel; the badges do not use these.
    val videoProfile: String? = null,
    val fps: Double? = null,
    val videoBitrate: Long? = null,
    val audioTitle: String? = null,
    val audioSampleRate: Int? = null,
    val audioBitrate: Long? = null,
)

/**
 * The playing media as release-name tokens ("2160p 4K HEVC x265 DV HDR10 10bit DD+ Atmos 5.1"), so
 * a badge pack written for release names can badge what is playing (upstream 344117e73). Several
 * spellings per fact, because packs disagree on which one they match.
 */
internal fun playbackMatchText(facts: PlayingMediaFacts): String = buildList {
    addAll(resolutionTokens(facts.width, facts.height))
    addAll(videoCodecTokens(facts.videoCodec))
    val dolbyVision = facts.dolbyVisionProfile > 0
    if (dolbyVision) addAll(listOf("DV", "DoVi", "Dolby Vision"))
    // mpv reports HDR for Dolby Vision too; a DV profile 5 file has no HDR10 layer to badge.
    if (facts.isHdr && !(dolbyVision && facts.dolbyVisionProfile == 5)) addAll(listOf("HDR", "HDR10"))
    if (facts.isHdr || dolbyVision) add("10bit")
    addAll(audioTokens(facts.audioCodec, facts.audioProfile))
    channelsToken(facts.audioChannels)?.let(::add)
    facts.audioLanguage?.takeIf { it.isNotBlank() }?.let(::add)
}.distinct().joinToString(" ")

/** The badges of [rules] that match what is playing; image badges only, as the HUD shows images. */
internal fun playerBadges(facts: PlayingMediaFacts, rules: StreamBadgeRules): List<StreamBadge> {
    val text = playbackMatchText(facts)
    if (text.isBlank()) return emptyList()
    // Case-insensitive: the text is ours, so a pack's casing carries no meaning against it.
    return StreamBadgeMatcher
        .matchedBadges(listOf(text), StreamBadgeMatcher.compile(rules, setOf(RegexOption.IGNORE_CASE)))
        .filter { it.imageURL.isNotBlank() }
}

private fun resolutionTokens(width: Int?, height: Int?): List<String> {
    if (width == null || height == null || width <= 0 || height <= 0) return emptyList()
    return when {
        width >= 3200 || height >= 1800 -> listOf("2160p", "4K", "UHD")
        width >= 2400 || height >= 1400 -> listOf("1440p", "2K")
        width >= 1800 || height >= 1000 -> listOf("1080p", "FHD")
        width >= 1200 || height >= 700 -> listOf("720p", "HD")
        else -> listOf("${height}p", "SD")
    }
}

private fun videoCodecTokens(codec: String?): List<String> = when (codec?.trim()?.lowercase()) {
    null, "" -> emptyList()
    "hevc", "h265" -> listOf("HEVC", "H.265", "H265", "x265")
    "h264", "avc" -> listOf("AVC", "H.264", "H264", "x264")
    "av1" -> listOf("AV1")
    "vp9" -> listOf("VP9")
    "mpeg2video" -> listOf("MPEG-2", "MPEG2")
    else -> listOf(codec.uppercase())
}

private fun audioTokens(codec: String?, profile: String?): List<String> {
    val name = codec?.trim()?.lowercase().orEmpty()
    val profileText = profile.orEmpty().lowercase()
    if (name.isEmpty()) return emptyList()
    return buildList {
        when (name) {
            "eac3" -> addAll(listOf("DD+", "DDP", "EAC3", "E-AC-3"))
            "ac3" -> addAll(listOf("DD", "AC3", "Dolby Digital"))
            "truehd", "mlp" -> addAll(listOf("TrueHD", "Dolby TrueHD"))
            "dts" -> {
                add("DTS")
                when {
                    profileText.contains("ma") -> addAll(listOf("DTS-HD", "DTS-HD MA"))
                    profileText.contains("hra") || profileText.contains("hd") -> add("DTS-HD")
                }
                if (profileText.contains("dts:x") || profileText.contains("dts-x")) add("DTS:X")
            }
            "aac" -> add("AAC")
            "opus" -> add("Opus")
            "flac" -> add("FLAC")
            "mp3" -> add("MP3")
            else -> add(name.uppercase())
        }
        if (profileText.contains("atmos") || profileText.contains("joc")) add("Atmos")
    }
}

private fun channelsToken(channels: Int?): String? = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> null
}
