package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
