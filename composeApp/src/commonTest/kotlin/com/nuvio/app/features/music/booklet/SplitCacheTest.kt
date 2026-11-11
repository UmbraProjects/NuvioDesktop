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

    @Test
    fun anotherModelsFactIsUsedWhenThisModelHasNone() {
        val capped = MapStore(); val kept = MapStore()
        var model = "openai/gpt-oss-120b"
        val cache = SplitCache(capped, kept, model = { model })
        cache.put("v4|ai:llama-3.3-70b|adele|21", hit(BookletSource.Ai))
        cache.put("story1|adele|hello|ai:llama-3.3-70b", hit(BookletSource.Ai))
        cache.put("tnotes1|adele|25|llama-3.3-70b", miss) // a miss is no stand-in
        // Same thing, another model: found. The story's ":web" mode still matches the plain one.
        assertNotNull(cache.get("v4|ai:openai/gpt-oss-120b|adele|21"))
        assertNotNull(cache.get("story1|adele|hello|ai:openai/gpt-oss-120b:web"))
        // Another album or song: not found.
        assertNull(cache.get("v4|ai:openai/gpt-oss-120b|adele|25"))
        assertNull(cache.get("story1|adele|hell|ai:openai/gpt-oss-120b"))
        assertNull(cache.get("tnotes1|adele|25|openai/gpt-oss-120b"))
        // This model's own miss gives way to another model's fact; its own fact wins over it.
        cache.put("v4|ai:openai/gpt-oss-120b|adele|21", miss)
        assertNotNull(cache.get("v4|ai:openai/gpt-oss-120b|adele|21"))
        model = ""
        assertEquals(miss, cache.get("v4|ai:openai/gpt-oss-120b|adele|21"))
    }

    @Test
    fun theModelMustBeAWholeSegment() {
        assertEquals(6, modelSegmentAt("v4|ai:m1|a|b", "m1"))
        assertNull(modelSegmentAt("v4|ai:m10|a|b", "m1"))
        assertEquals(9, modelSegmentAt("cut1|m1x|m1", "m1"))
    }
}
