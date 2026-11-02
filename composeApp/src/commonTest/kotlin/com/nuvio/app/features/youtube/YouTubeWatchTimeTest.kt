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
            realTimeSec = 15.0,
        )
        assertEquals(
            "https://s.youtube.com/api/stats/watchtime?ns=yt&docid=abc&ver=2&cpn=CPN&el=detailpage" +
                "&cmt=42.250&st=12.500&et=42.250&len=600.000&rt=15.000&state=paused",
            url,
        )
    }

    @Test
    fun youTubesOwnParametersAreReplacedNotRepeated() {
        val url = youTubeWatchtimePingUrl(
            base = "https://s.youtube.com/api/stats/watchtime?ns=yt&el=adunit&len=766.021&docid=abc",
            cpn = "CPN",
            segmentStartSec = 0.0,
            positionSec = 30.0,
            lengthSec = 765.9,
            playing = true,
            realTimeSec = 15.0,
        )
        assertEquals(1, Regex("[?&]el=").findAll(url).count(), url)
        assertTrue("el=detailpage" in url, url)
        // YouTube's length stands; mpv's duration is not sent alongside it.
        assertEquals(1, Regex("[?&]len=").findAll(url).count(), url)
        assertTrue("len=766.021" in url, url)
    }

    @Test
    fun aFinishedVideoIsClaimedWholeOnYouTubesLength() {
        val base = "https://s.youtube.com/api/stats/watchtime?ns=yt&len=766.021&docid=abc"
        assertEquals(766.021, youTubeStatsLengthSec(base))
        val url = youTubeFullWatchPingUrl(base, "CPN", 766.021, realTimeSec = 383.0)
        assertTrue("&cmt=766.021&st=0.000&et=766.021" in url, url)
    }

    @Test
    fun finishedMeansTheLastFivePercentAtMostFifteenSeconds() {
        assertTrue(youTubeWatchFinished(positionMs = 766_000, lengthMs = 766_000))
        assertTrue(youTubeWatchFinished(positionMs = 752_000, lengthMs = 766_000))
        assertTrue(!youTubeWatchFinished(positionMs = 740_000, lengthMs = 766_000))
        // A 60 s clip: within its last 3 s only.
        assertTrue(youTubeWatchFinished(positionMs = 57_500, lengthMs = 60_000))
        assertTrue(!youTubeWatchFinished(positionMs = 50_000, lengthMs = 60_000))
    }

    @Test
    fun baseWithoutQueryGetsOne() {
        assertTrue(youTubePlaybackPingUrl("https://s.youtube.com/api/stats/playback", "X").contains("playback?ver=2"))
    }
}
