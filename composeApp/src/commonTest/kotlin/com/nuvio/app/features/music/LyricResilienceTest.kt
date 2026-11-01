package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricResilienceTest {
    private class MemoryBackend : LyricCacheBackend {
        val map = LinkedHashMap<String, String>()
        override fun keys(): Set<String> = map.keys.toSet()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val day = 24L * 60L * 60L * 1_000L

    private val lrclib = Lyrics.Synced(
        lines = listOf(LyricLine(2_000, "First line"), LyricLine(6_000, "Second line")),
        durationSeconds = 200.0,
        source = LRCLIB_SOURCE,
    )

    @Test
    fun anLrclibLyricIsStillThereWeeksLaterWhenLrclibIsDown() {
        val store = LyricCacheStore(MemoryBackend())
        store.put(LRCLIB_SOURCE, "abcdefghijk", nowMs = 1_000L, lyrics = lrclib)
        // Twenty-nine days on, with no network: the remembered lyric is returned as it was stored, source and length included.
        val back = store.get(LRCLIB_SOURCE, "abcdefghijk", nowMs = 1_000L + 29 * day)
        assertNotNull(back)
        assertEquals(lrclib, back.lyrics)
        assertEquals("Line-synced · LRCLIB", back.lyrics?.sourceNote())
        // Past the trust period it is dropped, so a corrected lyric is eventually picked up.
        assertNull(store.get(LRCLIB_SOURCE, "abcdefghijk", nowMs = 1_000L + 31 * day))
    }

    @Test
    fun lrclibEntriesDoNotMixWithTheOtherSources() {
        val store = LyricCacheStore(MemoryBackend())
        store.put(LRCLIB_SOURCE, "abcdefghijk", 1_000L, lrclib)
        assertNull(store.get("unison", "abcdefghijk", 1_000L))
        assertNull(store.get("netease", "abcdefghijk", 1_000L))
        assertNotNull(store.get(LRCLIB_SOURCE, "abcdefghijk", 1_000L))
    }

    @Test
    fun theLyricViewAsksAgainSoonThenSettlesToOnceAMinute() {
        assertEquals(20_000L, lyricsRetryDelayMs(0))
        assertEquals(40_000L, lyricsRetryDelayMs(1))
        assertEquals(60_000L, lyricsRetryDelayMs(2))
        assertEquals(60_000L, lyricsRetryDelayMs(50))
        assertTrue(lyricsRetryDelayMs(-1) > 0L)
    }

    @Test
    fun theCacheHoldsRoomForAFewHundredTracks() {
        // LRCLIB plus four word-timing sources can each leave an entry for a track.
        assertTrue(LYRIC_CACHE_MAX_ENTRIES >= 5 * 150)
    }
}
