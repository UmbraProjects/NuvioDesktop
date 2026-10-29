package com.nuvio.app.features.music.torbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorboxLookupCacheTest {
    private val hit = StoredHit("abc", "Artist - Album [FLAC]", "magnet:?xt=urn:btih:abc", 12, 100)

    @Test
    fun roundTripsAndSurvivesGarbage() {
        val book = TorboxLookupBook().withLookup("album1", StoredLookup(1_000, listOf(hit)))
        assertEquals(book, decodeTorboxLookups(encodeTorboxLookups(book)))
        assertTrue(decodeTorboxLookups("not json").lookups.isEmpty())
        assertTrue(decodeTorboxLookups(null).lookups.isEmpty())
    }

    @Test
    fun foundLastsAWeekAndNotCachedADay() {
        val found = StoredLookup(0, listOf(hit))
        val none = StoredLookup(0)
        assertTrue(found.isFresh(TORBOX_FOUND_TTL_MS - 1))
        assertFalse(found.isFresh(TORBOX_FOUND_TTL_MS))
        assertTrue(none.isFresh(TORBOX_NONE_TTL_MS - 1))
        assertFalse(none.isFresh(TORBOX_NONE_TTL_MS))
        assertFalse(found.isFresh(-5)) // clock went backwards
    }

    @Test
    fun oldestEntriesDropPastTheLimit() {
        var book = TorboxLookupBook()
        repeat(520) { book = book.withLookup("a$it", StoredLookup(it.toLong(), listOf(hit))) }
        assertEquals(500, book.lookups.size)
        assertFalse("a0" in book.lookups)
        assertTrue("a519" in book.lookups)
        assertFalse("a519" in book.without("a519").lookups)
    }

    @Test
    fun storedHitsComeBackCached() {
        assertTrue(hit.toHit().cached)
        assertEquals(hit, hit.toHit().toStored())
    }
}
