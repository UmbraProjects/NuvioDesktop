package com.nuvio.app.core.ui

import coil3.Canvas
import coil3.Image
import coil3.memory.MemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopArtworkMemoryCacheLargeCapTest {

    private class FakeImage(override val size: Long) : Image {
        override val width = 1
        override val height = 1
        override val shareable = true
        override fun draw(canvas: Canvas) = Unit
    }

    private val mb = 1024L * 1024

    private fun newCache() = DesktopArtworkMemoryCache(
        MemoryCache.Builder().maxSizeBytes(1024 * mb).build(),
        maxLargeEntries = { 3 },
    )

    private fun key(name: String) = MemoryCache.Key(name)

    private fun MemoryCache.put(name: String, bytes: Long) {
        this[key(name)] = MemoryCache.Value(FakeImage(bytes))
    }

    @Test
    fun keepsOnlyTheThreeMostRecentLargeBitmaps() {
        val cache = newCache()
        listOf("hero1", "hero2", "hero3", "hero4").forEach { cache.put(it, 20 * mb) }
        assertNull(cache[key("hero1")])
        listOf("hero2", "hero3", "hero4").forEach { assertNotNull(cache[key(it)], it) }
    }

    @Test
    fun smallBitmapsAreNotCounted() {
        val cache = newCache()
        (1..50).forEach { cache.put("poster$it", 300 * 1024L) }
        listOf("hero1", "hero2", "hero3").forEach { cache.put(it, 20 * mb) }
        assertEquals(53, cache.keys.size)
    }

    @Test
    fun aCacheHitCountsAsRecentUse() {
        val cache = newCache()
        listOf("hero1", "hero2", "hero3").forEach { cache.put(it, 20 * mb) }
        assertNotNull(cache[key("hero1")]) // hero1 is now the most recent
        cache.put("hero4", 20 * mb)
        assertNull(cache[key("hero2")])
        assertNotNull(cache[key("hero1")])
    }
}
