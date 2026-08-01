package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.locallibrary.FilenameParser
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalFolderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The core invariant of library-scoped downloads: every name [LibraryFileNaming] generates must
 * parse back to the same (title-key, year, season, episode) via [FilenameParser], so the scanner
 * lands on the item we pre-seeded a match override for.
 */
class LibraryFileNamingTest {

    private fun lastComponent(path: String): String = path.substringAfterLast('/')
    private val movieFolder = LocalFolder(
        id = "movies",
        path = "C:/Movies",
        type = LocalFolderType.MOVIES,
    )
    private val seriesFolder = LocalFolder(
        id = "shows",
        path = "C:/Shows",
        type = LocalFolderType.SERIES,
    )

    @Test
    fun movieRoundTripsThroughParser() {
        val path = LibraryFileNaming.movieRelativePath("The Matrix", 1999, "mkv")
        assertEquals("The Matrix (1999)/The Matrix (1999).mkv", path)

        val parsed = FilenameParser.parseTitle(lastComponent(path))
        assertEquals("The Matrix", parsed.title)
        assertEquals(1999, parsed.year)
        assertEquals(
            "movies:${FilenameParser.normalizeKey("The Matrix", 1999)}",
            LibraryFileNaming.expectedItemKey(movieFolder, "The Matrix", 1999),
        )
    }

    @Test
    fun seriesExpectedKeyMatchesScannerFolderScopeAndOmitsYear() {
        assertEquals(
            "shows:${FilenameParser.normalizeKey("Breaking Bad", null)}",
            LibraryFileNaming.expectedItemKey(seriesFolder, "Breaking Bad", 2008),
        )
    }

    @Test
    fun movieWithoutYearRoundTrips() {
        val path = LibraryFileNaming.movieRelativePath("Some Indie Film", null, "mp4")
        val parsed = FilenameParser.parseTitle(lastComponent(path))
        assertEquals("Some Indie Film", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun episodeRoundTripsThroughParser() {
        val path = LibraryFileNaming.episodeRelativePath(
            title = "Breaking Bad",
            year = 2008,
            season = 2,
            episode = 5,
            episodeTitle = "Breakage",
            extension = "mkv",
        )
        assertEquals("Breaking Bad (2008)/Season 02/Breaking Bad (2008) - S02E05 - Breakage.mkv", path)

        val parsed = FilenameParser.parseEpisode(lastComponent(path))
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun episodeWithoutTitleRoundTrips() {
        val path = LibraryFileNaming.episodeRelativePath(
            title = "The Wire",
            year = 2002,
            season = 1,
            episode = 3,
            episodeTitle = null,
            extension = "mkv",
        )
        val parsed = FilenameParser.parseEpisode(lastComponent(path))
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun animeAbsoluteEpisodeRoundTrips() {
        val path = LibraryFileNaming.animeEpisodeRelativePath(
            title = "Frieren",
            year = 2023,
            absoluteEpisode = 12,
            extension = "mkv",
        )
        assertEquals("Frieren (2023)/Frieren (2023) - 12.mkv", path)

        val parsed = FilenameParser.parseEpisode(lastComponent(path), isAnime = true)
        assertNull(parsed.season)
        assertEquals(12, parsed.episode)
    }

    @Test
    fun animeHighAbsoluteEpisodeRoundTrips() {
        val path = LibraryFileNaming.animeEpisodeRelativePath("One Piece", null, 1075, "mkv")
        val parsed = FilenameParser.parseEpisode(lastComponent(path), isAnime = true)
        assertNull(parsed.season)
        assertEquals(1075, parsed.episode)
    }

    @Test
    fun reservedCharactersAreStripped() {
        val path = LibraryFileNaming.movieRelativePath("Spider-Man: No Way Home", 2021, "mkv")
        // No Windows-reserved characters survive in any component.
        assertTrue(path.none { it in "<>:\"\\|?*" }, "reserved chars leaked into: $path")
        val parsed = FilenameParser.parseTitle(lastComponent(path))
        assertEquals(2021, parsed.year)
        assertTrue(parsed.title.isNotBlank())
    }

    @Test
    fun reservedDeviceNamesAreSuffixed() {
        assertEquals("NUL_", LibraryFileNaming.sanitizeComponent("NUL"))
        assertEquals("con_", LibraryFileNaming.sanitizeComponent("con"))
        // A normal title containing a reserved word as a substring is untouched.
        assertEquals("Concert", LibraryFileNaming.sanitizeComponent("Concert"))
    }
}
