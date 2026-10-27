package com.nuvio.app.features.streams

import com.nuvio.app.features.metadata.nativeAnimeEntryEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManualLibraryFileNamingTest {

    @Test
    fun movieDownloadCarriesReleaseYearIntoFolderAndFile() {
        assertEquals(
            "The Matrix (1999)/The Matrix (1999).mkv",
            manualLibraryRelativePath(
                title = "The Matrix",
                releaseYear = 1999,
                isEpisode = false,
                isAnimeFolder = false,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                extension = "mkv",
            ),
        )
    }

    @Test
    fun seriesDownloadCarriesReleaseYearIntoFolderAndFile() {
        assertEquals(
            "Breaking Bad (2008)/Season 02/Breaking Bad (2008) - S02E05 - Breakage.mkv",
            manualLibraryRelativePath(
                title = "Breaking Bad",
                releaseYear = 2008,
                isEpisode = true,
                isAnimeFolder = false,
                seasonNumber = 2,
                episodeNumber = 5,
                episodeTitle = "Breakage",
                extension = "mkv",
            ),
        )
    }

    @Test
    fun animeDownloadCarriesReleaseYearIntoFolderAndFile() {
        assertEquals(
            "Frieren (2023)/Frieren (2023) - 12.mkv",
            manualLibraryRelativePath(
                title = "Frieren",
                releaseYear = 2023,
                isEpisode = true,
                isAnimeFolder = true,
                seasonNumber = 1,
                episodeNumber = 12,
                episodeTitle = "The Real Hero",
                extension = "mkv",
            ),
        )
    }

    @Test
    fun nativeAnimeEntryEpisodeIsTheEntrysOwnNumber() {
        // Apothecary Diaries season 3: the screen is handed TMDB S1E49, the entry calls it E1.
        assertEquals(1, nativeAnimeEntryEpisode(parentMetaId = "kitsu:50021", videoId = "kitsu:50021:1"))
        assertEquals(12, nativeAnimeEntryEpisode(parentMetaId = "kitsu:50021", videoId = "kitsu:50021:12"))
    }

    @Test
    fun nativeAnimeEntryEpisodeIgnoresFranchiseAndForeignIds() {
        // 4-part ids are franchise numbered; a sibling entry's id came from a franchise remap.
        assertNull(nativeAnimeEntryEpisode(parentMetaId = "kitsu:8174", videoId = "kitsu:8174:3:5"))
        assertNull(nativeAnimeEntryEpisode(parentMetaId = "kitsu:8174", videoId = "kitsu:6589:1"))
        assertNull(nativeAnimeEntryEpisode(parentMetaId = "tt26743760", videoId = "tt26743760:3:1"))
        assertNull(nativeAnimeEntryEpisode(parentMetaId = "kitsu:50021", videoId = "tt26743760:1:49"))
    }
}
