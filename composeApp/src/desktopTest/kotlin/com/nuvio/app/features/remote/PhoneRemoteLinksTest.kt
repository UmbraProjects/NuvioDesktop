package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRemoteLinksTest {
    @Test
    fun youTubeLinksInTheirUsualShapes() {
        assertEquals(SharedLink.YouTubeVideo("dQw4w9WgXcQ", null), PhoneRemoteLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        assertEquals(SharedLink.YouTubeVideo("dQw4w9WgXcQ", 43), PhoneRemoteLinks.parse("https://youtu.be/dQw4w9WgXcQ?si=abc&t=43"))
        assertEquals(SharedLink.YouTubeVideo("dQw4w9WgXcQ", 90), PhoneRemoteLinks.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s"))
        assertEquals(SharedLink.YouTubeVideo("abcdefghijk", null), PhoneRemoteLinks.parse("https://youtube.com/shorts/abcdefghijk?si=x"))
        assertEquals(SharedLink.YouTubeVideo("abcdefghijk", null), PhoneRemoteLinks.parse("https://www.youtube.com/live/abcdefghijk"))
    }

    @Test
    fun theLinkIsFoundInsideASharedSentence() {
        assertEquals(
            SharedLink.YouTubeVideo("dQw4w9WgXcQ", null),
            PhoneRemoteLinks.parse("Watch this, it's great: https://youtu.be/dQw4w9WgXcQ."),
        )
    }

    @Test
    fun youTubeMusicSongsAndLists() {
        assertEquals(SharedLink.MusicSong("Zi_XLOBDo_Y"), PhoneRemoteLinks.parse("https://music.youtube.com/watch?v=Zi_XLOBDo_Y&si=x"))
        assertEquals(SharedLink.MusicSong("Zi_XLOBDo_Y"), PhoneRemoteLinks.parse("https://music.youtube.com/watch?v=Zi_XLOBDo_Y&list=RDAMVMZi_XLOBDo_Y"))
        assertEquals(SharedLink.MusicList("OLAK5uy_abc"), PhoneRemoteLinks.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc&si=x"))
        assertNull(PhoneRemoteLinks.parse("https://music.youtube.com/browse/MPREb_abcdef"))
    }

    @Test
    fun appLinksStreamsAndTheRest() {
        assertEquals(SharedLink.App("nuvio://party/ABC123"), PhoneRemoteLinks.parse("nuvio://party/ABC123"))
        assertEquals(SharedLink.App("https://nuvio.tv/party/ABC123"), PhoneRemoteLinks.parse("Join us https://nuvio.tv/party/ABC123"))
        assertEquals(SharedLink.Stream("https://cdn.example.com/films/a.mkv"), PhoneRemoteLinks.parse("https://cdn.example.com/films/a.mkv"))
        assertEquals(SharedLink.Stream("http://10.0.0.5:8080/live/index.m3u8?x=1"), PhoneRemoteLinks.parse("http://10.0.0.5:8080/live/index.m3u8?x=1"))
        assertNull(PhoneRemoteLinks.parse("https://example.com/article"))
        assertNull(PhoneRemoteLinks.parse("no link here"))
        assertNull(PhoneRemoteLinks.parse("https://www.youtube.com/watch?v=short"))
    }

    @Test
    fun startTimes() {
        assertEquals(90, PhoneRemoteLinks.startSeconds("90"))
        assertEquals(90, PhoneRemoteLinks.startSeconds("90s"))
        assertEquals(3723, PhoneRemoteLinks.startSeconds("1h2m3s"))
        assertNull(PhoneRemoteLinks.startSeconds("0"))
        assertNull(PhoneRemoteLinks.startSeconds("soon"))
    }

    @Test
    fun theThrottleSendsChangesAndJumpsButNotTicks() {
        var now = 0L
        val throttle = RemoteStateThrottle(now = { now })
        val playing = RemoteNowPlaying(kind = "music", title = "Billie Jean", isPlaying = true, positionMs = 10_000L, durationMs = 294_000L)
        assertTrue(throttle.shouldSend(playing))
        now = 1_000L
        assertFalse(throttle.shouldSend(playing.copy(positionMs = 11_000L)), "a tick on time is not sent")
        now = 1_500L
        assertTrue(throttle.shouldSend(playing.copy(positionMs = 60_000L)), "a seek is sent")
        now = 2_000L
        assertTrue(throttle.shouldSend(playing.copy(positionMs = 60_500L, isPlaying = false)), "a pause is sent")
        now = 30_000L
        assertFalse(throttle.shouldSend(playing.copy(positionMs = 60_500L, isPlaying = false)), "nothing changes while paused")
        assertTrue(throttle.shouldSend(null), "stopping is sent")
        assertFalse(throttle.shouldSend(null))
    }

    @Test
    fun stateLinesEncodeTheirFields() {
        assertEquals("IDLE", PhoneRemoteProtocol.stateLine(null))
        assertEquals(
            "STATE film 1 5000 7200000 80 150 0 Blade%20Runner%202049 S01E02%20%C2%B7%20Pilot https%3A%2F%2Fimg%2Fp.jpg " +
                "English%20SDH English%205.1 - - 0 0 - - -",
            PhoneRemoteProtocol.stateLine(
                RemoteNowPlaying(
                    kind = "film", title = "Blade Runner 2049", subtitle = "S01E02 · Pilot", imageUrl = "https://img/p.jpg",
                    isPlaying = true, positionMs = 5_000L, durationMs = 7_200_000L, volume = 80, volumeMax = 150,
                    subtitleTrack = "English SDH", audioTrack = "English 5.1",
                ),
            ),
        )
    }

    @Test
    fun themeLinesAreSixDigitHex() {
        assertEquals("THEME 1E88E5 - 0D0D0F", PhoneRemoteProtocol.themeLine(0xFF1E88E5.toInt(), null, 0xFF0D0D0F.toInt()))
        assertEquals("THEME FFD700 FF8800 0B0F10", PhoneRemoteProtocol.themeLine(0xFFFFD700.toInt(), 0xFFFF8800.toInt(), 0xFF0B0F10.toInt()))
    }
}
