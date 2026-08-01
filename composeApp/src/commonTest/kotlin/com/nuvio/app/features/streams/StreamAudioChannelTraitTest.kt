package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Channel-layout detection against stream text that also quotes numbers. Addon formatters commonly
 * print a bitrate or a size next to the release name, and "5.1 Mbps" / "7.1 GB" are decimals that
 * look exactly like surround layouts — these pin down that only real channel tokens count.
 */
class StreamAudioChannelTraitTest {

    private fun channelsOf(name: String, description: String? = null): List<DebridStreamAudioChannel> =
        StreamTraitDetector.detect(
            StreamItem(
                name = name,
                description = description,
                addonName = "addon",
                addonId = "addon",
                url = "http://example.invalid/${name.hashCode()}",
                behaviorHints = StreamBehaviorHints(filename = name),
            ),
        ).audioChannels

    private fun unknownFor(description: String) = assertEquals(
        listOf(DebridStreamAudioChannel.UNKNOWN),
        channelsOf("Movie.2024.1080p.WEB-DL.x264-GROUP", description = description),
        "\"$description\" should not read as a channel layout",
    )

    @Test
    fun realChannelTokensAreDetected() {
        assertTrue(DebridStreamAudioChannel.CH_5_1 in channelsOf("Movie.2024.1080p.BluRay.DTS.5.1.x264-GROUP"))
        assertTrue(DebridStreamAudioChannel.CH_7_1 in channelsOf("Movie.2024.2160p.BluRay.TrueHD.7.1.Atmos-GROUP"))
        assertTrue(DebridStreamAudioChannel.CH_6_1 in channelsOf("Movie.2024.1080p.BluRay.DTS-ES.6.1-GROUP"))
        assertTrue(DebridStreamAudioChannel.CH_2_0 in channelsOf("Movie.2024.1080p.WEB-DL.AAC.2.0-GROUP"))
    }

    @Test
    fun bitrateIsNotMistakenForSurround() {
        unknownFor("📶 5.1 Mbps")
        unknownFor("📶 7.1Mbps")
        unknownFor("📶 2.0 mbit/s")
    }

    @Test
    fun fileSizeIsNotMistakenForSurround() {
        unknownFor("💾 7.1 GB")
        unknownFor("💾 5.1 GiB")
    }

    @Test
    fun genuineChannelSurvivesAlongsideAMatchingBitrate() {
        // The scan is per-occurrence, so the measurement is skipped without suppressing the real tag.
        assertTrue(
            DebridStreamAudioChannel.CH_5_1 in
                channelsOf(
                    "Movie.2024.1080p.BluRay.DTS.5.1.x264-GROUP",
                    description = "5.1 GB | 5.1 Mbps",
                ),
        )
    }

    @Test
    fun unitLikeReleaseWordsStillCountAsChannels() {
        // "BluRay" starts with a unit letter; the unit guard must not swallow it.
        assertTrue(DebridStreamAudioChannel.CH_5_1 in channelsOf("Movie.2024 5.1 BluRay 1080p-GROUP"))
        // The bare k/m/g/t branch needs a word boundary, so these keep their channel token.
        assertTrue(DebridStreamAudioChannel.CH_5_1 in channelsOf("Movie.2024 DTS 5.1 MKV 1080p-GROUP"))
        assertTrue(DebridStreamAudioChannel.CH_5_1 in channelsOf("Movie.2024 DTS 5.1 M2TS 1080p-GROUP"))
        // "h" is not a size prefix, so a codec right after the layout is safe.
        assertTrue(DebridStreamAudioChannel.CH_7_1 in channelsOf("Movie.2024 TrueHD 7.1 H.264 1080p-GROUP"))
    }

    @Test
    fun nonAsciiSpaceBeforeTheUnitIsStillAMeasurement() {
        // Formatter templates use NBSP/thin spaces to keep a number and its unit on one line, and
        // Kotlin's `\s` is ASCII-only on the JVM — the guard has to name them explicitly.
        unknownFor("5.1 Mbps")
        unknownFor("7.1 GB")
        unknownFor("5.1 GiB")
        unknownFor("2.0　Mbps")
    }

    @Test
    fun abbreviatedAndSpelledOutUnitsAreMeasurements() {
        unknownFor("5.1 G")
        unknownFor("7.1 gigabytes")
        unknownFor("5.1 megabits/s")
    }

    @Test
    fun adjacentDigitsAreNotAChannelToken() {
        // "5.11 Mbps" is not "5.1" — the token needs a boundary on both sides, unit or no unit.
        unknownFor("5.11 Mbps")
        unknownFor("15.1 Mbps")
        unknownFor("5.11")
    }
}
