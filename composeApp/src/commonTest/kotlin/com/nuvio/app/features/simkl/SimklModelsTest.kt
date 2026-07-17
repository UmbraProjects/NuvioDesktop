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
    fun `simkl-only payload translates to kitsu via the anime list`() {
        // SIMKL playback sessions often carry nothing but the simkl id. A "simkl:" content id
        // is opaque downstream (meta addons mangle it into wrong-title lookups, stream
        // scrapers return nothing), so it must be translated through anime-list-mini.json.
        // Real data: SAO the Movie: Ordinal Scale, simkl 527702 -> kitsu 11423.
        val ids = SimklMediaIds(simkl = 527702)

        assertEquals("kitsu:11423", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `unknown simkl id keeps the simkl fallback`() {
        val ids = SimklMediaIds(simkl = 999999999)

        assertEquals("simkl:999999999", ids.toBestAnimeMovieContentId())
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

    @Test
    fun `SIMKL SAO entry episode converts back to franchise season`() {
        val ids = SimklMediaIds(simkl = 46206, kitsu = "8174")

        assertEquals(2 to 5, ids.toCanonicalAnimeEpisode(season = 1, episode = 5))
    }

    @Test
    fun `SIMKL split cour episode restores its franchise offset`() {
        val ids = SimklMediaIds(simkl = 1186817, kitsu = "42927")

        assertEquals(4 to 15, ids.toCanonicalAnimeEpisode(season = 1, episode = 3))
    }
}
