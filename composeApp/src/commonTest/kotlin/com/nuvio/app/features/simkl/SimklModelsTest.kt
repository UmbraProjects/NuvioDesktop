package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

class SimklModelsTest {
    @Test
    fun `anime series ids prefer stable anime ids before tvdb`() {
        val ids = SimklMediaIds(
            simkl = 123,
            tmdb = "456",
            tvdb = 789,
            kitsu = "1011",
        )

        assertEquals("kitsu:1011", ids.toBestAnimeContentId())
    }

    @Test
    fun `anime movie ids avoid ambiguous tmdb before stable anime ids`() {
        val ids = SimklMediaIds(
            simkl = 123,
            tmdb = "456",
            tvdb = null,
            kitsu = "1011",
        )

        assertEquals("kitsu:1011", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `placeholder imdb id is ignored`() {
        // simkl deliberately omitted: it outranks tvdb in toBestAnimeMovieContentId's fallback
        // order, which would mask what this test is actually verifying (the imdb placeholder
        // being skipped in favor of the next available id).
        val ids = SimklMediaIds(
            imdb = "tt2250192",
            tmdb = "456",
            tvdb = 789,
        )

        assertEquals("tvdb:789", ids.toBestAnimeMovieContentId())
    }
}
