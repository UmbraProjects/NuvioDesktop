package com.nuvio.app.features.music.visualiser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotSourceTest {
    @Test
    fun youTubeStreamsAreReadStraightFromTheStream() {
        assertFalse(spotDownloadWanted("https://rr3---sn-8pgbpohxqp5-auod.googlevideo.com/videoplayback?expire=1&itag=251"))
        assertFalse(spotDownloadWanted("https://www.youtube.com/watch?v=x"))
        assertFalse(spotDownloadWanted("https://example.com/live/master.m3u8"))
        assertFalse(spotDownloadWanted("edl://something"))
        assertFalse(spotDownloadWanted(null))
    }

    @Test
    fun addonLinksAreDownloadedOnce() {
        assertTrue(spotDownloadWanted("https://nexus-176.neur.tb-cdn.st/dld/afc35cb6-3eab?token=abc"))
        // A host that merely contains a YouTube name is not YouTube.
        assertTrue(spotDownloadWanted("https://notgooglevideo.com.example.net/file.flac"))
    }

    @Test
    fun aShortDownloadIsNotTheWholeFile() {
        assertFalse(spotDownloadComplete(received = 4_055 * 1024L, expected = 9_500_000L))
        assertTrue(spotDownloadComplete(received = 9_500_000L, expected = 9_500_000L))
        assertTrue(spotDownloadComplete(received = 1_000L, expected = -1L))
        assertFalse(spotDownloadComplete(received = 0L, expected = -1L))
    }

    @Test
    fun aVideoBorrowsTheAudioMapOnlyAtTheSameLength() {
        assertTrue(audioMapKey("xwtdhWltSIg") == "yt:xwtdhWltSIg")
        assertTrue(audioMapKey("nebula:c1678a~tidal:track:1") == null)
        assertTrue(audioMapKey("  ") == null)
        assertTrue(sameLengthForMap(mapMs = 240_000, playingMs = 241_500))
        assertFalse(sameLengthForMap(mapMs = 240_000, playingMs = 559_000))
        assertFalse(sameLengthForMap(mapMs = 0, playingMs = 240_000))
    }
}
