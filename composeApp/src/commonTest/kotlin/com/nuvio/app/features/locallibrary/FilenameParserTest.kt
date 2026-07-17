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
    fun parsesAnimeAbsoluteEpisodeWithDashDelimiter() {
        val parsed = FilenameParser.parseEpisode("[SubsPlease] Frieren - 12 (1080p) [A1B2C3D4].mkv", isAnime = true)
        assertNull(parsed.season)
        assertEquals(12, parsed.episode)
    }

    @Test
    fun parsesAnimeHighAbsoluteEpisode() {
        val parsed = FilenameParser.parseEpisode("[Erai-raws] One Piece - 1075 [1080p][Multiple Subtitle].mkv", isAnime = true)
        assertNull(parsed.season)
        assertEquals(1075, parsed.episode)
    }

    @Test
    fun animeSxxExxKeepsSeasonAndEpisode() {
        // Explicit SxxExx is kept verbatim for anime too — LocalAnimeEpisodeMatcher translates
        // between franchise season/episode and per-entry absolute numbering at play time.
        val parsed = FilenameParser.parseEpisode("Sword Art Online S02E01 [1080p].mkv", isAnime = true)
        assertEquals(2, parsed.season)
        assertEquals(1, parsed.episode)

        val nominalS1 = FilenameParser.parseEpisode("Bleach S01E120 [1080p].mkv", isAnime = true)
        assertEquals(1, nominalS1.season)
        assertEquals(120, nominalS1.episode)
    }

    @Test
    fun animeBareEpisodeTakesSeasonFromFolder() {
        val parsed = FilenameParser.parseEpisode(
            "[Group] Show - 05 [1080p].mkv",
            seasonFolderName = "Season 02",
            isAnime = true,
        )
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun animeParsingIgnoresResolutionDigits() {
        // The 1080 in "1080p" must not be read as the episode when a real episode marker exists.
        val parsed = FilenameParser.parseEpisode("[Group] Jujutsu Kaisen - 05 [1080p][HEVC].mkv", isAnime = true)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun nonAnimeEpisodeUnaffectedByAnimeFlag() {
        val parsed = FilenameParser.parseEpisode("Breaking Bad - S02E05 - Breakage.mkv")
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
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
