package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandomPlayCandidatePoolRegressionTest {
    @Test
    fun resolvedSeriesTypeCorrectsARawAnimeMovieEvenWithoutEpisodeFacts() {
        val raw = MetaPreview(
            id = "mal:60489",
            type = "movie",
            name = "Takopi's Original Sin",
            carriesAnimeCatalogueId = true,
        )
        val verified = raw.withRandomPlayFacts(
            mapOf(raw.stableKey() to RandomPlayMetaFacts(resolvedType = "series")),
        )

        assertEquals(RandomPlayCategory.AnimeSeries, verified.randomPlayCategoryIn())
        assertFalse(verified.randomPlayAnimeMovieNeedsVerifying())
    }

    @Test
    fun resolvedMovieTypePromotesABareAnimeFilmWithZeroVideos() {
        val raw = MetaPreview(id = "kitsu:1", type = "anime", name = "Film")
        val verified = raw.withRandomPlayFacts(
            mapOf(raw.stableKey() to RandomPlayMetaFacts(resolvedType = "movie", episodeCount = 0)),
        )

        assertEquals(
            RandomPlayCategory.AnimeMovie,
            verified.randomPlayCategoryIn(RandomPlayRow(contentType = "anime")),
        )
        assertFalse(verified.randomPlayFormIsGuessed(RandomPlayRow(contentType = "anime")))
    }

    @Test
    fun onlySameFilterAppendOnlyRowUpdatesCanDrainWithoutCancellation() {
        val previous = linkedSetOf("home:movies", "collection:one")

        assertTrue(
            randomPlayRowSetChangeIsAppendOnly(
                previousFilterKey = "filters",
                previousSectionKeys = previous,
                nextFilterKey = "filters",
                nextSectionKeys = previous + "collection:two",
            ),
        )
        assertFalse(
            randomPlayRowSetChangeIsAppendOnly(
                previousFilterKey = "filters",
                previousSectionKeys = previous,
                nextFilterKey = "watched-changed",
                nextSectionKeys = previous + "collection:two",
            ),
        )
        assertFalse(
            randomPlayRowSetChangeIsAppendOnly(
                previousFilterKey = "filters",
                previousSectionKeys = previous,
                nextFilterKey = "filters",
                nextSectionKeys = setOf("home:movies"),
            ),
        )
    }
}
