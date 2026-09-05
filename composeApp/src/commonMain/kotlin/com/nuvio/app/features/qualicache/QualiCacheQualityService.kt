package com.nuvio.app.features.qualicache

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/** Why a lookup produced no tokens, so a caller can tell "not yet" from "nothing to show". */
enum class QualityLookupStatus {
    /** The server answered with a trusted release. */
    Ready,

    /** The server checked and found no trusted release. Asking again shortly will not help. */
    Empty,

    /** The server is querying its addons right now. Worth asking again in a few seconds. */
    Warming,

    /** Not configured, not an IMDb title, unreachable, or refusing us. */
    Unavailable,
}

/** The tokens for a title plus the reason there might not be any. */
data class QualityLookup(
    val tokens: List<String>,
    val status: QualityLookupStatus,
) {
    companion object {
        val Unavailable = QualityLookup(emptyList(), QualityLookupStatus.Unavailable)
    }
}

/**
 * Reads cached release-quality tokens (`4K`, `DV`, `ATMOS`, …) from a self-hosted QualiCache
 * instance.
 *
 * QualiCache's own read path is deliberately cheap — one SQLite row, never a live addon query — so
 * this client is thin. It still caches locally because the hero prefetches metadata for every
 * nearby item on every scroll, and that traffic should not reach the network at all once warm.
 *
 * Series are looked up at their representative first episode (S01E01), which is exactly the entry
 * QualiCache crawls and stores for a series. Per-episode quality is not requested: the hero and
 * details header describe the title, not one episode.
 */
object QualiCacheQualityService {
    // v2 stores the lookup status alongside the tokens. The bump also discards v1 entries, which
    // is wanted: those recorded "no quality" without recording that it was only provisional.
    const val CACHE_VERSION = 3

    private val log = Logger.withTag("QualiCache")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbRegex = Regex("tt\\d+")
    private val isoDateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}")

    private const val READY_TTL_MS = 24L * 60L * 60L * 1000L
    private const val EMPTY_TTL_MS = 6L * 60L * 60L * 1000L
    // "pending" means QualiCache has queued the title and is querying addons right now, which
    // takes seconds. Held only long enough to collapse a burst of recompositions: callers poll
    // while warming, and a TTL longer than their poll interval would answer from cache and hide
    // the result that just landed. Concurrent callers are already collapsed by single-flight.
    private const val PENDING_TTL_MS = 3L * 1000L
    private const val ERROR_TTL_MS = 30L * 60L * 1000L
    // A self-hosted server is routinely just switched off. One transport failure parks every
    // lookup briefly so browsing does not stall behind a connect timeout per visible item.
    private const val SERVER_DOWN_BACKOFF_MS = 5L * 60L * 1000L

    private var cache: MutableMap<String, CachedQuality>? = null
    // An immutable copy of [cache] readable without suspending. Composition needs the answer
    // during the frame it lays out, and the real cache is behind a coroutine Mutex — a warm hit
    // that arrives one frame late still reflows everything below the badge row.
    @Volatile
    private var cacheSnapshot: Map<String, CachedQuality> = emptyMap()
    private var serverDownUntilMs: Long = 0L
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<QualityLookup>>()
    // App-lifetime scope owning the request + cache write, for the same reason MDBList does it:
    // the hero prefetch lives in a LaunchedEffect that restarts on every scroll, and a caller that
    // scrolls away must not discard a reply that was already on the wire.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when [lookup] could produce something — i.e. configured, and this is an IMDb title. */
    fun shouldFetchFor(id: String?, settings: QualiCacheSettings): Boolean {
        if (!settings.isUsable) return false
        return extractImdbId(id) != null
    }

    /**
     * The already-cached answer for a title, without suspending, or null when there is none.
     *
     * Exists so a composable can seed its state with the right value instead of starting empty
     * and correcting itself a frame later, which reflows every row beneath it. Returns null
     * until the on-disk cache has been read once — the first lookup after launch does that.
     */
    fun cached(type: String, id: String?, settings: QualiCacheSettings): QualityLookup? {
        if (!shouldFetchFor(id, settings)) return null
        val imdbId = extractImdbId(id) ?: return null
        val cacheKey =
            "v$CACHE_VERSION:${settings.minimumTrust.apiValue}:${toQualiCacheMediaType(type)}:$imdbId"
        val entry = cacheSnapshot[cacheKey] ?: return null
        if (entry.expiresAtMs <= LibraryClock.nowEpochMs()) return null
        return QualityLookup(entry.tokens, entry.status)
    }

    /**
     * Returns the display tokens for a title along with why they might be missing.
     *
     * Never throws: quality badges are decoration and must not break a screen. A
     * [QualityLookupStatus.Warming] result means the server accepted the title and is querying
     * its addons — the caller should ask again shortly rather than treat it as "no quality".
     */
    suspend fun lookup(
        type: String,
        id: String?,
        settings: QualiCacheSettings,
        releaseDate: String? = null,
    ): QualityLookup {
        if (!shouldFetchFor(id, settings)) return QualityLookup.Unavailable
        val imdbId = extractImdbId(id) ?: return QualityLookup.Unavailable
        val mediaType = toQualiCacheMediaType(type)
        val cacheKey = "v$CACHE_VERSION:${settings.minimumTrust.apiValue}:$mediaType:$imdbId"
        val now = LibraryClock.nowEpochMs()

        val pending = cacheMutex.withLock {
            val loaded = ensureCacheLoaded()
            loaded[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > now) return QualityLookup(entry.tokens, entry.status)
            }
            if (serverDownUntilMs > now) return QualityLookup.Unavailable
            inFlightRequests[cacheKey] ?: CompletableDeferred<QualityLookup>().also { deferred ->
                inFlightRequests[cacheKey] = deferred
                serviceScope.launch {
                    performFetchAndCache(
                        cacheKey = cacheKey,
                        imdbId = imdbId,
                        mediaType = mediaType,
                        settings = settings,
                        releaseDate = releaseDate,
                        deferred = deferred,
                    )
                }
            }
        }
        return pending.await()
    }

    /**
     * Drops everything cached, in memory and on disk, and lifts any server-down backoff.
     * Called when the server address or access key changes: those entries describe a different
     * server (or were failures caused by the old, wrong value) and must not be reused.
     */
    fun invalidate() {
        serviceScope.launch {
            cacheMutex.withLock {
                cache = mutableMapOf()
                cacheSnapshot = emptyMap()
                inFlightRequests.clear()
                serverDownUntilMs = 0L
            }
            runCatching { QualiCacheQualityCacheStorage.save("{}") }
                .onFailure { error -> log.w { "Failed to clear QualiCache cache: ${error.message}" } }
        }
    }

    private suspend fun performFetchAndCache(
        cacheKey: String,
        imdbId: String,
        mediaType: String,
        settings: QualiCacheSettings,
        releaseDate: String?,
        deferred: CompletableDeferred<QualityLookup>,
    ) {
        val result = try {
            fetchFromApi(
                imdbId = imdbId,
                mediaType = mediaType,
                settings = settings,
                releaseDate = releaseDate,
            )
        } catch (error: CancellationException) {
            // Only reached when the service scope itself is torn down (app shutdown).
            cacheMutex.withLock { inFlightRequests.remove(cacheKey) }
            deferred.completeExceptionally(error)
            throw error
        } catch (error: QualiCacheUnreachableException) {
            log.w { "QualiCache unreachable; pausing lookups for 5 minutes: ${error.message}" }
            cacheMutex.withLock {
                serverDownUntilMs = LibraryClock.nowEpochMs() + SERVER_DOWN_BACKOFF_MS
                inFlightRequests.remove(cacheKey)
            }
            deferred.complete(QualityLookup.Unavailable)
            return
        } catch (error: Throwable) {
            // A bad key or a 5xx is not worth polling through, so it is cached as unavailable
            // rather than warming.
            log.w { "QualiCache request failed for $mediaType/$imdbId: ${error.message}" }
            val failure = QualityResult(emptyList(), QualityLookupStatus.Unavailable, ERROR_TTL_MS)
            cacheMutex.withLock {
                storeLocked(cacheKey, failure)
                inFlightRequests.remove(cacheKey)
            }
            deferred.complete(QualityLookup(emptyList(), QualityLookupStatus.Unavailable))
            return
        }

        cacheMutex.withLock {
            storeLocked(cacheKey, result)
            inFlightRequests.remove(cacheKey)
        }
        deferred.complete(QualityLookup(result.tokens, result.status))
    }

    private suspend fun fetchFromApi(
        imdbId: String,
        mediaType: String,
        settings: QualiCacheSettings,
        releaseDate: String?,
    ): QualityResult {
        val url = buildQualiCacheQualityUrl(
            baseUrl = settings.baseUrl,
            mediaType = mediaType,
            imdbId = imdbId,
            minimumTrust = settings.minimumTrust,
            releaseDate = isoReleaseDate(releaseDate),
        )
        val headers = buildMap {
            put("Accept", "application/json")
            // Header rather than the supported access_key query parameter: a secret does not belong
            // in a URL that ends up in logs.
            settings.accessKey.takeIf { it.isNotBlank() }?.let { key -> put("X-API-Key", key) }
        }

        val response = try {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw QualiCacheUnreachableException(error)
        }

        if (response.status == 401 || response.status == 403) {
            error("HTTP ${response.status} — check the QualiCache access key")
        }
        if (response.status !in 200..299) {
            error("HTTP ${response.status}")
        }
        val payload = response.body.takeIf { it.isNotBlank() } ?: error("Empty response body")
        val parsed = json.decodeFromString<QualiCacheQualityResponse>(payload)

        return when (parsed.status) {
            "ready" -> QualityResult(parsed.tokens, QualityLookupStatus.Ready, READY_TTL_MS)
            "empty" -> QualityResult(emptyList(), QualityLookupStatus.Empty, EMPTY_TTL_MS)
            // "pending" (queued/warming) and "error" (its addons failed this round) are both
            // transient states on the server; hold the miss briefly and ask again shortly.
            else -> QualityResult(emptyList(), QualityLookupStatus.Warming, PENDING_TTL_MS)
        }
    }

    private fun storeLocked(cacheKey: String, result: QualityResult) {
        val loaded = ensureCacheLoaded()
        loaded[cacheKey] = CachedQuality(
            tokens = result.tokens,
            status = result.status,
            expiresAtMs = LibraryClock.nowEpochMs() + result.ttlMs,
        )
        cacheSnapshot = loaded.toMap()
        persistCache(loaded)
    }

    private fun ensureCacheLoaded(): MutableMap<String, CachedQuality> {
        cache?.let { return it }
        val loaded = mutableMapOf<String, CachedQuality>()
        runCatching {
            QualiCacheQualityCacheStorage.load()?.let { raw ->
                json.decodeFromString<Map<String, CachedQualityDto>>(raw).forEach { (key, dto) ->
                    loaded[key] = CachedQuality(
                        tokens = dto.tokens,
                        status = statusFromName(dto.status),
                        expiresAtMs = dto.expiresAtMs,
                    )
                }
            }
        }.onFailure { error ->
            log.w { "Failed to load QualiCache cache: ${error.message}" }
        }
        // Prune superseded key schemes and expired entries on load. The blob is rewritten whole on
        // every miss, so letting it grow unbounded costs write time and widens the window in which
        // a crash mid-write corrupts the lot.
        val now = LibraryClock.nowEpochMs()
        val currentPrefix = "v$CACHE_VERSION:"
        loaded.entries.retainAll { (key, entry) -> key.startsWith(currentPrefix) && entry.expiresAtMs > now }
        cache = loaded
        cacheSnapshot = loaded.toMap()
        return loaded
    }

    private fun persistCache(entries: Map<String, CachedQuality>) {
        val dto = entries.mapValues { (_, entry) ->
            CachedQualityDto(
                tokens = entry.tokens,
                status = entry.status.name,
                expiresAtMs = entry.expiresAtMs,
            )
        }
        runCatching { QualiCacheQualityCacheStorage.save(json.encodeToString(dto)) }
            .onFailure { error -> log.w { "Failed to save QualiCache cache: ${error.message}" } }
    }

    /** Unknown names come from a future build's cache file; treat them as a miss, not a crash. */
    private fun statusFromName(value: String): QualityLookupStatus =
        QualityLookupStatus.entries.firstOrNull { it.name == value } ?: QualityLookupStatus.Unavailable

    private fun extractImdbId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return imdbRegex.find(value)?.value
    }

    private fun toQualiCacheMediaType(metaType: String): String =
        if (metaType.trim().lowercase() == "movie") "movie" else "series"

    /** QualiCache validates this as a date, so only a leading `YYYY-MM-DD` may be forwarded. */
    private fun isoReleaseDate(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return isoDateRegex.find(value.trim())?.value
    }
}

internal fun buildQualiCacheQualityUrl(
    baseUrl: String,
    mediaType: String,
    imdbId: String,
    minimumTrust: QualiCacheMinimumTrust,
    releaseDate: String? = null,
): String {
    val parameters = mutableListOf(
        "season=1",
        "episode=1",
        "min_trust=${minimumTrust.apiValue}",
    )
    releaseDate?.let { parameters += "release_date=$it" }
    return "$baseUrl/v1/quality/$mediaType/$imdbId?${parameters.joinToString("&")}"
}

private class QualiCacheUnreachableException(cause: Throwable) : RuntimeException(cause.message, cause)

private data class QualityResult(
    val tokens: List<String>,
    val status: QualityLookupStatus,
    val ttlMs: Long,
)

private data class CachedQuality(
    val tokens: List<String>,
    val status: QualityLookupStatus,
    val expiresAtMs: Long,
)

@Serializable
private data class CachedQualityDto(
    val tokens: List<String> = emptyList(),
    val status: String = "",
    val expiresAtMs: Long,
)

@Serializable
private data class QualiCacheQualityResponse(
    val status: String = "",
    val tokens: List<String> = emptyList(),
)
