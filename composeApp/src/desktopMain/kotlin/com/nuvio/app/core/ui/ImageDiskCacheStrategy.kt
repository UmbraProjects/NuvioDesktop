package com.nuvio.app.core.ui

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * What Nuvio's image disk cache is allowed to keep, and for how long.
 *
 * Coil's `CacheStrategy.DEFAULT` does two things that are wrong for artwork:
 *
 * 1. Its `write` caches any response whose code is 2xx **or in `{300, 301, 404, 405, 410, 414,
 *    501}`** — a 404 is stored exactly like a hit.
 * 2. Its `read` returns the cached response unconditionally. No expiry, no `Cache-Control`, no
 *    revalidation. Coil's own comment in `NetworkFetcher.doFetch` says as much: only a
 *    "cache-aware" strategy can "issue a fresh request when a cached failure (e.g. 404) has
 *    expired".
 *
 * Together those make one unlucky miss permanent. Metadata providers publish artwork *late* and
 * synthesize URLs for images that do not exist yet — `episodes.metahub.space/<imdb>/<s>/<e>/w780.jpg`
 * 301s to the TMDB still once it is published and 404s until then, with the 404 itself carrying
 * `Cache-Control: public, max-age=345600`, cached per CDN edge. So a client that asks a few hours
 * too early gets a 404, writes it to disk, and re-throws it from disk on every launch forever —
 * without a network request that could ever notice the image had arrived. The Continue Watching
 * card then falls down its artwork chain to the show backdrop, and no amount of metadata
 * re-resolution helps, because the URL was never the problem.
 *
 * The same unconditional `read` is why a poster *service* could not tell us anything either. A
 * PostersPlus poster is a composite whose sash is a moment-in-time fact — a trending rank, an
 * "airing" status — and the service says how long that fact holds by advertising the composite's
 * own remaining life as `max-age` (a day for a trending title, weeks for a settled one) with a
 * validator derived from the bytes. This strategy caches successes only, honours the freshness the
 * origin actually declared, and revalidates by ETag when it lapses so an unchanged poster costs a
 * 304 and not a download. What it decides is also recorded in [ImageFreshnessRegistry], because
 * Coil only asks a strategy on a memory-cache miss and its memory cache never expires on its own.
 *
 * A cached failure left behind by the old strategy is never served (see `ImageDiskCachePurge` for
 * removing them from disk).
 */
@OptIn(ExperimentalCoilApi::class)
internal object ImageDiskCacheStrategy : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        // Coil asks for cache-only when the network is unreachable or network reads are disabled.
        // Honour it even for a cached failure: the caller has said it would rather have the stale
        // answer than none, and a network request would not be served anyway.
        if (networkRequest.headers.cacheControlDirectives().contains(ONLY_IF_CACHED)) {
            return CacheStrategy.ReadResult(cacheResponse)
        }

        // Never serve a cached failure. Entries like this exist only on installs that ran the
        // previous strategy; going to the network is what should have happened all along.
        if (!cacheResponse.isSuccessful) {
            return CacheStrategy.ReadResult(networkRequest)
        }

        val directives = cacheResponse.headers.cacheControlDirectives()
        if (directives.contains(NO_STORE)) {
            return CacheStrategy.ReadResult(networkRequest)
        }

        val now = ImageFreshnessRegistry.nowMillis()
        val remainingMillis = cacheResponse.remainingFreshnessMillis(directives, now)
        if (remainingMillis > 0L) {
            // Nearly all artwork lands here: image.tmdb.org serves
            // `public, max-age=31536000, immutable`, so the common path is a pure disk read.
            recordFreshness(networkRequest, options, now, remainingMillis, bodyChanged = false)
            return CacheStrategy.ReadResult(cacheResponse)
        }

        // Stale. A validator turns the refetch into a 304 that costs no body, so prefer it. With
        // nothing to validate against the entry is simply re-downloaded: the origin said how long
        // its answer held, and that time is up.
        val conditionalRequest = conditionalRequestOrNull(networkRequest, cacheResponse)
        return CacheStrategy.ReadResult(conditionalRequest ?: networkRequest)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        val now = ImageFreshnessRegistry.nowMillis()

        if (networkResponse.code == HTTP_NOT_MODIFIED && cacheResponse != null) {
            // Refresh the stored metadata so the entry's age is measured from this revalidation
            // rather than from the original download — otherwise it is stale again immediately and
            // revalidates on every single load.
            //
            // The cached response is what gets re-stored, not the 304: its code (200) is what the
            // body on disk actually is, and `CacheNetworkResponse.readFrom` never populates a body,
            // so the null below leaves the existing data file untouched. The 304's headers win
            // where they overlap, which is how a service moves a poster's deadline without
            // resending it (RFC 9111 §4.3.4).
            val revalidated = cacheResponse.copy(
                requestMillis = networkResponse.requestMillis,
                responseMillis = now,
                headers = cacheResponse.headers + networkResponse.headers,
                body = null,
            )
            recordFreshness(
                networkRequest, options, now,
                revalidated.remainingFreshnessMillis(revalidated.headers.cacheControlDirectives(), now),
                bodyChanged = false,
            )
            return CacheStrategy.WriteResult(revalidated)
        }

        if (networkResponse.isSuccessful) {
            // Written even when the origin said `no-store`: `read` refuses to serve it, but an
            // offline `only-if-cached` read would still rather have it than nothing.
            val stored = networkResponse.copy(responseMillis = now)
            recordFreshness(
                networkRequest, options, now,
                stored.remainingFreshnessMillis(stored.headers.cacheControlDirectives(), now),
                bodyChanged = true,
            )
            return CacheStrategy.WriteResult(stored)
        }

        // Everything else — 404 for a still that has not been published yet above all — is a
        // moment-in-time answer, not a fact about the URL. Ask again next time.
        return CacheStrategy.WriteResult.DISABLED
    }

    private val NetworkResponse.isSuccessful: Boolean
        get() = code in 200 until 300

    /**
     * How much longer this response may be served without asking the origin, from [now]. Zero or
     * negative once it is stale.
     *
     * The origin's own declaration wins outright: `immutable` is forever, `max-age` is exactly
     * that (less the age the response already had), `no-cache` and `no-store` are nothing. Only a
     * response that says nothing at all gets the [DEFAULT_FRESHNESS_SECONDS] backstop — long,
     * because artwork under a stable URL rarely changes and this cache exists to avoid
     * re-fetching it; finite, because "never" is the bug this whole file is about.
     *
     * The backstop counts only the time *we* have held the entry. `Age` is the origin's statement
     * of how much of its own lifetime a CDN has used up, so it belongs against a declared `max-age`
     * and nothing else: `artworks.thetvdb.com` declares nothing and arrives through CloudFront with
     * an `Age` of months, and measuring the backstop from that made half the TVDB artwork on disk
     * stale the instant it was written — and stale again after every 304, because the 304 carried
     * the same `Age` — so each disk lookup cost a revalidation round trip.
     */
    private fun NetworkResponse.remainingFreshnessMillis(directives: List<String>, now: Long): Long {
        if (directives.contains(IMMUTABLE)) return Long.MAX_VALUE - now
        val declaredSeconds = when {
            directives.contains(NO_STORE) || directives.contains(NO_CACHE) -> 0L
            else -> directives.directiveSeconds(MAX_AGE)
        }
        val remainingSeconds = when (declaredSeconds) {
            null -> DEFAULT_FRESHNESS_SECONDS - heldSeconds(now)
            else -> declaredSeconds - storedAgeSeconds() - heldSeconds(now)
        }
        return remainingSeconds * 1000L
    }

    /**
     * Tells the memory cache how long it may keep serving this key without coming back here.
     *
     * The floor is about the memory cache alone, which is why it is applied here and not in
     * [remainingFreshnessMillis]: a `no-store` poster (PostersPlus serves one while its quality
     * badge is still being looked up) is refused by [read] on every disk lookup, which is right —
     * but if the memory copy expired at once too, every card that scrolled back into view would
     * re-download it. A minute bounds that to one download per card per minute.
     */
    private fun recordFreshness(
        networkRequest: NetworkRequest,
        options: Options,
        now: Long,
        remainingMillis: Long,
        bodyChanged: Boolean,
    ) {
        ImageFreshnessRegistry.record(
            diskCacheKey = options.diskCacheKey ?: networkRequest.url,
            expiresAtMillis = now + remainingMillis.coerceAtLeast(MIN_MEMORY_FRESHNESS_MILLIS),
            bodyChanged = bodyChanged,
        )
    }

    /** The age the origin reported when we stored the entry. */
    private fun NetworkResponse.storedAgeSeconds(): Long =
        headers[AGE]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    /**
     * How long we have been holding the entry. `responseMillis` is stamped by [write] from the same
     * clock [now] comes from, so the two are never compared across clocks.
     */
    private fun NetworkResponse.heldSeconds(now: Long): Long =
        (now - responseMillis).coerceAtLeast(0L) / 1000L

    private fun conditionalRequestOrNull(
        request: NetworkRequest,
        cacheResponse: NetworkResponse,
    ): NetworkRequest? {
        val etag = cacheResponse.headers[ETAG]?.takeIf(String::isNotBlank)
        val lastModified = cacheResponse.headers[LAST_MODIFIED]?.takeIf(String::isNotBlank)
        if (etag == null && lastModified == null) return null

        val headers = request.headers.newBuilder()
        if (etag != null) headers[IF_NONE_MATCH] = etag
        if (lastModified != null) headers[IF_MODIFIED_SINCE] = lastModified
        return request.copy(headers = headers.build())
    }

    /** Lower-cased, whitespace-trimmed `Cache-Control` tokens; empty when the header is absent. */
    private fun NetworkHeaders.cacheControlDirectives(): List<String> =
        get(CACHE_CONTROL)
            ?.split(',')
            ?.map { directive -> directive.trim().lowercase() }
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun List<String>.directiveSeconds(name: String): Long? = this
        .firstOrNull { directive -> directive.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)

    private operator fun NetworkHeaders.plus(other: NetworkHeaders): NetworkHeaders {
        val builder = newBuilder()
        for ((key, values) in other.asMap()) {
            builder[key] = values
        }
        return builder.build()
    }

    private const val HTTP_NOT_MODIFIED = 304

    private const val CACHE_CONTROL = "Cache-Control"
    private const val AGE = "Age"
    private const val ETAG = "ETag"
    private const val LAST_MODIFIED = "Last-Modified"
    private const val IF_NONE_MATCH = "If-None-Match"
    private const val IF_MODIFIED_SINCE = "If-Modified-Since"

    private const val ONLY_IF_CACHED = "only-if-cached"
    private const val NO_STORE = "no-store"
    private const val NO_CACHE = "no-cache"
    private const val IMMUTABLE = "immutable"
    private const val MAX_AGE = "max-age"

    /**
     * The backstop for a response that declared no freshness of its own. A declared lifetime is
     * never capped by this: an origin that asks for sixty days gets sixty days.
     */
    private const val DEFAULT_FRESHNESS_SECONDS = 30L * 24 * 60 * 60

    /** See [recordFreshness]. */
    private const val MIN_MEMORY_FRESHNESS_MILLIS = 60L * 1000
}
