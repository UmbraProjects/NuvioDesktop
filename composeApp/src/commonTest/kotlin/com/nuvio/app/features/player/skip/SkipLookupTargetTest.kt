package com.nuvio.app.features.player.skip

import com.nuvio.app.features.metadata.ResolvedMediaIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipLookupTargetTest {

    @Test
    fun `routes an imdb episode to the imdb chain`() {
        assertEquals(
            SkipLookupTarget.Episode("tt0434706", season = 1, episode = 1),
            skipLookupTargetFor("tt0434706:1:1", season = 1, episode = 1),
        )
    }

    @Test
    fun `routes an imdb id without coordinates to the film chain`() {
        assertEquals(
            SkipLookupTarget.Movie("tt0133093"),
            skipLookupTargetFor("tt0133093", season = null, episode = null),
        )
    }

    /**
     * The regression this whole routing exists for: Monster plays as `kitsu:10:1`, which the old
     * `startsWith("tt")` test turned into a null IMDb id, so no provider was ever contacted.
     */
    @Test
    fun `routes a kitsu id to the anime chain instead of dropping it`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "10", episode = 1),
            skipLookupTargetFor("kitsu:10:1", season = 1, episode = 1),
        )
    }

    @Test
    fun `recognises every anime namespace and spelling`() {
        val cases = mapOf(
            "kitsu:10:1" to AnimeIdNamespace.KITSU,
            "mal:19:1" to AnimeIdNamespace.MAL,
            "myanimelist:19:1" to AnimeIdNamespace.MAL,
            "anilist:19:1" to AnimeIdNamespace.ANILIST,
            "al:19:1" to AnimeIdNamespace.ANILIST,
            "anidb:1539:1" to AnimeIdNamespace.ANIDB,
        )
        cases.forEach { (videoId, namespace) ->
            val target = skipLookupTargetFor(videoId, season = 1, episode = 1)
            assertEquals(namespace, (target as? SkipLookupTarget.Anime)?.namespace, videoId)
        }
    }

    @Test
    fun `matches an anime prefix regardless of case`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "10", episode = 1),
            skipLookupTargetFor("Kitsu:10:1", season = 1, episode = 1),
        )
    }

    /**
     * A split-cour entry numbers its own episodes from 1 while the franchise keeps counting, so the
     * episode baked into the id and the one the player is showing disagree. AniSkip and Anime-Skip
     * are keyed on the entry-local one.
     */
    @Test
    fun `prefers the id's own episode over the franchise numbering`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "8174", episode = 2),
            skipLookupTargetFor("kitsu:8174:2", season = 2, episode = 27),
        )
    }

    @Test
    fun `takes the trailing episode from a stray season-bearing anime id`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "8174", episode = 3),
            skipLookupTargetFor("kitsu:8174:2:3", season = 2, episode = 3),
        )
    }

    @Test
    fun `falls back to the player's episode when the id carries none`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "10", episode = 4),
            skipLookupTargetFor("kitsu:10", season = 1, episode = 4),
        )
    }

    @Test
    fun `treats an episode-less anime id with no coordinates as a film`() {
        assertEquals(
            SkipLookupTarget.AnimeMovie(AnimeIdNamespace.MAL, "199"),
            skipLookupTargetFor("mal:199", season = null, episode = null),
        )
    }

    @Test
    fun `ignores ids no provider is keyed on`() {
        listOf("tmdb:1396:1:1", "tvdb:81189", "local:C/anime/ep1.mkv", "", "   ").forEach { videoId ->
            assertNull(skipLookupTargetFor(videoId, season = 1, episode = 1), videoId)
        }
        assertNull(skipLookupTargetFor(null, season = 1, episode = 1))
    }

    @Test
    fun `ignores an anime prefix with no id after it`() {
        listOf("kitsu:", "kitsu", "kitsu::1").forEach { videoId ->
            assertNull(skipLookupTargetFor(videoId, season = 1, episode = 1), videoId)
        }
    }

    // --- Resolved-id routing: what each metadata provider hands the player ---

    private fun ids(
        sourceId: String,
        contentType: String = "series",
        imdb: String? = null,
        tmdb: Int? = null,
        kitsu: Int? = null,
        mal: Int? = null,
        anilist: Int? = null,
        anidb: Int? = null,
    ) = ResolvedMediaIds(
        sourceId = sourceId,
        contentType = contentType,
        imdb = imdb,
        tmdb = tmdb,
        kitsu = kitsu,
        mal = mal,
        anilist = anilist,
        anidb = anidb,
    )

    /**
     * The TMDB addon and AIOMetadata address content as `tmdb:`, which no provider is keyed on.
     * Resolution supplies the IMDb id, so the episode chain still runs.
     */
    @Test
    fun `a tmdb-addressed episode routes once resolved to imdb`() {
        assertEquals(
            SkipLookupTarget.Episode("tt0434706", season = 1, episode = 1),
            skipLookupTargetFromResolvedIds(
                ids("tmdb:19885", tmdb = 19885, imdb = "tt0434706"),
                season = 1,
                episode = 1,
            ),
        )
    }

    @Test
    fun `a tmdb-addressed film routes to the film chain`() {
        assertEquals(
            SkipLookupTarget.Movie("tt0133093"),
            skipLookupTargetFromResolvedIds(
                ids("tmdb:603", contentType = "movie", tmdb = 603, imdb = "tt0133093"),
                season = null,
                episode = null,
            ),
        )
    }

    /**
     * An IMDb id wins even when anime ids resolved alongside it: the season and episode in hand are
     * franchise coordinates, and the IMDb chain is the one that maps those onto an anime entry.
     */
    @Test
    fun `prefers imdb over anime ids because the coordinates are franchise numbering`() {
        assertEquals(
            SkipLookupTarget.Episode("tt0434706", season = 1, episode = 1),
            skipLookupTargetFromResolvedIds(
                ids("tmdb:19885", imdb = "tt0434706", kitsu = 10, mal = 19, anilist = 19),
                season = 1,
                episode = 1,
            ),
        )
    }

    @Test
    fun `falls back to an anime entry when nothing maps to imdb`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "395", episode = 3),
            skipLookupTargetFromResolvedIds(ids("kitsu:395", kitsu = 395, mal = 1535), season = 1, episode = 3),
        )
    }

    @Test
    fun `uses whichever anime namespace resolved`() {
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.MAL, "1535", episode = 3),
            skipLookupTargetFromResolvedIds(ids("mal:1535", mal = 1535), season = 1, episode = 3),
        )
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.ANILIST, "1535", episode = 3),
            skipLookupTargetFromResolvedIds(ids("anilist:1535", anilist = 1535), season = 1, episode = 3),
        )
        assertEquals(
            SkipLookupTarget.Anime(AnimeIdNamespace.ANIDB, "4563", episode = 3),
            skipLookupTargetFromResolvedIds(ids("anidb:4563", anidb = 4563), season = 1, episode = 3),
        )
    }

    @Test
    fun `an anime entry with no episode is a film`() {
        assertEquals(
            SkipLookupTarget.AnimeMovie(AnimeIdNamespace.MAL, "199"),
            skipLookupTargetFromResolvedIds(
                ids("mal:199", contentType = "movie", mal = 199),
                season = null,
                episode = null,
            ),
        )
    }

    @Test
    fun `gives up when resolution produced no usable id`() {
        assertNull(skipLookupTargetFromResolvedIds(ids("tvdb:81189", contentType = "series"), 1, 1))
    }

    @Test
    fun `ignores a non-imdb value in the imdb field`() {
        assertNull(skipLookupTargetFromResolvedIds(ids("x", imdb = "12345"), season = 1, episode = 1))
    }
}
