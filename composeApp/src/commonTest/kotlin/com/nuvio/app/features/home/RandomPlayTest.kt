package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.watched.watchedItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RandomPlayTest {
    @Test
    fun candidatesRespectTypeGenreAndMinimumRating() {
        val section = section(
            genre = "Drama",
            items = listOf(
                preview(id = "movie-low", type = "movie", rating = "6.9", genres = listOf("Drama")),
                preview(id = "movie-good", type = "movie", rating = "IMDb 7.5 / 10", genres = listOf("Drama")),
                preview(id = "series", type = "series", rating = "8.4", genres = listOf("Drama")),
                preview(id = "anime", type = "anime", rating = "8.1", genres = listOf("Anime", "Drama")),
            ),
        )

        val result = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Movie,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 7f,
        )

        assertEquals(listOf("movie-good"), result.map(MetaPreview::id))
    }

    @Test
    fun animeMovieAndAnimeSeriesStaySeparate() {
        val section = section(
            items = listOf(
                preview(id = "film", type = "anime_movie", rating = "8.0", genres = listOf("Anime")),
                preview(id = "show", type = "anime", rating = "8.0", genres = listOf("Anime")),
            ),
        )

        assertEquals(
            listOf("film"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.AnimeMovie,
                RandomPlayGenres.toSet(),
                0f,
            ).map(MetaPreview::id),
        )
        assertEquals(
            listOf("show"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.AnimeSeries,
                RandomPlayGenres.toSet(),
                0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun catalogGenreCanQualifyItemsWithoutItemGenres() {
        val section = section(
            genre = "Science-Fiction",
            items = listOf(preview(id = "movie", type = "movie", rating = "7.0")),
        )

        assertEquals(
            listOf("movie"),
            randomPlayCandidates(
                listOf(section),
                RandomPlayCategory.Movie,
                setOf("Science Fiction"),
                0f,
            ).map(MetaPreview::id),
        )
    }

    @Test
    fun watchedTitlesAreExcludedButPartiallyWatchedSeriesRemainEligible() {
        val section = section(
            items = listOf(
                preview(id = "watched", type = "movie", rating = "8.0", genres = listOf("Drama")),
                preview(id = "unwatched", type = "movie", rating = "8.0", genres = listOf("Drama")),
                preview(id = "in-progress", type = "series", rating = "8.0", genres = listOf("Drama")),
            ),
        )
        val watchedKeys = setOf(
            watchedItemKey(type = "movie", id = "watched"),
            watchedItemKey(type = "series", id = "in-progress", season = 1, episode = 1),
        )

        val movieCandidates = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Movie,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 0f,
            watchedKeys = watchedKeys,
        )
        val seriesCandidates = randomPlayCandidates(
            sourceSections = listOf(section),
            category = RandomPlayCategory.Series,
            allowedGenres = setOf("Drama"),
            minimumImdbRating = 0f,
            watchedKeys = watchedKeys,
        )

        assertEquals(listOf("unwatched"), movieCandidates.map(MetaPreview::id))
        assertEquals(listOf("in-progress"), seriesCandidates.map(MetaPreview::id))
    }

    @Test
    fun launchCardsRoundTripTheirCategory() {
        RandomPlayCategory.entries.forEach { category ->
            val card = MetaPreview(
                id = "random:${category.name}",
                type = "random_play",
                name = category.name,
            )
            assertEquals(category, card.randomPlayCategoryOrNull())
        }
        assertNull(preview(id = "normal", type = "movie", rating = "7.0").randomPlayCategoryOrNull())
    }

    @Test
    fun virtualCatalogIsIncludedInNormalHomeRows() {
        val randomSection = section(items = listOf(preview(id = "random", type = "random_play", rating = "")))
            .copy(key = RANDOM_PLAY_SECTION_KEY, title = "Random Play", addonName = "Nuvio")
        val catalog = HomeCatalogSettingsItem(
            key = "catalog",
            defaultTitle = "Catalog",
            addonName = "Addon",
        )
        val disabled = HomeCatalogSettingsItem(
            key = "disabled",
            defaultTitle = "Disabled",
            addonName = "Addon",
            enabled = false,
        )

        val result = buildEnabledHomeItems(
            settingsItems = listOf(catalog, disabled),
            effectiveSections = listOf(randomSection),
        )

        assertEquals(listOf(RANDOM_PLAY_SECTION_KEY, "catalog"), result.map { it.key })
        assertFalse(result.first().heroSourceEnabled)
    }

    @Test
    fun collageOnlyUsesPostersFromItsCategory() {
        val categoryPosters = (1..7).map { "category-$it" }

        val categoryCollage = randomPlayPosterCollage(
            category = RandomPlayCategory.Movie,
            posters = categoryPosters,
        )
        val emptyAnimeCollage = randomPlayPosterCollage(
            category = RandomPlayCategory.AnimeSeries,
            posters = emptyList(),
        )

        assertEquals(5, categoryCollage.size)
        assertTrue(categoryCollage.all { it in categoryPosters })
        assertTrue(emptyAnimeCollage.isEmpty())
    }

    private fun preview(
        id: String,
        type: String,
        rating: String,
        genres: List<String> = emptyList(),
    ) = MetaPreview(
        id = id,
        type = type,
        name = id,
        imdbRating = rating,
        genres = genres,
    )

    private fun section(
        genre: String? = null,
        items: List<MetaPreview>,
    ) = HomeCatalogSection(
        key = "test",
        title = "Test",
        subtitle = "Test",
        addonName = "Test",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.test/manifest.json",
            contentType = "movie",
            catalogId = "test",
            genre = genre,
        ),
        items = items,
    )
}
