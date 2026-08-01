package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCatalogSectionTest {
    @Test
    fun `can open catalog when page has more below preview limit`() {
        val section = section(
            availableItemCount = 11,
            hasMore = true,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `can open catalog when non paginated section exceeds preview limit`() {
        val section = section(
            availableItemCount = 19,
            hasMore = false,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `cannot open catalog when section fits preview and has no more pages`() {
        val section = section(
            availableItemCount = 11,
            hasMore = false,
        )

        assertFalse(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun `paginating catalog uses infinite home row by default`() {
        val section = section(
            availableItemCount = 20,
            hasMore = true,
            paginates = true,
        )

        assertTrue(section.usesInfiniteHomeRow(catalogSeeMoreEnabled = false))
    }

    @Test
    fun `see more preference disables infinite home row without changing pagination capability`() {
        val section = section(
            availableItemCount = 20,
            hasMore = true,
            paginates = true,
        )

        assertFalse(section.usesInfiniteHomeRow(catalogSeeMoreEnabled = true))
        assertTrue(section.paginates)
        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    private fun section(
        availableItemCount: Int,
        hasMore: Boolean,
        paginates: Boolean = false,
    ): HomeCatalogSection =
        HomeCatalogSection(
            key = "addon:movie:popular",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "popular",
                supportsPagination = hasMore,
            ),
            items = listOf(
                MetaPreview(
                    id = "tt1",
                    type = "movie",
                    name = "Movie",
                ),
            ),
            availableItemCount = availableItemCount,
            hasMore = hasMore,
            paginates = paginates,
        )
}
