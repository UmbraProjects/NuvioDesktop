package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.rankHeroTrailers
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** The hunt for a sharper upload is bounded; the first upload plays if it overruns. */
    private const val IMPROVE_TIMEOUT_MS = 20_000L

    private const val MAX_PARALLEL_RESOLVES = 2
    private const val UNRELIABLE_RETRIES = 2
    private const val UNRELIABLE_RETRY_DELAY_MS = 800L
    private val resolveGate = Semaphore(MAX_PARALLEL_RESOLVES)

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
            // Summary rather than the details-screen record. It scopes TMDB trailers to season 1,
            // which is exactly what the spoiler filter below keeps - the full fetch was pulling a
            // trailer list per season so this could throw all but one of them away.
            val meta = MetaDetailsRepository.peek(type = type, id = id)
                ?: MetaDetailsRepository.fetchHeroSummary(type = type, id = id)
            // Only consider series-agnostic or season 1 trailers. Later-season trailers are
            // a major spoiler risk to surface unprompted on the hero.
            val spoilerSafeTrailers = meta?.trailers
                ?.filter { it.seasonNumber == null || it.seasonNumber == 1 }
            val ranked = spoilerSafeTrailers?.let(::rankHeroTrailers).orEmpty()
            log.i {
                "resolve $type/$id metaFound=${meta != null} trailers=${meta?.trailers?.size ?: 0} " +
                    "candidate=${ranked.firstOrNull()?.key} (${ranked.firstOrNull()?.site})"
            }
            resolveSharpest(meta?.name, meta?.releaseInfo, ranked.map { it.key })
                .also { log.i { "resolve $type/$id playbackSource=${it != null} height=${it?.heightPx}" } }
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

    /**
     * Plays the sharpest upload of the trailer rather than just the listed one. The listed trailer
     * is resolved first and accepted as it is when it reaches [GOOD_ENOUGH_TRAILER_HEIGHT], which
     * keeps the common case to a single resolve. Only a soft one (or none) triggers the search:
     * the next listed trailers and the best YouTube search results are resolved together and the
     * tallest wins, ties keeping the listed order. Everything past the first resolve is best
     * effort and bounded, so it can only improve on, never lose, the trailer already in hand.
     */
    private suspend fun resolveSharpest(title: String?, releaseInfo: String?, listedKeys: List<String>): TrailerPlaybackSource? {
        val first = listedKeys.firstOrNull()?.let { resolveKey(it) }
        if (first != null && first.reliable && first.heightPx >= GOOD_ENOUGH_TRAILER_HEIGHT) return first
        if (title.isNullOrBlank() && listedKeys.size < 2) return first

        val tried = listedKeys.take(1).toMutableSet()
        val firstOption = first?.let { TrailerOption(listedKeys.first(), it, order = 0) }
        val better = withTimeoutOrNull(IMPROVE_TIMEOUT_MS) {
            coroutineScope {
                val listed = listedKeys.drop(1).take(MAX_LISTED_ALTERNATIVES)
                tried += listed
                val listedJobs = listed.mapIndexed { index, key ->
                    async { resolveKey(key)?.let { TrailerOption(key, it, order = index + 1) } }
                }
                val searchJob = async {
                    if (title.isNullOrBlank()) return@async emptyList<TrailerOption>()
                    val year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                    val hits = filterTrailerSearchHits(
                        hits = runCatching { TrailerPlaybackResolver.searchYouTube(trailerSearchQuery(title, year)) }
                            .getOrDefault(emptyList()),
                        title = title,
                        year = year,
                        excludeKeys = tried,
                    )
                    log.i { "search alternatives for \"$title\": ${hits.map { it.videoId }}" }
                    hits.mapIndexed { index, hit ->
                        async { resolveKey(hit.videoId)?.let { TrailerOption(hit.videoId, it, order = 100 + index) } }
                    }.awaitAll().filterNotNull()
                }
                listedJobs.awaitAll().filterNotNull() + searchJob.await()
            }
        }.orEmpty()

        val all = listOfNotNull(firstOption) + better
        val best = pickBestTrailer(all)
        log.i {
            "sharpest of ${all.size}: " + all.joinToString { "${it.key}=${it.source.heightPx}p" } +
                " -> ${best?.key}"
        }
        return best?.source ?: first
    }

    /**
     * Resolves one upload. When the unrestricted client did not answer, the result is a source
     * whose URLs 403, so it is looked up again after a pause (the usual cause is throttling while
     * several uploads are resolved at once) and the unreliable one only kept if that fails too.
     * At most [MAX_PARALLEL_RESOLVES] lookups run at once, so a search for a sharper upload does
     * not itself cause the throttling.
     */
    private suspend fun resolveKey(key: String): TrailerPlaybackSource? {
        suspend fun once(): TrailerPlaybackSource? = resolveGate.withPermit {
            runCatching { TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubePlaybackUrlFor(key)).sourceOrNull() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        var source = once()
        var attempt = 0
        while (source != null && !source.reliable && attempt < UNRELIABLE_RETRIES) {
            attempt++
            log.i { "$key came back through a restricted client, retrying ($attempt)" }
            delay(UNRELIABLE_RETRY_DELAY_MS * attempt)
            source = once() ?: source
        }
        return source
    }

    private fun youtubePlaybackUrlFor(key: String): String =
        key.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "https://www.youtube.com/watch?v=$key"

    private fun cacheKey(type: String, id: String): String = "$type:$id"

    private data class CachedTrailer(
        val source: TrailerPlaybackSource?,
        val expiresAtMs: Long,
    )
}
