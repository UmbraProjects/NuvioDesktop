package com.nuvio.app.features.streams

import com.nuvio.app.features.metadata.MediaIdResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session-scoped cache of individual **provider responses**, so a stream search that already ran in
 * the background can be replayed the moment the user actually presses play.
 *
 * The unit of caching is deliberately one provider's answer rather than a whole screen's worth of
 * results. Caching a finished [StreamsUiState] would mean teaching [StreamsRepository.load] to skip
 * its fan-out, its completion channel, its auto-select gating, its timeout job and its binge-group
 * handling — the most delicate control flow in the feature. Caching per provider instead lets every
 * existing fetch site stay exactly as it is and simply answer from memory: the completions arrive in
 * ~0 ms, and ordering, scoring, badge presentation and auto-play all run unchanged on top of them.
 * A partial prefetch is useful too, since the fast providers hit cache while the slow ones still go
 * out to the network.
 *
 * ### What is not stored
 *
 * **Failures.** A provider that errored or timed out never answered, so there is nothing to replay;
 * the interactive path re-runs it. A forced refresh bypasses the cache entirely for the same reason.
 *
 * A provider that answered "I have nothing for this title" **is** stored, empty and all. That is a
 * real answer, and at kitchen-sink scale it is most of them: with 61 scrapers enabled, a typical
 * title has a dozen providers with results and roughly fifty without. Treating those fifty as
 * uncacheable meant re-running every one of them on the real play — including the ones that take a
 * full sixty seconds to time out — for an answer already sitting in hand.
 *
 * **Resolved debrid playback URLs.** Those expire, and `DirectDebridResolver` already owns their
 * freshness with its own 15-minute TTL. Entries hold the stream rows a provider returned (already
 * episode-filtered, and annotated with debrid cache availability where the producer did that work);
 * turning one into a playable link stays the resolver's job on every path.
 *
 * Callers supply the max age, mirroring [StreamLinkCacheRepository.getValid], so the TTL stays a
 * user setting rather than a constant buried here.
 */
object StreamPrefetchCache {

    /** One provider's cached answer for one target. */
    data class ProviderEntry(
        val streams: List<StreamItem>,
        val fetchedAtMs: Long,
    )

    /**
     * How many distinct targets are retained. A target is one type/video/season/episode, and holds
     * one entry per provider that answered for it. Browsing detail pages must not grow this without
     * bound, and there is no value in holding a title the user walked away from many screens ago.
     */
    private const val MAX_TARGETS = 12

    private val lock = Any()

    /**
     * Insertion-ordered, with recency refreshed explicitly on read and write, so the eviction below
     * drops the least recently *used* target rather than merely the oldest written one.
     */
    private val targets = object : LinkedHashMap<String, MutableMap<String, ProviderEntry>>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MutableMap<String, ProviderEntry>>,
        ): Boolean = size > MAX_TARGETS
    }

    /**
     * Key for a target whose identity has **already been resolved** through [MediaIdResolver].
     *
     * Mirrors the shape of [StreamsRepository.requestToken] minus its manual-selection flag: what a
     * provider returns does not depend on whether the user will be shown a picker. Callers holding
     * raw ids should use [resolvedContentKey] instead — keying a write on a raw anime id and the
     * matching read on a mapped one would simply never hit.
     */
    fun contentKey(type: String, videoId: String, season: Int?, episode: Int?): String =
        "${type.trim().lowercase()}::$videoId::$season::$episode"

    /** Resolves [videoId]/[season]/[episode] the way every fetch path does, then keys on the result. */
    fun resolvedContentKey(
        type: String,
        parentMetaId: String,
        videoId: String,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): String {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId,
            videoId = videoId,
            title = title,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        return contentKey(
            type = type,
            videoId = resolved.videoId,
            season = resolved.streamSeason,
            episode = resolved.streamEpisode,
        )
    }

    /** Provider id for a plugin scraper, which is cached per scraper rather than per display group. */
    fun scraperProviderId(scraperId: String): String = "plugin-scraper:$scraperId"

    /**
     * Stores one provider's answer, which may legitimately be empty. Only call this for a provider
     * that actually *answered* — a failure has nothing to store and must be left to re-run.
     */
    fun put(contentKey: String, providerId: String, streams: List<StreamItem>) {
        putAt(nowMs = epochMs(), contentKey = contentKey, providerId = providerId, streams = streams)
    }

    internal fun putAt(nowMs: Long, contentKey: String, providerId: String, streams: List<StreamItem>) {
        synchronized(lock) {
            val providers = targets.remove(contentKey) ?: mutableMapOf()
            providers[providerId] = ProviderEntry(streams = streams, fetchedAtMs = nowMs)
            targets[contentKey] = providers
        }
    }

    /** Returns a still-fresh entry, or null when there is none, it aged out, or [maxAgeMs] is zero. */
    fun get(contentKey: String, providerId: String, maxAgeMs: Long): ProviderEntry? =
        getAt(nowMs = epochMs(), contentKey = contentKey, providerId = providerId, maxAgeMs = maxAgeMs)

    internal fun getAt(
        nowMs: Long,
        contentKey: String,
        providerId: String,
        maxAgeMs: Long,
    ): ProviderEntry? {
        if (maxAgeMs <= 0L) return null
        return synchronized(lock) {
            val providers = targets[contentKey] ?: return@synchronized null
            val entry = providers[providerId] ?: return@synchronized null
            val age = nowMs - entry.fetchedAtMs
            if (age !in 0..maxAgeMs) {
                providers.remove(providerId)
                if (providers.isEmpty()) targets.remove(contentKey)
                return@synchronized null
            }
            // Touch: a target being read from is in active use, whatever the write order was.
            targets.remove(contentKey)
            targets[contentKey] = providers
            entry
        }
    }

    // --- in-flight single flight -------------------------------------------------------------
    //
    // A sweep takes seconds, and the user can press play in the middle of one. Without this the
    // interactive load would find an empty cache and send a second identical request to every
    // provider the sweep is already waiting on — the worst outcome available, since it doubles the
    // calls *and* the user still waits. Registering the in-flight fetch lets that load wait for the
    // answer already on its way instead.

    private val inFlightByProviderKey = mutableMapOf<String, CompletableDeferred<List<StreamItem>?>>()

    private fun providerKey(contentKey: String, providerId: String) = "$contentKey||$providerId"

    /**
     * Announces that a fetch for this provider is starting. Returns false when one is already
     * registered, so the caller knows not to release a slot it does not own.
     */
    internal fun claimInFlight(contentKey: String, providerId: String): Boolean =
        synchronized(lock) {
            val key = providerKey(contentKey, providerId)
            if (inFlightByProviderKey.containsKey(key)) return@synchronized false
            inFlightByProviderKey[key] = CompletableDeferred()
            true
        }

    /**
     * Publishes a claimed fetch's result and releases the slot.
     *
     * [streams] distinguishes the two outcomes that look alike from the outside: an empty list is a
     * provider that answered and had nothing, which is cached and served; null is a provider that
     * failed, was cancelled or timed out, which is not cached and leaves anyone waiting to fetch for
     * themselves. Must run for every successful [claimInFlight], or a waiter blocks until its own
     * timeout.
     */
    internal fun releaseInFlight(contentKey: String, providerId: String, streams: List<StreamItem>?) {
        if (streams != null) put(contentKey, providerId, streams)
        val pending = synchronized(lock) { inFlightByProviderKey.remove(providerKey(contentKey, providerId)) }
        pending?.complete(streams)
    }

    /**
     * Waits for a sweep already fetching this provider, or returns null immediately when none is.
     *
     * Null also covers a sweep that failed, so the caller fetches for itself in exactly the cases
     * where there is nothing to reuse. An **empty** list is a real answer and is returned as one.
     * [maxAgeMs] of zero means the caller wants a forced refresh, which must not wait on — or
     * accept — speculative work.
     *
     * The timeout is a backstop against a leaked claim, not a normal path: a provider that takes
     * longer than this is one the caller's own request would also have been waiting on.
     */
    suspend fun awaitInFlight(contentKey: String, providerId: String, maxAgeMs: Long): List<StreamItem>? {
        if (maxAgeMs <= 0L) return null
        val pending = synchronized(lock) { inFlightByProviderKey[providerKey(contentKey, providerId)] }
            ?: return null
        // Null covers all three "fetch it yourself" cases: nothing in flight, the fetch failed, or
        // it outlived the backstop. An empty list is an answer and is returned as one.
        return withTimeoutOrNull(IN_FLIGHT_AWAIT_TIMEOUT_MS) { pending.await() }
    }

    private const val IN_FLIGHT_AWAIT_TIMEOUT_MS = 20_000L

    /** Drops everything cached for one target. */
    fun invalidate(contentKey: String) {
        synchronized(lock) { targets.remove(contentKey) }
    }

    fun clear() {
        val pending = synchronized(lock) {
            targets.clear()
            inFlightByProviderKey.values.toList().also { inFlightByProviderKey.clear() }
        }
        // Anyone waiting is released rather than stranded until their timeout; they fetch instead.
        pending.forEach { it.complete(null) }
    }

    /** Number of retained targets. For tests and diagnostics. */
    internal fun targetCount(): Int = synchronized(lock) { targets.size }
}
