package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * This cache exists so that scrolling a card away and back does not re-run a 2-5 ms reduction on the
 * draw thread. The properties that matter are that a re-entering card finds its own bitmap, that two
 * cards at different sizes never share one, and that a long browse cannot grow it without bound.
 */
class ScaledBitmapCacheTest {

    @BeforeTest
    fun setUp() = ScaledBitmapCache.clear()

    @AfterTest
    fun tearDown() = ScaledBitmapCache.clear()

    private fun bitmap(width: Int, height: Int) = ImageBitmap(width, height)

    @Test
    fun `a card returning to the viewport finds the reduction the previous painter paid for`() {
        val poster = bitmap(210, 315)
        val key = ScaledBitmapCache.key("https://example/poster.jpg", IntSize(210, 315))

        ScaledBitmapCache.put(key, poster)

        assertSame(poster, ScaledBitmapCache.get(key))
    }

    @Test
    fun `the same source at two card sizes keeps two entries`() {
        val source = "https://example/poster.jpg"
        val small = bitmap(210, 315)
        val large = bitmap(315, 472)

        ScaledBitmapCache.put(ScaledBitmapCache.key(source, IntSize(210, 315)), small)
        ScaledBitmapCache.put(ScaledBitmapCache.key(source, IntSize(315, 472)), large)

        assertSame(small, ScaledBitmapCache.get(ScaledBitmapCache.key(source, IntSize(210, 315))))
        assertSame(large, ScaledBitmapCache.get(ScaledBitmapCache.key(source, IntSize(315, 472))))
    }

    @Test
    fun `different sources never collide`() {
        val size = IntSize(210, 315)
        val a = bitmap(210, 315)
        val b = bitmap(210, 315)

        ScaledBitmapCache.put(ScaledBitmapCache.key("a", size), a)
        ScaledBitmapCache.put(ScaledBitmapCache.key("b", size), b)

        assertSame(a, ScaledBitmapCache.get(ScaledBitmapCache.key("a", size)))
        assertSame(b, ScaledBitmapCache.get(ScaledBitmapCache.key("b", size)))
    }

    @Test
    fun `a missing entry reports itself rather than throwing`() {
        assertNull(ScaledBitmapCache.get(ScaledBitmapCache.key("never-stored", IntSize(1, 1))))
    }

    @Test
    fun `a long browse cannot grow the cache past its budget`() {
        // 210x315 at 4 bytes per pixel is ~265 KB, so this stores roughly four times the budget.
        val entries = (ScaledBitmapCache.MaxBytes / (210L * 315L * 4)).toInt() * 4
        repeat(entries) { index ->
            ScaledBitmapCache.put(ScaledBitmapCache.key("poster-$index", IntSize(210, 315)), bitmap(210, 315))
        }

        val (count, bytes) = ScaledBitmapCache.debugState()
        assertEquals(true, bytes <= ScaledBitmapCache.MaxBytes, "held $bytes bytes, budget ${ScaledBitmapCache.MaxBytes}")
        assertEquals(true, count < entries, "nothing was evicted: $count of $entries still held")
    }

    @Test
    fun `eviction drops the least recently used, not the oldest`() {
        val perEntry = 210L * 315L * 4
        val capacity = (ScaledBitmapCache.MaxBytes / perEntry).toInt()

        repeat(capacity) { index ->
            ScaledBitmapCache.put(ScaledBitmapCache.key("poster-$index", IntSize(210, 315)), bitmap(210, 315))
        }
        // Touch the oldest so it becomes the most recently used.
        val oldestKey = ScaledBitmapCache.key("poster-0", IntSize(210, 315))
        assertNotNull(ScaledBitmapCache.get(oldestKey))

        // Push the cache over its budget, which must evict poster-1 rather than the freshly read one.
        repeat(2) { index ->
            ScaledBitmapCache.put(ScaledBitmapCache.key("late-$index", IntSize(210, 315)), bitmap(210, 315))
        }

        assertNotNull(ScaledBitmapCache.get(oldestKey), "the recently read entry was evicted")
        assertNull(ScaledBitmapCache.get(ScaledBitmapCache.key("poster-1", IntSize(210, 315))))
    }
}
