package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilenameParserTest {

    @Test
    fun parsesPlexMovieFolder() {
        val parsed = FilenameParser.parseTitle("The Matrix (1999)")
        assertEquals("The Matrix", parsed.title)
        assertEquals(1999, parsed.year)
    }

    @Test
    fun parsesMovieFileWithReleaseTags() {
        val parsed = FilenameParser.parseTitle("Blade.Runner.2049.2017.2160p.UHD.BluRay.x265-GROUP.mkv")
        assertEquals("Blade Runner 2049", parsed.title)
        assertEquals(2017, parsed.year)
    }

    @Test
    fun parsesMovieWithoutYear() {
        val parsed = FilenameParser.parseTitle("Some_Indie_Film")
        assertEquals("Some Indie Film", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun parsesStandardEpisode() {
        val parsed = FilenameParser.parseEpisode("Breaking Bad - S02E05 - Breakage.mkv")
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun parsesDottedEpisode() {
        val parsed = FilenameParser.parseEpisode("The.Wire.S01E03.1080p.WEB.mkv")
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun parsesCrossFormatEpisode() {
        val parsed = FilenameParser.parseEpisode("Firefly 1x02.avi")
        assertEquals(1, parsed.season)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun parsesEpisodeFromSeasonFolder() {
        val parsed = FilenameParser.parseEpisode("Episode 04.mkv", seasonFolderName = "Season 03")
        assertEquals(3, parsed.season)
        assertEquals(4, parsed.episode)
    }

    @Test
    fun normalizeKeyIsStable() {
        assertEquals(
            FilenameParser.normalizeKey("The Matrix", 1999),
            FilenameParser.normalizeKey("the   matrix", 1999),
        )
        assertEquals("the-matrix-1999", FilenameParser.normalizeKey("The Matrix", 1999))
    }
}
