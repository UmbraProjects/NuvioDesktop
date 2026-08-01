package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.toSimklIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
                // Enrich items with genres/description/runtime from the addon system in the
                // background. Results update the uiState so poster row labels and the hero
                // both reflect real metadata without requiring a detail-page visit first.
                // Enrichment is NOT launched here — addons may not be loaded yet at this
                // point. HomeScreen triggers enrichLibraryItems() once addons are ready.
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

    /** Adds to Plan to Watch, or removes the item from SIMKL's library entirely. */
    suspend fun setPlanToWatch(item: LibraryItem, desired: Boolean) {
        val headers = SimklAuthRepository.authorizedHeaders()
            ?: error("SIMKL is not connected")
        val resolved = MediaIdResolver.resolve(
            contentType = item.type,
            parentMetaId = item.id,
            videoId = null,
            title = item.name,
            isAnimeHint = item.type.equals("anime", ignoreCase = true),
        )
        val ids = resolved.toSimklIds()
        if (!ids.hasAddressableLibraryId()) {
            error("SIMKL could not identify ${item.name}")
        }

        val body = encodeLibraryMutation(
            item = item,
            ids = ids,
            desired = desired,
            isAnime = resolved.isAnime,
        )
        val endpoint = if (desired) "/sync/add-to-list" else "/sync/history/remove"
        val url = SimklAuthRepository.appendParams("$BASE_URL$endpoint")
        val previous = _uiState.value
        _uiState.value = previous.withMembership(item, desired, resolved.isAnime)
        val response = runCatching {
            httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
        }.getOrElse { error ->
            _uiState.value = previous
            throw error
        }
        if (response.status !in 200..299) {
            _uiState.value = previous
            error("SIMKL library update failed (${response.status}): ${response.body.take(200)}")
        }
        // The write bumps /sync/activities. Clearing the saved watermark prevents a later refresh
        // from incorrectly treating the optimistic snapshot as already reconciled.
        SimklSettingsRepository.setLastLibraryActivitiesAt("")
    }

    fun contains(itemId: String, contentType: String? = null): Boolean =
        _uiState.value.allItems.any { candidate ->
            candidate.id == itemId && (contentType == null || candidate.type.equals(contentType, ignoreCase = true))
        }

    fun find(itemId: String): LibraryItem? = _uiState.value.allItems.firstOrNull { it.id == itemId }

    @Serializable
    private data class SimklLibraryEntry(
        val title: String? = null,
        val to: String? = null,
        val ids: SimklScrobbleRepository.SimklIds,
    )

    @Serializable
    private data class SimklLibraryMutation(
        val movies: List<SimklLibraryEntry> = emptyList(),
        val shows: List<SimklLibraryEntry> = emptyList(),
        val anime: List<SimklLibraryEntry> = emptyList(),
    )

    private fun encodeLibraryMutation(
        item: LibraryItem,
        ids: SimklScrobbleRepository.SimklIds,
        desired: Boolean,
        isAnime: Boolean,
    ): String {
        val entry = SimklLibraryEntry(
            title = item.name.takeIf { it.isNotBlank() },
            to = "plantowatch".takeIf { desired },
            ids = ids,
        )
        val request = when {
            isAnime -> SimklLibraryMutation(anime = listOf(entry))
            item.type.equals("movie", ignoreCase = true) -> SimklLibraryMutation(movies = listOf(entry))
            else -> SimklLibraryMutation(shows = listOf(entry))
        }
        return json.encodeToString(request)
    }

    internal fun encodeLibraryMutationForTest(
        item: LibraryItem,
        ids: SimklScrobbleRepository.SimklIds,
        desired: Boolean,
        isAnime: Boolean = false,
    ): String = encodeLibraryMutation(item, ids, desired, isAnime)

    private fun SimklScrobbleRepository.SimklIds.hasAddressableLibraryId(): Boolean =
        simkl != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null || mal != null ||
            kitsu != null || anilist != null || anidb != null

    private fun SimklLibraryUiState.withMembership(
        item: LibraryItem,
        desired: Boolean,
        isAnime: Boolean,
    ): SimklLibraryUiState {
        fun List<LibraryItem>.withoutItem() = filterNot { existing ->
            existing.id == item.id && existing.type.equals(item.type, ignoreCase = true)
        }
        val nextShows = shows.withoutItem().toMutableList()
        val nextMovies = movies.withoutItem().toMutableList()
        val nextAnime = anime.withoutItem().toMutableList()
        if (desired) {
            val added = item.copy(savedAtEpochMs = System.currentTimeMillis())
            when {
                isAnime -> nextAnime += added
                item.type.equals("movie", ignoreCase = true) -> nextMovies += added
                else -> nextShows += added
            }
        }
        return copy(shows = nextShows, movies = nextMovies, anime = nextAnime, hasLoaded = true)
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

        val imdbId = ids.imdb?.takeIf { it.isNotBlank() }
        val year = show?.year ?: movie?.year ?: anime?.year
        // Baseline backdrop so there is always something to show before enrichment runs.
        // enrichItems() calls fetchLightweightMeta which upgrades this to a TMDB-quality
        // URL when AIOMetadata (or the TMDB fallback) responds.
        val banner = imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }

        return LibraryItem(
            id = contentId,
            type = type,
            name = title,
            poster = poster,
            banner = banner,
            releaseInfo = year?.toString(),
            imdbId = imdbId,
            tmdbId = ids.tmdb?.toIntOrNull(),
            savedAtEpochMs = savedAt,
        )
    }

    // Called from HomeScreen once addons are confirmed loaded, so findMetaManifests has
    // something to query. Safe to call multiple times — lightweightMetaCache makes
    // repeat calls for already-fetched items instant.
    fun triggerEnrichment() {
        val current = _uiState.value
        if (current.allItems.isEmpty()) return
        scope.launch { enrichItems(current.shows, current.movies, current.anime) }
    }

    // Fetches genres/description/runtime from the addon system for all library items and
    // writes them back into uiState so poster row labels and the hero show real metadata.
    // Limited to 3 concurrent requests; items already in the lightweight cache skip the
    // network entirely so re-runs (e.g. after a restart) are instant.
    private suspend fun enrichItems(
        shows: List<LibraryItem>,
        movies: List<LibraryItem>,
        anime: List<LibraryItem>,
    ) {
        val all = shows + movies + anime
        val sem = Semaphore(3)
        val enriched = mutableMapOf<String, LibraryItem>()

        all.map { item ->
            scope.launch {
                sem.withPermit {
                    // preferTmdbImages = true so logos and backdrops come from TMDB/TVDB
                    // rather than metahub.space (Fanart.tv) which serves lower-quality art.
                    val meta = runCatching {
                        MetaDetailsRepository.fetchLightweightMeta(
                            item.type, item.id, preferTmdbImages = true,
                        )
                    }.getOrNull() ?: return@withPermit
                    if (meta.genres.isEmpty() && meta.description == null && meta.runtime == null) return@withPermit
                    enriched[item.id] = item.copy(
                        genres = meta.genres.ifEmpty { item.genres },
                        description = meta.description ?: item.description,
                        releaseInfo = item.releaseInfo ?: meta.releaseInfo,
                        runtime = item.runtime ?: meta.runtime,
                        banner = run {
                            val fetched = meta.background
                            val existing = item.banner
                            when {
                                fetched?.contains("image.tmdb.org") == true -> fetched
                                existing?.contains("image.tmdb.org") == true -> existing
                                fetched != null && fetched.contains("images.metahub.space") != true -> fetched
                                existing != null -> existing
                                else -> fetched?.replace("/background/medium/", "/background/large/")
                            }
                        },
                        // Prefer fetched logo (TMDB clearlogo) over existing (metahub/Fanart.tv).
                        logo = meta.logo ?: item.logo,
                    )
                }
            }
        }.forEach { it.join() }

        if (enriched.isEmpty()) return

        fun List<LibraryItem>.applyEnrichment() = map { enriched[it.id] ?: it }
        _uiState.value = _uiState.value.copy(
            shows = shows.applyEnrichment(),
            movies = movies.applyEnrichment(),
            anime = anime.applyEnrichment(),
        )
        log.d { "SIMKL library: enriched ${enriched.size} / ${all.size} items with addon metadata" }
    }
}
