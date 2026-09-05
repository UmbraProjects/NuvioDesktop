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

    // --- Existing-folder reuse -------------------------------------------------------------
    //
    // The year reaching these builders depends on whether the metadata cache happened to be warm,
    // so on its own it files consecutive episodes of one show into two folders. Every builder has
    // to defer to what the destination already holds.

    @Test
    fun existingFolderWithYearWinsOverAYearlessName() {
        val path = LibraryFileNaming.animeEpisodeRelativePath(
            title = "Mushoku Tensei",
            year = null,
            absoluteEpisode = 5,
            extension = "mkv",
            existingFolderNames = listOf("Mushoku Tensei (2021)"),
        )
        assertEquals("Mushoku Tensei (2021)", path.substringBefore('/'))
    }

    @Test
    fun existingYearlessFolderWinsOverAYearedName() {
        val path = LibraryFileNaming.episodeRelativePath(
            title = "Mushoku Tensei",
            year = 2021,
            season = 1,
            episode = 5,
            episodeTitle = null,
            extension = "mkv",
            existingFolderNames = listOf("Mushoku Tensei"),
        )
        assertEquals("Mushoku Tensei", path.substringBefore('/'))
    }

    @Test
    fun anUnrelatedExistingFolderIsIgnored() {
        val path = LibraryFileNaming.movieRelativePath(
            title = "Dune",
            year = 2021,
            extension = "mkv",
            existingFolderNames = listOf("Blade Runner 2049 (2017)", "Arrival (2016)"),
        )
        assertEquals("Dune (2021)", path.substringBefore('/'))
    }

    @Test
    fun reusedFolderNameStillRoundTripsThroughTheScanner() {
        // Reuse must not break the guarantee the rest of this file tests: whatever folder name is
        // chosen, the generated file name still parses back to the coordinates it was built from.
        val path = LibraryFileNaming.episodeRelativePath(
            title = "Mushoku Tensei",
            year = null,
            season = 2,
            episode = 7,
            episodeTitle = "Turning Point",
            extension = "mkv",
            existingFolderNames = listOf("Mushoku Tensei (2021)"),
        )
        val parsed = FilenameParser.parseEpisode(lastComponent(path), isAnime = false)
        assertEquals(2, parsed.season)
        assertEquals(7, parsed.episode)
    }

    @Test
    fun expectedItemKeyFollowsTheReusedFolder() {
        // The pre-seeded match override is keyed on the folder name that will actually be written,
        // so a reused folder must produce the same key the scanner will compute for it.
        val folder = LocalFolder(id = "f1", path = "D:/Anime", type = LocalFolderType.SERIES)
        val reused = LibraryFileNaming.expectedItemKey(
            folder = folder,
            title = "Mushoku Tensei",
            year = null,
            existingFolderNames = listOf("Mushoku Tensei (2021)"),
        )
        val direct = LibraryFileNaming.expectedItemKey(folder, "Mushoku Tensei", 2021)
        assertEquals(direct, reused)
    }

    @Test
    fun reservedDeviceNamesAreSuffixed() {
        assertEquals("NUL_", LibraryFileNaming.sanitizeComponent("NUL"))
        assertEquals("con_", LibraryFileNaming.sanitizeComponent("con"))
        // A normal title containing a reserved word as a substring is untouched.
        assertEquals("Concert", LibraryFileNaming.sanitizeComponent("Concert"))
    }
}
