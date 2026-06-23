package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class SimklLibraryUiState(
    val shows: List<LibraryItem> = emptyList(),
    val movies: List<LibraryItem> = emptyList(),
    val anime: List<LibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    val allItems: List<LibraryItem> get() = shows + movies + anime
}

private const val BASE_URL = "https://api.simkl.com"

internal object SimklLibraryRepository {
    private val log = Logger.withTag("SimklLibrary")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val _uiState = MutableStateFlow(SimklLibraryUiState())
    val uiState: StateFlow<SimklLibraryUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (SimklAuthRepository.isAuthenticated.value) refreshAsync()
    }

    fun refreshAsync() {
        refreshJob?.cancel()
        refreshJob = scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        if (!SimklAuthRepository.isAuthenticated.value) {
            _uiState.value = SimklLibraryUiState(hasLoaded = true)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Rule: always check /sync/activities before /sync/all-items.
        val activities = SimklAuthRepository.fetchActivities()
        val latestTs = listOfNotNull(
            activities?.tvShows?.planToWatch,
            activities?.movies?.planToWatch,
            activities?.anime?.planToWatch,
        ).maxOrNull()
        val savedTs = SimklSettingsRepository.lastLibraryActivitiesAt()
        if (latestTs != null && latestTs == savedTs && _uiState.value.allItems.isNotEmpty()) {
            // Nothing changed — reuse the cached state rather than downloading the full library.
            log.d { "SIMKL library: activities unchanged, skipping full fetch" }
            _uiState.value = _uiState.value.copy(isLoading = false, hasLoaded = true)
            return
        }

        runCatching {
            // Rule: fetch shows, movies, anime SEQUENTIALLY (not in parallel) to avoid
            // CPU spikes on SIMKL's servers during large initial library downloads.
            val shows = fetchType("shows")
            val movies = fetchType("movies")
            val anime = fetchType("anime")
            Triple(shows, movies, anime)
        }.fold(
            onSuccess = { (shows, movies, anime) ->
                _uiState.value = SimklLibraryUiState(
                    shows = shows,
                    movies = movies,
                    anime = anime,
                    isLoading = false,
                    hasLoaded = true,
                )
                log.d { "SIMKL library: ${shows.size} shows, ${movies.size} movies, ${anime.size} anime" }
                if (latestTs != null) SimklSettingsRepository.setLastLibraryActivitiesAt(latestTs)
            },
            onFailure = { error ->
                log.w(error) { "SIMKL library fetch failed" }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message,
                )
            },
        )
    }

    fun clearLocalState() {
        refreshJob?.cancel()
        loaded = false
        _uiState.value = SimklLibraryUiState()
    }

    private suspend fun fetchType(type: String): List<LibraryItem> {
        val headers = SimklAuthRepository.authorizedHeaders() ?: return emptyList()
        // Filter to plantowatch only — the user's "want to watch" list, not their full history.
        val url = SimklAuthRepository.appendParams("$BASE_URL/sync/all-items/$type/plantowatch")
        val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        if (response.status !in 200..299) {
            error("SIMKL /sync/all-items/$type returned ${response.status}")
        }
        val parsed = json.decodeFromString<SimklAllItemsResponse>(response.body)
        val contentType = if (type == "movies") "movie" else "series"
        return buildList {
            parsed.shows.forEach { it.toLibraryItem(contentType)?.let(::add) }
            parsed.movies.forEach { it.toLibraryItem(contentType)?.let(::add) }
            parsed.anime.forEach { it.toLibraryItem(contentType)?.let(::add) }
        }
    }

    private fun SimklAllItemsEntry.toLibraryItem(type: String): LibraryItem? {
        val ids = show?.ids ?: movie?.ids ?: anime?.ids ?: return null
        val contentId = ids.toBestContentId() ?: return null
        val title = show?.title ?: movie?.title ?: anime?.title ?: return null
        val poster = (show?.poster ?: movie?.poster ?: anime?.poster)
            ?.takeIf { it.isNotBlank() }?.simklPosterUrl()
        val savedAt = (addedToWatchlistAt ?: lastWatchedAt)
            ?.let { parseSimklTimestamp(it) }
            ?: System.currentTimeMillis()

        return LibraryItem(
            id = contentId,
            type = type,
            name = title,
            poster = poster,
            imdbId = ids.imdb,
            tmdbId = ids.tmdb?.toIntOrNull(),
            savedAtEpochMs = savedAt,
        )
    }
}
