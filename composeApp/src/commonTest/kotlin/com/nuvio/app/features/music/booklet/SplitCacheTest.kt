package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitCacheTest {
    private class MapStore : TextStore {
        val map = mutableMapOf<String, String>()
        override fun keys() = map.keys.toSet()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private fun hit(source: BookletSource) = encodeBookletEntry(BookletEntry(5L, AlbumBooklet("An intro that is long enough.", emptyList(), emptyMap(), source)))
    private val miss = encodeBookletEntry(BookletEntry(5L, null))

    @Test
    fun aiFactsGoToTheKeptStoreAndTheRestToTheCappedOne() {
        val capped = MapStore(); val kept = MapStore()
        val cache = SplitCache(capped, kept)
        cache.put("ai", hit(BookletSource.Ai))
        cache.put("wiki", hit(BookletSource.Wikipedia))
        cache.put("miss", miss)
        assertEquals(setOf("ai"), kept.keys())
        assertEquals(setOf("wiki", "miss"), capped.keys())
        assertNotNull(cache.get("ai"))
        assertNotNull(cache.get("wiki"))
        assertEquals(setOf("wiki", "miss"), cache.cappedKeys())
    }

    @Test
    fun oldAiFactsInTheCappedStoreAreMovedOnFirstUse() {
        val capped = MapStore(); val kept = MapStore()
        capped.put("ai", hit(BookletSource.Ai))
        capped.put("wiki", hit(BookletSource.Wikipedia))
        val cache = SplitCache(capped, kept)
        assertEquals(setOf("ai"), cache.keptKeys())
        assertEquals(setOf("wiki"), capped.keys())
        assertTrue(cache.get("ai") != null)
    }

    @Test
    fun droppingTouchesOnlyTheCappedStoreAndNullRemovesFromBoth() {
        val capped = MapStore(); val kept = MapStore()
        val cache = SplitCache(capped, kept)
        cache.put("ai", hit(BookletSource.Ai))
        cache.put("wiki", hit(BookletSource.Wikipedia))
        cache.drop("wiki"); cache.drop("ai")
        assertNull(cache.get("wiki"))
        assertNotNull(cache.get("ai"), "an AI fact is not dropped by the capped store's trimming")
        cache.put("ai", null)
        assertNull(cache.get("ai"))
    }
}
