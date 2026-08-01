package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMatcherIdentityTest {

    @Test
    fun `explicit imdb identity replaces native anime identity`() {
        val item = animeItem().withFranchiseIdentity(
            imdbId = "tt0168366",
            tmdbId = 60572,
            poster = "poster",
            matchState = LocalMatchState.MANUAL,
        )

        assertEquals("tt0168366", item.contentId)
        assertEquals("tt0168366", item.imdbId)
        assertEquals(60572, item.tmdbId)
        assertNull(item.kitsuId)
        assertNull(item.malId)
    }

    @Test
    fun `explicit tmdb identity is primary when imdb lookup is unavailable`() {
        val item = animeItem().withFranchiseIdentity(
            imdbId = null,
            tmdbId = 60572,
            poster = null,
            matchState = LocalMatchState.MANUAL,
        )

        assertEquals("tmdb:60572", item.contentId)
        assertNull(item.imdbId)
        assertEquals(60572, item.tmdbId)
        assertNull(item.kitsuId)
        assertNull(item.malId)
    }

    @Test
    fun `unified search recognizes explicit ids independently of provider`() {
        assertEquals(
            LocalMatchInputId.Imdb("tt0168366"),
            parseLocalMatchId("TT0168366", LocalMatchProvider.KITSU),
        )
        assertEquals(
            LocalMatchInputId.Tmdb(60572),
            parseLocalMatchId(
                "https://www.themoviedb.org/tv/60572-pokemon",
                LocalMatchProvider.KITSU,
            ),
        )
        assertEquals(
            LocalMatchInputId.Kitsu(11367),
            parseLocalMatchId("kitsu:11367", LocalMatchProvider.TMDB),
        )
        assertEquals(
            LocalMatchInputId.Mal(31592),
            parseLocalMatchId("myanimelist/31592", LocalMatchProvider.TMDB),
        )
    }

    @Test
    fun `bare numeric input is tmdb id only under tmdb provider`() {
        assertEquals(
            LocalMatchInputId.Tmdb(60572),
            parseLocalMatchId("60572", LocalMatchProvider.TMDB),
        )
        assertNull(parseLocalMatchId("86", LocalMatchProvider.KITSU))
    }

    @Test
    fun `ordinary title remains provider search text`() {
        assertNull(parseLocalMatchId("Pokemon the Series XYZ", LocalMatchProvider.KITSU))
        assertNull(parseLocalMatchId("Pokemon the Series XYZ", LocalMatchProvider.TMDB))
        assertTrue(animeItem().isAnime)
    }

    private fun animeItem() = LocalMediaItem(
        key = "folder:pokemon-xyz",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Pokemon the Series XYZ",
        imdbId = "tt0168366",
        tmdbId = 60572,
        kitsuId = 11367,
        malId = 31592,
    )
}
