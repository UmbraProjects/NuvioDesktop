package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val EXTERNAL_ID_CACHE_WRITE_DELAY_MS = 5_000L

object TmdbService {
    private val log = Logger.withTag("TmdbService")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbToTmdbCache = linkedMapOf<String, String>()
    private val tmdbToImdbCache = linkedMapOf<String, String>()

    /**
     * External-id lookups that came back with nothing, keyed as their positive cache would be.
     *
     * A miss is as worth remembering as a hit: plenty of TMDB records genuinely carry no IMDb id,
     * and without this every one of them is re-requested on each cold pass. The custom-poster path
     * makes that visible — a rail of 20 unmatched titles is 20 requests, repeated per entity load.
     */
    private val unresolvedExternalIds = mutableSetOf<String>()
    private val cacheMutex = Mutex()

    /**
     * Disk hydration for the TMDB→IMDb half — plan §20.2.
     *
     * A title's IMDb id never changes, so re-fetching it every launch was pure waste: the
     * hide-watched prune pass alone issued ~495 `external_ids` calls per Discover build and starved
     * the row fetches of TMDB permits for 25 seconds. Loaded once, lazily, on the first external-id
     * lookup rather than at startup — nothing may be added to the startup path (§8).
     */
    private var externalIdCacheHydrated = false

    /** Set when a new mapping is learned, cleared by the writer. Guarded by [cacheMutex]. */
    private var externalIdCacheDirty = false

    /**
     * Whether a debounced write is already pending.
     *
     * Without this, a prune pass learning several hundred mappings launches several hundred
     * coroutines that each sleep five seconds to discover there is nothing left to do — piling
     * scheduler work onto the exact burst this cache exists to make cheaper.
     */
    private var externalIdCacheWritePending = false

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun hydrateExternalIdCache() {
        cacheMutex.withLock {
            if (externalIdCacheHydrated) return
            externalIdCacheHydrated = true
            val stored = runCatching { TmdbExternalIdCacheStorage.load() }.getOrNull().orEmpty()
            if (stored.isEmpty()) return
            stored.forEach { (key, imdbId) ->
                // Anything already resolved this session wins: it came from the API just now.
                if (key !in tmdbToImdbCache) tmdbToImdbCache[key] = imdbId
                val type = key.substringAfterLast(':', "")
                if (type.isNotBlank()) imdbToTmdbCache.getOrPut("$imdbId:$type") {
                    key.substringBeforeLast(':')
                }
            }
            log.d { "External-id cache restored: ${stored.size} mappings" }
        }
    }

    /**
     * Schedules a write.
     *
     * Debounced rather than written per resolution: a prune pass learns hundreds of mappings in a
     * burst, and serialising the whole map each time would turn a fix for request pressure into
     * disk pressure. Losing the last few seconds of learning to a crash costs one re-fetch each.
     */
    private suspend fun scheduleExternalIdCacheWrite() {
        val alreadyPending = cacheMutex.withLock {
            val pending = externalIdCacheWritePending
            externalIdCacheWritePending = true
            pending
        }
        if (alreadyPending) return
        cacheScope.launch {
            kotlinx.coroutines.delay(EXTERNAL_ID_CACHE_WRITE_DELAY_MS)
            val snapshot = cacheMutex.withLock {
                externalIdCacheWritePending = false
                if (!externalIdCacheDirty) return@launch
                externalIdCacheDirty = false
                // Snapshotted under the lock: the writer serialises on another thread, and the map
                // keeps being written to while it does.
                tmdbToImdbCache.toMap()
            }
            runCatching { TmdbExternalIdCacheStorage.save(snapshot) }
                .onFailure { log.w(it) { "External-id cache write failed" } }
        }
    }

    // One lock per external id being resolved. The cache alone doesn't help a fan-out, because it
    // is only populated once a lookup finishes: a plugin repository with 60+ scrapers resolves the
    // same title 60+ times in parallel, all missing the cache and all issuing the same TMDB /find
    // request — enough to draw a rate limit, which then resolves to null and hands scrapers an
    // IMDB id they can't use. Followers wait for the first lookup and read its cached answer.
    private val externalIdLookupLocks = mutableMapOf<String, Mutex>()

    private suspend fun <T> withExternalIdLookupLock(cacheKey: String, block: suspend () -> T): T {
        val lock = cacheMutex.withLock { externalIdLookupLocks.getOrPut(cacheKey) { Mutex() } }
        return lock.withLock { block() }
    }

    private suspend fun cachedTmdbId(cacheKey: String): String? =
        cacheMutex.withLock { imdbToTmdbCache[cacheKey] }

    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null

        // Handle TVDB IDs (e.g. "tvdb:73244") — AIOMetadata returns these when TVDB is
        // the configured search/metadata source. Convert via TMDB's /find endpoint.
        if (videoId.startsWith("tvdb:", ignoreCase = true)) {
            val tvdbId = videoId.substringAfter(':').trim()
            return tvdbToTmdb(tvdbId = tvdbId, mediaType = mediaType, apiKey = apiKey)
        }

        val normalized = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (normalized.isBlank()) return null
        if (normalized.all(Char::isDigit)) return normalized
        if (!normalized.startsWith("tt", ignoreCase = true)) return null

        return imdbToTmdb(imdbId = normalized, mediaType = mediaType, apiKey = apiKey)
    }

    private suspend fun tvdbToTmdb(tvdbId: String, mediaType: String, apiKey: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "tvdb:$tvdbId:$normalizedType"
        cachedTmdbId(cacheKey)?.let { return it }

        return withExternalIdLookupLock(cacheKey) {
            // Re-check: whoever held the lock may have resolved this while we queued behind them.
            cachedTmdbId(cacheKey)?.let { return@withExternalIdLookupLock it }

            val body = fetch<TmdbFindResponse>(
                endpoint = "find/$tvdbId",
                apiKey = apiKey,
                query = mapOf("external_source" to "tvdb_id"),
            ) ?: return@withExternalIdLookupLock null

            val resultId = when (normalizedType) {
                "movie" -> body.movieResults.firstOrNull()?.id
                "tv" -> body.tvResults.firstOrNull()?.id
                else -> body.tvResults.firstOrNull()?.id ?: body.movieResults.firstOrNull()?.id
            }?.takeIf { it > 0 }?.toString()

            if (resultId != null) {
                cacheMutex.withLock { imdbToTmdbCache[cacheKey] = resultId }
            }
            resultId
        }
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null

        val cacheKey = "$tmdbId:${normalizeMediaType(mediaType)}"
        val negativeKey = "tmdbToImdb:$cacheKey"
        hydrateExternalIdCache()
        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey]?.let { return it }
            if (negativeKey in unresolvedExternalIds) return null
        }

        // Single-flighted like the imdb→tmdb direction. Custom posters fan out across a whole rail
        // at once, so without this the same id is requested once per concurrent card.
        return withExternalIdLookupLock(negativeKey) {
            cacheMutex.withLock {
                tmdbToImdbCache[cacheKey]?.let { return@withExternalIdLookupLock it }
                if (negativeKey in unresolvedExternalIds) return@withExternalIdLookupLock null
            }

            val endpoint = when (normalizeMediaType(mediaType)) {
                "tv" -> "tv/$tmdbId/external_ids"
                else -> "movie/$tmdbId/external_ids"
            }
            val body = fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey)
            val imdbId = body?.imdbId?.trim()?.takeIf(String::isNotBlank)
            if (imdbId == null) {
                // Only a parsed response proves the record has no IMDb id. A null body is a
                // transport failure or a rate limit, which must stay retryable.
                if (body != null) cacheMutex.withLock { unresolvedExternalIds += negativeKey }
                return@withExternalIdLookupLock null
            }

            val learned = cacheMutex.withLock {
                val isNew = tmdbToImdbCache.put(cacheKey, imdbId) != imdbId
                imdbToTmdbCache["$imdbId:${normalizeMediaType(mediaType)}"] = tmdbId.toString()
                if (isNew) externalIdCacheDirty = true
                isNew
            }
            if (learned) scheduleExternalIdCacheWrite()
            imdbId
        }
    }

    suspend fun fetchMovieReleaseStatus(
        tmdbId: Int,
        tmdbStatus: String?,
    ): String? {
        val apiKey = currentApiKey() ?: return null
        val normalizedStatus = tmdbStatus.orEmpty().trim()
        when (normalizedStatus.lowercase()) {
            "in production", "post production", "post-production", "planned", "pilot", "rumored" -> return "Production"
            "cancelled", "canceled" -> return null
        }

        val body = fetchReleaseDates(tmdbId, apiKey) ?: return null

        val todayIso = com.nuvio.app.features.watchprogress.CurrentDateProvider.todayIsoDate()
        var hasPhysical = false
        var hasDigital = false
        var theatricalDate: String? = null
        var futureTheatricalDate: String? = null

        body.results.forEach { country ->
            country.releaseDates.forEach { release ->
                val date = release.releaseDate.take(10).takeIf(::isIsoDate) ?: return@forEach
                if (date > todayIso) {
                    if (release.type == TMDB_RELEASE_TYPE_THEATRICAL) {
                        futureTheatricalDate = minIsoDate(futureTheatricalDate, date)
                    }
                    return@forEach
                }
                when (release.type) {
                    TMDB_RELEASE_TYPE_PHYSICAL -> hasPhysical = true
                    TMDB_RELEASE_TYPE_DIGITAL, TMDB_RELEASE_TYPE_TV -> hasDigital = true
                    TMDB_RELEASE_TYPE_THEATRICAL -> theatricalDate = maxIsoDate(theatricalDate, date)
                }
            }
        }

        return when {
            hasPhysical -> "Physical"
            hasDigital -> "Streaming"
            theatricalDate != null -> "Cinema".takeUnless { isStaleReleaseStatusDate(theatricalDate, todayIso) }
            futureTheatricalDate != null -> "Production"
            normalizedStatus.equals("released", ignoreCase = true) -> "Streaming"
            else -> "Production"
        }
    }

    /**
     * Most recent past date a movie became available to watch at home (digital / physical / TV)
     * as ISO `yyyy-MM-dd`, or null if it has no such release yet. Deliberately ignores theatrical
     * dates: a cinema-only title is "in cinemas", not a "new release", and the two must never
     * coexist. Used by hero discovery to flag genuinely new (streaming) releases.
     */
    suspend fun fetchMovieAvailabilityDate(tmdbId: Int): String? {
        val apiKey = currentApiKey() ?: return null
        val body = fetchReleaseDates(tmdbId, apiKey) ?: return null

        val todayIso = com.nuvio.app.features.watchprogress.CurrentDateProvider.todayIsoDate()
        var homeDate: String? = null
        body.results.forEach { country ->
            country.releaseDates.forEach { release ->
                val date = release.releaseDate.take(10).takeIf(::isIsoDate) ?: return@forEach
                if (date > todayIso) return@forEach
                when (release.type) {
                    TMDB_RELEASE_TYPE_DIGITAL, TMDB_RELEASE_TYPE_PHYSICAL, TMDB_RELEASE_TYPE_TV ->
                        homeDate = maxIsoDate(homeDate, date)
                }
            }
        }
        return homeDate
    }

    private val releaseDatesMutex = Mutex()
    private val releaseDatesCache = mutableMapOf<Int, Pair<TmdbReleaseStatusDatesResponse, Long>>()

    /**
     * Shared, TTL-cached fetch of `movie/{id}/release_dates` — [fetchMovieReleaseStatus] and
     * [fetchMovieAvailabilityDate] both derive their answer from the same payload, so hero
     * discovery evaluating both facts for one movie (the default priority list includes both)
     * no longer fires two HTTP requests for identical data.
     */
    private suspend fun fetchReleaseDates(tmdbId: Int, apiKey: String): TmdbReleaseStatusDatesResponse? {
        val now = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
        releaseDatesMutex.withLock {
            releaseDatesCache[tmdbId]?.let { (cached, fetchedAtMs) ->
                if (now - fetchedAtMs < TRENDING_CACHE_TTL_MS) return cached
            }
        }
        val body = fetch<TmdbReleaseStatusDatesResponse>(
            endpoint = "movie/$tmdbId/release_dates",
            apiKey = apiKey,
        ) ?: return null
        releaseDatesMutex.withLock {
            releaseDatesCache[tmdbId] = body to now
        }
        return body
    }

    private val genreNamesMutex = Mutex()
    private val genreNamesCache = mutableMapOf<String, Map<Int, String>>()

    private val trendingMutex = Mutex()
    private var trendingMovieIds: Set<Int> = emptySet()
    private var trendingTvIds: Set<Int> = emptySet()
    // The same fetch the id sets are derived from, kept whole. The trending badge only ever needed
    // the ids, but Discover's "Trending in <genre>" rows need each item's genre ids and artwork —
    // and re-requesting the identical pages to get them would double the cost of a cache that
    // already holds the answer.
    private var trendingMovieResults: List<TmdbSearchResult> = emptyList()
    private var trendingTvResults: List<TmdbSearchResult> = emptyList()
    private var trendingFetchedAtMs: Long = 0L

    /**
     * True if the title is on TMDB's trending-this-week list (top [TRENDING_PAGES]×20 per media
     * type — movies and TV are separate TMDB lists, so they never compete for slots). Backed by
     * a 6-hour cache. Media types that are neither movie nor tv (e.g. "anime" catalog entries,
     * which can be either) are accepted from both lists rather than silently checked against
     * the movie list only.
     */
    suspend fun isTrending(tmdbId: Int, mediaType: String): Boolean {
        ensureTrendingLoaded()
        return trendingMutex.withLock {
            when (normalizeMediaType(mediaType)) {
                "tv" -> tmdbId in trendingTvIds
                "movie" -> tmdbId in trendingMovieIds
                else -> tmdbId in trendingTvIds || tmdbId in trendingMovieIds
            }
        }
    }

    /**
     * TMDB's trending-this-week list for one media type, as whole records rather than bare ids.
     *
     * Shares [ensureTrendingLoaded]'s 6-hour cache with the trending badge, so a caller that wants
     * the items pays nothing extra once the badge has warmed it (and warms it for the badge if it
     * gets there first).
     */
    suspend fun fetchTrending(mediaType: String): List<TmdbSearchResult> {
        ensureTrendingLoaded()
        return trendingMutex.withLock {
            when (normalizeMediaType(mediaType)) {
                "tv" -> trendingTvResults
                "movie" -> trendingMovieResults
                else -> trendingMovieResults + trendingTvResults
            }
        }
    }

    private suspend fun ensureTrendingLoaded() {
        val now = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
        trendingMutex.withLock {
            val fresh = now - trendingFetchedAtMs < TRENDING_CACHE_TTL_MS
            if (fresh && trendingMovieIds.isNotEmpty() && trendingTvIds.isNotEmpty()) return
        }
        val apiKey = currentApiKey() ?: return
        val movieResults = fetchTrendingResults("trending/movie/week", apiKey, "movie")
        val tvResults = fetchTrendingResults("trending/tv/week", apiKey, "tv")
        val movies = movieResults.mapTo(mutableSetOf()) { it.id }
        val tv = tvResults.mapTo(mutableSetOf()) { it.id }
        // The full id sets are logged so the in-memory cache can be inspected from nuvio.log —
        // there is no on-disk copy of this cache.
        log.i { "TMDB trending refreshed: ${movies.size} movies, ${tv.size} tv" }
        log.d { "TMDB trending movie ids: ${movies.sorted()}" }
        log.d { "TMDB trending tv ids: ${tv.sorted()}" }
        trendingMutex.withLock {
            if (movies.isNotEmpty()) {
                trendingMovieIds = movies
                trendingMovieResults = movieResults
            }
            if (tv.isNotEmpty()) {
                trendingTvIds = tv
                trendingTvResults = tvResults
            }
            // Only a fully successful refresh earns the full TTL. Previously one endpoint
            // failing (timeout/rate limit) while the other succeeded still stamped the cache
            // fresh, freezing the failed side as empty for 6 hours — no TV title could badge
            // as trending for the whole window. Partial/total failures now retry sooner while
            // still backing off enough to avoid hammering TMDB on every check.
            trendingFetchedAtMs = if (movies.isNotEmpty() && tv.isNotEmpty()) {
                now
            } else {
                now - (TRENDING_CACHE_TTL_MS - TRENDING_FAILURE_RETRY_MS)
            }
        }
    }

    /**
     * [mediaType] is stamped onto every result because the per-type trending endpoints omit
     * `media_type` — and an unstamped tv record would later be looked up in the movie id space.
     */
    private suspend fun fetchTrendingResults(
        endpoint: String,
        apiKey: String,
        mediaType: String,
    ): List<TmdbSearchResult> {
        val collected = mutableListOf<TmdbSearchResult>()
        for (page in 1..TRENDING_PAGES) {
            val results = fetch<TmdbSearchResponse>(
                endpoint = endpoint,
                apiKey = apiKey,
                query = mapOf("page" to page.toString()),
            )?.results ?: break
            collected += results.filter { it.id > 0 }.map { it.withMediaType(mediaType) }
            if (results.isEmpty()) break
        }
        return collected.distinctBy { it.id }
    }

    private val unreleasedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unreleasedWarmMutex = Mutex()
    private val unreleasedAttemptedIds = mutableSetOf<String>()

    @Volatile
    private var unreleasedCache: Map<String, Boolean> = emptyMap()

    /**
     * Best-effort, non-suspending read of a prior [warmUnreleasedStatusAsync] result for a movie
     * id — null if unresolved (caller should fall back to its own heuristic). A volatile,
     * copy-on-write map keeps this lock-free: reads never block, and [warmUnreleasedStatusAsync]
     * publishes the whole map atomically when a lookup completes.
     */
    fun peekUnreleasedStatus(itemId: String): Boolean? = unreleasedCache[itemId]

    /**
     * Resolves whether [itemId] (a movie) is still unreleased via the same `/release_dates` data
     * [fetchMovieReleaseStatus] already uses for the hero "recently released" badge — reused here
     * rather than duplicating a separate TMDB lookup. Fires in the background and caches the
     * result for [peekUnreleasedStatus]; callers should use their own heuristic for the current
     * call and pick up the resolved answer on a later pass. No-ops without a TMDB key, for
     * non-movie types, or if already attempted for this id this session.
     */
    fun warmUnreleasedStatusAsync(itemId: String, type: String) {
        if (normalizeMediaType(type) != "movie") return
        unreleasedScope.launch {
            val alreadyAttempted = unreleasedWarmMutex.withLock {
                if (itemId in unreleasedAttemptedIds) true else { unreleasedAttemptedIds += itemId; false }
            }
            if (alreadyAttempted) return@launch
            val tmdbId = ensureTmdbId(itemId, type)?.toIntOrNull() ?: return@launch
            val status = fetchMovieReleaseStatus(tmdbId = tmdbId, tmdbStatus = null) ?: return@launch
            unreleasedCache = unreleasedCache + (itemId to (status == "Production"))
        }
    }

    private suspend fun imdbToTmdb(imdbId: String, mediaType: String, apiKey: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"
        cachedTmdbId(cacheKey)?.let { return it }

        return withExternalIdLookupLock(cacheKey) {
            // Re-check: whoever held the lock may have resolved this while we queued behind them.
            cachedTmdbId(cacheKey)?.let { return@withExternalIdLookupLock it }

            val body = fetch<TmdbFindResponse>(
                endpoint = "find/$imdbId",
                apiKey = apiKey,
                query = mapOf("external_source" to "imdb_id"),
            ) ?: return@withExternalIdLookupLock null

            val resultId = when (normalizedType) {
                "movie" -> body.movieResults.firstOrNull()?.id
                "tv" -> body.tvResults.firstOrNull()?.id
                else -> body.movieResults.firstOrNull()?.id ?: body.tvResults.firstOrNull()?.id
            }?.takeIf { it > 0 }?.toString()

            if (resultId != null) {
                cacheMutex.withLock {
                    imdbToTmdbCache[cacheKey] = resultId
                    tmdbToImdbCache["$resultId:$normalizedType"] = imdbId
                }
            } else {
                log.d { "No TMDB ID found for $imdbId ($normalizedType)" }
            }

            resultId
        }
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(TmdbHttp.getText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }

    // Resolves an IMDB ID to a TVDB numeric ID via TMDB's /external_ids endpoint.
    // More reliable than TVDB's own /search?remote_id= which often returns null data.
    suspend fun imdbToTvdbId(imdbId: String, mediaType: String): Int? {
        val apiKey = currentApiKey() ?: return null
        val tmdbId = imdbToTmdb(imdbId = imdbId, mediaType = mediaType, apiKey = apiKey)
            ?.toIntOrNull() ?: return null
        val normalizedType = normalizeMediaType(mediaType)
        val endpoint = when (normalizedType) {
            "tv" -> "tv/$tmdbId/external_ids"
            else -> "movie/$tmdbId/external_ids"
        }
        return fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey)?.tvdbId
    }

    /**
     * Free-text TMDB search used by the local-library match/fix flow.
     *
     * [mediaType] "movie" or "tv" targets the matching search endpoint; anything else queries
     * both. [year] is passed as a hint (primary_release_year / first_air_date_year) but never
     * required, so a slightly-off folder year still returns candidates.
     */
    suspend fun searchTitles(query: String, mediaType: String? = null, year: Int? = null): List<TmdbSearchResult> {
        val apiKey = currentApiKey() ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val normalized = mediaType?.let { normalizeMediaType(it) }
        val movieResults = if (normalized == null || normalized == "movie") {
            fetch<TmdbSearchResponse>(
                endpoint = "search/movie",
                apiKey = apiKey,
                query = buildMap {
                    put("query", trimmed)
                    put("include_adult", "false")
                    year?.let { put("primary_release_year", it.toString()) }
                },
            )?.results.orEmpty().map { it.withMediaType("movie") }
        } else emptyList()

        val tvResults = if (normalized == null || normalized == "tv") {
            fetch<TmdbSearchResponse>(
                endpoint = "search/tv",
                apiKey = apiKey,
                query = buildMap {
                    put("query", trimmed)
                    put("include_adult", "false")
                    year?.let { put("first_air_date_year", it.toString()) }
                },
            )?.results.orEmpty().map { it.withMediaType("tv") }
        } else emptyList()

        return (movieResults + tvResults)
            .filter { it.id > 0 }
            .sortedByDescending { it.popularity }
    }

    /**
     * People matching [query], most prominent first.
     *
     * [TmdbPersonResult.knownForDepartment] is carried through because it is the only thing that
     * disambiguates a picker result: TMDB is full of same-named people, and "Directing" beside a
     * name is the difference between picking the director and picking an extra who shares his name.
     */
    suspend fun searchPeople(query: String): List<TmdbPersonResult> {
        val apiKey = currentApiKey() ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return fetch<TmdbPersonSearchResponse>(
            endpoint = "search/person",
            apiKey = apiKey,
            query = mapOf("query" to trimmed, "include_adult" to "false"),
        )?.results.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
            .sortedByDescending { it.popularity }
    }

    /** Production companies matching [query]. Ordered as TMDB returns them — no popularity here. */
    suspend fun searchCompanies(query: String): List<TmdbCompanyResult> {
        val apiKey = currentApiKey() ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return fetch<TmdbCompanySearchResponse>(
            endpoint = "search/company",
            apiKey = apiKey,
            query = mapOf("query" to trimmed),
        )?.results.orEmpty()
            .filter { it.id > 0 && it.name.isNotBlank() }
    }

    /**
     * TMDB's own "people who watched this also watched" list for one title.
     *
     * One request per seed, so callers must cap how many seeds they fan out over. Results carry
     * [TmdbSearchResult.mediaType] stamped from [mediaType], because the endpoint omits it — and
     * without it a tv result would later be looked up in the movie id space.
     */
    suspend fun fetchRecommendations(
        tmdbId: Int,
        mediaType: String,
        page: Int = 1,
    ): List<TmdbSearchResult> {
        val apiKey = currentApiKey() ?: return emptyList()
        if (tmdbId <= 0) return emptyList()
        val normalized = normalizeMediaType(mediaType)
        return fetch<TmdbSearchResponse>(
            endpoint = "$normalized/$tmdbId/recommendations",
            apiKey = apiKey,
            query = mapOf("page" to page.toString()),
        )?.results.orEmpty()
            .filter { it.id > 0 && it.displayTitle.isNotBlank() }
            .map { it.withMediaType(normalized) }
    }

    /**
     * TMDB `/discover`, the query-backed counterpart to [fetchRecommendations].
     *
     * [genreIds] are ANDed (TMDB reads a comma-separated `with_genres` as "all of these"), so a
     * two-genre call is deliberately narrow — callers wanting breadth should pass one. The vote
     * floors exist because `popularity.desc` with no floor surfaces a lot of barely-rated noise.
     * [maxVoteCount] is the inverse knob, for "well rated but little seen".
     */
    suspend fun fetchDiscover(
        mediaType: String,
        genreIds: List<Int> = emptyList(),
        /**
         * How multiple [genreIds] combine: `true` ANDs them (TMDB's comma form), `false` ORs them
         * (its `|` form). Defaults to AND because that is what every generated row wants — each
         * passes one or two genres it chose deliberately — but a user-defined row ticking four
         * boxes means "any of these", and ANDing four genres returns essentially nothing.
         */
        genreMatchAll: Boolean = true,
        sortBy: String = "popularity.desc",
        minVoteCount: Int? = null,
        maxVoteCount: Int? = null,
        minVoteAverage: Double? = null,
        /** Genre ids to exclude outright. ANDed against [genreIds] by TMDB. */
        withoutGenreIds: List<Int> = emptyList(),
        /**
         * ISO dates bounding the release window. The parameters TMDB wants differ by media type,
         * which is why these are named arguments rather than something callers pass through.
         */
        releasedBefore: String? = null,
        releasedAfter: String? = null,
        /** ISO 639-1 original language. */
        originalLanguage: String? = null,
        /**
         * `with_release_type` (films) and `with_status` (television) — different questions with no
         * equivalent in the other namespace, so each is sent only where it means something.
         */
        movieReleaseTypes: String? = null,
        tvStatuses: String? = null,
        /**
         * Film certification. TMDB rejects `certification` without a `certification_country`, so
         * both travel together or neither does. Films only; the tv endpoint has no such parameter.
         */
        certification: String? = null,
        certificationCountry: String? = null,
        /** `with_runtime` bounds in minutes. */
        minRuntime: Int? = null,
        maxRuntime: Int? = null,
        /**
         * Id lists, already joined by the caller — `|` ORs, `,` ANDs. `with_companies` works in both
         * namespaces; `with_cast` and `with_crew` are film-only and are not sent to the tv endpoint,
         * which would ignore them silently and return an unfiltered list.
         */
        companyIds: String? = null,
        castIds: String? = null,
        crewIds: String? = null,
        page: Int = 1,
    ): List<TmdbSearchResult> {
        val apiKey = currentApiKey() ?: return emptyList()
        val normalized = normalizeMediaType(mediaType)
        if (normalized != "movie" && normalized != "tv") return emptyList()
        return fetch<TmdbSearchResponse>(
            endpoint = "discover/$normalized",
            apiKey = apiKey,
            query = buildMap {
                put("sort_by", sortBy)
                put("include_adult", "false")
                put("page", page.toString())
                if (genreIds.isNotEmpty()) {
                    put("with_genres", genreIds.joinToString(if (genreMatchAll) "," else "|"))
                }
                if (withoutGenreIds.isNotEmpty()) put("without_genres", withoutGenreIds.joinToString(","))
                minVoteCount?.let { put("vote_count.gte", it.toString()) }
                maxVoteCount?.let { put("vote_count.lte", it.toString()) }
                minVoteAverage?.let { put("vote_average.gte", it.toString()) }
                releasedBefore?.let {
                    put(if (normalized == "tv") "first_air_date.lte" else "primary_release_date.lte", it)
                }
                releasedAfter?.let {
                    put(if (normalized == "tv") "first_air_date.gte" else "primary_release_date.gte", it)
                }
                originalLanguage?.takeIf { it.isNotBlank() }?.let { put("with_original_language", it) }
                minRuntime?.takeIf { it > 0 }?.let { put("with_runtime.gte", it.toString()) }
                maxRuntime?.takeIf { it > 0 }?.let { put("with_runtime.lte", it.toString()) }
                companyIds?.takeIf { it.isNotBlank() }?.let { put("with_companies", it) }
                if (normalized == "tv") {
                    tvStatuses?.takeIf { it.isNotBlank() }?.let { put("with_status", it) }
                } else {
                    castIds?.takeIf { it.isNotBlank() }?.let { put("with_cast", it) }
                    crewIds?.takeIf { it.isNotBlank() }?.let { put("with_crew", it) }
                    movieReleaseTypes?.takeIf { it.isNotBlank() }?.let { put("with_release_type", it) }
                    val country = certificationCountry?.takeIf { it.isNotBlank() }
                    val rating = certification?.takeIf { it.isNotBlank() }
                    if (country != null && rating != null) {
                        put("certification_country", country)
                        put("certification", rating)
                    }
                }
            },
        )?.results.orEmpty()
            .filter { it.id > 0 && it.displayTitle.isNotBlank() }
            .map { it.withMediaType(normalized) }
    }

    /**
     * Genre id → display name for one media type. TMDB's genre lists change about never, so this
     * is cached for the process lifetime rather than on a TTL.
     */
    suspend fun fetchGenreNames(mediaType: String): Map<Int, String> {
        val normalized = normalizeMediaType(mediaType)
        if (normalized != "movie" && normalized != "tv") return emptyMap()
        genreNamesMutex.withLock { genreNamesCache[normalized] }?.let { return it }
        val apiKey = currentApiKey() ?: return emptyMap()
        val body = fetch<TmdbGenreListResponse>(
            endpoint = "genre/$normalized/list",
            apiKey = apiKey,
        ) ?: return emptyMap()
        val names = body.genres
            .filter { it.id > 0 && it.name.isNotBlank() }
            .associate { it.id to it.name }
        if (names.isEmpty()) return emptyMap()
        genreNamesMutex.withLock { genreNamesCache[normalized] = names }
        return names
    }

    /**
     * Genre ids for one title, from its details record — the only place TMDB states them for a
     * title you hold by id rather than one that arrived in a list response.
     */
    suspend fun fetchGenreIds(tmdbId: Int, mediaType: String): List<Int> {
        val apiKey = currentApiKey() ?: return emptyList()
        if (tmdbId <= 0) return emptyList()
        val endpoint = if (normalizeMediaType(mediaType) == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        val body = fetch<TmdbPosterResponse>(endpoint = endpoint, apiKey = apiKey) ?: return emptyList()
        return body.genres.map { it.id }.filter { it > 0 }
    }

    fun tmdbImageUrl(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /**
     * Canonical title for a TMDB id. Used when local data knows *which* title something is but not
     * what it is called — some watch-history rows carry an empty name.
     */
    suspend fun fetchTitle(tmdbId: Int, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null
        if (tmdbId <= 0) return null
        val endpoint = if (normalizeMediaType(mediaType) == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        val body = fetch<TmdbPosterResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        return (body.title ?: body.name)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Poster URL for a TMDB id, used when a match was made by id (no poster path in hand). */
    suspend fun fetchPosterUrl(tmdbId: Int, mediaType: String, size: String = "w500"): String? =
        fetchArtwork(tmdbId, mediaType, posterSize = size)?.poster

    /**
     * Poster + backdrop for a TMDB id in one request. Callers that need both (the local library,
     * whose rows render as landscape cards) would otherwise pay for the same detail lookup twice.
     */
    suspend fun fetchArtwork(
        tmdbId: Int,
        mediaType: String,
        posterSize: String = "w500",
        backdropSize: String = "w1280",
    ): TmdbArtwork? {
        val apiKey = currentApiKey() ?: return null
        val endpoint = if (normalizeMediaType(mediaType) == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        val body = fetch<TmdbPosterResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        return TmdbArtwork(
            poster = tmdbImageUrl(body.posterPath, posterSize),
            backdrop = tmdbImageUrl(body.backdropPath, backdropSize),
        )
    }

    private fun currentApiKey(): String? =
        TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank)

    internal fun normalizeMediaType(mediaType: String): String =
        when (mediaType.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "tvshow" -> "tv"
            else -> mediaType.trim().lowercase()
        }
}

private const val TMDB_RELEASE_TYPE_THEATRICAL = 3
private const val TMDB_RELEASE_TYPE_DIGITAL = 4
private const val TMDB_RELEASE_TYPE_PHYSICAL = 5
private const val TMDB_RELEASE_TYPE_TV = 6
private const val RELEASE_STATUS_STALE_YEAR_GAP = 3
private const val TRENDING_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

// TMDB trending pages are 20 items each; 2 pages = top 40 per media type (movies and TV
// each get their own 40 — the lists are independent).
// Three, not two, since Discover's "Trending in <genre>" rows hold 50 items and take their
// candidates from this shared pool by intersecting it with one genre — two pages left every such
// row leaning on its discover top-up. Also widens the trending badge's window, which is harmless:
// the badge asks whether a title is in the pool, and the pool is cached for six hours either way.
private const val TRENDING_PAGES = 3

// Retry delay after a failed/partial trending refresh (see ensureTrendingLoaded).
private const val TRENDING_FAILURE_RETRY_MS = 15 * 60 * 1000L

private fun isIsoDate(value: String): Boolean =
    value.length == 10 &&
        value[4] == '-' &&
        value[7] == '-' &&
        value.substring(0, 4).all(Char::isDigit) &&
        value.substring(5, 7).all(Char::isDigit) &&
        value.substring(8, 10).all(Char::isDigit)

private fun maxIsoDate(current: String?, candidate: String): String =
    if (current == null || candidate > current) candidate else current

private fun minIsoDate(current: String?, candidate: String): String =
    if (current == null || candidate < current) candidate else current

private fun isStaleReleaseStatusDate(date: String?, todayIso: String): Boolean {
    val releaseYear = date?.take(4)?.toIntOrNull() ?: return false
    val currentYear = todayIso.take(4).toIntOrNull() ?: return false
    return currentYear - releaseYear > RELEASE_STATUS_STALE_YEAR_GAP
}

@Serializable
private data class TmdbReleaseStatusDatesResponse(
    val results: List<TmdbReleaseStatusDatesCountry> = emptyList(),
)

@Serializable
private data class TmdbReleaseStatusDatesCountry(
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseStatusDate> = emptyList(),
)

@Serializable
private data class TmdbReleaseStatusDate(
    @SerialName("release_date") val releaseDate: String = "",
    val type: Int = 0,
)

@Serializable
private data class TmdbSearchResponse(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
private data class TmdbPersonSearchResponse(
    val results: List<TmdbPersonResult> = emptyList(),
)

@Serializable
data class TmdbPersonResult(
    val id: Int = 0,
    val name: String = "",
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
private data class TmdbCompanySearchResponse(
    val results: List<TmdbCompanyResult> = emptyList(),
)

@Serializable
data class TmdbCompanyResult(
    val id: Int = 0,
    val name: String = "",
    /** Where the company is registered. Two studios share a name often enough for this to matter. */
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbPosterResponse(
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    // The same details request already carries the canonical title; `title` for films, `name` for
    // shows. Read here so a caller needing only the name does not pay for a second endpoint.
    val title: String? = null,
    val name: String? = null,
    // Details records spell genres out as objects, unlike the bare `genre_ids` of list responses.
    val genres: List<TmdbGenre> = emptyList(),
)

@Serializable
internal data class TmdbGenre(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
private data class TmdbGenreListResponse(
    val genres: List<TmdbGenre> = emptyList(),
)

/** Ready-to-use image URLs for a TMDB title. */
data class TmdbArtwork(
    val poster: String?,
    val backdrop: String?,
)

@Serializable
data class TmdbSearchResult(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("media_type") val mediaType: String? = null,
    /**
     * TMDB genre ids, as the list endpoints return them. **Namespaced by media type**: 10759
     * ("Action & Adventure") exists only for tv and 28 ("Action") only for movies, so an id is
     * only meaningful alongside [mediaType]. Resolve names with [TmdbService.fetchGenreNames] for
     * the *same* type the item came from.
     */
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
) {
    val displayTitle: String
        get() = (title ?: name).orEmpty()

    val year: Int?
        get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

    val isTv: Boolean
        get() = mediaType.equals("tv", ignoreCase = true)

    internal fun withMediaType(value: String): TmdbSearchResult = copy(mediaType = value)
}

internal fun buildTmdbUrl(
    endpoint: String,
    apiKey: String,
    query: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf("api_key" to apiKey)
    query.forEach { (key, value) ->
        if (value.isNotBlank()) {
            params[key] = value
        }
    }
    return buildString {
        append("https://api.themoviedb.org/3/")
        append(endpoint.removePrefix("/"))
        if (params.isNotEmpty()) {
            append("?")
            append(params.entries.joinToString("&") { (key, value) -> "$key=$value" })
        }
    }
}

@Serializable
private data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbExternalResult> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbExternalResult> = emptyList(),
)

@Serializable
private data class TmdbExternalResult(
    val id: Int,
)

@Serializable
private data class TmdbExternalIdsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)
