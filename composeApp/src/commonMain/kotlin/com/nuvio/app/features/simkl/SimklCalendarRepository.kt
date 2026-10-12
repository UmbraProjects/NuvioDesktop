package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.TraktCalendarUiState
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val API_URL = "https://api.simkl.com"
private const val MAX_CONCURRENT_EPISODE_FETCHES = 5

/**
 * How often SIMKL's `/changes` feed is consulted. Its docs ask for at most once a day per user,
 * gated on a stored timestamp — never a timer. A launch inside this window with an unchanged
 * library costs one request (`/sync/activities`) and no catalog reads at all.
 */
private const val CHANGES_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/**
 * `/changes` only looks back 14 days (older `date_from` values silently clamp), so a cache whose
 * last check is older than this could have missed a change: everything is refetched instead.
 * A day short of the cap leaves room for the one-day overlap on `date_from`.
 */
private const val CHANGES_WINDOW_MS = 13 * 24 * 60 * 60 * 1000L

@Serializable
private data class SimklEpisodeDto(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val aired: Boolean = false,
    val date: String? = null,
)

internal object SimklCalendarRepository {
    private val log = Logger.withTag("SimklCalendar")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktCalendarUiState())
    val uiState: StateFlow<TraktCalendarUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var loadJob: kotlinx.coroutines.Job? = null

    // Provenance of whatever entries are currently published, whether fetched or restored from
    // disk; together they decide whether the next load can stop after `/sync/activities`.
    private var hydrated = false
    private var lastActivitiesStamp: String? = null
    private var lastChangesCheckAtEpochMs = 0L
    private var items: Map<String, SimklCalendarCachedItem> = emptyMap()

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
        hydrated = false
        lastActivitiesStamp = null
        lastChangesCheckAtEpochMs = 0L
        items = emptyMap()
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
        // `copy`, not a fresh state: entries restored from disk on an earlier attempt stay on
        // screen while this one decides whether they are still current.
        _uiState.value = _uiState.value.copy(isLoading = true, isAuthenticated = true, errorMessage = null)

        if (!SimklAuthRepository.hasUsableToken()) run {
            _uiState.value = TraktCalendarUiState(isAuthenticated = false, hasLoaded = true)
            return
        }

        hydrateFromDisk()
        val profileId = ProfileRepository.activeProfileId

        // Rule: check /sync/activities before any /sync/all-items call.
        val activities = SimklAuthRepository.fetchActivities()
        val stamp = simklCalendarActivitiesStamp(activities)
        val haveItems = items.isNotEmpty()
        if (haveItems && activities == null) {
            // No activities means no SIMKL at all right now; the library fetch would only fail the
            // same way, and a failed fetch used to publish an empty calendar over a good one.
            log.d { "SIMKL calendar: activities unavailable, keeping cached data" }
            finishWithCachedData()
            return
        }

        val now = TraktPlatformClock.nowEpochMs()
        val libraryChanged = !haveItems || stamp == null || stamp != lastActivitiesStamp
        val sinceCheck = now - lastChangesCheckAtEpochMs
        val changesDue = sinceCheck !in 0 until CHANGES_CHECK_INTERVAL_MS
        val hasFailedItems = items.values.any { it.fetchedAtEpochMs == 0L }
        if (!libraryChanged && !changesDue && !hasFailedItems) {
            log.d { "SIMKL calendar: library unchanged, catalog checked ${sinceCheck}ms ago; reusing cached data" }
            finishWithCachedData()
            return
        }

        // Step 1: the user's library decides what is on the calendar. Read only when it moved —
        // otherwise the cached items already are the library.
        val library = if (libraryChanged) {
            fetchLibrary() ?: run {
                if (haveItems) {
                    log.d { "SIMKL calendar: library fetch failed, keeping cached data" }
                    finishWithCachedData()
                } else {
                    _uiState.value = TraktCalendarUiState(
                        isLoading = false,
                        isAuthenticated = true,
                        errorMessage = "Failed to load calendar",
                        hasLoaded = true,
                    )
                }
                return
            }
        } else {
            items.values.map { it.info }
        }

        // Step 2: decide which catalog records to fetch. A missing or too-old `/changes` check
        // cannot vouch for the cache, so everything is fetched and the fetch itself becomes the check.
        val refreshAll = lastChangesCheckAtEpochMs <= 0L || sinceCheck !in 0 until CHANGES_WINDOW_MS
        var changesCheckedAt = lastChangesCheckAtEpochMs
        val changes = when {
            refreshAll -> {
                changesCheckedAt = now
                null
            }
            changesDue && library.isNotEmpty() -> fetchChanges(lastChangesCheckAtEpochMs, library)
                ?.also { changesCheckedAt = now }
            else -> null
        }
        val toFetch = simklCalendarItemsToFetch(library, items, changes, refreshAll)
        log.d {
            "SIMKL calendar: library=${library.size} cached=${items.size} fetching=${toFetch.size} " +
                "(libraryChanged=$libraryChanged refreshAll=$refreshAll changesChecked=${changes != null})"
        }

        // Step 3: fetch only those, in parallel. A failed fetch keeps the item's previous entries
        // and is marked for retry rather than blanking it.
        val semaphore = Semaphore(MAX_CONCURRENT_EPISODE_FETCHES)
        val fetched: Map<String, List<TraktCalendarEntry>?> = coroutineScope {
            toFetch.map { info ->
                async {
                    info.key to semaphore.withPermit {
                        if (info.kind == SimklCalendarItemInfo.KIND_MOVIE) fetchMovieReleaseEntry(info)
                        else fetchShowEntries(info)
                    }
                }
            }.awaitAll().toMap()
        }
        // The commit below does not suspend, so a profile switch that lands during the fetch would
        // otherwise publish — and persist under the NEW profile's id — the old profile's calendar.
        if (profileId != ProfileRepository.activeProfileId) return
        val fetchedAt = TraktPlatformClock.nowEpochMs()
        val nextItems = library.associate { info ->
            val previous = items[info.key]
            val item = if (fetched.containsKey(info.key)) {
                val entries = fetched[info.key]
                if (entries != null) {
                    SimklCalendarCachedItem(info, fetchedAt, entries.map { it.toCached() })
                } else {
                    SimklCalendarCachedItem(info, 0L, previous?.entries.orEmpty())
                }
            } else {
                previous ?: SimklCalendarCachedItem(info, 0L)
            }
            info.key to item
        }

        items = nextItems
        loaded = true
        lastActivitiesStamp = stamp
        lastChangesCheckAtEpochMs = changesCheckedAt
        val allEntries = nextItems.values.flatMap { item -> item.entries.map { it.toEntry() } }
        val byDate = allEntries.groupedByDate()
        log.d { "SIMKL calendar: ${allEntries.size} entries across ${byDate.size} dates" }
        _uiState.value = TraktCalendarUiState(
            isLoading = false,
            isAuthenticated = true,
            hasLoaded = true,
            entriesByDate = byDate,
        )
        persistToDisk(profileId)
    }

    private fun finishWithCachedData() {
        loaded = true
        _uiState.value = _uiState.value.copy(isLoading = false, hasLoaded = true)
    }

    private fun List<TraktCalendarEntry>.groupedByDate(): Map<String, List<TraktCalendarEntry>> =
        groupBy { it.dateKey }
            .mapValues { (_, entries) ->
                entries
                    .distinctBy { "${it.type}:${it.contentId}:${it.seasonNumber}:${it.episodeNumber}" }
                    .sortedBy { it.title.lowercase() }
            }

    /**
     * Publishes the last fetched calendar from disk, once per process, so the screen has entries
     * before `/sync/activities` answers — and so that answer can end the load at one request.
     *
     * Only a readable, current-version file for the active profile is restored; nothing here
     * decides freshness, [loadAll] does that with the stamp and check time this records.
     */
    private suspend fun hydrateFromDisk() {
        if (hydrated) return
        hydrated = true

        val read = withContext(Dispatchers.IO) { runCatching { SimklCalendarCacheStorage.load() } }
        val raw = read.getOrElse { error ->
            log.d { "Could not read the SIMKL calendar cache: ${error.message}" }
            return
        }
        if (raw == null) {
            log.d { "No SIMKL calendar cache on disk" }
            return
        }
        val entry = runCatching { json.decodeFromString<SimklCalendarCacheEntry>(raw) }
            .getOrElse { error ->
                log.d { "Discarding unreadable SIMKL calendar cache: ${error.message}" }
                runCatching { SimklCalendarCacheStorage.save(null) }
                return
            }
        if (entry.version != SimklCalendarCacheEntry.VERSION) {
            log.d { "SIMKL calendar cache is v${entry.version}, expected v${SimklCalendarCacheEntry.VERSION}; ignoring" }
            runCatching { SimklCalendarCacheStorage.save(null) }
            return
        }
        val profileId = ProfileRepository.activeProfileId
        if (entry.profileId != profileId) {
            log.d { "SIMKL calendar cache belongs to profile ${entry.profileId}, active is $profileId; ignoring" }
            return
        }
        if (entry.items.isEmpty()) return

        items = entry.items.associateBy { it.info.key }
        lastActivitiesStamp = entry.activitiesStamp
        lastChangesCheckAtEpochMs = entry.changesCheckedAtEpochMs
        val byDate = entry.items.flatMap { item -> item.entries.map { it.toEntry() } }.groupedByDate()
        _uiState.value = _uiState.value.copy(
            isAuthenticated = true,
            hasLoaded = true,
            entriesByDate = byDate,
        )
        log.d {
            "SIMKL calendar cache restored: ${entry.items.size} items, " +
                "built ${TraktPlatformClock.nowEpochMs() - entry.builtAtEpochMs}ms ago, " +
                "catalog checked ${TraktPlatformClock.nowEpochMs() - entry.changesCheckedAtEpochMs}ms ago"
        }
    }

    /** Fire-and-forget: nothing waits on the file, and an empty library clears it rather than leaving the old one. */
    private fun persistToDisk(profileId: Int) {
        val snapshot = SimklCalendarCacheEntry(
            builtAtEpochMs = TraktPlatformClock.nowEpochMs(),
            profileId = profileId,
            activitiesStamp = lastActivitiesStamp,
            changesCheckedAtEpochMs = lastChangesCheckAtEpochMs,
            items = items.values.toList(),
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                SimklCalendarCacheStorage.save(
                    if (snapshot.items.isEmpty()) null else json.encodeToString(snapshot),
                )
            }.onFailure { error -> log.d { "Could not persist the SIMKL calendar: ${error.message}" } }
        }
    }

    /**
     * SIMKL's catalog change feed since [sinceEpochMs], narrowed to the buckets the library uses.
     * One request a day replaces re-reading every followed show to catch new air dates. Null on any
     * failure, so the caller keeps the previous check time and asks again next load.
     */
    private suspend fun fetchChanges(
        sinceEpochMs: Long,
        library: List<SimklCalendarItemInfo>,
    ): SimklChangesResponse? {
        // A day of overlap: `date_from` is a date, and the server excludes the last five minutes.
        val dateFrom = java.time.Instant.ofEpochMilli(sinceEpochMs)
            .minus(java.time.Duration.ofDays(1))
            .toString()
            .take(10)
        val types = library.map { it.changesBucket }.distinct().sorted().joinToString(",")
        val url = SimklAuthRepository.appendParams("$API_URL/changes?date_from=$dateFrom&type=$types")
        val resp = runCatching {
            simklRequest(method = "GET", url = url, allowLargeResponse = true)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return null
        if (resp.status !in 200..299) {
            log.w { "SIMKL /changes answered ${resp.status}; keeping the cached calendar" }
            return null
        }
        val body = resp.body.trim()
        if (body.isEmpty() || body == "null" || body == "[]") return SimklChangesResponse()
        return runCatching { json.decodeFromString<SimklChangesResponse>(body) }
            .onFailure { log.w(it) { "Failed to parse SIMKL /changes" } }
            .getOrNull()
    }

    /** Null when SIMKL could not be reached or answered with an error — distinct from an empty library. */
    private suspend fun fetchLibrary(): List<SimklCalendarItemInfo>? {
        val url = SimklAuthRepository.appendParams("$API_URL/sync/all-items/all")
        val resp = runCatching {
            simklRequest(method = "GET", url = url)
        }.getOrNull() ?: return null
        if (resp.status !in 200..299) return null

        return runCatching {
            val parsed = json.decodeFromString<SimklAllItemsResponse>(resp.body)
            buildList {
                parsed.movies.forEach { entry ->
                    val status = entry.status
                    if (status == "completed" || isSimklDroppedStatus(status)) return@forEach
                    val movie = entry.movie ?: return@forEach
                    val simklId = movie.ids.simkl ?: return@forEach
                    val movieContentId = if (movie.ids.isKnownAnime()) {
                        movie.ids.toBestAnimeMovieContentId()
                    } else {
                        movie.ids.toBestContentId()
                    }
                    add(SimklCalendarItemInfo(
                        kind = SimklCalendarItemInfo.KIND_MOVIE,
                        simklId = simklId,
                        // The movie detail endpoint doesn't reliably include IMDB/TMDB ids, so the
                        // library's are kept; simkl: is a fallback the detail screen can resolve.
                        contentId = movieContentId ?: "simkl:$simklId",
                        title = movie.title.orEmpty(),
                        posterUrl = movie.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl(),
                        ids = movie.ids,
                    ))
                }
                parsed.shows.forEach { entry ->
                    val status = entry.status
                    if (status == "completed" || isSimklDroppedStatus(status)) return@forEach
                    val show = entry.show ?: return@forEach
                    val simklId = show.ids.simkl ?: return@forEach
                    val contentId = show.ids.toBestContentId() ?: return@forEach
                    add(SimklCalendarItemInfo(
                        kind = SimklCalendarItemInfo.KIND_TV,
                        simklId = simklId,
                        contentId = contentId,
                        title = show.title.orEmpty(),
                        posterUrl = show.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl(),
                        ids = show.ids,
                    ))
                }
                parsed.anime.forEach { entry ->
                    val status = entry.status
                    if (status == "completed" || isSimklDroppedStatus(status)) return@forEach
                    val anime = entry.anime ?: return@forEach
                    val simklId = anime.ids.simkl ?: return@forEach
                    // Anime-aware, like every other SIMKL surface: a calendar row keyed by a
                    // franchise id cannot be matched against a library or Continue Watching row
                    // keyed by a kitsu/mal one, which is what silently drops the release badges.
                    val contentId = anime.ids.toBestAnimeContentId() ?: return@forEach
                    add(SimklCalendarItemInfo(
                        kind = SimklCalendarItemInfo.KIND_ANIME,
                        simklId = simklId,
                        contentId = contentId,
                        title = anime.title.orEmpty(),
                        posterUrl = anime.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl(),
                        ids = anime.ids,
                    ))
                }
            }.distinctBy { it.key }
        }.onFailure { log.w(it) { "Failed to parse SIMKL library" } }.getOrNull()
    }

    /** Null on a failed request or unparseable body, so the caller keeps the item's previous entries. */
    private suspend fun fetchShowEntries(
        show: SimklCalendarItemInfo,
    ): List<TraktCalendarEntry>? {
        val isAnime = show.kind == SimklCalendarItemInfo.KIND_ANIME
        val type = if (isAnime) "anime" else "tv"
        val url = SimklAuthRepository.appendParams("$API_URL/$type/episodes/${show.simklId}")
        val resp = runCatching {
            // Public, edge-cached catalog data: no Authorization, or Cloudflare bypasses its cache.
            simklRequest(method = "GET", url = url, authenticated = false)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return null
        // Gone from SIMKL's catalog: an answer, not a failure — otherwise it is retried every launch.
        if (resp.status == 404) return emptyList()
        if (resp.status !in 200..299) return null

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
                            isAnime = isAnime,
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
                        airAt = ep.date?.takeIf { it.length > 10 && 'T' in it },
                    )
                }
        }.onFailure { log.w(it) { "Failed to parse episodes for ${show.title}" } }
            .getOrNull()
    }

    /**
     * Fetches release dates for a single movie from /movies/{id} and returns a calendar
     * entry for the best available date (digital > theatrical, US/GB preferred).
     * The CDN movie_release.json was tried first but proved incomplete for smaller titles.
     * Null on a failed request; an empty list when SIMKL simply has no usable date.
     */
    private suspend fun fetchMovieReleaseEntry(
        movie: SimklCalendarItemInfo,
    ): List<TraktCalendarEntry>? {
        val url = SimklAuthRepository.appendParams("$API_URL/movies/${movie.simklId}")
        val resp = runCatching {
            simklRequest(method = "GET", url = url, authenticated = false)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return null
        if (resp.status == 404) return emptyList()
        if (resp.status !in 200..299) return null

        return runCatching {
            val details = json.decodeFromString<SimklMovieDetails>(resp.body)
            val releaseDates = details.releaseDates ?: return emptyList()
            val dateKey = releaseDates.bestCalendarDate()
                ?.take(10)?.takeIf { it.length == 10 }
                ?: return emptyList()
            val title = details.title ?: movie.title.takeIf { it.isNotBlank() } ?: return emptyList()
            listOf(
                TraktCalendarEntry(
                    dateKey = dateKey,
                    type = "movie",
                    contentId = movie.contentId,
                    title = title,
                    posterUrl = movie.posterUrl,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                )
            )
        }.onFailure { log.w(it) { "SIMKL movie ${movie.simklId} release fetch failed" } }
            .getOrNull()
    }
}
