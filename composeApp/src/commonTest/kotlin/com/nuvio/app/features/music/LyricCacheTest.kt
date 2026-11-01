package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricCacheTest {
    private class MemoryBackend : LyricCacheBackend {
        val map = LinkedHashMap<String, String>()
        override fun keys(): Set<String> = map.keys.toSet()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val lyrics = Lyrics.Synced(
        lines = listOf(
            LyricLine(2_000, "Paper boats", listOf(TimedWord(2_000, 2_900, "Paper"), TimedWord(2_900, 3_300, "boats"))),
            LyricLine(4_100, ""),
            LyricLine(20_000, "Only \"line\" timed, with a \\ and é你"),
        ),
        durationSeconds = 61.5,
        source = "Unison",
    )

    private val day = 24L * 60L * 60L * 1_000L

    @Test
    fun aLyricSurvivesBeingWrittenAndRead() {
        val read = decodeCachedLyric(encodeCachedLyric(123_456L, lyrics))
        assertNotNull(read)
        assertEquals(123_456L, read.atEpochMs)
        assertEquals(lyrics, read.lyrics)
    }

    @Test
    fun aRememberedMissComesBackAsAMiss() {
        val read = decodeCachedLyric(encodeCachedLyric(99L, null))
        assertEquals(CachedLyric(99L, null), read)
    }

    @Test
    fun theAgeCanBeReadWithoutParsingTheRest() {
        assertTrue(encodeCachedLyric(1_700_000_000_000L, lyrics).startsWith("{\"at\":1700000000000"))
    }

    @Test
    fun damagedEntriesAreNotEntries() {
        assertNull(decodeCachedLyric(""))
        assertNull(decodeCachedLyric("not json"))
        assertNull(decodeCachedLyric("{\"l\":[]}"))
        assertNull(decodeCachedLyric("{\"at\":5,\"l\":[[\"x\"]]}"))
        assertNull(decodeCachedLyric("[1,2]"))
    }

    @Test
    fun aFoundLyricIsTrustedForThirtyDaysAndAMissForSeven() {
        val store = LyricCacheStore(MemoryBackend())
        store.put("unison", "abc", 0L, lyrics)
        store.put("amll", "abc", 0L, null)
        assertEquals(lyrics, store.get("unison", "abc", 29 * day)?.lyrics)
        assertNull(store.get("unison", "abc", 31 * day))
        assertEquals(CachedLyric(0L, null), store.get("amll", "abc", 6 * day))
        assertNull(store.get("amll", "abc", 8 * day))
    }

    @Test
    fun aStaleEntryIsDroppedAndEachSourceHasItsOwn() {
        val backend = MemoryBackend()
        val store = LyricCacheStore(backend)
        store.put("unison", "abc", 0L, lyrics)
        assertNull(store.get("netease", "abc", 1L))
        assertNull(store.get("unison", "other", 1L))
        assertNull(store.get("unison", "abc", 40 * day))
        assertTrue(backend.map.isEmpty())
    }

    @Test
    fun anEntryFromTheFutureIsNotTrusted() {
        val store = LyricCacheStore(MemoryBackend())
        store.put("unison", "abc", 10 * day, lyrics)
        assertNull(store.get("unison", "abc", 1 * day))
    }

    @Test
    fun theOldestEntriesGoWhenThereAreTooMany() {
        val backend = MemoryBackend()
        val store = LyricCacheStore(backend, maxEntries = 3, slack = 0)
        for (i in 1..5) store.put("unison", "track$i", i * 1_000L, lyrics)
        assertEquals(3, backend.map.size)
        assertNull(store.get("unison", "track1", 6_000L))
        assertNull(store.get("unison", "track2", 6_000L))
        assertNotNull(store.get("unison", "track5", 6_000L))
    }

    @Test
    fun entriesFromOtherVersionsOfTheCacheAreLeftAlone() {
        val backend = MemoryBackend()
        backend.map["v0:unison:abc"] = "old layout"
        val store = LyricCacheStore(backend, maxEntries = 1, slack = 0)
        store.put("unison", "a", 1L, lyrics)
        store.put("unison", "b", 2L, lyrics)
        assertEquals("old layout", backend.map["v0:unison:abc"])
    }
}
