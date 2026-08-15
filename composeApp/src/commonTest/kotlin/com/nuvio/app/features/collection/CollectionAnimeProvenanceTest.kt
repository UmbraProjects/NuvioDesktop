package com.nuvio.app.features.collection

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.RandomPlayCategory
import com.nuvio.app.features.home.randomPlayCategoryIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionAnimeProvenanceTest {
    @Test
    fun japaneseAnimationCarriesExactMovieAndSeriesFormsIntoRandomPlay() {
        val movieAnimeType = collectionAnimeType(
            mediaType = TmdbCollectionMediaType.MOVIE,
            isAnimation = true,
            originalLanguage = "ja",
        )
        val seriesAnimeType = collectionAnimeType(
            mediaType = TmdbCollectionMediaType.TV,
            isAnimation = true,
            originCountries = listOf("JP"),
        )

        assertEquals("movie", movieAnimeType)
        assertEquals("TV", seriesAnimeType)
        assertEquals(
            RandomPlayCategory.AnimeMovie,
            MetaPreview(id = "tmdb:1", type = "movie", name = "Film", animeType = movieAnimeType)
                .randomPlayCategoryIn(),
        )
        assertEquals(
            RandomPlayCategory.AnimeSeries,
            MetaPreview(id = "trakt:2", type = "series", name = "Show", animeType = seriesAnimeType)
                .randomPlayCategoryIn(),
        )
    }

    @Test
    fun animationAloneAndJapaneseLiveActionDoNotBecomeAnime() {
        assertNull(
            collectionAnimeType(
                mediaType = TmdbCollectionMediaType.MOVIE,
                isAnimation = true,
                originalLanguage = "en",
                originCountries = listOf("US"),
            ),
        )
        assertNull(
            collectionAnimeType(
                mediaType = TmdbCollectionMediaType.TV,
                isAnimation = false,
                originalLanguage = "ja",
                originCountries = listOf("JP"),
            ),
        )
    }

    @Test
    fun explicitAnimeGenreDoesNotRequireLanguageMetadata() {
        assertEquals(
            "movie",
            collectionAnimeType(
                mediaType = TmdbCollectionMediaType.MOVIE,
                genres = listOf("action", "anime"),
            ),
        )
    }

    @Test
    fun tmdbDiscoverProvenanceRequiresBothAnimationAndJapaneseConstraint() {
        assertTrue(
            TmdbCollectionFilters(withGenres = "16,35", withOriginalLanguage = "ja").declaresAnime(),
        )
        assertTrue(
            TmdbCollectionFilters(withGenres = "16", withOriginCountry = "JP").declaresAnime(),
        )
        assertFalse(TmdbCollectionFilters(withGenres = "16").declaresAnime())
        assertFalse(TmdbCollectionFilters(withOriginalLanguage = "ja").declaresAnime())
        assertFalse(
            TmdbCollectionFilters(withGenres = "16|35", withOriginalLanguage = "ja").declaresAnime(),
        )
    }
}
