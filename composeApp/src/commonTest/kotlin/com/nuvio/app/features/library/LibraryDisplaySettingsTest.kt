package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDisplaySettingsTest {
    private fun item(id: String, title: String, saved: Long, rank: Int? = null) = LibraryItem(
        id = id,
        type = "movie",
        name = title,
        savedAtEpochMs = saved,
        traktRank = rank,
    )

    @Test
    fun titleSortIgnoresLeadingArticles() {
        val items = listOf(item("2", "The Zebra", 1), item("1", "Apple", 2))
        assertEquals(listOf("Apple", "The Zebra"), sortLibraryItems(items, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL).map { it.name })
    }

    @Test
    fun defaultLocalSortUsesNewestFirst() {
        val items = listOf(item("1", "Older", 1), item("2", "Newer", 2))
        assertEquals(listOf("Newer", "Older"), sortLibraryItems(items, LibrarySortOption.DEFAULT, LibrarySourceMode.LOCAL).map { it.name })
    }

    @Test
    fun gridProjectionDeduplicatesItemsAcrossLists() {
        val same = item("tt1", "Same", 2)
        val sections = listOf(
            LibrarySection("watchlist", "Watchlist", listOf(same)),
            LibrarySection("favorites", "Favorites", listOf(same)),
        )
        assertEquals(1, libraryGridEntries(sections, LibrarySortOption.ADDED_DESC, LibrarySourceMode.TRAKT).size)
    }
}
