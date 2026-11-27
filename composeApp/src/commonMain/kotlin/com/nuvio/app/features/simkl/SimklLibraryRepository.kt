package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.metadata.toSimklIds
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.customPosterTemplateUsesNativeAnimeId
import com.nuvio.app.features.posterservice.resolveCustomPosterIds
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

/** [SimklListCacheStore] key of the Plan to Watch list. */
private const val PLAN_TO_WATCH_CACHE_KEY = "simkl_plan_to_watch"

internal object SimklLibraryRepository {
    private val log = Logger.withTag("SimklLibrary")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val _uiState = MutableStateFlow(SimklLibraryUiState())
    val uiState: StateFlow<SimklLibraryUiState> = _uiState.asStateFlow()

    private val loadStateLock = Any()
    private var refreshJob: Job? = null
    private var loaded = false

    /** One sync at a time, so overlapping refreshes do not repeat each other's requests. */
    private val syncMutex = Mutex()

    /** The stored Plan to Watch list for [listCacheProfileId]; see [SimklListCache]. */
    private var listCache: SimklListCache? = null
    private var listCacheProfileId: Int? = null

    fun ensureLoaded() {
        synchronized(loadStateLock) {
            if (loaded) return
            loaded = true
            if (!SimklAuthRepository.isAuthenticated.value) return
            // The stored list shows as soon as it is read; the refresh then only asks SIMKL what
            // changed. Read off the caller's thread: this runs from Home's first composition, and the
            // store's open has taken 7.8s at launch (2026-11-26: on-access scan of the file), which
            // froze the window that long when it was read here.
            refreshJob?.cancel()
            refreshJob = scope.launch {
                syncMutex.withLock {
                    val cache = cacheFor(ProfileRepository.activeProfileId)
                    if (cache.hasBaseline && _uiState.value.allItems.isEmpty()) {
                        publish(cache, touchedKeys = emptySet())
                    }
                    refreshLocked()
                }
            }
        }
    }

    fun refreshAsync() {
        synchronized(loadStateLock) {
            refreshJob?.cancel()
            refreshJob = scope.launch { refreshNow() }
        }
    }

    suspend fun refreshNow() = syncMutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        if (!SimklAuthRepository.isAuthenticated.value) {
            _uiState.value = SimklLibraryUiState(hasLoaded = true)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Rule: always check /sync/activities before /sync/all-items. Without it nothing is read.
        val profileId = ProfileRepository.activeProfileId
        val cache = cacheFor(profileId)
        val activities = SimklAuthRepository.fetchActivities()
        val latestTs = simklPlanToWatchActivitiesStamp(activities)
        val savedTs = SimklSettingsRepository.lastLibraryActivitiesAt()
        if (activities == null || (cache.hasBaseline && latestTs != null && latestTs == savedTs)) {
            if (activities == null) log.w { "SIMKL library: activity state could not be read; serving the stored list" }
            else log.d { "SIMKL library: activities unchanged, skipping fetch" }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                hasLoaded = true,
                errorMessage = "SIMKL activity state could not be read.".takeIf { !cache.hasBaseline },
            )
            return
        }

        runCatching { syncPlanToWatch(cache, activities) }.fold(
            onSuccess = { (next, touchedKeys) ->
                if (profileId != ProfileRepository.activeProfileId) return
                listCache = next
                SimklListCacheStore.save(profileId, PLAN_TO_WATCH_CACHE_KEY, next)
                publish(next, touchedKeys)
                val state = _uiState.value
                log.d { "SIMKL library: ${state.shows.size} shows, ${state.movies.size} movies, ${state.anime.size} anime" }
                if (latestTs != null) SimklSettingsRepository.setLastLibraryActivitiesAt(latestTs)
                // Enrichment is NOT launched here — addons may not be loaded yet at this point.
                // HomeScreen triggers enrichLibraryItems() once addons are ready.
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

    /**
     * Plan to Watch kept current the way SIMKL's sync guide asks: the three per-type reads once, as
     * the baseline, then a `date_from` delta when activities moved, and the ids-only deletion diff
     * only when `removed_from_list` moved. This used to re-read all three in full on every launch
     * and after every history write. Returns the next list and the SIMKL ids the delta changed.
     */
    private suspend fun syncPlanToWatch(
        cache: SimklListCache,
        activities: SimklActivities,
    ): Pair<SimklListCache, Set<Int>> {
        val removedStamp = simklRemovedFromListStamp(activities)
        // Read before the all-items requests, so a change landing meanwhile is newer than it.
        val nextDateFrom = activities.all?.takeIf(String::isNotBlank)
        if (!cache.hasBaseline) {
            // Rule: fetch shows, movies, anime SEQUENTIALLY (not in parallel) to avoid
            // CPU spikes on SIMKL's servers during large initial library downloads.
            val entries = fetchType("shows") + fetchType("movies") + fetchType("anime")
            return SimklListCache(dateFrom = nextDateFrom, removedStamp = removedStamp, entries = entries) to emptySet()
        }
        var next = cache
        val touched = mutableSetOf<Int>()
        if (removedStamp != null && removedStamp != cache.removedStamp) {
            val present = SimklDeletionCheck.canonicalKeys(SimklDeletionCheck.currentLibrary(removedStamp))
            next = next.withoutRemoved(present).copy(removedStamp = removedStamp)
            log.i { "SIMKL library: deletion check dropped ${cache.entries.size - next.entries.size} title(s)" }
        }
        val dateFrom = cache.dateFrom.orEmpty()
        if (nextDateFrom != dateFrom) {
            // Every status: a title that left Plan to Watch arrives under its new one and is dropped.
            // `extended=full` for the same reason as [fetchType].
            val delta = fetchAllItems("/sync/all-items?extended=full&date_from=${simklUrlEncode(dateFrom)}")
            next = next.applyDelta(delta) { _, entry -> entry.hasStatus("plantowatch") }
            delta.cachedEntries().mapNotNullTo(touched) { it.entry.simklKey }
            log.i { "SIMKL library: delta since $dateFrom, ${touched.size} changed -> ${next.entries.size} planned" }
        }
        return next.copy(dateFrom = nextDateFrom ?: cache.dateFrom) to touched
    }

    private fun cacheFor(profileId: Int): SimklListCache =
        listCache?.takeIf { listCacheProfileId == profileId && SimklListCacheStore.isForCurrentAccount(it) }
            ?: SimklListCacheStore.load(profileId, PLAN_TO_WATCH_CACHE_KEY).also {
                listCache = it
                listCacheProfileId = profileId
            }

    /**
     * Rebuilds the visible lists from [cache]. An item whose title the delta did not touch keeps its
     * current, already-enriched row — re-deriving it would drop the addon metadata until the next
     * enrichment pass.
     */
    private fun publish(cache: SimklListCache, touchedKeys: Set<Int>) {
        val current = _uiState.value.allItems.associateBy { it.type to it.id }
        fun build(bucket: SimklListBucket, contentType: String) = cache.entries
            .filter { it.bucket == bucket }
            .mapNotNull { cached ->
                val item = cached.entry.toLibraryItem(contentType) ?: return@mapNotNull null
                if (cached.entry.simklKey in touchedKeys) item else current[item.type to item.id] ?: item
            }
        _uiState.value = SimklLibraryUiState(
            shows = build(SimklListBucket.SHOWS, "series"),
            movies = build(SimklListBucket.MOVIES, "movie"),
            anime = build(SimklListBucket.ANIME, "series"),
            isLoading = false,
            hasLoaded = true,
        )
    }

    fun onProfileChanged() {
        synchronized(loadStateLock) {
            refreshJob?.cancel()
            refreshJob = null
            loaded = false
            listCache = null
            listCacheProfileId = null
            _uiState.value = SimklLibraryUiState()
        }
    }

    /** Called on disconnect: also forgets the stored list. */
    fun clearLocalState() {
        onProfileChanged()
        SimklListCacheStore.clear(ProfileRepository.activeProfileId, PLAN_TO_WATCH_CACHE_KEY)
    }

    /** Adds to Plan to Watch, or removes the item from SIMKL's library entirely. */
    suspend fun setPlanToWatch(item: LibraryItem, desired: Boolean) {
        if (!SimklAuthRepository.hasUsableToken()) error("SIMKL is not connected")
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
            simklRequest(method = "POST", url = url, body = body)
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

    private suspend fun fetchType(type: String): List<SimklCachedEntry> {
        if (!SimklAuthRepository.hasUsableToken()) return emptyList()
        // Filter to plantowatch only — the user's "want to watch" list, not their full history.
        // `extended=full` for the ids: the default response states the one id SIMKL indexes the
        // entry by, and a row that knows only its IMDb id cannot fill a poster template that also
        // names the TMDB one. Matches SimklWatchedRepository, and the parser ignores the extra
        // fields it brings along.
        return fetchAllItems("/sync/all-items/$type/plantowatch?extended=full").cachedEntries()
    }

    private suspend fun fetchAllItems(pathAndQuery: String): SimklAllItemsResponse {
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$BASE_URL$pathAndQuery"),
            body = "",
        )
        if (response.status !in 200..299) {
            error("SIMKL ${pathAndQuery.substringBefore('?')} returned ${response.status}")
        }
        val body = response.body.trim()
        if (body.isEmpty() || body == "null" || body == "[]") return SimklAllItemsResponse()
        return json.decodeFromString<SimklAllItemsResponse>(body)
    }

    private fun SimklAllItemsEntry.toLibraryItem(type: String): LibraryItem? {
        val ids = show?.ids ?: movie?.ids ?: anime?.ids ?: return null
        // Anime takes the anime-aware chain, or the library is the one SIMKL surface where the
        // AnimeIdPreference does nothing: every row here would be addressed by a franchise id (or
        // by an unresolvable `simkl:` one) while Continue Watching, watched state and the calendar
        // use the id the user asked for, and nothing matches across them. The node is not proof on
        // its own — SIMKL delivers anime movies under the plain `movie` node too (isKnownAnime).
        val isAnime = anime != null || ids.isKnownAnime()
        val contentId = when {
            !isAnime -> ids.toBestContentId()
            type.equals("movie", ignoreCase = true) -> ids.toBestAnimeMovieContentId()
            else -> ids.toBestAnimeContentId()
        } ?: return null
        val title = show?.title ?: movie?.title ?: anime?.title ?: return null
        val poster = (show?.poster ?: movie?.poster ?: anime?.poster)
            ?.takeIf { it.isNotBlank() }?.simklPosterUrl()
        val savedAt = (addedToWatchlistAt ?: lastWatchedAt)
            ?.let { parseSimklTimestamp(it) }
            ?: System.currentTimeMillis()

        // Not the placeholder: SIMKL hands tt2250192 back for anime it has no real imdb id for, and
        // this id addresses the backdrop and the poster-service URL below — an unrelated title's
        // artwork on every anime that carries it.
        val imdbId = ids.imdb?.takeIf { it.isNotBlank() && it != PLACEHOLDER_IMDB_ID }
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
            anilistId = ids.anilist?.toIntOrNull(),
            kitsuId = ids.kitsu?.toIntOrNull(),
            malId = ids.mal?.toIntOrNull(),
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
        // Three permits means three concurrent writers; a plain map is not safe to publish from.
        val enrichedMutex = Mutex()
        suspend fun record(item: LibraryItem) = enrichedMutex.withLock { enriched[item.id] = item }

        all.map { item ->
            scope.launch {
                sem.withPermit {
                    // preferTmdbImages = true so logos and backdrops come from TMDB/TVDB
                    // rather than metahub.space (Fanart.tv) which serves lower-quality art.
                    val meta = runCatching {
                        MetaDetailsRepository.fetchLightweightMeta(
                            item.type, item.id, preferTmdbImages = true,
                        )
                    }.getOrNull()
                    // SIMKL states one id per entry, so the poster service's other placeholder is
                    // unfillable until someone asks TMDB for it. Done here rather than at render
                    // time because it is a network call, and before the text check below because an
                    // entry with no addon metadata still deserves its poster.
                    val withIds = item.withPosterServiceIds(meta)
                    if (meta == null || (meta.genres.isEmpty() && meta.description == null && meta.runtime == null)) {
                        if (withIds != item) record(withIds)
                        return@withPermit
                    }
                    record(withIds.copy(
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
                    ))
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

    /**
     * Fills in the id the user's poster template names but this entry does not carry.
     *
     * The lightweight meta is consulted first because it costs nothing — it has already been
     * fetched — and only what it cannot answer becomes a TMDB lookup. Anime addressed by a
     * per-entry id is left alone: its ids belong to the franchise, so a poster service would
     * return season 1's art for every season.
     */
    private suspend fun LibraryItem.withPosterServiceIds(meta: MetaDetails?): LibraryItem {
        val settings = CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Library)
        if (id.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return this
        val resolved = resolveCustomPosterIds(
            settings = settings,
            imdbId = imdbId ?: meta?.imdbId ?: id.takeIf { it.startsWith("tt") },
            tmdbId = tmdbId ?: meta?.tmdbId,
            type = type,
        )
        if (resolved.imdbId == imdbId && resolved.tmdbId == tmdbId) return this
        return copy(imdbId = resolved.imdbId ?: imdbId, tmdbId = resolved.tmdbId ?: tmdbId)
    }
}

/**
 * The activities stamp that decides whether Plan to Watch may have changed: every status a title can
 * enter or leave it through, plus `removed_from_list`, for all three categories. A title leaving the
 * list bumps the stamp of the status it moved to, which `plantowatch` alone would miss.
 */
internal fun simklPlanToWatchActivitiesStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    val categories = listOf(
        "shows" to activities.tvShows,
        "movies" to activities.movies,
        "anime" to activities.anime,
    )
    val parts = categories.flatMap { (name, category) ->
        listOf(
            "$name.plantowatch" to category?.planToWatch,
            "$name.watching" to category?.watching,
            "$name.completed" to category?.completed,
            "$name.hold" to category?.hold,
            "$name.dropped" to category?.dropped,
            "$name.removed" to category?.removedFromList,
        )
    }
    if (parts.all { (_, value) -> value == null }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
}
