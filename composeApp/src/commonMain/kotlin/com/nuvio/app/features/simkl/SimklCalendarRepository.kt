package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.TraktCalendarUiState
import com.nuvio.app.features.trakt.addMonth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val API_URL = "https://api.simkl.com"
private const val CDN_URL = "https://data.simkl.in"
private const val MAX_CONCURRENT_EPISODE_FETCHES = 5

@Serializable
private data class SimklEpisodeDto(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val aired: Boolean = false,
    val date: String? = null,
)

private data class ShowCalendarInfo(
    val simklId: Int,
    val contentId: String,
    val title: String,
    val posterUrl: String?,
    val isAnime: Boolean,
    /** Kept so episode coordinates can be projected into [contentId]'s space. */
    val ids: SimklMediaIds,
)

internal object SimklCalendarRepository {
    private val log = Logger.withTag("SimklCalendar")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktCalendarUiState())
    val uiState: StateFlow<TraktCalendarUiState> = _uiState.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false
    private var loadJob: kotlinx.coroutines.Job? = null

    // Movie watchlist data — populated from /sync/all-items, used when fetching per-movie
    // release dates from /movies/{id}. Keyed by simkl_id.
    private val movieWatchlistIds = mutableSetOf<Int>()
    private val movieWatchlistContentIds = mutableMapOf<Int, String>()  // simkl_id → imdb/tmdb id
    private val movieWatchlistPosters = mutableMapOf<Int, String?>()    // simkl_id → poster url
    private val loadedMovieMonths = mutableSetOf<String>()

    fun ensureLoaded() {
        if (!SimklAuthRepository.isAuthenticated.value) {
            _uiState.value = TraktCalendarUiState(isAuthenticated = false, hasLoaded = true)
            return
        }
        if (loaded) return
        triggerLoad()
    }

    fun ensureMonthsAround(year: Int, month: Int) {
        // All data (show episodes + movie releases) is fetched at once in loadAll().
        // Month navigation is a pure in-memory filter.
        ensureLoaded()
    }

    fun onProfileChanged() {
        loadJob?.cancel()
        loaded = false
        movieWatchlistIds.clear()
        movieWatchlistContentIds.clear()
        movieWatchlistPosters.clear()
        loadedMovieMonths.clear()
        _uiState.value = TraktCalendarUiState()
    }

    private fun triggerLoad() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            runCatching { loadAll() }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "SIMKL calendar load failed" }
                _uiState.value = TraktCalendarUiState(
                    isLoading = false,
                    isAuthenticated = true,
                    errorMessage = "Failed to load calendar",
                    hasLoaded = true,
                )
            }
        }
    }

    private suspend fun loadAll() {
        _uiState.value = TraktCalendarUiState(isLoading = true, isAuthenticated = true)

        val headers = SimklAuthRepository.authorizedHeaders() ?: run {
            _uiState.value = TraktCalendarUiState(isAuthenticated = false, hasLoaded = true)
            return
        }

        // Rule: check /sync/activities before any /sync/all-items call.
        val activities = SimklAuthRepository.fetchActivities()
        val latestTs = activities?.tvShows?.all
        val savedTs = SimklSettingsRepository.lastCalendarActivitiesAt()
        if (latestTs != null && latestTs == savedTs && loaded && _uiState.value.entriesByDate.isNotEmpty()) {
            log.d { "SIMKL calendar: activities unchanged, reusing cached data" }
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        // Step 1: fetch the user's library to get shows to include in the calendar.
        val shows = fetchWatchingShows(headers)
        if (shows.isEmpty()) {
            _uiState.value = TraktCalendarUiState(
                isLoading = false,
                isAuthenticated = true,
                hasLoaded = true,
                entriesByDate = emptyMap(),
            )
            loaded = true
            return
        }
        log.d { "SIMKL calendar: fetching episodes for ${shows.size} shows" }

        // Step 2: fetch episodes for each show and release dates for each movie in parallel.
        val semaphore = Semaphore(MAX_CONCURRENT_EPISODE_FETCHES)
        val allEntries: List<TraktCalendarEntry> = coroutineScope {
            val showJobs = shows.map { show ->
                async {
                    semaphore.withPermit { fetchShowEntries(show, headers) }
                }
            }
            val movieJobs = movieWatchlistIds.map { simklId ->
                async {
                    semaphore.withPermit { fetchMovieReleaseEntry(simklId, headers) }
                }
            }
            (showJobs + movieJobs).awaitAll().flatten()
        }

        // Step 3: bucket by date and publish.
        val byDate = allEntries
            .groupBy { it.dateKey }
            .mapValues { (_, entries) ->
                entries
                    .distinctBy { "${it.type}:${it.contentId}:${it.seasonNumber}:${it.episodeNumber}" }
                    .sortedBy { it.title.lowercase() }
            }

        log.d { "SIMKL calendar: ${allEntries.size} entries across ${byDate.size} dates" }
        loaded = true
        if (latestTs != null) SimklSettingsRepository.setLastCalendarActivitiesAt(latestTs)
        _uiState.value = TraktCalendarUiState(
            isLoading = false,
            isAuthenticated = true,
            hasLoaded = true,
            entriesByDate = byDate,
        )
    }

    private suspend fun fetchWatchingShows(headers: Map<String, String>): List<ShowCalendarInfo> {
        val url = SimklAuthRepository.appendParams("$API_URL/sync/all-items/all")
        val resp = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.getOrNull() ?: return emptyList()
        if (resp.status !in 200..299) return emptyList()

        return runCatching {
            val parsed = json.decodeFromString<SimklAllItemsResponse>(resp.body)

            // Collect movie watchlist data from the same response so per-movie release
            // fetches (/movies/{id}) can be launched without a second all-items call.
            movieWatchlistIds.clear()
            movieWatchlistContentIds.clear()
            movieWatchlistPosters.clear()
            loadedMovieMonths.clear()
            (parsed.movies).forEach { entry ->
                val status = entry.status
                if (status == "completed" || status == "dropped") return@forEach
                val movie = entry.movie ?: return@forEach
                val simklId = movie.ids.simkl ?: return@forEach
                movieWatchlistIds.add(simklId)
                val movieContentId = if (movie.ids.isKnownAnime()) {
                    movie.ids.toBestAnimeMovieContentId()
                } else {
                    movie.ids.toBestContentId()
                }
                movieContentId?.let { movieWatchlistContentIds[simklId] = it }
                movie.poster?.takeIf { it.isNotBlank() }
                    ?.let { movieWatchlistPosters[simklId] = it.simklPosterUrl() }
            }
            log.d { "SIMKL calendar: ${movieWatchlistIds.size} movies in watchlist for release tracking" }

            buildList {
                (parsed.shows).forEach { entry ->
                    val status = entry.status
                    if (status == "completed" || status == "dropped") return@forEach
                    val show = entry.show ?: return@forEach
                    val simklId = show.ids.simkl ?: return@forEach
                    val contentId = show.ids.toBestContentId() ?: return@forEach
                    add(ShowCalendarInfo(
                        simklId = simklId,
                        contentId = contentId,
                        title = show.title.orEmpty(),
                        posterUrl = show.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl(),
                        isAnime = false,
                        ids = show.ids,
                    ))
                }
                (parsed.anime).forEach { entry ->
                    val status = entry.status
                    if (status == "completed" || status == "dropped") return@forEach
                    val anime = entry.anime ?: return@forEach
                    val simklId = anime.ids.simkl ?: return@forEach
                    // Anime-aware, like every other SIMKL surface: a calendar row keyed by a
                    // franchise id cannot be matched against a library or Continue Watching row
                    // keyed by a kitsu/mal one, which is what silently drops the release badges.
                    val contentId = anime.ids.toBestAnimeContentId() ?: return@forEach
                    add(ShowCalendarInfo(
                        simklId = simklId,
                        contentId = contentId,
                        title = anime.title.orEmpty(),
                        posterUrl = anime.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl(),
                        isAnime = true,
                        ids = anime.ids,
                    ))
                }
            }
        }.onFailure { log.w(it) { "Failed to parse SIMKL library" } }.getOrDefault(emptyList())
    }

    private suspend fun fetchShowEntries(
        show: ShowCalendarInfo,
        headers: Map<String, String>,
    ): List<TraktCalendarEntry> {
        val type = if (show.isAnime) "anime" else "tv"
        val url = SimklAuthRepository.appendParams("$API_URL/$type/episodes/${show.simklId}")
        val resp = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.getOrNull() ?: return emptyList()
        if (resp.status !in 200..299) return emptyList()

        return runCatching {
            json.decodeFromString<List<SimklEpisodeDto>>(resp.body)
                .mapNotNull { ep ->
                    val dateKey = ep.date?.take(10)?.takeIf { it.length == 10 } ?: return@mapNotNull null
                    // /anime/episodes numbers episodes within the entry, so a franchise content id
                    // needs them lifted onto the franchise season the entry maps to; a per-entry id
                    // takes them as they are. Only a complete pair can be projected.
                    val coordinates = if (ep.season != null && ep.episode != null) {
                        show.ids.episodeCoordinatesFor(
                            contentId = show.contentId,
                            isAnime = show.isAnime,
                            entrySeason = ep.season,
                            entryEpisode = ep.episode,
                        )
                    } else {
                        ep.season to ep.episode
                    }
                    TraktCalendarEntry(
                        dateKey = dateKey,
                        type = "series",
                        contentId = show.contentId,
                        title = show.title,
                        posterUrl = show.posterUrl,
                        seasonNumber = coordinates.first,
                        episodeNumber = coordinates.second,
                        episodeTitle = ep.title?.takeIf { it.isNotBlank() },
                    )
                }
        }.onFailure { log.w(it) { "Failed to parse episodes for ${show.title}" } }
            .getOrDefault(emptyList())
    }

    /**
     * Fetches release dates for a single movie from /movies/{id} and returns a calendar
     * entry for the best available date (digital > theatrical, US/GB preferred).
     * The CDN movie_release.json was tried first but proved incomplete for smaller titles.
     */
    private suspend fun fetchMovieReleaseEntry(
        simklId: Int,
        headers: Map<String, String>,
    ): List<TraktCalendarEntry> {
        val url = SimklAuthRepository.appendParams("$API_URL/movies/$simklId")
        val resp = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.getOrNull() ?: return emptyList()
        if (resp.status !in 200..299) return emptyList()

        return runCatching {
            val details = json.decodeFromString<SimklMovieDetails>(resp.body)
            val releaseDates = details.releaseDates ?: return emptyList()
            val dateKey = releaseDates.bestCalendarDate()
                ?.take(10)?.takeIf { it.length == 10 }
                ?: return emptyList()

            // Look up content ID from the watchlist — the movie detail endpoint doesn't
            // reliably include IMDB/TMDB IDs, but we have them from /sync/all-items.
            // Fall back to simkl: prefix which the detail screen can resolve.
            val contentId = movieWatchlistContentIds[simklId] ?: "simkl:$simklId"
            val title = details.title ?: return emptyList()
            listOf(
                TraktCalendarEntry(
                    dateKey = dateKey,
                    type = "movie",
                    contentId = contentId,
                    title = title,
                    posterUrl = movieWatchlistPosters[simklId],
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                )
            )
        }.onFailure { log.w(it) { "SIMKL movie $simklId release fetch failed" } }
            .getOrDefault(emptyList())
    }

    private fun monthKey(year: Int, month: Int): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
}
