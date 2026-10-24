package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import coil3.EventListener
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult

private val imageFetchLog = Logger.withTag("ImageFetch")

/**
 * One line per image that had to leave the memory cache, split into where the time went:
 *
 * ```
 * Info: (ImageFetch) NETWORK 812ms wait=6 fetch=780 decode=22 300x450 https://posters.example/…
 * ```
 *
 *  - `wait` — request start to the fetcher being invoked: memory-cache miss, size resolution and,
 *    above all, queueing for a dispatcher slot. A large value with a small `fetch` is the client
 *    being busy, not the server.
 *  - `fetch` — the fetcher's own time. For `NETWORK` that is the round trip to the poster service
 *    plus writing the disk-cache entry; for `DISK` it is the cache read (a conditional request that
 *    came back 304 also lands here).
 *  - `decode` — turning bytes into a bitmap.
 *
 * This is the instrumentation that was missing on 2026-09-22, when "the posters took ages" could
 * not be split between client and provider from the log. `NETWORK` loads are always written; disk
 * hits only when they are slow enough to matter, since a warm screen produces hundreds of them at a
 * few milliseconds each. Query parameters that look like credentials are masked, because a custom
 * poster template forwards the user's TMDB key in the URL. Written at Debug: see
 * DesktopFileLogging for how to turn Debug on.
 */
internal class ImageFetchTimingListener(
    private val now: () -> Long = System::nanoTime,
    private val emit: (String) -> Unit = { imageFetchLog.d { it } },
) : EventListener() {

    private var startedAt = 0L
    private var fetchStartedAt = 0L
    private var fetchNanos = -1L
    private var waitNanos = -1L
    private var decodeStartedAt = 0L
    private var decodeNanos = -1L

    override fun onStart(request: ImageRequest) {
        startedAt = now()
    }

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        fetchStartedAt = now()
        waitNanos = fetchStartedAt - startedAt
    }

    override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
        fetchNanos = now() - fetchStartedAt
    }

    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        decodeStartedAt = now()
    }

    override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
        decodeNanos = now() - decodeStartedAt
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val source = result.dataSource
        if (source == DataSource.MEMORY_CACHE || source == DataSource.MEMORY) return
        val totalMs = (now() - startedAt) / 1_000_000
        if (source != DataSource.NETWORK && totalMs < SlowDiskLoadMs) return
        emit(
            "$source ${totalMs}ms ${timings()} " +
                "${result.image.width}x${result.image.height} ${describeData(request.data)}",
        )
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val totalMs = (now() - startedAt) / 1_000_000
        val error = result.throwable
        emit(
            "FAILED ${totalMs}ms ${timings()} ${describeData(request.data)} " +
                "error=${error.javaClass.simpleName}: ${error.message?.take(160)}",
        )
    }

    private fun timings(): String = buildString {
        append("wait=").append(msOrDash(waitNanos))
        append(" fetch=").append(msOrDash(fetchNanos))
        append(" decode=").append(msOrDash(decodeNanos))
    }

    private fun msOrDash(nanos: Long): String = if (nanos < 0) "-" else (nanos / 1_000_000).toString()

    class Factory : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener = ImageFetchTimingListener()
    }

    internal companion object {
        /** A disk read slower than this is worth a line; a normal one is a few milliseconds. */
        const val SlowDiskLoadMs = 100L

        private val SecretParam = Regex("([?&][^=&]*(?:key|token|secret|password)[^=&]*=)[^&#]*", RegexOption.IGNORE_CASE)

        /** Longer URLs (poster-service templates run to ~800 characters) are shortened. */
        private const val MaxLoggedUrlLength = 100

        /**
         * The request's data as a string, with credential-looking query values masked. A long URL
         * becomes host + last path segment + a short hash of the whole thing: the host is what
         * separates provider latency from ours, the hash still tells two requests apart, and the
         * full poster-service URLs were the biggest single emitter in nuvio.log.
         */
        fun describeData(data: Any): String {
            val masked = SecretParam.replace(data.toString(), "$1***")
            if (masked.length <= MaxLoggedUrlLength) return masked
            val afterScheme = masked.substringAfter("://", missingDelimiterValue = masked)
            val host = afterScheme.substringBefore('/')
            val lastSegment = afterScheme.substringBefore('?').substringAfterLast('/').take(40)
            val hash = (masked.hashCode().toLong() and 0xffffffffL).toString(16).padStart(8, '0')
            return "${masked.substringBefore("://")}://$host/…/$lastSegment #$hash"
        }
    }
}
