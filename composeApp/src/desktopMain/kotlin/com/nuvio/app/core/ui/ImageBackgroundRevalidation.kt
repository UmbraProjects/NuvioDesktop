package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val revalidationLog = Logger.withTag("ImageRevalidate")

/**
 * Stale artwork on disk is shown at once and refreshed in the background (`stale-while-revalidate`,
 * RFC 5861), instead of the card waiting for the refresh.
 *
 * Before this, a poster whose freshness had lapsed was held until the origin answered its
 * conditional request. On 2026-11-18 18:47:59 five such refreshes (four TMDB posters and the profile
 * avatar) hung for 11-15 s while the network was having a bad moment, and 128 posters that were on
 * disk and needed 8-40 ms to read waited behind them: Library and Home showed blank cards for ~11 s
 * after launch. A refresh exists to catch a changed sash or badge; showing yesterday's for one more
 * view is the right trade against showing nothing.
 *
 * [ImageDiskCacheStrategy] decides what is stale and calls [schedule]; the refresh is an ordinary
 * Coil request marked with [BackgroundRevalidationKey], so it takes the strategy's network path
 * (conditional request, 304 or new bytes, freshness recorded) and writes the disk cache as any fetch
 * would. New bytes bump the body version in [ImageFreshnessRegistry], which retires every decoded
 * copy in memory, so the next time the card is drawn it shows the new poster. A 404 or 410 means the
 * poster is gone: its disk copy is dropped so the next load fails and the artwork chain falls back,
 * as it did before.
 */
internal object ImageBackgroundRevalidation {

    /** Few at a time: these are never urgent, and must not crowd out what is on screen. */
    private val permits = Semaphore(MaxConcurrent)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)

    /** When each key's last refresh started, so a row of cards for one poster refreshes it once. */
    private val lastStarted = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MaxTrackedKeys
    }

    /**
     * Starts a background refresh of [diskCacheKey] unless one started within [CooldownMillis].
     * Returns whether one was started.
     *
     * Inside a request that passed [ImageRevalidationStartInterceptor] (every request in the app)
     * the refresh is held until that request has finished. Started at once, a quick refresh can
     * finish before the stale copy it replaces has been decoded and put in memory; the memory cache
     * then tags the old bitmap with the new body version and keeps serving it as current.
     */
    suspend fun schedule(fetch: ImageFetchScope, diskCacheKey: String): Boolean {
        val now = ImageFreshnessRegistry.nowMillis()
        synchronized(lastStarted) {
            val previous = lastStarted[diskCacheKey]
            if (previous != null && now - previous < CooldownMillis) return false
            lastStarted[diskCacheKey] = now
        }
        val pending = currentCoroutineContext()[PendingRevalidations]
        if (pending != null) {
            pending.add { start(fetch, diskCacheKey) }
        } else {
            start(fetch, diskCacheKey)
        }
        return true
    }

    private fun start(fetch: ImageFetchScope, diskCacheKey: String) {
        val request = ImageRequest.Builder(fetch.options.context)
            .data(fetch.data)
            .diskCacheKey(fetch.options.diskCacheKey)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(1, 1)
            .apply { extras[BackgroundRevalidationKey] = true }
            .build()
        scope.launch {
            permits.withPermit {
                val result = runCatching { fetch.imageLoader.execute(request) }.getOrNull()
                val code = ((result as? ErrorResult)?.throwable as? HttpException)?.response?.code
                if (code == 404 || code == 410) {
                    // Gone, not down: drop the copy so the artwork chain can fall back next time.
                    runCatching { fetch.imageLoader.diskCache?.remove(diskCacheKey) }
                    ImageFreshnessRegistry.record(diskCacheKey, expiresAtMillis = 0L, bodyChanged = true)
                    revalidationLog.d { "dropped $code ${ImageFetchTimingListener.describeData(diskCacheKey)}" }
                }
            }
        }
    }

    /** Tests: waits for every refresh started so far. */
    internal suspend fun awaitIdle() {
        supervisor.children.toList().joinAll()
    }

    /** Tests: forgets the cooldowns. */
    internal fun reset() = synchronized(lastStarted) { lastStarted.clear() }

    private const val MaxConcurrent = 2
    private const val MaxTrackedKeys = 4096
    private const val CooldownMillis = 5L * 60 * 1000
}

/** Refreshes a request asked for, started once it has finished; see [ImageBackgroundRevalidation.schedule]. */
internal class PendingRevalidations : AbstractCoroutineContextElement(Key) {
    private val starts = mutableListOf<() -> Unit>()

    fun add(start: () -> Unit) = synchronized(starts) { starts += start }

    fun drain(): List<() -> Unit> = synchronized(starts) { starts.toList().also { starts.clear() } }

    companion object Key : CoroutineContext.Key<PendingRevalidations>
}

/**
 * Outermost interceptor: runs the request with a [PendingRevalidations] in its context and starts
 * whatever refreshes it asked for once it is done, the decoded result already in memory. Started
 * on failure or cancellation too: the stale entry still wants refreshing.
 */
internal class ImageRevalidationStartInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val pending = PendingRevalidations()
        try {
            return withContext(pending) { chain.proceed() }
        } finally {
            pending.drain().forEach { it() }
        }
    }
}

/** Marks the background refresh request [ImageBackgroundRevalidation] issues. */
internal val BackgroundRevalidationKey = Extras.Key(default = false)

internal fun Options.isBackgroundRevalidation(): Boolean = extras[BackgroundRevalidationKey] == true

/**
 * The loader and request a network fetch belongs to, readable from inside it — which is where
 * [ImageDiskCacheStrategy] runs, and Coil hands a strategy neither.
 */
internal class ImageFetchScope(
    val imageLoader: ImageLoader,
    val data: Uri,
    val options: Options,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImageFetchScope>
}

/** Wraps the network fetcher so each fetch runs inside its [ImageFetchScope]. */
internal class ScopedNetworkFetcherFactory(
    private val delegate: Fetcher.Factory<Uri>,
) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val fetcher = delegate.create(data, options, imageLoader) ?: return null
        val scope = ImageFetchScope(imageLoader, data, options)
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? = withContext(scope) { fetcher.fetch() }
        }
    }
}

/**
 * A background refresh only needs the disk cache written, which the fetcher has done by the time a
 * decoder is asked; decoding the poster nobody is waiting for would be wasted work.
 */
internal class BackgroundRevalidationDecoder(private val result: SourceFetchResult) : Decoder {
    override suspend fun decode(): DecodeResult {
        result.source.close()
        return DecodeResult(image = Bitmap().apply { allocN32Pixels(1, 1) }.asImage(), isSampled = true)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (options.isBackgroundRevalidation()) BackgroundRevalidationDecoder(result) else null
    }
}
