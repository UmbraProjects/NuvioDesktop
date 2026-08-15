package com.nuvio.app.features.tracking

import com.nuvio.app.features.metadata.ResolvedMediaIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two anime coordinate families, side by side.
 *
 * Every case is asserted against both families, because the whole risk of this layer is that a
 * provider silently receives the other family's numbering: a wrong season/episode is accepted with
 * HTTP 200 and marks the wrong episode watched.
 */
class TrackingIdProjectionTest {
    private fun ResolvedMediaIds.entryLocal(season: Int?, episode: Int?, isAnime: Boolean = true) =
        projectScrobbleCoordinates(
            family = TrackingCoordinateFamily.ENTRY_LOCAL,
            sourceSeason = season,
            sourceEpisode = episode,
            isAnime = isAnime,
        )

    private fun ResolvedMediaIds.franchise(season: Int?, episode: Int?, isAnime: Boolean = true) =
        projectScrobbleCoordinates(
            family = TrackingCoordinateFamily.FRANCHISE,
            sourceSeason = season,
            sourceEpisode = episode,
            isAnime = isAnime,
        )

    // ── Native ids are dropped when the coordinates stayed franchise ───────────

    @Test
    fun `an unconvertible anime season drops the native ids`() {
        // No simkl entry resolved, so entryLocalSeasonNumber has nothing to convert with and the
        // franchise season passes through. Sending a per-entry id alongside season 3 describes an
        // episode that does not exist — the franchise ids alone are the answerable request.
        val ids = ResolvedMediaIds(
            sourceId = "tt13293588",
            contentType = "series",
            imdb = "tt13293588",
            kitsu = 49002,
            isAnime = true,
        )

        val coordinates = ids.entryLocal(season = 3, episode = 1)

        assertEquals(3, coordinates.season)
        assertFalse(coordinates.retainsNativeAnimeIds)
    }

    @Test
    fun `a converted anime season keeps the native ids`() {
        // The mapped season collapses to the entry's own season 1, so the per-entry id and the
        // coordinates now describe the same episode and may travel together.
        val ids = ResolvedMediaIds(
            sourceId = "tt13293588",
            contentType = "series",
            imdb = "tt13293588",
            simkl = 2832226,
            kitsu = 49002,
            tvdbSeason = 3,
            isAnime = true,
        )

        val coordinates = ids.entryLocal(season = 3, episode = 1)

        assertEquals(1, coordinates.season)
        assertTrue(coordinates.retainsNativeAnimeIds)
    }

    @Test
    fun `a non-anime season is never treated as unconvertible`() {
        val ids = ResolvedMediaIds(
            sourceId = "tt0108778",
            contentType = "series",
            imdb = "tt0108778",
        )

        assertTrue(ids.entryLocal(season = 4, episode = 2, isAnime = false).retainsNativeAnimeIds)
    }

    @Test
    fun `an anime movie has no season to contradict`() {
        val ids = ResolvedMediaIds(
            sourceId = "tt5544384",
            contentType = "movie",
            imdb = "tt5544384",
            kitsu = 11423,
            isAnime = true,
        )

        assertTrue(ids.entryLocal(season = null, episode = null).retainsNativeAnimeIds)
    }

    // ── Ordinary content ──────────────────────────────────────────────────────

    @Test
    fun `a plain series is numbered identically by both families`() {
        val ids = ResolvedMediaIds(
            sourceId = "tt11083696",
            contentType = "series",
            imdb = "tt11083696",
            tmdb = 98201,
        )

        val entryLocal = ids.entryLocal(season = 2, episode = 5, isAnime = false)
        val franchise = ids.franchise(season = 2, episode = 5, isAnime = false)

        assertEquals(2, entryLocal.season)
        assertEquals(5, entryLocal.episode)
        assertEquals(2, franchise.season)
        assertEquals(5, franchise.episode)
        assertTrue(ids.hasFranchiseScrobbleId)
    }

    @Test
    fun `a movie carries no episode coordinates`() {
        val ids = ResolvedMediaIds(sourceId = "tt0111161", contentType = "movie", imdb = "tt0111161")

        val franchise = ids.franchise(season = null, episode = null, isAnime = false)

        assertEquals(null, franchise.season)
        assertEquals(null, franchise.episode)
    }

    // ── Entry-local anime ─────────────────────────────────────────────────────

    @Test
    fun `entry-local anime keeps its own season 1 while franchise lifts it onto the mapped season`() {
        // A Kitsu-addon meta for a second-season entry: reports season 1, episode 3, and the
        // anime-list row says that entry is TVDB/TMDB season 2 starting after 12 episodes.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:7442",
            contentType = "series",
            imdb = "tt2560140",
            tmdb = 1429,
            tvdb = 269613,
            simkl = 41234,
            kitsu = 7442,
            isAnime = true,
            tmdbSeason = 2,
            tvdbSeason = 2,
            tmdbEpisodeOffset = 12,
            tvdbEpisodeOffset = 12,
        )

        val entryLocal = ids.entryLocal(season = 1, episode = 3)
        assertEquals(1, entryLocal.season)
        assertEquals(3, entryLocal.episode)

        val franchise = ids.franchise(season = 1, episode = 3)
        assertEquals(2, franchise.season)
        assertEquals(15, franchise.episode)
    }

    @Test
    fun `franchise-numbered anime collapses to entry-local coordinates for SIMKL`() {
        // The same entry addressed the other way round — the caller already speaks TVDB numbering.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:7442",
            contentType = "series",
            imdb = "tt2560140",
            tmdb = 1429,
            simkl = 41234,
            kitsu = 7442,
            isAnime = true,
            tmdbSeason = 2,
            tvdbSeason = 2,
            tmdbEpisodeOffset = 12,
            tvdbEpisodeOffset = 12,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = true,
        )

        val entryLocal = ids.entryLocal(season = 2, episode = 15)
        assertEquals(1, entryLocal.season)
        assertEquals(3, entryLocal.episode)
        assertTrue(entryLocal.retainsNativeAnimeIds)

        // Franchise numbering in, franchise numbering out — no double-mapping.
        val franchise = ids.franchise(season = 2, episode = 15)
        assertEquals(2, franchise.season)
        assertEquals(15, franchise.episode)
    }

    @Test
    fun `an entry-local episode below its offset never goes non-positive`() {
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:7442",
            contentType = "series",
            simkl = 41234,
            kitsu = 7442,
            isAnime = true,
            tvdbSeason = 2,
            tvdbEpisodeOffset = 12,
        )

        assertEquals(1, ids.entryLocal(season = 2, episode = 4).episode)
    }

    @Test
    fun `the offset is only removed when the season was actually collapsed`() {
        // Regression: the shipped code subtracted the offset whenever the content was anime with a
        // SIMKL id, without checking whether the caller's season was this entry's mapped season.
        // An entry-local source (season 1) therefore had an offset removed that was never added.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:7442",
            contentType = "series",
            simkl = 41234,
            kitsu = 7442,
            isAnime = true,
            tvdbSeason = 2,
            tvdbEpisodeOffset = 12,
        )

        // Season not collapsed → episode untouched.
        assertEquals(1, ids.entryLocal(season = 1, episode = 3).season)
        assertEquals(3, ids.entryLocal(season = 1, episode = 3).episode)
        // Season collapsed → offset removed.
        assertEquals(1, ids.entryLocal(season = 2, episode = 15).season)
        assertEquals(3, ids.entryLocal(season = 2, episode = 15).episode)
    }

    @Test
    fun `an ambiguous franchise season keeps its franchise episode number intact`() {
        // Same regression seen from the other side: season 7 is passed through because it is not
        // this entry's mapped season, so episode 41 must be passed through with it. The shipped
        // code shifted the episode while leaving the season alone, producing a mismatched pair.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:486",
            contentType = "series",
            imdb = "tt0168366",
            tvdb = 76703,
            simkl = 1234,
            kitsu = 486,
            isAnime = true,
            tvdbSeason = 6,
            tvdbEpisodeOffset = 158,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = false,
        )

        val entryLocal = ids.entryLocal(season = 7, episode = 41)
        assertEquals(7, entryLocal.season)
        assertEquals(41, entryLocal.episode)
        assertFalse(entryLocal.retainsNativeAnimeIds)
    }

    @Test
    fun `without a SIMKL id the entry-local family passes coordinates through untouched`() {
        // No simkl id means no entry to be local to; inventing one would be a guess.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:7442",
            contentType = "series",
            kitsu = 7442,
            isAnime = true,
            tvdbSeason = 2,
            tvdbEpisodeOffset = 12,
        )

        val entryLocal = ids.entryLocal(season = 2, episode = 15)
        assertEquals(2, entryLocal.season)
        assertEquals(15, entryLocal.episode)
    }

    // ── The ambiguous-franchise guard ─────────────────────────────────────────

    @Test
    fun `an ambiguous franchise season drops entry-local ids but never franchise ones`() {
        // Pokémon: one anime-list entry spans several TVDB seasons, so no entry unambiguously owns
        // this episode. Pairing the opened entry's ids with a franchise season would address an
        // episode that cannot exist.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:486",
            contentType = "series",
            imdb = "tt0168366",
            tmdb = 60572,
            tvdb = 76703,
            simkl = 1234,
            mal = 527,
            kitsu = 486,
            isAnime = true,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = false,
        )

        assertFalse(ids.entryLocal(season = 7, episode = 41).retainsNativeAnimeIds)
        // The franchise family is never pinned to an entry, so the guard does not apply to it and
        // the coordinates it was given are already the ones it wants.
        val franchise = ids.franchise(season = 7, episode = 41)
        assertTrue(franchise.retainsNativeAnimeIds)
        assertEquals(7, franchise.season)
        assertEquals(41, franchise.episode)
    }

    @Test
    fun `an unambiguous franchise entry keeps its entry-local ids`() {
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:1404",
            contentType = "series",
            simkl = 5678,
            kitsu = 1404,
            isAnime = true,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = true,
        )

        assertTrue(ids.entryLocal(season = 1, episode = 4).retainsNativeAnimeIds)
    }

    @Test
    fun `the guard does not fire for non-anime content`() {
        val ids = ResolvedMediaIds(
            sourceId = "tt0903747",
            contentType = "series",
            imdb = "tt0903747",
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = false,
        )

        assertTrue(ids.entryLocal(season = 1, episode = 1, isAnime = false).retainsNativeAnimeIds)
    }

    // ── Addressability ────────────────────────────────────────────────────────

    @Test
    fun `anime with no anime-list entry cannot be addressed by the franchise family`() {
        // The permanent state of anime that started airing after the bundled anime-list snapshot's
        // cut-off: a native id and nothing else. MDBList and Yamtrack accept none of these ids, so
        // the only correct behaviour is to skip. A guessed season/episode would be accepted.
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:99999",
            contentType = "series",
            kitsu = 99999,
            isAnime = true,
        )

        assertFalse(ids.hasFranchiseScrobbleId)
    }

    @Test
    fun `each franchise-level id on its own makes an item addressable`() {
        val base = ResolvedMediaIds(sourceId = "x", contentType = "series")

        assertTrue(base.copy(imdb = "tt0903747").hasFranchiseScrobbleId)
        assertTrue(base.copy(tmdb = 1396).hasFranchiseScrobbleId)
        assertTrue(base.copy(tvdb = 81189).hasFranchiseScrobbleId)
        assertTrue(base.copy(trakt = 1388).hasFranchiseScrobbleId)
        assertFalse(base.hasFranchiseScrobbleId)
        assertFalse(base.copy(mal = 16498, kitsu = 7442, anilist = 11061, anidb = 8692).hasFranchiseScrobbleId)
        // A SIMKL id is not a franchise id either — SIMKL is the entry-local provider.
        assertFalse(base.copy(simkl = 41234).hasFranchiseScrobbleId)
    }

    @Test
    fun `a blank imdb string does not count as addressable`() {
        val ids = ResolvedMediaIds(sourceId = "x", contentType = "series", imdb = "")

        assertFalse(ids.hasFranchiseScrobbleId)
    }
}
