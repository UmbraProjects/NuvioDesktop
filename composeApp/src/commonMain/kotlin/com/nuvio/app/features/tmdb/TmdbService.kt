package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TmdbService {
    private val log = Logger.withTag("TmdbService")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbToTmdbCache = linkedMapOf<String, String>()
    private val tmdbToImdbCache = linkedMapOf<String, String>()
    private val cacheMutex = Mutex()

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
        cacheMutex.withLock {
            imdbToTmdbCache[cacheKey]?.let { return it }
        }

        val body = fetch<TmdbFindResponse>(
            endpoint = "find/$tvdbId",
            apiKey = apiKey,
            query = mapOf("external_source" to "tvdb_id"),
        ) ?: return null

        val resultId = when (normalizedType) {
            "movie" -> body.movieResults.firstOrNull()?.id
            "tv" -> body.tvResults.firstOrNull()?.id
            else -> body.tvResults.firstOrNull()?.id ?: body.movieResults.firstOrNull()?.id
        }?.takeIf { it > 0 }?.toString()

        if (resultId != null) {
            cacheMutex.withLock { imdbToTmdbCache[cacheKey] = resultId }
        }
        return resultId
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val apiKey = currentApiKey() ?: return null

        val cacheKey = "$tmdbId:${normalizeMediaType(mediaType)}"
        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey]?.let { return it }
        }

        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId/external_ids"
            else -> "movie/$tmdbId/external_ids"
        }
        val body = fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        val imdbId = body.imdbId?.trim()?.takeIf(String::isNotBlank) ?: return null

        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey] = imdbId
            imdbToTmdbCache["$imdbId:${normalizeMediaType(mediaType)}"] = tmdbId.toString()
        }
        return imdbId
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

    private val trendingMutex = Mutex()
    private var trendingMovieIds: Set<Int> = emptySet()
    private var trendingTvIds: Set<Int> = emptySet()
    private var trendingFetchedAtMs: Long = 0L

    /** True if the title is on TMDB's trending-this-week list. Backed by a 6-hour cache. */
    suspend fun isTrending(tmdbId: Int, mediaType: String): Boolean {
        ensureTrendingLoaded()
        return trendingMutex.withLock {
            if (normalizeMediaType(mediaType) == "tv") tmdbId in trendingTvIds else tmdbId in trendingMovieIds
        }
    }

    private suspend fun ensureTrendingLoaded() {
        val now = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
        trendingMutex.withLock {
            val fresh = now - trendingFetchedAtMs < TRENDING_CACHE_TTL_MS
            if (fresh && (trendingMovieIds.isNotEmpty() || trendingTvIds.isNotEmpty())) return
        }
        val apiKey = currentApiKey() ?: return
        val movies = fetch<TmdbTrendingResponse>(endpoint = "trending/movie/week", apiKey = apiKey)
            ?.results?.mapNotNull { it.id }?.toSet().orEmpty()
        val tv = fetch<TmdbTrendingResponse>(endpoint = "trending/tv/week", apiKey = apiKey)
            ?.results?.mapNotNull { it.id }?.toSet().orEmpty()
        trendingMutex.withLock {
            if (movies.isNotEmpty()) trendingMovieIds = movies
            if (tv.isNotEmpty()) trendingTvIds = tv
            if (movies.isNotEmpty() || tv.isNotEmpty()) trendingFetchedAtMs = now
        }
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
        cacheMutex.withLock {
            imdbToTmdbCache[cacheKey]?.let { return it }
        }

        val body = fetch<TmdbFindResponse>(
            endpoint = "find/$imdbId",
            apiKey = apiKey,
            query = mapOf("external_source" to "imdb_id"),
        ) ?: return null

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

        return resultId
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
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
private data class TmdbTrendingResponse(
    val results: List<TmdbTrendingItem> = emptyList(),
)

@Serializable
private data class TmdbTrendingItem(
    val id: Int? = null,
)

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
