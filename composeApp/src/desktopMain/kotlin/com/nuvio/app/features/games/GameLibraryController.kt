package com.nuvio.app.features.games

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the game library for the lifetime of the app.
 *
 * Held as a singleton rather than remembered by the screen: game mode is a toggle, and rebuilding
 * the controller on every toggle would re-read the library and re-issue the IGDB metadata top-up
 * each time the user glanced at their games.
 */
class GameLibraryController(
    private val repository: GameLibraryRepository = GameLibraryRepository(),
    private val igdbClient: IgdbClient = IgdbClient(),
    private val steamGridDbClient: SteamGridDbClient = SteamGridDbClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    val games: StateFlow<List<GameEntry>> = _games.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _lastExecutableDirectory = MutableStateFlow("")
    val lastExecutableDirectory: StateFlow<String> = _lastExecutableDirectory.asStateFlow()

    private val settings: GameLibrarySettings
        get() = GameLibrarySettingsRepository.snapshot()

    init {
        scope.launch {
            val data = repository.load()
            _games.value = data.games
            _lastExecutableDirectory.value = data.settings.lastExecutableDirectory
            _loading.value = false
            refreshMetadata(data.games, settings)
        }
        // Entering credentials for the first time should fill in the artwork that could not be
        // fetched without them, without the user having to re-add anything.
        scope.launch {
            GameLibrarySettingsRepository.uiState.drop(1).collect { updated ->
                refreshMetadata(_games.value, updated)
            }
        }
    }

    suspend fun searchIgdb(query: String): List<IgdbGame> = igdbClient.search(query, settings)

    suspend fun loadIgdbGame(gameId: Long): IgdbGame? = igdbClient.game(gameId, settings)

    suspend fun loadLogoCandidates(title: String): List<LogoCandidate> =
        steamGridDbClient.logosFor(title, settings.steamGridDbApiKey)

    suspend fun loadHeroCandidates(title: String): List<ArtworkCandidate> =
        steamGridDbClient.heroesFor(title, settings.steamGridDbApiKey)

    fun rememberExecutableDirectory(executablePath: String) {
        val directory = File(executablePath).parentFile?.absolutePath ?: return
        if (_lastExecutableDirectory.value == directory) return
        _lastExecutableDirectory.value = directory
        scope.launch { repository.saveLastExecutableDirectory(directory) }
    }

    fun add(game: GameEntry) {
        _games.value = _games.value + game
        persistGames()
        scope.launch { refreshMetadata(listOf(game), settings) }
    }

    fun update(game: GameEntry) {
        // Matching the game against a different IGDB entry invalidates the artwork fetched for the
        // old one, so the top-up is re-armed. This is the only thing that re-arms it — merely
        // saving the editor must not, or every save would re-fetch and clobber chosen artwork.
        val rematched = _games.value.firstOrNull { it.id == game.id }?.igdbId != game.igdbId
        val updated = if (rematched) game.copy(logoLookupVersion = 0) else game
        _games.value = _games.value.map { if (it.id == game.id) updated else it }
        persistGames()
        scope.launch { refreshMetadata(listOf(updated), settings) }
    }

    fun delete(gameId: String) {
        _games.value = _games.value.filterNot { it.id == gameId }
        persistGames()
    }

    fun launch(game: GameEntry, onExit: (Int) -> Unit = {}): Result<Long> =
        GameLauncher.launch(game, onExit)

    private fun persistGames() {
        val snapshot = _games.value
        scope.launch { repository.saveGames(snapshot) }
    }

    /**
     * Tops up artwork and metadata for entries that have not been through the current lookup.
     *
     * `logoLookupVersion` is the ratchet: 2 means IGDB metadata has been fetched, 3 means a
     * SteamGridDB logo was looked up as well. Adding a SteamGridDB key therefore re-runs only the
     * logo half for games already at 2, and each candidate is spaced out so a first-run library
     * does not hammer either API.
     */
    private suspend fun refreshMetadata(
        candidates: List<GameEntry>,
        settings: GameLibrarySettings,
    ) {
        if (!settings.igdbConfigured) return
        var changed = false
        val targetVersion = if (settings.steamGridDbConfigured) 3 else 2
        candidates.forEach { candidate ->
            val entry = _games.value.firstOrNull { it.id == candidate.id } ?: return@forEach
            val igdbId = entry.igdbId ?: return@forEach
            if (entry.logoLookupVersion >= targetVersion) return@forEach

            val metadataResult = if (entry.logoLookupVersion < 2) {
                runCatching { igdbClient.game(igdbId, settings) }
            } else {
                Result.success(null)
            }
            if (metadataResult.isFailure) {
                delay(300)
                return@forEach
            }

            val metadata = metadataResult.getOrNull()
            val metadataLogo = if (entry.logoLookupVersion < 2) metadata?.logoUrl else entry.logoUrl
            // No request at all for a hand-picked logo: its result could only be discarded.
            val steamGridLogoResult = if (settings.steamGridDbConfigured && !entry.logoManuallyChosen) {
                runCatching {
                    steamGridDbClient.logoFor(metadata?.title ?: entry.title, settings.steamGridDbApiKey)
                }
            } else {
                Result.success(null)
            }
            val completedVersion = when {
                settings.steamGridDbConfigured && steamGridLogoResult.isSuccess -> 3
                entry.logoLookupVersion < 2 && metadataResult.isSuccess -> 2
                else -> entry.logoLookupVersion
            }
            val refreshed = entry.copy(
                coverUrl = metadata?.coverUrl ?: entry.coverUrl,
                backdropUrl = entry.backdropUrl ?: metadata?.backdropUrl,
                logoUrl = entry.refreshedLogoUrl(steamGridLogoResult.getOrNull() ?: metadataLogo),
                summary = metadata?.summary ?: entry.summary,
                releaseDateEpochSeconds = metadata?.releaseDateEpochSeconds ?: entry.releaseDateEpochSeconds,
                genres = metadata?.genres?.ifEmpty { entry.genres } ?: entry.genres,
                platforms = metadata?.platforms?.ifEmpty { entry.platforms } ?: entry.platforms,
                rating = metadata?.rating ?: entry.rating,
                logoLookupCompleted = completedVersion >= 2,
                logoLookupVersion = completedVersion,
            )
            _games.value = _games.value.map { if (it.id == refreshed.id) refreshed else it }
            changed = true
            delay(300)
        }
        if (changed) {
            repository.saveGames(_games.value)
        }
    }

    companion object {
        val shared: GameLibraryController by lazy { GameLibraryController() }
    }
}
