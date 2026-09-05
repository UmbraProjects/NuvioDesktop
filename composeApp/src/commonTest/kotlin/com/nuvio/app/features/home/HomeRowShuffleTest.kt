package com.nuvio.app.features.home

import com.nuvio.app.core.ui.ShuffleDealMaxStagger
import com.nuvio.app.core.ui.ShuffleDealSettled
import com.nuvio.app.core.ui.shuffleDealProgressFor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HomeRowShuffleTest {

    @Test
    fun `an unshuffled row is returned untouched`() {
        val items = previews(5)

        assertEquals(items, applyHomeRowShuffle(items, order = null))
    }

    @Test
    fun `the stored key order drives the result`() {
        val items = previews(4)
        val order = HomeRowShuffleOrder(
            generation = 1,
            keys = listOf("movie:tt3", "movie:tt0", "movie:tt2", "movie:tt1"),
        )

        val shuffled = applyHomeRowShuffle(items, order)

        assertEquals(listOf("tt3", "tt0", "tt2", "tt1"), shuffled.map { it.id })
    }

    @Test
    fun `items that arrived after the shuffle keep catalog order at the end`() {
        // Infinite scroll appends pages under a shuffled row. Those must not be re-rolled into the
        // middle of what the user is currently looking at.
        val items = previews(6)
        val order = HomeRowShuffleOrder(
            generation = 1,
            keys = listOf("movie:tt2", "movie:tt0", "movie:tt1"),
        )

        val shuffled = applyHomeRowShuffle(items, order)

        assertEquals(listOf("tt2", "tt0", "tt1", "tt3", "tt4", "tt5"), shuffled.map { it.id })
    }

    @Test
    fun `a key the row no longer holds is skipped rather than leaving a hole`() {
        val items = previews(3)
        val order = HomeRowShuffleOrder(
            generation = 2,
            keys = listOf("movie:tt2", "movie:ttGONE", "movie:tt0", "movie:tt1"),
        )

        val shuffled = applyHomeRowShuffle(items, order)

        assertEquals(listOf("tt2", "tt0", "tt1"), shuffled.map { it.id })
    }

    @Test
    fun `duplicate keys survive a shuffle instead of collapsing`() {
        // Two entries can share a stableKey — an addon listing the same title twice. A key-to-item
        // map would drop one and silently shorten the row every time it was shuffled.
        val items = listOf(
            preview("tt0"),
            preview("tt1"),
            preview("tt0"),
        )
        val order = HomeRowShuffleOrder(generation = 1, keys = listOf("movie:tt0", "movie:tt1"))

        val shuffled = applyHomeRowShuffle(items, order)

        assertEquals(3, shuffled.size)
        assertEquals(2, shuffled.count { it.id == "tt0" })
    }

    @Test
    fun `rolling moves the head so the button never looks dead`() {
        val items = previews(8)

        repeat(20) { seed ->
            val keys = rollHomeRowShuffleKeys(
                items = items,
                previousHeadKey = "movie:tt0",
                random = Random(seed),
            )

            assertNotEquals("movie:tt0", keys.first())
            assertEquals(items.size, keys.size)
        }
    }

    @Test
    fun `rolling a row whose entries all share one key terminates`() {
        val items = listOf(preview("tt0"), preview("tt0"), preview("tt0"))

        val keys = rollHomeRowShuffleKeys(
            items = items,
            previousHeadKey = "movie:tt0",
            random = Random(1),
        )

        assertEquals(listOf("movie:tt0", "movie:tt0", "movie:tt0"), keys)
    }

    @Test
    fun `a shuffle is a permutation, so index-based lookups stay in range`() {
        // Load-bearing. tvRows indexes the same positions the row draws — metaItems[itemIndex] is
        // what the immersive hero describes, and onEnter(index) is what Enter opens. Both apply
        // this function independently, so they only agree if it neither drops nor invents entries.
        // The first version of this feature did not apply it to tvRows at all: the hero showed one
        // film's metadata while the row highlighted another.
        val items = previews(30)
        val order = HomeRowShuffleOrder(
            generation = 1,
            keys = items.map { it.stableKey() }.shuffled(Random(7)),
        )

        val shuffled = applyHomeRowShuffle(items, order)

        assertEquals(items.size, shuffled.size)
        assertEquals(items.toSet(), shuffled.toSet())
        assertEquals(items.sortedBy { it.id }, shuffled.sortedBy { it.id })
    }

    @Test
    fun `a short row offers no shuffle`() {
        assertTrue(section(itemCount = HOME_ROW_SHUFFLE_MIN_ITEMS).canShuffleRow())
        assertTrue(!section(itemCount = HOME_ROW_SHUFFLE_MIN_ITEMS - 1).canShuffleRow())
    }

    @Test
    fun `the deal driver settles every card, including the most delayed one`() {
        // The driver's range runs past 1 by exactly the largest stagger. If it did not, the tail of
        // a long row would still be folded edge-on when the animation ended.
        assertEquals(1f, shuffleDealProgressFor(ShuffleDealSettled, index = 0))
        assertEquals(1f, shuffleDealProgressFor(ShuffleDealSettled, index = 500))
    }

    @Test
    fun `cards deal in order, and far-off cards share the last beat`() {
        val driver = 0.5f

        val first = shuffleDealProgressFor(driver, index = 0)
        val third = shuffleDealProgressFor(driver, index = 2)

        assertTrue(first > third, "earlier cards must lead: $first !> $third")
        assertEquals(
            shuffleDealProgressFor(driver, index = 400),
            shuffleDealProgressFor(driver, index = 4000),
        )
        assertTrue(ShuffleDealMaxStagger < ShuffleDealSettled)
    }

    private fun previews(count: Int): List<MetaPreview> = (0 until count).map { preview("tt$it") }

    private fun preview(id: String): MetaPreview =
        MetaPreview(id = id, type = "movie", name = "Movie $id")

    private fun section(itemCount: Int): HomeCatalogSection = HomeCatalogSection(
        key = "addon:movie:top",
        title = "Top",
        subtitle = "Addon",
        addonName = "Addon",
        target = null,
        items = previews(itemCount),
        availableItemCount = itemCount,
        hasMore = false,
    )
}
