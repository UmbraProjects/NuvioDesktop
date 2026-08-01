package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.selectHeroTrailer
import com.nuvio.app.features.details.youtubePlaybackUrl
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves the focused item's hero trailer into a playable [TrailerPlaybackSource] for the
 * TV-mode home hero. Mirrors [com.nuvio.app.features.mdblist.HeroCastMetadataService]: it
 * reuses the already-warmed meta details cache to pick a YouTube trailer, then resolves it
 * to direct stream URLs. Results are cached in memory with a short TTL because resolved
 * YouTube media URLs expire.
 */
object HeroTrailerMetadataService {
    private const val FOUND_TTL_MS = 2L * 60L * 60L * 1000L
    private const val MISSING_TTL_MS = 10L * 60L * 1000L

    private val log = Logger.withTag("HeroTrailerMetadata")
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<TrailerPlaybackSource?>>()
    private val cache = mutableMapOf<String, CachedTrailer>()

    /**
     * Drops the cached resolution for [type]/[id] so the next [resolve] re-extracts from YouTube.
     *
     * Needed because what is cached here is not "this item has a trailer" but a pair of resolved
     * googlevideo media URLs, and those can be rejected (403) by the time — or from the moment —
     * mpv asks for them. Without this the [FOUND_TTL_MS] entry would hand the same dead URLs to
     * every later attempt, so an item that failed once stayed silent on the home hero for two hours
     * while the details screen (which always re-extracts) played it fine.
     */
    suspend fun invalidate(type: String, id: String) {
        cacheMutex.withLock { cache.remove(cacheKey(type, id)) }
    }

    /** Returns a cached, still-valid resolved trailer source if present, else null. */
    fun peek(type: String, id: String): TrailerPlaybackSource? {
        val entry = cache[cacheKey(type, id)] ?: return null
        return entry.source.takeIf { entry.expiresAtMs > LibraryClock.nowEpochMs() }
    }

    /**
     * Resolves a playable trailer source for [type]/[id], or null when the item has no
     * usable YouTube trailer or resolution fails. Concurrent callers for the same item
     * share a single resolution.
     */
    suspend fun resolve(type: String, id: String): TrailerPlaybackSource? {
        val cacheKey = cacheKey(type, id)
        val now = LibraryClock.nowEpochMs()
        var ownsRequest = false
        val pending = cacheMutex.withLock {
            cache[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > now) return entry.source
            }
            inFlightRequests[cacheKey] ?: CompletableDeferred<TrailerPlaybackSource?>().also {
                inFlightRequests[cacheKey] = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) return pending.await()

        val source = try {
            val meta = MetaDetailsRepository.peek(type = type, id = id)
                ?: MetaDetailsRepository.fetch(type = type, id = id)
            // Only consider series-agnostic or season 1 trailers. Later-season trailers are
            // a major spoiler risk to surface unprompted on the hero.
            val spoilerSafeTrailers = meta?.trailers
                ?.filter { it.seasonNumber == null || it.seasonNumber == 1 }
            val candidate = spoilerSafeTrailers?.let(::selectHeroTrailer)
            log.i {
                "resolve $type/$id metaFound=${meta != null} trailers=${meta?.trailers?.size ?: 0} " +
                    "candidate=${candidate?.key} (${candidate?.site})"
            }
            candidate?.let { TrailerPlaybackResolver.resolveFromYouTubeUrl(it.youtubePlaybackUrl()).sourceOrNull() }
                .also { log.i { "resolve $type/$id playbackSource=${it != null}" } }
        } catch (error: CancellationException) {
            cacheMutex.withLock {
                inFlightRequests.remove(cacheKey)?.completeExceptionally(error)
            }
            throw error
        } catch (error: Throwable) {
            log.w { "Hero trailer resolve failed for $type/$id: ${error.message}" }
            null
        }

        val ttl = if (source == null) MISSING_TTL_MS else FOUND_TTL_MS
        cacheMutex.withLock {
            cache[cacheKey] = CachedTrailer(source = source, expiresAtMs = now + ttl)
            inFlightRequests.remove(cacheKey)?.complete(source)
        }
        return source
    }

    private fun cacheKey(type: String, id: String): String = "$type:$id"

    private data class CachedTrailer(
        val source: TrailerPlaybackSource?,
        val expiresAtMs: Long,
    )
}
