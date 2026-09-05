package com.nuvio.app.features.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudLibraryFileEntriesTest {
    @Test
    fun `season pack files are listed in episode order regardless of provider order`() {
        val item = cloudFolder(
            "Show.S01E10.1080p.WEB-DL.x265-GRP.mkv",
            "Show.S01E02.1080p.WEB-DL.x265-GRP.mkv",
            "Show.S01E01.1080p.WEB-DL.x265-GRP.mkv",
        )

        val entries = item.playableFileEntries()

        assertEquals(listOf("S01E01", "S01E02", "S01E10"), entries.map { it.episodeLabel })
        assertTrue(entries.looksLikeEpisodeFolder())
    }

    @Test
    fun `multi-season packs order by season then episode`() {
        val item = cloudFolder(
            "Show.S02E01.1080p.WEB-DL.mkv",
            "Show.S01E02.1080p.WEB-DL.mkv",
            "Show.S01E01.1080p.WEB-DL.mkv",
        )

        assertEquals(
            listOf("S01E01", "S01E02", "S02E01"),
            item.playableFileEntries().map { it.episodeLabel },
        )
    }

    @Test
    fun `unnumbered extras sort after the episodes and keep their filename`() {
        val item = cloudFolder(
            "extras-behind-the-scenes.mkv",
            "Show.S01E02.1080p.WEB-DL.mkv",
            "Show.S01E01.1080p.WEB-DL.mkv",
        )

        val entries = item.playableFileEntries()

        assertEquals(
            listOf("S01E01", "S01E02", null),
            entries.map { it.episodeLabel },
        )
        assertFalse(entries.last().hasEpisodeCoordinates)
    }

    @Test
    fun `a movie folder with one episode-looking file is not treated as an episode folder`() {
        // One marked file plus a sample is a movie folder; showing it as a season would be a lie.
        val item = cloudFolder(
            "The.Movie.2019.1080p.BluRay.x264-GRP.mkv",
            "sample.mkv",
        )

        val entries = item.playableFileEntries()

        assertFalse(entries.looksLikeEpisodeFolder())
        assertNull(entries.first().episodeLabel)
    }

    @Test
    fun `an episode number without a season is labelled against season one`() {
        val item = cloudFolder("Show - Ep03 - Something.mkv", "Show - Ep01 - Pilot.mkv")

        assertEquals(listOf("S01E01", "S01E03"), item.playableFileEntries().map { it.episodeLabel })
    }
}

private fun cloudFolder(vararg fileNames: String): CloudLibraryItem =
    CloudLibraryItem(
        providerId = "torbox",
        providerName = "TorBox",
        id = "folder",
        type = CloudLibraryItemType.Torrent,
        name = "Show S01 1080p WEB-DL x265-GRP",
        files = fileNames.mapIndexed { index, name ->
            CloudLibraryFile(id = "file-$index", name = name, playable = true)
        },
    )
