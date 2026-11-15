package com.nuvio.app.features.catalog

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/** See more's filters on what has loaded (2026-11-15). */
class CatalogLocalFiltersTest {
    private fun item(id: String, type: String = "movie", year: String? = null, rating: String? = null) =
        MetaPreview(id = id, type = type, name = id, releaseInfo = year, imdbRating = rating)

    private val items = listOf(
        item("a", year = "2026", rating = "7.4"),
        item("b", type = "series", year = "2019–2023", rating = "8.1"),
        item("c", year = "1994", rating = "6.2"),
        item("d"),
    )

    private fun ids(filters: CatalogLocalFilters, watched: Set<String> = emptySet()) =
        applyCatalogFilters(items, filters) { it.id in watched }.map { it.id }

    @Test
    fun `no filters keeps everything, titles without a year or rating included`() {
        assertEquals(listOf("a", "b", "c", "d"), ids(CatalogLocalFilters()))
    }

    @Test
    fun `type, year and rating each narrow it`() {
        assertEquals(listOf("b"), ids(CatalogLocalFilters(type = "series")))
        assertEquals(listOf("a", "b"), ids(CatalogLocalFilters(yearFrom = 2010)))
        assertEquals(listOf("c"), ids(CatalogLocalFilters(yearTo = 1999)))
        assertEquals(listOf("a", "b"), ids(CatalogLocalFilters(minRating = 7.0)))
    }

    @Test
    fun `a year or rating filter leaves out titles that carry none`() {
        assertEquals(listOf("a", "b", "c"), ids(CatalogLocalFilters(yearFrom = 1900)))
        assertEquals(listOf("a", "b", "c"), ids(CatalogLocalFilters(minRating = 1.0)))
    }

    @Test
    fun `hide watched drops the watched ones`() {
        assertEquals(listOf("b", "c", "d"), ids(CatalogLocalFilters(hideWatched = true), watched = setOf("a")))
    }

    @Test
    fun `a release date wins over release info for the year`() {
        val dated = MetaPreview(id = "x", type = "movie", name = "x", releaseInfo = "Coming soon", rawReleaseDate = "2027-03-01")
        assertEquals(2027, dated.catalogYear())
    }
}
