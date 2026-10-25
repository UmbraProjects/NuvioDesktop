package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdbListLibraryListsTest {

    @Test
    fun `own lists split into static and dynamic`() {
        val lists = MdbListLibraryRepository.decodeOwnListsForTest(
            """[
                {"id": 11, "name": "Favourites", "dynamic": false, "mediatype": "movie", "updated": "2026-10-01"},
                {"id": 12, "name": "Top Sci-Fi", "dynamic": true},
                {"id": 0, "name": "broken"}
            ]""",
        )
        assertEquals(listOf("mdblist:list:11", "mdblist:dynamic:12"), lists.map { it.key })
        assertTrue(lists[0].acceptsItems)
        assertFalse(lists[1].acceptsItems)
        assertEquals("movie", lists[0].mediaType)
        assertEquals("/lists/12/items", lists[1].itemsPath)
    }

    @Test
    fun `unified list items use the row id as tmdb and skip seasons`() {
        val items = MdbListLibraryRepository.decodeListPageForTest(
            """[
                {"id": 603, "imdb_id": "tt0133093", "title": "The Matrix", "mediatype": "movie", "release_year": 1999},
                {"id": 1399, "title": "Game of Thrones", "mediatype": "show"},
                {"id": 5, "title": "Season 1", "mediatype": "season"}
            ]""",
        )
        assertEquals(listOf("tt0133093", "tmdb:1399"), items.map { it.id })
        assertEquals(listOf("movie", "series"), items.map { it.type })
        assertEquals(603, items[0].tmdbId)
    }

    @Test
    fun `bucketed list pages are read too`() {
        val items = MdbListLibraryRepository.decodeListPageForTest(
            """{"movies": [{"ids": {"tmdb": 27205, "imdb": "tt1375666"}, "title": "Inception"}],
                "shows": [{"ids": {"tvdb": 81189}, "title": "Breaking Bad"}]}""",
        )
        assertEquals(listOf("tt1375666", "tvdb:81189"), items.map { it.id })
    }
}
