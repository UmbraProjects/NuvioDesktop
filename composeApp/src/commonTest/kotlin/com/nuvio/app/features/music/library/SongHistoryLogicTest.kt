package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongHistoryLogicTest {
    private fun run(t: ListenTracker, key: String?, fromMs: Long, toMs: Long, durationMs: Long = 240_000, playing: Boolean = true): List<ListenVerdict> {
        val out = ArrayList<ListenVerdict>()
        var at = fromMs
        while (at <= toMs) { out += t.feed(ListenSample(key, at - fromMs, durationMs, playing, at)); at += 250 }
        return out
    }

    @Test
    fun thirtySecondsCountsAsAPlayOnce() {
        val t = ListenTracker()
        val v = run(t, "a", 0, 60_000)
        assertEquals(listOf(ListenVerdict("a", true)), v)
    }

    @Test
    fun aShortSongNeedsHalfItsLength() {
        val t = ListenTracker()
        assertTrue(run(t, "a", 0, 9_000, durationMs = 20_000).isEmpty())
        assertEquals(listOf(ListenVerdict("a", true)), run(t, "a", 9_250, 10_500, durationMs = 20_000))
    }

    @Test
    fun leavingEarlyIsASkipButABlinkIsNothing() {
        val t = ListenTracker()
        run(t, "a", 0, 10_000)
        assertEquals(listOf(ListenVerdict("a", false)), t.feed(ListenSample("b", 0, 240_000, true, 10_250)))
        assertTrue(t.feed(ListenSample("c", 0, 240_000, true, 10_500)).isEmpty()) // b heard for 0.25 s
    }

    @Test
    fun pausedTimeAndLongGapsDoNotCount() {
        val t = ListenTracker()
        assertTrue(run(t, "a", 0, 60_000, playing = false).isEmpty())
        t.feed(ListenSample("a", 0, 240_000, true, 70_000))
        assertTrue(t.feed(ListenSample("a", 0, 240_000, true, 600_000)).isEmpty()) // a 9-minute gap counts as 2 s
    }

    @Test
    fun playingTheSongAgainFromTheStartIsANewListen() {
        val t = ListenTracker()
        run(t, "a", 0, 31_000)
        t.feed(ListenSample("a", 200_000, 240_000, true, 31_250))
        t.feed(ListenSample("a", 500, 240_000, true, 31_500)) // back to the start
        val again = run(t, "a", 31_750, 62_000)
        assertEquals(listOf(ListenVerdict("a", true)), again)
    }

    @Test
    fun statsAddUpAndSurviveStorage() {
        val s = null.plus(true, 100).plus(false, 200).plus(true, 300)
        assertEquals(SongStat(2, 1, 100, 300), s)
        assertEquals(s, decodeSongStat(encodeSongStat(s)))
        assertEquals(null, decodeSongStat("junk"))
        // Each version counts on its own; the plain title keys as it always did, so its plays carry over.
        assertEquals("song1|MPREb_x|virtualinsanity", songHistoryKey("MPREb_x", "Virtual Insanity"))
        assertEquals("song1|MPREb_x|virtualinsanitysalaamremiremix", songHistoryKey("MPREb_x", "Virtual Insanity (Salaam Remi Remix)"))
        assertEquals("song1|MPREb_x|virtualinsanityliveattheverona", songHistoryKey("MPREb_x", "Virtual Insanity - Live at the Verona"))
    }

    @Test
    fun theAlbumSummary() {
        val stats = listOf(SongStat(47, 0, 10, 900), null, SongStat(1, 0, 5, 50), SongStat(0, 3, 0, 0), null)
        val h = albumHistory(stats, listOf(300, 200, 100, 240, 60), listOf(true, true, true, true, false))
        assertEquals(48, h.totalPlays)
        assertEquals(47L * 300 + 100, h.listenedSeconds)
        assertEquals(5L, h.firstAtMs)
        assertEquals(900L, h.lastAtMs)
        assertEquals(listOf(0, 2), h.top)
        assertEquals(listOf(1, 3), h.never) // the unavailable song is not offered
        assertEquals(1, h.playedOnce)
        assertEquals(listOf(3), h.oftenSkipped)
        assertEquals(440, h.neverSeconds)
        assertEquals("13 hours", listenedText(47_000))
    }

    @Test
    fun datesRead() {
        assertEquals("March 2026", monthYear(1_773_000_000_000L)) // 8 Mar 2026
        assertEquals("January 1970", monthYear(0))
        val now = 1_793_830_000_000L // 4 Nov 2026
        assertEquals("Today", whenText(now - 1_000, now))
        assertEquals("Yesterday", whenText(now - 86_400_000L, now))
        assertEquals("5 days ago", whenText(now - 5 * 86_400_000L, now))
        assertEquals("March 2026", whenText(1_773_000_000_000L, now))
    }
}
