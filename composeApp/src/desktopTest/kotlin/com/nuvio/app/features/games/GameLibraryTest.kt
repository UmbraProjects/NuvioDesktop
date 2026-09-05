package com.nuvio.app.features.games

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLibraryRepositoryTest {
    @Test
    fun gamesAndLastExecutableDirectorySurviveIndependentUpdates() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val game = GameEntry(
            id = "game-1",
            title = "Test Game",
            executablePath = "C:\\Games\\Test\\game.exe",
            genres = listOf("Adventure"),
        )

        repository.saveGames(listOf(game))
        repository.saveLastExecutableDirectory("C:\\Games")
        val loaded = repository.load()

        assertEquals(listOf(game), loaded.games)
        assertEquals("C:\\Games", loaded.settings.lastExecutableDirectory)
    }

    @Test
    fun migratesAStandaloneUmbraLibraryWhenNoneExistsYet() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-migration-test")
        val legacyFile = directory.resolve("umbra/library.json")
        val mergedFile = directory.resolve("nuvio/games/library.json")
        val legacyRepository = GameLibraryRepository(legacyFile, legacyDataFiles = emptyList())
        val game = GameEntry(id = "tracked", title = "Coming Soon", executablePath = null)
        legacyRepository.saveGames(listOf(game))

        val merged = GameLibraryRepository(mergedFile, legacyDataFiles = listOf(legacyFile))

        assertEquals(listOf(game), merged.load().games)
    }

    @Test
    fun firstExistingLegacyLocationWins() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-legacy-order-test")
        val preferred = directory.resolve("umbra/library.json")
        val older = directory.resolve("nuvio-games/library.json")
        GameLibraryRepository(preferred, legacyDataFiles = emptyList())
            .saveGames(listOf(GameEntry(id = "preferred", title = "Preferred")))
        GameLibraryRepository(older, legacyDataFiles = emptyList())
            .saveGames(listOf(GameEntry(id = "older", title = "Older")))

        val merged = GameLibraryRepository(
            dataFile = directory.resolve("merged/library.json"),
            legacyDataFiles = listOf(preferred, older),
        )

        assertEquals(listOf("preferred"), merged.load().games.map(GameEntry::id))
    }
}

class GameLauncherTest {
    @Test
    fun parsesQuotedArgumentsWithoutUsingAShell() {
        assertEquals(
            listOf("--profile", "Living Room", "--safe"),
            parseArguments("--profile \"Living Room\" --safe"),
        )
    }

    @Test
    fun derivesReadableTitleFromExecutable() {
        assertEquals("Super game", executableDisplayName("C:\\Games\\Super_game.exe"))
    }

    @Test
    fun installationStateIsDefinedOnlyByExecutablePresence() {
        assertEquals(false, GameEntry(id = "1", title = "Tracked", executablePath = null).isInstalled)
        assertEquals(false, GameEntry(id = "2", title = "Tracked", executablePath = "  ").isInstalled)
        assertEquals(true, GameEntry(id = "3", title = "Installed", executablePath = "game.exe").isInstalled)
    }
}

class GameArtworkTest {
    @Test
    fun upgradesPersistedIgdbCoverVariant() {
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/t_1080p/co1v8w.jpg",
            highResolutionIgdbCoverUrl(
                "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1v8w.jpg",
            ),
        )
    }

    @Test
    fun leavesNonIgdbArtworkUntouched() {
        val url = "https://cdn.example.test/covers/game.png"
        assertEquals(url, highResolutionIgdbCoverUrl(url))
        assertNull(highResolutionIgdbCoverUrl(null))
    }

    @Test
    fun formatsArtworkResolution() {
        assertEquals(
            "3840 × 2160",
            ArtworkCandidate("https://example.test/art.jpg", 3840, 2160, ArtworkSource.ARTWORK).resolutionLabel,
        )
        assertEquals(
            "Resolution unknown",
            ArtworkCandidate("https://example.test/art.jpg", 0, 0, ArtworkSource.SCREENSHOT).resolutionLabel,
        )
    }

    @Test
    fun formatsLogoPickerDetails() {
        val logo = LogoCandidate(
            url = "https://example.test/logo.png",
            width = 1600,
            height = 620,
            score = 12,
            language = "en",
        )

        assertEquals("1600 × 620", logo.resolutionLabel)
        assertEquals("EN", logo.languageLabel)
    }

    @Test
    fun omitsMissingLogoLanguage() {
        val logo = LogoCandidate(url = "https://example.test/logo.png", width = 0, height = 0, score = 0)

        assertEquals("Resolution unknown", logo.resolutionLabel)
        assertNull(logo.languageLabel)
    }
}

class ManualLogoTest {
    private val chosen = "https://cdn.example.test/valheim-white.png"

    @Test
    fun aHandPickedLogoSurvivesASaveThatChangesNothing() {
        // The editor auto-loads the existing IGDB match when it opens, so a no-op save used to
        // look like a re-match, reset the lookup version, and let the top-up overwrite the logo.
        assertTrue(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = chosen,
                logoUrlToSave = chosen,
            ),
        )
    }

    @Test
    fun pickingALogoNowMarksItManual() {
        assertTrue(
            logoRemainsManuallyChosen(
                pickedNow = true,
                previouslyManual = false,
                previousLogoUrl = null,
                logoUrlToSave = chosen,
            ),
        )
    }

    @Test
    fun rematchingToADifferentGameEndsTheManualChoice() {
        assertFalse(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = chosen,
                logoUrlToSave = "https://images.igdb.com/igdb/image/upload/t_1080p/other.png",
            ),
        )
    }

    @Test
    fun aBlankPreviousLogoIsNeverTreatedAsAManualChoice() {
        assertFalse(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = "",
                logoUrlToSave = "",
            ),
        )
    }

    @Test
    fun theTopUpLeavesAManualLogoAloneAndFillsAnAutomaticOne() {
        val fetched = "https://cdn.example.test/steamgriddb-default.png"
        val manual = GameEntry(id = "1", title = "Valheim", logoUrl = chosen, logoManuallyChosen = true)
        val automatic = GameEntry(id = "2", title = "Valheim", logoUrl = chosen)

        assertEquals(chosen, manual.refreshedLogoUrl(fetched))
        assertEquals(fetched, automatic.refreshedLogoUrl(fetched))
        // Nothing fetched must never blank an existing logo.
        assertEquals(chosen, automatic.refreshedLogoUrl(null))
    }

    @Test
    fun theManualFlagRoundTripsThroughTheLibraryFile() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-manual-logo-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val game = GameEntry(id = "1", title = "Valheim", logoUrl = chosen, logoManuallyChosen = true)

        repository.saveGames(listOf(game))

        assertEquals(listOf(game), repository.load().games)
    }
}

class GameLibraryNavigationTest {
    @Test
    fun wheelDownMovesToUninstalledAndWheelUpMovesToInstalled() {
        assertEquals(1, adjacentLibraryRow(0, 1, installedAvailable = true, uninstalledAvailable = true))
        assertEquals(0, adjacentLibraryRow(1, -1, installedAvailable = true, uninstalledAvailable = true))
    }

    @Test
    fun missingRowsAreSkipped() {
        assertEquals(0, adjacentLibraryRow(0, 1, installedAvailable = true, uninstalledAvailable = false))
        assertEquals(1, adjacentLibraryRow(1, -1, installedAvailable = false, uninstalledAvailable = true))
    }
}
