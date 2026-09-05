package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A refresh stages its results in a map snapshotted when it began, so pages appended by horizontal
 * pagination while it runs are missing from that snapshot. Writing it back verbatim dropped them,
 * the row re-published its first page, and — with the scroll position still past the paging
 * threshold — it requested the same page again, and again on the next batch. A single startup
 * refetched `skip=20` fifteen times while the row snapped backwards on each cycle.
 */
class HomeSectionBatchMergeTest {

    @Test
    fun `pages appended during a refresh survive the batch write-back`() {
        val staged = mapOf(KEY to section(itemCount = 20))
        val live = mapOf(KEY to section(itemCount = 40))

        val merged = mergeHomeSectionBatch(live = live, staged = staged)

        assertEquals(40, merged.getValue(KEY).items.size)
    }

    @Test
    fun `a batch returning more items than the cache still wins`() {
        val staged = mapOf(KEY to section(itemCount = 60))
        val live = mapOf(KEY to section(itemCount = 40))

        val merged = mergeHomeSectionBatch(live = live, staged = staged)

        assertEquals(60, merged.getValue(KEY).items.size)
    }

    @Test
    fun `a forced refresh carries nothing across, because it clears the cache first`() {
        val staged = mapOf(KEY to section(itemCount = 20))

        val merged = mergeHomeSectionBatch(live = emptyMap(), staged = staged)

        assertEquals(20, merged.getValue(KEY).items.size)
    }

    @Test
    fun `sections the batch did not stage are not resurrected from the cache`() {
        // The staged map is the authority on which sections exist; preserving item counts must not
        // also preserve membership, or a removed catalog would come back.
        val staged = mapOf(KEY to section(itemCount = 20))
        val live = mapOf(KEY to section(itemCount = 40), "gone" to section(itemCount = 99))

        val merged = mergeHomeSectionBatch(live = live, staged = staged)

        assertEquals(setOf(KEY), merged.keys)
    }

    @Test
    fun `each section is decided independently`() {
        val staged = mapOf(KEY to section(itemCount = 20), OTHER to section(itemCount = 80))
        val live = mapOf(KEY to section(itemCount = 40), OTHER to section(itemCount = 20))

        val merged = mergeHomeSectionBatch(live = live, staged = staged)

        assertEquals(40, merged.getValue(KEY).items.size)
        assertEquals(80, merged.getValue(OTHER).items.size)
    }

    @Test
    fun `an equal count keeps the freshly staged section`() {
        val staged = mapOf(KEY to section(itemCount = 20, title = "fresh"))
        val live = mapOf(KEY to section(itemCount = 20, title = "stale"))

        val merged = mergeHomeSectionBatch(live = live, staged = staged)

        assertEquals("fresh", merged.getValue(KEY).title)
    }

    private fun section(
        itemCount: Int,
        title: String = "Box Office",
    ): HomeCatalogSection = HomeCatalogSection(
        key = KEY,
        title = title,
        subtitle = "AIOMetadata",
        addonName = "AIOMetadata",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.com/manifest.json",
            contentType = "movie",
            catalogId = "simkl.recipe.boxoffice.movies",
            supportsPagination = true,
        ),
        items = List(itemCount) { index ->
            MetaPreview(id = "tt$index", type = "movie", name = "Movie $index")
        },
        paginates = true,
        nextSkip = itemCount,
        hasMore = true,
    )

    private companion object {
        const val KEY = "aio-metadata:Films:simkl.recipe.boxoffice.movies"
        const val OTHER = "aio-metadata:Films:other"
    }
}
