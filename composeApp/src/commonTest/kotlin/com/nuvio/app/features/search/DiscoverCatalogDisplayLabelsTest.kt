package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverCatalogDisplayLabelsTest {
    @Test
    fun `adjacent duplicate catalog names include content type`() {
        val labels = discoverCatalogDisplayLabels(
            listOf(
                catalog(name = "Trending", type = "movie", key = "movie"),
                catalog(name = "Trending", type = "series", key = "series"),
                catalog(name = "Popular", type = "movie", key = "popular"),
            ),
        )

        assertEquals(listOf("Trending (Movie)", "Trending (Show)", "Popular"), labels)
    }

    @Test
    fun `non-adjacent matching names remain unqualified`() {
        val labels = discoverCatalogDisplayLabels(
            listOf(
                catalog(name = "Trending", type = "movie", key = "first"),
                catalog(name = "Popular", type = "movie", key = "middle"),
                catalog(name = "Trending", type = "series", key = "last"),
            ),
        )

        assertEquals(listOf("Trending", "Popular", "Trending"), labels)
    }

    private fun catalog(name: String, type: String, key: String) = DiscoverCatalogOption(
        key = key,
        addonName = "Test",
        manifestUrl = "https://example.invalid/manifest.json",
        type = type,
        catalogId = key,
        catalogName = name,
    )
}
