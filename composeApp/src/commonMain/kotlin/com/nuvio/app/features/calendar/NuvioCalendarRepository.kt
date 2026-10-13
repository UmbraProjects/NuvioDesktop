package com.nuvio.app.features.calendar

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.TraktCalendarUiState
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.resolveReleaseInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

private const val MAX_CONCURRENT_FETCHES = 4

/** A screen reopened within this long reuses the last pass instead of re-checking what is due. */
private const val RECHECK_INTERVAL_MS = 15 * 60 * 1000L

/** While a long first pass runs, the grid is refreshed after every this many titles. */
private const val PUBLISH_EVERY = 20

/**
 * The calendar with no tracking service: every show in the profile's Nuvio library and watch
 * history, dated from the user's own meta addons, plus library movies dated from TMDB.
 *
 * The same data Continue Watching's Up Next already reads, so the calendar agrees with it — the
 * addon's exact air times, with AIOMetadata's noon-UTC placeholder repaired from TVDB inside the
 * fetch. Each title is cached on disk and re-read on its own schedule (12h while running, 30 days
 * once finished; see [nuvioCalendarRefreshDue]), so a launch usually costs a handful of requests.
 */
internal object NuvioCalendarRepository {
    private val log = Logger.withTag("NuvioCalendar")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktCalendarUiState(isAuthenticated = true))
    val uiState: StateFlow<TraktCalendarUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var hydrated = false
    private var lastPassAtEpochMs = 0L
    @Volatile
    private var items: Map<String, NuvioCalendarCachedItem> = emptyMap()

    fun ensureLoaded() {
        if (loadJob?.isActive == true) return
        val sinceLastPass = TraktPlatformClock.nowEpochMs() - lastPassAtEpochMs
        if (lastPassAtEpochMs > 0L && sinceLastPass in 0 until RECHECK_INTERVAL_MS) return
        loadJob = scope.launch {
            runCatching { loadAll() }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Local calendar load failed" }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = "Failed to load calendar",
                )
            }
        }
    }

    /** Everything is fetched per title in [loadAll]; month navigation is an in-memory filter. */
    fun ensureMonthsAround(year: Int, month: Int) = ensureLoaded()

    fun onProfileChanged() {
        loadJob?.cancel()
        hydrated = false
        lastPassAtEpochMs = 0L
        items = emptyMap()
        _uiState.value = TraktCalendarUiState(isAuthenticated = true)
    }

    fun clearLocalState() {
        onProfileChanged()
        scope.launch(Dispatchers.IO) { runCatching { NuvioCalendarCacheStorage.save(null) } }
    }

    private suspend fun loadAll() {
        _uiState.value = _uiState.value.copy(isLoading = true, isAuthenticated = true, errorMessage = null)
        hydrateFromDisk()
        val profileId = ProfileRepository.activeProfileId

        WatchedRepository.ensureLoaded()
        LibraryRepository.ensureLoaded()
        val follows = nuvioCalendarFollows(
            library = LibraryRepository.nuvioLibraryItems(),
            watched = WatchedRepository.uiState.value.items,
        )
        val now = TraktPlatformClock.nowEpochMs()
        val toFetch = follows.filter { nuvioCalendarRefreshDue(items[it.key], now) }
        log.d { "Local calendar: following=${follows.size} cached=${items.size} fetching=${toFetch.size}" }

        // Titles no longer followed drop out now; the rest keep their entries while being re-read.
        items = follows.mapNotNull { follow -> items[follow.key]?.let { follow.key to it.copy(follow = follow) } }.toMap()

        if (toFetch.isNotEmpty()) {
            AddonRepository.initialize()
            withTimeoutOrNull(10_000L) { AddonRepository.awaitManifestsLoaded() }

            val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
            val commitLock = Mutex()
            var done = 0
            coroutineScope {
                toFetch.map { follow ->
                    async {
                        val fetched = semaphore.withPermit { fetchItem(follow, items[follow.key]) }
                        commitLock.withLock {
                            if (profileId != ProfileRepository.activeProfileId) return@withLock
                            items = items + (follow.key to fetched)
                            done++
                            if (done % PUBLISH_EVERY == 0) publish(isLoading = true)
                        }
                    }
                }.awaitAll()
            }
        }

        // A profile switch landing mid-pass must not publish, or persist under the new profile's
        // id, the old profile's calendar.
        if (profileId != ProfileRepository.activeProfileId) return
        lastPassAtEpochMs = TraktPlatformClock.nowEpochMs()
        publish(isLoading = false)
        persistToDisk(profileId)
    }

    private suspend fun fetchItem(
        follow: NuvioCalendarFollow,
        previous: NuvioCalendarCachedItem?,
    ): NuvioCalendarCachedItem {
        val fetchedAt = TraktPlatformClock.nowEpochMs()
        val result = runCatching {
            if (follow.kind == NuvioCalendarFollow.KIND_MOVIE) fetchMovie(follow) else fetchShow(follow)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.d { "Local calendar: ${follow.key} failed: ${error.message}" }
        }.getOrNull()
        return result?.copy(fetchedAtEpochMs = fetchedAt)
            ?: NuvioCalendarCachedItem(
                follow = follow,
                fetchedAtEpochMs = fetchedAt,
                failed = true,
                scheduleKey = previous?.scheduleKey,
                entries = previous?.entries.orEmpty(),
            )
    }

    /** Null when no addon (nor the TMDB fallback) answered, so the previous entries are kept. */
    private suspend fun fetchShow(follow: NuvioCalendarFollow): NuvioCalendarCachedItem? {
        val meta = MetaDetailsRepository.fetchForSchedule(follow.type, follow.contentId) ?: return null
        val title = meta.name.ifBlank { follow.title }
        val poster = follow.posterUrl ?: meta.poster?.takeIf { it.isNotBlank() }
        val entries = meta.videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            // Specials carry arbitrary dates (featurettes, recaps) and no place in a show's run.
            if (season <= 0) return@mapNotNull null
            val instant = resolveReleaseInstant(video.released) ?: return@mapNotNull null
            NuvioCalendarCachedEntry(
                dateKey = instant.localIsoDate,
                type = follow.type,
                contentId = follow.contentId,
                title = title,
                posterUrl = poster,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = video.title.trim().takeIf { it.isNotBlank() },
                airAt = video.released?.trim()?.takeIf { instant.hasTimeOfDay },
            )
        }
        return NuvioCalendarCachedItem(
            follow = follow,
            finished = nuvioCalendarIsFinished(entries.map { it.dateKey }, meta.status, CurrentDateProvider.todayIsoDate()),
            scheduleKey = meta.imdbId?.takeIf { it.isNotBlank() } ?: meta.id.takeIf { it.isNotBlank() },
            entries = entries,
        )
    }

    /** A library movie on its release date. Null on a failed lookup; no TMDB key is an empty answer. */
    private suspend fun fetchMovie(follow: NuvioCalendarFollow): NuvioCalendarCachedItem? {
        val tmdbId = follow.tmdbId
            ?: TmdbService.ensureTmdbId(follow.contentId, "movie")?.toIntOrNull()
        val date = tmdbId?.let { TmdbService.fetchMovieCalendarDate(it) }
        val entries = listOfNotNull(
            date?.let {
                NuvioCalendarCachedEntry(
                    dateKey = it,
                    type = "movie",
                    contentId = follow.contentId,
                    title = follow.title,
                    posterUrl = follow.posterUrl,
                )
            },
        )
        return NuvioCalendarCachedItem(
            follow = follow,
            finished = nuvioCalendarIsFinished(listOfNotNull(date), "released", CurrentDateProvider.todayIsoDate()),
            scheduleKey = tmdbId?.let { "tmdb-movie:$it" },
            entries = entries,
        )
    }

    private fun publish(isLoading: Boolean) {
        // The first follow of a title wins (library before history), so a show followed under two
        // ids is listed once, under the id the library uses.
        val seen = HashSet<String>()
        val entries = items.values
            .filter { item -> item.scheduleKey == null || seen.add(item.scheduleKey) }
            .flatMap { item -> item.entries.map { it.toEntry() } }
        _uiState.value = TraktCalendarUiState(
            isLoading = isLoading,
            isAuthenticated = true,
            hasLoaded = true,
            entriesByDate = entries.groupedByDate(),
        )
        if (!isLoading) log.d { "Local calendar: ${entries.size} entries from ${items.size} titles" }
    }

    private fun List<TraktCalendarEntry>.groupedByDate(): Map<String, List<TraktCalendarEntry>> =
        groupBy { it.dateKey }
            .mapValues { (_, entries) ->
                entries
                    .distinctBy { "${it.type}:${it.contentId}:${it.seasonNumber}:${it.episodeNumber}" }
                    .sortedBy { it.title.lowercase() }
            }

    /** Publishes the last pass from disk, once per process, so the screen has entries at once. */
    private suspend fun hydrateFromDisk() {
        if (hydrated) return
        hydrated = true
        val raw = withContext(Dispatchers.IO) { runCatching { NuvioCalendarCacheStorage.load() } }
            .getOrElse { error ->
                log.d { "Could not read the local calendar cache: ${error.message}" }
                return
            } ?: run {
            log.d { "No local calendar cache on disk" }
            return
        }
        val entry = runCatching { json.decodeFromString<NuvioCalendarCacheEntry>(raw) }
            .getOrElse { error ->
                log.d { "Discarding unreadable local calendar cache: ${error.message}" }
                runCatching { NuvioCalendarCacheStorage.save(null) }
                return
            }
        if (entry.version != NuvioCalendarCacheEntry.VERSION) {
            runCatching { NuvioCalendarCacheStorage.save(null) }
            return
        }
        val profileId = ProfileRepository.activeProfileId
        if (entry.profileId != profileId) {
            log.d { "Local calendar cache belongs to profile ${entry.profileId}, active is $profileId; ignoring" }
            return
        }
        if (entry.items.isEmpty()) return
        items = entry.items.associateBy { it.follow.key }
        publish(isLoading = true)
        log.d {
            "Local calendar cache restored: ${entry.items.size} titles, " +
                "built ${TraktPlatformClock.nowEpochMs() - entry.builtAtEpochMs}ms ago"
        }
    }

    /** Fire-and-forget; an empty calendar clears the file rather than leaving the old one. */
    private fun persistToDisk(profileId: Int) {
        val snapshot = NuvioCalendarCacheEntry(
            builtAtEpochMs = TraktPlatformClock.nowEpochMs(),
            profileId = profileId,
            items = items.values.toList(),
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                NuvioCalendarCacheStorage.save(
                    if (snapshot.items.isEmpty()) null else json.encodeToString(snapshot),
                )
            }.onFailure { error -> log.d { "Could not persist the local calendar: ${error.message}" } }
        }
    }
}
