package com.nuvio.app.features.simkl

import com.nuvio.app.features.metadata.AnimeIdPreference
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

        assertEquals("tt0443717", ids.toBestAnimeContentId(AnimeIdPreference.IMDB))
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

        assertEquals("tmdb:456", ids.toBestAnimeContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `an unmapped anime movie still prefers the payload's franchise ids`() {
        val ids = SimklMediaIds(
            tmdb = "456",
            tvdb = null,
            kitsu = UNMAPPED_KITSU_ID,
        )

        assertEquals("tmdb:456", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `an unmapped anime movie never takes a tvdb id`() {
        // A TVDB record for an anime film is season 0 of its parent series, not the film.
        val ids = SimklMediaIds(tvdb = 789, kitsu = UNMAPPED_KITSU_ID)

        assertEquals("kitsu:$UNMAPPED_KITSU_ID", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `simkl-only payload resolves a franchise id through the anime list`() {
        // SIMKL playback sessions often carry nothing but the simkl id, so the mapping is what
        // turns them into something addressable at all.
        // Real data: SAO the Movie: Ordinal Scale, simkl 527702 -> imdb tt5544384.
        val ids = SimklMediaIds(simkl = 527702)

        assertEquals("tt5544384", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `anime movies never borrow the franchise tv or tvdb id`() {
        // Accel World: Infinite Burst is a MOVIE whose anime-list entry carries only a TMDB *tv*
        // id and a TVDB id, both belonging to the parent series. Taking either would open a
        // completely different record, so the native id is the correct answer here.
        val ids = SimklMediaIds(simkl = 527700)

        assertEquals("tt5923132", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `unknown simkl id keeps the simkl fallback`() {
        val ids = SimklMediaIds(simkl = 999999999)

        assertEquals("simkl:999999999", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `placeholder imdb id is ignored`() {
        // SIMKL reuses tt2250192 for anime it has no real imdb id for, so it must never win.
        val ids = SimklMediaIds(
            imdb = "tt2250192",
            tmdb = "456",
            tvdb = 789,
        )

        assertEquals("tmdb:456", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `native anime ids remain the last resort`() {
        // No franchise id anywhere — neither on the payload nor in the mapping.
        val ids = SimklMediaIds(kitsu = UNMAPPED_KITSU_ID, mal = UNMAPPED_MAL_ID)

        assertEquals("kitsu:$UNMAPPED_KITSU_ID", ids.toBestAnimeMovieContentId(AnimeIdPreference.IMDB))
    }

    @Test
    fun `the KITSU preference addresses anime by its own entry`() {
        // Mushoku Tensei 3rd season carries a franchise imdb, which the default preference would
        // pick. KITSU asks for the per-entry identity instead.
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002", imdb = "tt13293588")

        assertEquals("kitsu:49002", ids.toBestAnimeContentId(AnimeIdPreference.KITSU))
    }

    @Test
    fun `the MAL preference prefers mal and falls back to kitsu`() {
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002", imdb = "tt13293588")

        // mal is absent from the payload but the anime-list entry carries it (mal 59193).
        assertEquals("mal:59193", ids.toBestAnimeContentId(AnimeIdPreference.MAL))
    }

    @Test
    fun `a sparse simkl-only payload still honours the preference`() {
        // The Continue Watching case: playback sessions often carry nothing but a simkl id, so the
        // preference would be silently ignored without the anime-list lookup behind it.
        val ids = SimklMediaIds(simkl = 46206)

        assertEquals("kitsu:8174", ids.toBestAnimeContentId(AnimeIdPreference.KITSU))
    }

    @Test
    fun `a simkl-only stub entry does not shadow the entry that has the season`() {
        // Mushoku Tensei 3rd season: the anime-list holds a stub row carrying simkl 2832226 and
        // nothing else, while the real entry (kitsu 49002) records tvdb season 3. Preferring the
        // stub left the episode at S1E1, which against a franchise id is the first season's
        // opener rather than this season's.
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002")

        assertEquals(3 to 1, ids.toCanonicalAnimeEpisode(season = 1, episode = 1))
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

    @Test
    fun `a per-entry id keeps SIMKL's own episode coordinates`() {
        // Mushoku Tensei 3rd season under the KITSU preference. kitsu:49002 has one season, so the
        // franchise pair SIMKL states for the same episode (S3E1) would address nothing.
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002", imdb = "tt13293588")

        val coordinates = ids.episodeCoordinatesFor(
            contentId = "kitsu:49002",
            isAnime = true,
            entrySeason = 1,
            entryEpisode = 1,
            franchiseSeason = 3,
            franchiseEpisode = 1,
        )

        assertEquals(1 to 1, coordinates)
    }

    @Test
    fun `a simkl id is per-entry too`() {
        // The last-resort id for an entry with no franchise id at all. It is still one SIMKL entry,
        // so it is numbered like one.
        val ids = SimklMediaIds(simkl = 2832226)

        val coordinates = ids.episodeCoordinatesFor(
            contentId = "simkl:2832226",
            isAnime = true,
            entrySeason = 1,
            entryEpisode = 1,
            franchiseSeason = 3,
            franchiseEpisode = 1,
        )

        assertEquals(1 to 1, coordinates)
    }

    @Test
    fun `a franchise id takes the franchise coordinates SIMKL states`() {
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002", imdb = "tt13293588")

        val coordinates = ids.episodeCoordinatesFor(
            contentId = "tt13293588",
            isAnime = true,
            entrySeason = 1,
            entryEpisode = 1,
            franchiseSeason = 3,
            franchiseEpisode = 1,
        )

        assertEquals(3 to 1, coordinates)
    }

    @Test
    fun `a franchise id falls back to the anime list when SIMKL states no coordinates`() {
        // The watchlist marker and calendar paths, which carry entry-local numbers only.
        val ids = SimklMediaIds(simkl = 2832226, kitsu = "49002", imdb = "tt13293588")

        val coordinates = ids.episodeCoordinatesFor(
            contentId = "tt13293588",
            isAnime = true,
            entrySeason = 1,
            entryEpisode = 1,
        )

        assertEquals(3 to 1, coordinates)
    }

    @Test
    fun `non-anime coordinates are never reinterpreted`() {
        val ids = SimklMediaIds(simkl = 2832226, imdb = "tt13293588")

        val coordinates = ids.episodeCoordinatesFor(
            contentId = "tt13293588",
            isAnime = false,
            entrySeason = 2,
            entryEpisode = 7,
        )

        assertEquals(2 to 7, coordinates)
    }
}
