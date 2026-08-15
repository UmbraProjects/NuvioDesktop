package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.usesInfiniteHomeRow
import com.nuvio.app.features.tmdb.TmdbEntityBrowseData
import com.nuvio.app.features.tmdb.TmdbEntityHeader
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbEntityRail
import com.nuvio.app.features.tmdb.TmdbEntityRailType
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.withCustomLibraryPoster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbEntityBrowseCatalogTest {
    @Test
    fun `entity rails become inline home catalogs with stable paging state`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "plain-poster",
        )
        val data = TmdbEntityBrowseData(
            header = TmdbEntityHeader(
                id = 49,
                kind = TmdbEntityKind.NETWORK,
                name = "HBO",
                logo = "hbo-logo",
                originCountry = "US",
                secondaryLabel = null,
                description = null,
            ),
            rails = listOf(
                TmdbEntityRail(
                    mediaType = TmdbEntityMediaType.TV,
                    railType = TmdbEntityRailType.POPULAR,
                    items = listOf(preview),
                    currentPage = 2,
                    hasMore = true,
                    isLoading = true,
                ),
            ),
        )

        val section = buildEntityCatalogSections(
            data = data,
            labels = EntityCatalogLabels(
                movies = "Movies",
                series = "Series",
                popular = "Popular",
                topRated = "Top rated",
                recent = "Recent",
            ),
        ).single()

        assertEquals("tmdb-entity:tv:popular", section.key)
        assertEquals("Series • Popular • HBO", section.title)
        assertEquals("", section.subtitle)
        assertEquals(listOf(preview), section.items)
        assertNull(section.target)
        assertTrue(section.inlineOnly)
        assertTrue(section.paginates)
        assertTrue(section.hasMore)
        assertTrue(section.isLoadingMore)
        assertEquals(3, section.nextSkip)
        assertTrue(section.usesInfiniteHomeRow(catalogSeeMoreEnabled = true))
    }

    @Test
    fun `entity poster uses custom library template and keeps TMDB fallback`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "https://image.tmdb.org/t/p/w500/plain.jpg",
        )

        val styled = preview.withCustomLibraryPoster(
            settings = TmdbSettings(
                libraryPosterEnabled = true,
                libraryPosterUrlTemplate = "https://posters.example/{type}/{tmdb_id}",
            ),
            imdbId = null,
            tmdbId = 1399,
        )

        assertEquals("https://posters.example/series/1399", styled.poster)
        assertEquals(preview.poster, styled.posterFallback)
    }

    @Test
    fun `disabled custom library poster setting leaves TMDB poster unchanged`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "plain-poster",
        )

        val styled = preview.withCustomLibraryPoster(
            settings = TmdbSettings(
                libraryPosterEnabled = false,
                libraryPosterUrlTemplate = "https://posters.example/{tmdb_id}",
            ),
            imdbId = null,
            tmdbId = 1399,
        )

        assertEquals(preview, styled)
        assertFalse(styled.posterFallback != null)
    }
}
