package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.metadata.AnimeIdPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalEpisodeAvailabilityTest {

    /**
     * Absolute-numbered files (no season) under a franchise identity — the shape that makes the
     * entry-relative shortcut dangerous, since Pokémon spans many franchise seasons in one entry.
     */
    private val xyz = LocalMediaItem(
        key = "folder:pokemon-xyz",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Pokémon the Series: XYZ",
        imdbId = "tt0168366",
        tmdbId = 60572,
        kitsuId = 11367,
        malId = 31592,
        files = (1..47).map { episode ->
            LocalMediaFile(
                path = "/anime/Pokémon XYZ - ${episode.toString().padStart(2, '0')}.mkv",
                season = null,
                episode = episode,
            )
        },
    )

    /** The same title with no franchise id, so its own content id stays entry-local. */
    private val xyzNativeOnly = xyz.copy(imdbId = null, tmdbId = null)

    @Test
    fun `franchise ids win the content id, native ids remain the fallback`() {
        // Stated explicitly: the live preference is per-profile user state, and a test that reads
        // it passes or fails depending on whose machine it runs on.
        assertEquals("tt0168366", xyz.contentIdFor(AnimeIdPreference.IMDB))
        assertEquals("kitsu:11367", xyzNativeOnly.contentIdFor(AnimeIdPreference.IMDB))
    }

    @Test
    fun `a native preference addresses the item by its own entry`() {
        assertEquals("kitsu:11367", xyz.contentIdFor(AnimeIdPreference.KITSU))
        assertEquals("mal:31592", xyz.contentIdFor(AnimeIdPreference.MAL))
    }

    @Test
    fun `an entry-local page trusts entry-relative local files`() {
        // The details payload may label the episode as season 1, but Kitsu 11367 and the local
        // item both number XYZ relative to this entry. The absent file season must not hide it.
        assertTrue(xyzNativeOnly.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 1) == true)
        assertTrue(xyzNativeOnly.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 47) == true)
        assertFalse(xyzNativeOnly.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 48) == true)
    }

    @Test
    fun `a franchise page never matches absolute files by episode alone`() {
        // XYZ is one entry covering franchise seasons 18-19. Comparing its absolute file numbers
        // against franchise coordinates would advertise a local file for every season's episode 1.
        // LocalAnimeEpisodeMatcher converts the coordinates instead; this path must decline.
        assertFalse(xyz.hasDirectMappedEpisode("tt0168366", season = 1, episode = 1) == true)
        assertFalse(xyz.hasDirectMappedEpisode("tt0168366", season = 18, episode = 1) == true)
        assertFalse(xyz.hasDirectMappedEpisode("tt0168366", season = 25, episode = 1) == true)
    }

    @Test
    fun `different anime entry is left for cross mapping`() {
        assertNull(xyz.hasDirectMappedEpisode("kitsu:7850", season = 1, episode = 1))
        assertNull(xyzNativeOnly.hasDirectMappedEpisode("kitsu:7850", season = 1, episode = 1))
    }
}
