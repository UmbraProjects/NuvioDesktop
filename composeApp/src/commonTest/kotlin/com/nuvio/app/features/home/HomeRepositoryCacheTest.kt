package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRepositoryCacheTest {
    @Test
    fun `completed empty catalog is not fetched again during normal synchronization`() {
        assertFalse(
            shouldFetchHomeCatalog(
                force = false,
                cachedSection = emptySection(),
            ),
        )
    }

    @Test
    fun `missing catalog is fetched during normal synchronization`() {
        assertTrue(
            shouldFetchHomeCatalog(
                force = false,
                cachedSection = null,
            ),
        )
    }

    @Test
    fun `explicit refresh refetches an empty cached catalog`() {
        assertTrue(
            shouldFetchHomeCatalog(
                force = true,
                cachedSection = emptySection(),
            ),
        )
    }

    private fun emptySection(): HomeCatalogSection =
        HomeCatalogSection(
            key = "aiometadata:movie:mdblist.123",
            title = "MDBList",
            subtitle = "AIOMetadata",
            addonName = "AIOMetadata",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "mdblist.123",
                supportsPagination = true,
            ),
            items = emptyList(),
        )
}
