package com.nuvio.app.features.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.TmdbBrowseCompany
import com.nuvio.app.features.discover.TmdbBrowseStatus
import com.nuvio.app.features.discover.watchedParentKey
import com.nuvio.app.features.tmdb.TmdbBackgroundLane
import com.nuvio.app.features.tmdb.TmdbHttp
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The Library's filters, ported from the Nuvio Tracker site's library page (library.ts): the saved
 * titles narrowed with Discover's filters where TMDB's details can answer them — genre, year,
 * rating, votes, length, language, status, studio, network — plus where you are with each title.
 * Where a title streams and its US rating stay Discover's, as in the tracker.
 */

@Serializable
enum class LibraryWatchFilter { All, Unwatched, InProgress, Watched }

@Serializable
data class LibraryBrowseFilters(
    val watch: LibraryWatchFilter = LibraryWatchFilter.All,
    val genres: List<String> = emptyList(),
    val allGenres: Boolean = false,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val minVotes: Int? = null,
    val runtimeMin: Int? = null,
    val runtimeMax: Int? = null,
    val language: String? = null,
    val status: TmdbBrowseStatus = TmdbBrowseStatus.Any,
    val companies: List<TmdbBrowseCompany> = emptyList(),
    val networks: List<Int> = emptyList(),
) {
    /** Filters that read TMDB's details (everything but where you are with a title). */
    val needsFacts: Boolean
        get() = LibraryBrowseFilters() != copy(watch = LibraryWatchFilter.All)

    /** What "Clear filters" resets — everything but the watch state, as in the tracker. */
    fun clearedFilters(): LibraryBrowseFilters = LibraryBrowseFilters(watch = watch)

    val hasFilters: Boolean
        get() = needsFacts
}

/** What the filters need to know about one title, from its TMDB details. */
@Serializable
data class LibraryTitleFacts(
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val originalLanguage: String? = null,
    val companyIds: List<Int> = emptyList(),
    val networkIds: List<Int> = emptyList(),
    val status: String? = null,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val popularity: Double? = null,
    val numberOfEpisodes: Int? = null,
    val fetchedAtEpochMs: Long = 0L,
    /** No TMDB id could be found: kept so the title isn't looked up on every visit. */
    val unresolved: Boolean = false,
)

internal fun LibrarySortOption.needsFacts(): Boolean = when (this) {
    LibrarySortOption.POPULAR, LibrarySortOption.TOP_RATED, LibrarySortOption.MOST_VOTED,
    LibrarySortOption.NEWEST, LibrarySortOption.OLDEST -> true
    else -> false
}

/**
 * TMDB details for library titles, fetched only once a filter or sort needs them and kept on disk:
 * a week for most titles, a day for shows still airing (their status and episode count move).
 */
object LibraryTitleFactsRepository {
    private val log = Logger.withTag("LibraryTitleFacts")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _facts = MutableStateFlow<Map<String, LibraryTitleFacts>>(emptyMap())
    val facts: StateFlow<Map<String, LibraryTitleFacts>> = _facts.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private var loaded = false
    private val inFlight = HashSet<String>()
    private var persistJob: Job? = null
    private val gate = Semaphore(FETCH_CONCURRENCY)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = LibraryTitleFactsStorage.loadPayload()?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString<Map<String, LibraryTitleFacts>>(it) }.getOrNull()
        }
        if (stored != null) _facts.value = stored
    }

    /** Fetches whatever [items] are missing (or stale). Cheap to call on every change to the list. */
    fun ensureFor(items: List<LibraryItem>) {
        ensureLoaded()
        if (TmdbSettingsRepository.snapshot().apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val wanted = synchronized(inFlight) {
            items.distinctBy { libraryItemKey(it.id, it.type) }.filter { item ->
                val key = libraryItemKey(item.id, item.type)
                if (key in inFlight) return@filter false
                val fact = _facts.value[key] ?: return@filter true
                now - fact.fetchedAtEpochMs > fact.maxAgeMs()
            }.also { list -> list.forEach { inFlight += libraryItemKey(it.id, it.type) }; _loading.value = inFlight.isNotEmpty() }
        }
        if (wanted.isEmpty()) return
        scope.launch {
            withContext(TmdbBackgroundLane) {
                coroutineScope {
                    wanted.map { item ->
                        async {
                            val key = libraryItemKey(item.id, item.type)
                            try {
                                // Each title lands as soon as it is known, so the filtered rows
                                // fill in while a large library is still being looked up.
                                gate.withPermit { fetchFacts(item, now) }?.let { fact ->
                                    _facts.update { it + (key to fact) }
                                    schedulePersist()
                                }
                            } finally {
                                synchronized(inFlight) {
                                    inFlight -= key
                                    _loading.value = inFlight.isNotEmpty()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun schedulePersist() {
        // Batched: a large library lands in one write rather than one per title.
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1_000)
            LibraryTitleFactsStorage.savePayload(json.encodeToString(_facts.value))
        }
    }

    private fun LibraryTitleFacts.maxAgeMs(): Long = when {
        unresolved -> 7 * DAY_MS
        status in AiringStatuses -> DAY_MS
        else -> 7 * DAY_MS
    }

    private suspend fun fetchFacts(item: LibraryItem, now: Long): LibraryTitleFacts? {
        val media = if (item.type.equals("movie", ignoreCase = true)) "movie" else "tv"
        val tmdbId = item.tmdbId?.toString()
            ?: runCatching { TmdbService.ensureTmdbId(item.imdbId ?: item.id, media) }.getOrNull()
            ?: return LibraryTitleFacts(fetchedAtEpochMs = now, unresolved = true)
        val apiKey = TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank) ?: return null
        val url = buildTmdbUrl(endpoint = "$media/$tmdbId", apiKey = apiKey)
        val d = runCatching { json.decodeFromString<TmdbFactsDetails>(TmdbHttp.getText(url)) }
            .onFailure { log.w { "Details failed for ${item.id}: ${it.message}" } }
            .getOrNull() ?: return null
        val date = (d.releaseDate ?: d.firstAirDate)?.takeIf(String::isNotBlank)
        return LibraryTitleFacts(
            genres = d.genres.map { it.name }.filter(String::isNotBlank),
            year = date?.take(4)?.toIntOrNull(),
            releaseDate = date,
            runtime = d.runtime ?: d.episodeRunTime.firstOrNull() ?: d.lastEpisodeToAir?.runtime,
            originalLanguage = d.originalLanguage,
            companyIds = d.productionCompanies.map { it.id },
            networkIds = d.networks.map { it.id },
            status = d.status,
            voteAverage = d.voteAverage,
            voteCount = d.voteCount,
            popularity = d.popularity,
            numberOfEpisodes = d.numberOfEpisodes,
            fetchedAtEpochMs = now,
        )
    }

    private const val DAY_MS = 86_400_000L
    private const val FETCH_CONCURRENCY = 6
    private val AiringStatuses = setOf("Returning Series", "In Production", "Planned", "Pilot")
}

/** Where the profile is with one title — the tracker's rules, on this app's history. */
internal fun libraryWatchState(
    item: LibraryItem,
    facts: LibraryTitleFacts?,
    watched: Map<String, List<WatchedItem>>,
    inProgress: Set<String>,
): LibraryWatchFilter {
    val key = watchedParentKey(item.type, item.id)
    val rows = watched[key].orEmpty()
    val isMovie = item.type.equals("movie", ignoreCase = true)
    val episodes = rows.mapNotNullTo(HashSet()) { row -> row.season?.let { it to row.episode } }.size
    val done = if (isMovie) {
        rows.isNotEmpty()
    } else {
        // A show marked watched as a whole has a row with no episode on it.
        rows.any { it.season == null } || (facts?.numberOfEpisodes?.let { it > 0 && episodes >= it } ?: false)
    }
    return when {
        done -> LibraryWatchFilter.Watched
        key in inProgress || rows.isNotEmpty() -> LibraryWatchFilter.InProgress
        else -> LibraryWatchFilter.Unwatched
    }
}

internal fun buildWatchedByTitle(items: List<WatchedItem>): Map<String, List<WatchedItem>> =
    items.filter { it.id.isNotBlank() }.groupBy { watchedParentKey(it.type, it.id) }

internal fun buildInProgressTitles(entries: List<WatchProgressEntry>): Set<String> =
    entries.asSequence()
        .filter { it.lastPositionMs > 0L && !it.isEffectivelyCompleted }
        .mapTo(HashSet()) { watchedParentKey(it.parentMetaType, it.parentMetaId) }

/** Whether [facts] pass every TMDB-backed filter. A title whose details aren't known passes none. */
internal fun LibraryBrowseFilters.matches(facts: LibraryTitleFacts?, todayIso: String): Boolean {
    if (!needsFacts) return true
    if (facts == null || facts.unresolved) return false
    if (genres.isNotEmpty()) {
        val ok = if (allGenres) facts.genres.containsAll(genres) else genres.any { it in facts.genres }
        if (!ok) return false
    }
    if (yearFrom != null && (facts.year == null || facts.year < yearFrom)) return false
    if (yearTo != null && (facts.year == null || facts.year > yearTo)) return false
    if (minRating != null && (facts.voteAverage == null || facts.voteAverage < minRating)) return false
    if (minVotes != null && (facts.voteCount == null || facts.voteCount < minVotes)) return false
    if (runtimeMin != null && (facts.runtime == null || facts.runtime < runtimeMin)) return false
    if (runtimeMax != null && (facts.runtime == null || facts.runtime > runtimeMax)) return false
    if (language != null && facts.originalLanguage != language) return false
    if (companies.isNotEmpty() && companies.none { it.id in facts.companyIds }) return false
    if (networks.isNotEmpty() && networks.none { it in facts.networkIds }) return false
    val date = facts.releaseDate
    return when (status) {
        TmdbBrowseStatus.Any -> true
        TmdbBrowseStatus.Returning -> facts.status in setOf("Returning Series", "In Production", "Planned", "Pilot")
        TmdbBrowseStatus.Ended -> facts.status in setOf("Ended", "Canceled")
        TmdbBrowseStatus.Upcoming -> date != null && date > todayIso
        TmdbBrowseStatus.InCinemas, TmdbBrowseStatus.HomeRelease -> date != null && date <= todayIso
    }
}

/** The library's sections with the filters applied; sections left empty drop out. */
internal fun filterLibrarySections(
    sections: List<LibrarySection>,
    filters: LibraryBrowseFilters,
    facts: Map<String, LibraryTitleFacts>,
    watched: Map<String, List<WatchedItem>>,
    inProgress: Set<String>,
    todayIso: String,
): List<LibrarySection> {
    if (!filters.needsFacts && filters.watch == LibraryWatchFilter.All) return sections
    return sections.mapNotNull { section ->
        val kept = section.items.filter { item ->
            val fact = facts[libraryItemKey(item.id, item.type)]
            filters.matches(fact, todayIso) &&
                (filters.watch == LibraryWatchFilter.All || libraryWatchState(item, fact, watched, inProgress) == filters.watch)
        }
        when {
            kept.isEmpty() -> null
            kept.size == section.items.size -> section
            else -> section.copy(items = kept)
        }
    }
}

@Serializable
private data class TmdbFactsDetails(
    val genres: List<TmdbFactsNamed> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbFactsEpisode? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("production_companies") val productionCompanies: List<TmdbFactsNamed> = emptyList(),
    val networks: List<TmdbFactsNamed> = emptyList(),
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
)

@Serializable
private data class TmdbFactsNamed(val id: Int = 0, val name: String = "")

@Serializable
private data class TmdbFactsEpisode(val runtime: Int? = null)
