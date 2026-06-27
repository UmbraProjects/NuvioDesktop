package com.nuvio.app.features.tvdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches hero backdrops and clearlogos for TV series and anime directly from TheTVDB v4 API.
 *
 * Authentication: logs in with the stored API key to get a short-lived JWT token (cached
 * for 24 hours). Subsequent calls use the cached token without another login round-trip.
 *
 * ID resolution: accepts tvdb:{id}, tt… (IMDB), tmdb:{id}, or bare numeric IDs. For IMDB
 * and TMDB IDs, uses TVDB's /search?remoteId= endpoint to find the TVDB series ID.
 *
 * Only handles series/anime types — movies are left to TMDB.
 */
object TvdbImageService {

    data class TvdbImages(
        val backdrop: String?,
        val logo: String?,
    )

    private const val BASE_URL = "https://api4.thetvdb.com/v4"
    private const val ARTWORK_BACKGROUND_SERIES = 3   // series landscape backdrop
    private const val ARTWORK_BACKGROUND_MOVIE = 15  // movie landscape backdrop
    private const val ARTWORK_BACKGROUND = 3   // kept for compat, use typed versions
    private const val ARTWORK_CLEARLOGO = 23   // transparent logo

    private val log = Logger.withTag("TvdbImageService")
    private val json = Json { ignoreUnknownKeys = true }

    // Token state
    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiryMs: Long = 0L

    // Image cache keyed by "tvdb:{numericId}"
    private val imageCache = mutableMapOf<String, TvdbImages>()
    private val imageCacheMutex = Mutex()

    // Remote-ID → TVDB-numeric-ID cache (avoids repeat /search calls)
    private val remoteIdCache = mutableMapOf<String, String>()
    private val remoteIdCacheMutex = Mutex()

    /** Pre-warms the TVDB auth token so it is cached before any image fetch needs it. */
    suspend fun warmToken() {
        val apiKey = TvdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank) ?: return
        acquireToken(apiKey) // no-op if already cached
    }

    fun clearCache() {
        imageCache.clear()
        remoteIdCache.clear()
    }

    /**
     * Fast path: TVDB ID already known (from AIOMetadata's _tvdbId field).
     * Skips IMDB→TMDB→TVDB resolution — one HTTP call instead of three.
     */
    suspend fun fetchWithKnownTvdbId(tvdbId: String): TvdbImages? {
        val apiKey = TvdbSettingsRepository.snapshot().apiKey
            .trim().takeIf(String::isNotBlank) ?: return null
        val cacheKey = "tvdb:$tvdbId"
        imageCacheMutex.withLock { imageCache[cacheKey] }?.let { return it }
        val token = acquireToken(apiKey) ?: return null
        val artworks = fetchArtworks(tvdbId, token) ?: return null
        val backdrops2 = artworks.filter { it.type == ARTWORK_BACKGROUND_SERIES && !it.image.isNullOrBlank() }
        val backdrop = backdrops2.firstOrNull { it.language.isNullOrBlank() }?.image
            ?: backdrops2.firstOrNull()?.image
        val logoList = artworks.filter { !it.image.isNullOrBlank() && it.type == ARTWORK_CLEARLOGO }
        val logo = logoList.firstOrNull { it.language == "eng" }?.image
            ?: logoList.firstOrNull()?.image
        val result = TvdbImages(backdrop = backdrop, logo = logo)
        imageCacheMutex.withLock { imageCache[cacheKey] = result }
        return result
    }

    /** Returns null if no API key is configured or the type is not a TV/anime type. */
    suspend fun fetch(type: String, id: String): TvdbImages? {
        if (!isTvType(type)) return null
        val apiKey = TvdbSettingsRepository.snapshot().apiKey
            .trim().takeIf(String::isNotBlank) ?: return null

        val token = acquireToken(apiKey) ?: return null
        val tvdbId = resolveTvdbId(id, token) ?: return null

        val cacheKey = "tvdb:$tvdbId"
        imageCacheMutex.withLock { imageCache[cacheKey] }?.let { return it }

        val artworks = fetchArtworks(tvdbId, token) ?: return null

        // AIOMetadata algorithm: prefer lang=null (no text overlay) then any.
        // Uses first-match, not score-sort — the API returns by descending ID (newest first).
        val backdropType = if (isTvType(type)) ARTWORK_BACKGROUND_SERIES else ARTWORK_BACKGROUND_MOVIE
        val backdrops = artworks.filter { it.type == backdropType && !it.image.isNullOrBlank() }
        val backdrop = backdrops.firstOrNull { it.language.isNullOrBlank() }?.image
            ?: backdrops.firstOrNull()?.image
        val logoList = artworks.filter { it.type == ARTWORK_CLEARLOGO && !it.image.isNullOrBlank() }
        val logo = logoList.firstOrNull { it.language == "eng" }?.image
            ?: logoList.firstOrNull()?.image

        val result = TvdbImages(backdrop = backdrop, logo = logo)
        imageCacheMutex.withLock { imageCache[cacheKey] = result }
        return result
    }

    // ── ID resolution ──────────────────────────────────────────────────────────

    private suspend fun resolveTvdbId(id: String, token: String): String? {
        // Already a TVDB ID
        if (id.startsWith("tvdb:", ignoreCase = true)) return id.substringAfter(':').trim()
        // Bare numeric — assume TVDB ID (AIOMetadata may return these without prefix)
        if (id.all(Char::isDigit)) return id

        // For IMDB (tt…), TMDB (tmdb:…) and other external IDs: search by remoteId
        val remoteId = when {
            id.startsWith("tt", ignoreCase = true) -> id
            id.startsWith("tmdb:", ignoreCase = true) -> id.substringAfter(':')
            else -> return null
        }

        remoteIdCacheMutex.withLock { remoteIdCache[remoteId] }?.let { return it }

        val result = searchByRemoteId(remoteId, token)
        if (result != null) {
            remoteIdCacheMutex.withLock { remoteIdCache[remoteId] = result }
        }
        return result
    }

    private suspend fun searchByRemoteId(remoteId: String, token: String): String? {
        // Use TMDB's cross-reference (IMDB → TMDB → TVDB external_ids) — more reliable
        // than TVDB's own /search?remote_id= which returns null data for many IDs.
        if (remoteId.startsWith("tt", ignoreCase = true)) {
            val tvdbId = runCatching {
                TmdbService.imdbToTvdbId(remoteId, "tv")?.toString()
            }.getOrNull()
            log.d { "TVDB ID via TMDB cross-ref for $remoteId → $tvdbId" }
            if (tvdbId != null) return tvdbId
        }

        // Fallback: try TVDB's own search endpoint.
        val url = "$BASE_URL/search?remote_id=$remoteId"
        val body = runCatching {
            httpGetTextWithHeaders(url, authHeaders(token))
        }.onFailure { log.w { "TVDB search request failed for $remoteId: ${it.message}" } }
            .getOrNull() ?: return null

        val result = runCatching {
            json.decodeFromString<TvdbSearchResponse>(body)
                .data?.firstOrNull()
                ?.numericId()
        }.onFailure { log.w { "TVDB search parse failed for $remoteId: ${it.message}" } }
            .getOrNull()
        log.d { "TVDB search fallback remoteId=$remoteId → tvdbId=$result" }
        return result
    }

    // ── Artworks ───────────────────────────────────────────────────────────────

    private suspend fun fetchArtworks(tvdbId: String, token: String): List<TvdbArtwork>? {
        val url = "$BASE_URL/series/$tvdbId/artworks"
        val body = runCatching {
            httpGetTextWithHeaders(url, authHeaders(token))
        }.onFailure { log.w { "TVDB artworks fetch failed for $tvdbId: ${it.message}" } }
            .getOrNull() ?: return null

        val artworks = runCatching {
            json.decodeFromString<TvdbArtworksResponse>(body).data?.artworks
        }.onFailure { log.w { "TVDB artworks parse failed for $tvdbId: ${it.message}" } }
            .getOrNull()
        log.d { "TVDB artworks for $tvdbId: ${artworks?.size} total, backgrounds=${artworks?.count { it.type == ARTWORK_BACKGROUND }}, logos=${artworks?.count { it.type == ARTWORK_CLEARLOGO }}" }

        return artworks
    }

    // ── Token management ───────────────────────────────────────────────────────

    private suspend fun acquireToken(apiKey: String): String? {
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedToken != null && now < tokenExpiryMs - 60_000) return cachedToken
        }

        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/login",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"apikey":"$apiKey"}""",
            )
        }.getOrNull()

        if (response == null || response.status !in 200..299) {
            log.w { "TVDB login failed: ${response?.status}" }
            return null
        }

        val token = runCatching {
            json.decodeFromString<TvdbLoginResponse>(response.body).data?.token
        }.getOrNull()

        if (token != null) {
            tokenMutex.withLock {
                cachedToken = token
                // TVDB tokens are valid for 30 days; refresh well before expiry.
                tokenExpiryMs = System.currentTimeMillis() + 23L * 60 * 60 * 1000
            }
        } else {
            log.w { "TVDB login returned no token" }
        }
        return token
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun authHeaders(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")

    private fun isTvType(type: String): Boolean =
        type.equals("series", ignoreCase = true) ||
            type.equals("anime", ignoreCase = true)
}

// ── Response models ────────────────────────────────────────────────────────────

@Serializable
private data class TvdbLoginResponse(
    val data: TvdbTokenData? = null,
)

@Serializable
private data class TvdbTokenData(
    val token: String? = null,
)

@Serializable
private data class TvdbSearchResponse(
    val data: List<TvdbSearchResult>? = null,
)

@Serializable
private data class TvdbSearchResult(
    // TVDB v4 returns tvdb_id as a String (e.g. "305749"), not an Int.
    @SerialName("tvdb_id") val tvdbId: String? = null,
    // objectID is "series-305749" or "movie-12345" — used as fallback when tvdb_id is absent.
    @SerialName("objectID") val objectId: String? = null,
) {
    fun numericId(): String? =
        tvdbId?.trim()?.takeIf { it.all(Char::isDigit) }
            ?: objectId?.substringAfterLast("-")?.trim()?.takeIf { it.all(Char::isDigit) }
}

@Serializable
private data class TvdbArtworksResponse(
    // TVDB v4: data is a series object containing an artworks array, not a flat list.
    val data: TvdbSeriesWithArtworks? = null,
)

@Serializable
private data class TvdbSeriesWithArtworks(
    val id: Int? = null,
    val artworks: List<TvdbArtwork> = emptyList(),
)

@Serializable
private data class TvdbArtwork(
    val id: Int? = null,
    val type: Int? = null,
    val image: String? = null,
    val thumbnail: String? = null,
    val language: String? = null,
    val score: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)
