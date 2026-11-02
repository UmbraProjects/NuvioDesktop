package com.nuvio.app.features.youtube

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YouTubeWatchTimeTest {

    @Test
    fun cpnIsSixteenUrlSafeCharacters() {
        val cpn = newYouTubeCpn(Random(1))
        assertEquals(16, cpn.length)
        assertTrue(cpn.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(cpn, newYouTubeCpn(Random(2)))
    }

    @Test
    fun playbackPingAppendsVersionAndNonce() {
        assertEquals(
            "https://s.youtube.com/api/stats/playback?ns=yt&docid=abc&ver=2&cpn=CPN",
            youTubePlaybackPingUrl("https://s.youtube.com/api/stats/playback?ns=yt&docid=abc", "CPN"),
        )
    }

    @Test
    fun watchtimePingCarriesTheStretchAndPlayhead() {
        val url = youTubeWatchtimePingUrl(
            base = "https://s.youtube.com/api/stats/watchtime?ns=yt&docid=abc",
            cpn = "CPN",
            segmentStartSec = 12.5,
            positionSec = 42.25,
            lengthSec = 600.0,
            playing = false,
        )
        assertEquals(
            "https://s.youtube.com/api/stats/watchtime?ns=yt&docid=abc&ver=2&cpn=CPN&el=detailpage" +
                "&cmt=42.250&st=12.500&et=42.250&len=600.000&state=paused",
            url,
        )
    }

    @Test
    fun baseWithoutQueryGetsOne() {
        assertTrue(youTubePlaybackPingUrl("https://s.youtube.com/api/stats/playback", "X").contains("playback?ver=2"))
    }
}
