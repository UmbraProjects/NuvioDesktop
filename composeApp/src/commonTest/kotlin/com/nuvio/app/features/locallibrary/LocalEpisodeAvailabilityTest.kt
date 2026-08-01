package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalEpisodeAvailabilityTest {

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

    @Test
    fun `directly mapped anime trusts entry-relative local files`() {
        // The details payload may label the episode as season 1, but Kitsu 11367 and the local
        // item both number XYZ relative to this entry. The absent file season must not hide it.
        assertTrue(xyz.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 1) == true)
        assertTrue(xyz.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 47) == true)
        assertFalse(xyz.hasDirectMappedEpisode("kitsu:11367", season = 1, episode = 48) == true)
    }

    @Test
    fun `different anime entry is left for cross mapping`() {
        assertNull(xyz.hasDirectMappedEpisode("kitsu:7850", season = 1, episode = 1))
    }
}
