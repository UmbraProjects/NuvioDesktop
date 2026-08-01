package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListLibraryRepositoryTest {
    @Test
    fun `combined watchlist payload maps movies and shows`() {
        val items = MdbListLibraryRepository.decodeWatchlistForTest(
            """
            {
              "movies": [{
                "title": "Arrival",
                "release_year": 2016,
                "ids": {"imdb": "tt2543164", "tmdb": 329865},
                "poster": "https://example.test/arrival.jpg",
                "genres": ["Drama", "Science Fiction"]
              }],
              "shows": [{
                "title": "Severance",
                "mediatype": "show",
                "ids": {"tmdb": 95396, "tvdb": 371980},
                "poster": {"url": "https://example.test/severance.jpg"}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("movie", "series"), items.map { it.type })
        assertEquals("tt2543164", items[0].id)
        assertEquals("tmdb:95396", items[1].id)
        assertEquals("https://example.test/severance.jpg", items[1].poster)
    }

    @Test
    fun `watchlist mutation uses MDBList movies and shows wire shape`() {
        val ids = MdbListScrobbleRepository.MdbListIds(imdb = "tt2543164", tmdb = 329865)
        val movie = MdbListLibraryRepository.encodeMutationForTest("movie", ids)
        val show = MdbListLibraryRepository.encodeMutationForTest("series", ids)

        assertTrue("\"movies\"" in movie)
        assertTrue("\"shows\"" in show)
        assertTrue("\"imdb\":\"tt2543164\"" in movie)
        assertTrue("\"tmdb\":329865" in movie)
    }
}
