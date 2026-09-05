package com.nuvio.app.features.games

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The game library document: one JSON file holding every tracked game.
 *
 * Writes go through a temp file and an atomic move, and always re-read the current document first,
 * so a games write and a directory write issued from different coroutines cannot clobber one
 * another.
 */
class GameLibraryRepository(
    private val dataFile: Path = defaultDataFile(),
    private val legacyDataFiles: List<Path> = defaultLegacyDataFiles(),
) {
    private val writeMutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): GameLibraryData = withContext(Dispatchers.IO) { loadBlocking() }

    /**
     * Reading without a coroutine, for the one-shot settings migration that runs while the
     * settings repository is loading its own values.
     */
    internal fun loadBlocking(): GameLibraryData {
        migrateLegacyDataIfNeeded()
        if (!Files.isRegularFile(dataFile)) return GameLibraryData()
        return runCatching {
            json.decodeFromString<GameLibraryData>(Files.readString(dataFile))
        }.getOrElse { GameLibraryData() }
    }

    private fun migrateLegacyDataIfNeeded() {
        if (Files.exists(dataFile)) return
        val legacy = legacyDataFiles.firstOrNull { Files.isRegularFile(it) } ?: return
        runCatching {
            Files.createDirectories(dataFile.parent)
            Files.copy(legacy, dataFile, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    suspend fun saveGames(games: List<GameEntry>) = update { it.copy(games = games) }

    suspend fun saveLastExecutableDirectory(directory: String) =
        update { it.copy(settings = it.settings.copy(lastExecutableDirectory = directory)) }

    private suspend fun update(transform: (GameLibraryData) -> GameLibraryData) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            Files.createDirectories(dataFile.parent)
            val existing = if (Files.isRegularFile(dataFile)) {
                runCatching { json.decodeFromString<GameLibraryData>(Files.readString(dataFile)) }
                    .getOrElse { GameLibraryData() }
            } else {
                GameLibraryData()
            }
            val temporary = dataFile.resolveSibling("${dataFile.fileName}.tmp")
            Files.writeString(temporary, json.encodeToString(transform(existing)))
            runCatching {
                Files.move(
                    temporary,
                    dataFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        /** Lives under Nuvio's data directory now that game mode is part of the app. */
        fun defaultDataFile(): Path = DesktopStorage.rootDir.resolve("games").resolve("library.json")

        /**
         * Where the library lived before game mode was merged in — first the standalone Umbra
         * launcher, then its own pre-rebrand directory. The first one that exists is copied in
         * once, so an existing library survives the move without the user doing anything.
         */
        fun defaultLegacyDataFiles(): List<Path> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            return buildList {
                if (localAppData != null) {
                    add(Paths.get(localAppData, "Umbra", "library.json"))
                    add(Paths.get(localAppData, "NuvioGames", "library.json"))
                }
                add(Paths.get(System.getProperty("user.home"), ".umbra", "library.json"))
            }
        }
    }
}
