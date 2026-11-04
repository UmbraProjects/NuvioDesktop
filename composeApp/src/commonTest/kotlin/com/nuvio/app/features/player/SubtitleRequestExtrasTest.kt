package com.nuvio.app.features.player

import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Adapted from upstream's SubtitleRequestExtrasTest. */
class SubtitleRequestExtrasTest {

    @Test
    fun buildsExtrasSegmentInStremioFieldOrderWithEncodedValues() {
        val extras = SubtitleRequestExtras(
            filename = "John.Wick.Chapter.4.2023.720p.WEBRip.x264.AAC-[YTS.MX].mp4",
            videoSize = 1_632_335_363L,
            videoHash = "9d8c1e6c3d434f50",
        )
        assertEquals(
            "videoHash=9d8c1e6c3d434f50&videoSize=1632335363&filename=John.Wick.Chapter.4.2023.720p.WEBRip.x264.AAC-%5BYTS.MX%5D.mp4",
            extras.toPathSegment(),
        )
    }

    @Test
    fun ignoresBlankAndNonPositiveValues() {
        assertNull(SubtitleRequestExtras().toPathSegment())
        assertNull(SubtitleRequestExtras(filename = "   ", videoHash = "").toPathSegment())
        assertNull(SubtitleRequestExtras(videoSize = 0L).toPathSegment())
        assertEquals("videoSize=1", SubtitleRequestExtras(filename = " ", videoSize = 1L).toPathSegment())
    }

    @Test
    fun buildsFromHintsWithFilenameFallingBackToTheUrl() {
        assertEquals(
            "videoHash=abc123&videoSize=42&filename=Movie.2024.mkv",
            SubtitleRequestExtras.from(
                StreamBehaviorHints(filename = "Movie.2024.mkv", videoSize = 42L, videoHash = "abc123"),
                sourceUrl = "https://cdn.example.com/other.mkv",
            )?.toPathSegment(),
        )
        assertEquals(
            "filename=Show.S01E02.mkv",
            SubtitleRequestExtras.from(StreamBehaviorHints(), "https://cdn.example.com/dl/Show.S01E02.mkv?token=x")?.toPathSegment(),
        )
        assertNull(SubtitleRequestExtras.from(StreamBehaviorHints(), "https://cdn.example.com/play/12345"))
        assertNull(SubtitleRequestExtras.from(null, null))
    }

    @Test
    fun subtitleRequestUrlCarriesExtrasBeforeJsonSuffix() {
        val url = buildAddonResourceUrl(
            manifestUrl = "https://addon.example.com/manifest.json",
            resource = "subtitles",
            type = "series",
            id = "tt37532893:1:5",
            extraPathSegment = SubtitleRequestExtras(filename = "Episode Five.mkv", videoSize = 123L, videoHash = "deadbeef").toPathSegment(),
        )
        assertEquals(
            "https://addon.example.com/subtitles/series/tt37532893%3A1%3A5/videoHash=deadbeef&videoSize=123&filename=Episode%20Five.mkv.json",
            url,
        )
    }
}
