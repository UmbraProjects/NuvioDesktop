package com.nuvio.app.features.trakt

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktLibraryRepositoryTest {

    @Test
    fun `hydration skips items that already have core library data`() {
        val item = LibraryItem(
            id = "tt1234567",
            type = "movie",
            name = "Example",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            banner = null,
            logo = null,
            description = null,
            releaseInfo = "2024",
            imdbRating = null,
            genres = emptyList(),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        assertFalse(shouldHydrateTraktLibraryItem(item))
    }

    @Test
    fun `hydration keeps filling missing poster metadata`() {
        val item = LibraryItem(
            id = "tt7654321",
            type = "series",
            name = "Example Show",
            poster = null,
            banner = null,
            logo = null,
            description = "",
            releaseInfo = "2025",
            imdbRating = null,
            genres = emptyList(),
            posterShape = PosterShape.Poster,
            savedAtEpochMs = 1L,
        )

        assertTrue(shouldHydrateTraktLibraryItem(item))
    }
}