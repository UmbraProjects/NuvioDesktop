package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MdbListMetadataService {
    const val PROVIDER_IMDB = "imdb"
    const val PROVIDER_TMDB = "tmdb"
    const val PROVIDER_TOMATOES = "tomatoes"
    const val PROVIDER_METACRITIC = "metacritic"
    const val PROVIDER_TRAKT = "trakt"
    const val PROVIDER_LETTERBOXD = "letterboxd"
    const val PROVIDER_AUDIENCE = "audience"

    val PROVIDER_PRIORITY_ORDER = listOf(
        PROVIDER_IMDB,
        PROVIDER_TMDB,
        PROVIDER_TOMATOES,
        PROVIDER_METACRITIC,
        PROVIDER_TRAKT,
        PROVIDER_LETTERBOXD,
        PROVIDER_AUDIENCE,
    )

    private val log = Logger.withTag("MdbListMetadata")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbRegex = Regex("tt\\d+")

    private val sourceToProvider = mapOf(
        "imdb" to PROVIDER_IMDB,
        "tmdb" to PROVIDER_TMDB,
        "tomatoes" to PROVIDER_TOMATOES,
        "metacritic" to PROVIDER_METACRITIC,
        "trakt" to PROVIDER_TRAKT,
        "letterboxd" to PROVIDER_LETTERBOXD,
        "popcorn" to PROVIDER_AUDIENCE,
    )

    private const val FOUND_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val RATE_LIMIT_BACKOFF_MS = 30L * 60L * 1000L

    private var cache: MutableMap<String, CachedRatings>? = null
    private var rateLimitedUntilMs: Long = 0L
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<List<MetaExternalRating>>>()

    fun shouldFetchForMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): Boolean {
        if (!settings.enabled) return false
        if (settings.apiKey.trim().isBlank()) return false
        if (settings.enabledProvidersInPriorityOrder().isEmpty()) return false
        return extractImdbId(meta.id) != null || extractImdbId(fallbackItemId) != null
    }

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): MetaDetails {
        if (!shouldFetchForMeta(meta, fallbackItemId, settings)) {
            return meta.copy(externalRatings = emptyList())
        }
        val apiKey = settings.apiKey.trim()

        val imdbId = extractImdbId(meta.id)
            ?: extractImdbId(fallbackItemId)
            ?: return meta.copy(externalRatings = emptyList())
        val mediaType = toMdbListMediaType(meta.type)
        val enabledProviders = settings.enabledProvidersInPriorityOrder().toSet()

        val ratings = fetchRatings(
            imdbId = imdbId,
            mediaType = mediaType,
            apiKey = apiKey,
        ).filter { it.source in enabledProviders }

        return meta.copy(externalRatings = ratings)
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            cache = mutableMapOf()
            inFlightRequests.clear()
        }
        runCatching { MdbListRatingsCacheStorage.save("{}") }
            .onFailure { error -> log.w { "Failed to clear MDBList ratings cache: ${error.message}" } }
    }

    private suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String,
    ): List<MetaExternalRating> {
        val cacheKey = "$mediaType:$imdbId"
        val now = LibraryClock.nowEpochMs()
        var ownsRequest = false
        val pending = cacheMutex.withLock {
            if (rateLimitedUntilMs > now) return emptyList()
            val loaded = ensureCacheLoaded()
            loaded[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > now) return entry.ratings
            }
            inFlightRequests[cacheKey] ?: CompletableDeferred<List<MetaExternalRating>>().also {
                inFlightRequests[cacheKey] = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) return pending.await()

        val ratings = try {
            fetchFromApi(imdbId = imdbId, mediaType = mediaType, apiKey = apiKey)
        } catch (error: CancellationException) {
            cacheMutex.withLock {
                inFlightRequests.remove(cacheKey)?.completeExceptionally(error)
            }
            throw error
        } catch (error: MdbListRateLimitedException) {
            log.w { "MDBList rate limit hit; backing off for 30 minutes" }
            cacheMutex.withLock {
                rateLimitedUntilMs = LibraryClock.nowEpochMs() + RATE_LIMIT_BACKOFF_MS
                inFlightRequests.remove(cacheKey)?.complete(emptyList())
            }
            return emptyList()
        } catch (error: Throwable) {
            log.w { "MDBList request failed for $mediaType/$imdbId: ${error.message}" }
            cacheMutex.withLock {
                inFlightRequests.remove(cacheKey)?.complete(emptyList())
            }
            return emptyList()
        }

        cacheMutex.withLock {
            if (ratings.isNotEmpty()) {
                val loaded = ensureCacheLoaded()
                loaded[cacheKey] = CachedRatings(ratings = ratings, expiresAtMs = now + FOUND_TTL_MS)
                persistCache(loaded)
            }
            inFlightRequests.remove(cacheKey)?.complete(ratings)
        }

        return ratings
    }

    private suspend fun fetchFromApi(
        imdbId: String,
        mediaType: String,
        apiKey: String,
    ): List<MetaExternalRating> {
        val url = "https://api.mdblist.com/imdb/$mediaType/$imdbId?apikey=$apiKey"
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status == 429) {
            throw MdbListRateLimitedException()
        }
        if (response.status !in 200..299) {
            error("HTTP ${response.status}")
        }
        val payload = response.body.takeIf { it.isNotBlank() } ?: error("Empty response body")
        val parsed = json.decodeFromString<MdbListRatingsResponse>(payload)
        return parsed.ratings.mapNotNull { item ->
            val providerId = sourceToProvider[item.source?.lowercase()] ?: return@mapNotNull null
            val value = item.value ?: return@mapNotNull null
            MetaExternalRating(source = providerId, value = value)
        }
    }

    private fun ensureCacheLoaded(): MutableMap<String, CachedRatings> {
        cache?.let { return it }
        val loaded = mutableMapOf<String, CachedRatings>()
        runCatching {
            MdbListRatingsCacheStorage.load()?.let { raw ->
                json.decodeFromString<Map<String, CachedRatingsDto>>(raw).forEach { (key, dto) ->
                    loaded[key] = CachedRatings(
                        ratings = dto.ratings.map { MetaExternalRating(source = it.source, value = it.value) },
                        expiresAtMs = dto.expiresAtMs,
                    )
                }
            }
        }.onFailure { error ->
            log.w { "Failed to load MDBList ratings cache: ${error.message}" }
        }
        cache = loaded
        return loaded
    }

    private fun persistCache(entries: Map<String, CachedRatings>) {
        val dto = entries.mapValues { (_, entry) ->
            CachedRatingsDto(
                ratings = entry.ratings.map { MetaExternalRatingDto(source = it.source, value = it.value) },
                expiresAtMs = entry.expiresAtMs,
            )
        }
        runCatching { MdbListRatingsCacheStorage.save(json.encodeToString(dto)) }
            .onFailure { error -> log.w { "Failed to save MDBList ratings cache: ${error.message}" } }
    }

    private fun extractImdbId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return imdbRegex.find(value)?.value
    }

    private fun toMdbListMediaType(metaType: String): String {
        val normalized = metaType.trim().lowercase()
        return if (normalized == "movie") "movie" else "show"
    }
}

private class MdbListRateLimitedException : RuntimeException()

private data class CachedRatings(
    val ratings: List<MetaExternalRating>,
    val expiresAtMs: Long,
)

@Serializable
private data class CachedRatingsDto(
    val ratings: List<MetaExternalRatingDto>,
    val expiresAtMs: Long,
)

@Serializable
private data class MetaExternalRatingDto(
    val source: String,
    val value: Double,
)

@Serializable
private data class MdbListRatingsResponse(
    val ratings: List<MdbListRatingItem> = emptyList(),
)

@Serializable
private data class MdbListRatingItem(
    val source: String? = null,
    val value: Double? = null,
)
