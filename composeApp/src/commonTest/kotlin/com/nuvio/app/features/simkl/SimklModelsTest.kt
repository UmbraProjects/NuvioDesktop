package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

// Absent from anime-list-mini.json, so the mapping lookup misses and the payload chain is used.
// Plausible-looking ids are a trap here: most small numbers are real entries and quietly resolve
// to a real franchise id, which is the mapping working correctly but not what these tests assert.
private const val UNMAPPED_KITSU_ID = "99999999"
private const val UNMAPPED_MAL_ID = "99999998"

class SimklModelsTest {
    @Test
    fun `a mapped anime series resolves the franchise imdb id from the anime list`() {
        // Mirrors upstream's default preference: a franchise id is what ordinary meta addons,
        // MDBList and TMDB can resolve; a kitsu id is only servable by a kitsu-aware addon.
        // Real data: kitsu 1011 (UFO Ultramaiden Valkyrie 2) -> imdb tt0443717. The mapping's
        // imdb beats the payload's own tmdb/tvdb because it is namespace-unambiguous.
        val ids = SimklMediaIds(
            simkl = 123,
            tmdb = "456",
            tvdb = 789,
            kitsu = "1011",
        )

        assertEquals("tt0443717", ids.toBestAnimeContentId())
    }

    @Test
    fun `an unmapped anime series still prefers the payload's franchise ids`() {
        // No anime-list entry, so the chain falls through to SIMKL's own ids — where a franchise
        // id still outranks the native one.
        val ids = SimklMediaIds(
            tmdb = "456",
            tvdb = 789,
            kitsu = UNMAPPED_KITSU_ID,
        )

        assertEquals("tmdb:456", ids.toBestAnimeContentId())
    }

    @Test
    fun `an unmapped anime movie still prefers the payload's franchise ids`() {
        val ids = SimklMediaIds(
            tmdb = "456",
            tvdb = null,
            kitsu = UNMAPPED_KITSU_ID,
        )

        assertEquals("tmdb:456", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `an unmapped anime movie never takes a tvdb id`() {
        // A TVDB record for an anime film is season 0 of its parent series, not the film.
        val ids = SimklMediaIds(tvdb = 789, kitsu = UNMAPPED_KITSU_ID)

        assertEquals("kitsu:$UNMAPPED_KITSU_ID", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `simkl-only payload resolves a franchise id through the anime list`() {
        // SIMKL playback sessions often carry nothing but the simkl id, so the mapping is what
        // turns them into something addressable at all.
        // Real data: SAO the Movie: Ordinal Scale, simkl 527702 -> imdb tt5544384.
        val ids = SimklMediaIds(simkl = 527702)

        assertEquals("tt5544384", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `anime movies never borrow the franchise tv or tvdb id`() {
        // Accel World: Infinite Burst is a MOVIE whose anime-list entry carries only a TMDB *tv*
        // id and a TVDB id, both belonging to the parent series. Taking either would open a
        // completely different record, so the native id is the correct answer here.
        val ids = SimklMediaIds(simkl = 527700)

        assertEquals("tt5923132", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `unknown simkl id keeps the simkl fallback`() {
        val ids = SimklMediaIds(simkl = 999999999)

        assertEquals("simkl:999999999", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `placeholder imdb id is ignored`() {
        // SIMKL reuses tt2250192 for anime it has no real imdb id for, so it must never win.
        val ids = SimklMediaIds(
            imdb = "tt2250192",
            tmdb = "456",
            tvdb = 789,
        )

        assertEquals("tmdb:456", ids.toBestAnimeMovieContentId())
    }

    @Test
    fun `native anime ids remain the last resort`() {
        // No franchise id anywhere — neither on the payload nor in the mapping.
        val ids = SimklMediaIds(kitsu = UNMAPPED_KITSU_ID, mal = UNMAPPED_MAL_ID)

        assertEquals("kitsu:$UNMAPPED_KITSU_ID", ids.toBestAnimeMovieContentId())
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
