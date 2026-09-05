package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString

/**
 * Which queue a TMDB request waits in.
 *
 * One shared queue was the first design, and it produced the failure it was meant to prevent from
 * the other direction: a cold Discover open puts several hundred hero-enrichment requests in front
 * of the row build, and the build — whose requests are indistinguishable from any other — spent 15s
 * waiting for permits rather than for TMDB. Splitting the queue means a burst in one lane can no
 * longer starve the other, which merely widening one queue would not have fixed: the row build
 * would still have been at the back of it.
 *
 * [Interactive] is the default because it is the one a person is watching — hero art, the details
 * screen, anything filling a surface already on screen. [Background] is work whose latency nobody
 * is timing to the second: the Discover build and its prune pass.
 */
internal enum class TmdbRequestLane { Interactive, Background }

/**
 * Carries the lane down the call chain.
 *
 * A context element rather than a parameter, because the lane is a property of *why* the request is
 * being made and the services in between neither know nor should know: `TmdbService.fetchDiscover`
 * is the same call whether Discover's cold build or a settings preview asked for it. Threading a
 * parameter would mean touching every signature between the caller and the wire to carry something
 * none of them use.
 */
internal class TmdbLane(val lane: TmdbRequestLane) : AbstractCoroutineContextElement(TmdbLane) {
    companion object Key : CoroutineContext.Key<TmdbLane>

    override fun toString(): String = "TmdbLane($lane)"
}

/** Ready-made element, so callers write `scope.launch(TmdbBackgroundLane)` and nothing else. */
internal val TmdbBackgroundLane = TmdbLane(TmdbRequestLane.Background)

/**
 * The single door every TMDB request in the app goes through.
 *
 * TMDB answers 429 under load and the app had no handling for it at all: the request simply failed
 * and the caller silently got nothing. That is not a visible error anywhere — it shows up as a
 * missing hero logo, a blank Continue Watching thumbnail, or a Discover row that came back thin —
 * which is why 74 of them sat in one log without anyone noticing.
 *
 * Three mechanisms, and they answer different problems:
 *
 *  - **[gateFor] caps concurrency, per lane.** Bursts are what draw the limit; the app's fan-outs
 *    (the prune pass at 8, seed resolution at 6, plus whatever the hero and CW are doing at the
 *    same moment) can otherwise coincide into something much wider than any one of them intended.
 *    See [TmdbRequestLane] for why there are two queues rather than one.
 *  - **[retryAfterEpochMs] is a shared cool-down.** Per-request backoff alone is not enough: a
 *    dozen coroutines that each independently discover the limit and each independently retry are
 *    still a burst. One request hitting a 429 parks *all* of them, in both lanes, which is the
 *    behaviour the server is asking for.
 *  - **[inFlight] is single-flight by URL.** The hero pipeline enriches each item down two paths at
 *    once, so a cold start asked TMDB for the same six endpoints per title twice over. Identical
 *    concurrent requests now share one trip.
 *
 * Deliberately TMDB-only. `httpGetText` is shared with addon traffic, and an addon that is slow or
 * rate-limited has nothing to do with TMDB's budget — throttling both through one gate would let
 * either starve the other.
 */
internal object TmdbHttp {
    private val log = Logger.withTag("TmdbHttp")

    // App-lifetime, and deliberately not the caller's scope — see [getText] for what that buys.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val interactiveGate = Semaphore(INTERACTIVE_CONCURRENCY)
    private val backgroundGate = Semaphore(BACKGROUND_CONCURRENCY)
    private val cooldownMutex = Mutex()
    private var retryAfterEpochMs = 0L

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<String>>()

    /**
     * Fetches [url], honouring TMDB's rate limit.
     *
     * Throws on a non-2xx that is not a retryable 429, and on an empty body — matching what
     * `httpGetText` did before, because every caller wraps this in `runCatching` and logs the
     * message.
     *
     * **The request runs in [scope], not in the caller's coroutine.** Callers put a budget around
     * this — hero enrichment allows its whole six-endpoint fan-out 5s — and that budget was being
     * spent queueing rather than waiting on TMDB. When it expired the work was discarded and
     * re-issued by the next dispatch, so the queue was fed faster than it drained and requests
     * timed out on the wait alone. A caller giving up now abandons only its own await: the trip
     * finishes, and whoever asks next joins it instead of starting another.
     *
     * The cost is that an abandoned request still holds its permit to completion. It already did —
     * `httpRequestRaw` blocks in OkHttp, which cancellation cannot interrupt — so this trades
     * nothing away, and the sharing removes far more traffic than the abandonment leaves behind.
     */
    suspend fun getText(url: String): String {
        // Read here, in the caller's context: the shared job runs in [scope] and would otherwise
        // see no lane at all. First caller wins for a URL two lanes want at once, which is the
        // right way round — it is already queued, and the second caller wanted the answer, not the
        // queue position.
        val lane = currentCoroutineContext()[TmdbLane]?.lane ?: TmdbRequestLane.Interactive
        val request = inFlightMutex.withLock {
            inFlight.getOrPut(url) {
                scope.async {
                    try {
                        fetchThroughGate(url, lane)
                    } finally {
                        // A failed request must not leave its Deferred behind for later callers to
                        // await forever, so the removal has to survive cancellation too.
                        withContext(NonCancellable) {
                            inFlightMutex.withLock { inFlight.remove(url) }
                        }
                    }
                }
            }
        }
        return request.await()
    }

    private suspend fun fetchThroughGate(url: String, lane: TmdbRequestLane): String {
        val gate = gateFor(lane)
        var attempt = 0
        while (true) {
            awaitCooldown()
            val queuedAt = WatchProgressClock.nowEpochMs()
            val response = gate.withPermit {
                logQueueWait(lane, gate, WatchProgressClock.nowEpochMs() - queuedAt, url)
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf("Accept" to "application/json"),
                    body = "",
                    // TMDB is a trusted first-party endpoint and the helper this replaced read
                    // bodies uncapped, so capping here would be a new failure mode on large
                    // season payloads rather than a safety win.
                    allowLargeResponse = true,
                )
            }

            if (response.status == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
                val waitMs = tmdbRetryDelayMs(attempt, response.headers["retry-after"])
                noteRateLimited(waitMs)
                attempt++
                log.d { "429 from TMDB; holding all requests ${waitMs}ms (attempt $attempt/$MAX_RETRIES)" }
                continue
            }
            if (response.status == HTTP_TOO_MANY_REQUESTS) {
                log.w { "TMDB still rate-limiting after $MAX_RETRIES retries: ${redactApiKey(url)}" }
            }

            if (response.status !in 200..299) {
                error(getString(Res.string.network_request_failed_http, response.status))
            }
            if (response.body.isBlank()) {
                throw IllegalStateException(getString(Res.string.network_empty_response_body))
            }
            return response.body
        }
    }

    private fun gateFor(lane: TmdbRequestLane): Semaphore = when (lane) {
        TmdbRequestLane.Interactive -> interactiveGate
        TmdbRequestLane.Background -> backgroundGate
    }

    /**
     * Says that the gate, not the network, is what a request spent its time on.
     *
     * Without this the 15s stall that prompted the lane split left no trace of its own: the only
     * symptom was the *callers* reporting timeouts, which reads as a slow network and sends the
     * investigation off to look at TMDB's response times. The 429 path was the only thing in here
     * that logged, so a full queue looked exactly like nothing happening.
     */
    private fun logQueueWait(lane: TmdbRequestLane, gate: Semaphore, waitedMs: Long, url: String) {
        if (waitedMs < GATE_WAIT_LOG_THRESHOLD_MS) return
        log.d {
            "Queued ${waitedMs}ms for a $lane permit (${gate.availablePermits} free): ${redactApiKey(url)}"
        }
    }

    /**
     * Blocks until any cool-down a *different* request set has expired.
     *
     * Loops rather than sleeping once because the deadline can be pushed further out while this
     * caller is already waiting on it.
     */
    private suspend fun awaitCooldown() {
        while (true) {
            val remaining = cooldownMutex.withLock { retryAfterEpochMs - WatchProgressClock.nowEpochMs() }
            if (remaining <= 0) return
            delay(remaining.coerceAtMost(TMDB_MAX_COOLDOWN_MS))
        }
    }

    /** Never shortens an existing cool-down — the longest answer TMDB gave is the one to respect. */
    private suspend fun noteRateLimited(waitMs: Long) {
        cooldownMutex.withLock {
            retryAfterEpochMs = maxOf(retryAfterEpochMs, WatchProgressClock.nowEpochMs() + waitMs)
        }
    }

    private const val HTTP_TOO_MANY_REQUESTS = 429

    private const val MAX_RETRIES = 3
}

/**
 * The user-facing lane. Sized above the widest deliberate fan-out on this side — the hero's
 * six-endpoint enrichment, several titles at once — so no feature is throttled by its own
 * concurrency, only by coinciding with others.
 */
internal const val INTERACTIVE_CONCURRENCY = 12

/**
 * The background lane. Sized to the widest deliberate fan-out that runs in it, the prune pass at 8,
 * for the same reason.
 *
 * The two lanes together allow more concurrency than the single queue did, which is a deliberate
 * trade: the burst this was originally sized against did not produce a single 429 in the run that
 * prompted the split, and the shared cool-down still catches it if one appears.
 */
internal const val BACKGROUND_CONCURRENCY = 8

/** Long enough that ordinary contention stays quiet, short enough to see a queue forming. */
internal const val GATE_WAIT_LOG_THRESHOLD_MS = 250L

/** Ceiling on any single rate-limit wait, whether the server asked for it or backoff produced it. */
internal const val TMDB_MAX_COOLDOWN_MS = 10_000L

/** Floor, and the first step of the exponential backoff. */
internal const val TMDB_MIN_BACKOFF_MS = 1_000L

/**
 * A TMDB URL with the API key taken out, for logging.
 *
 * The key rides in the query string of every request here, and these lines land in `nuvio.log`,
 * which is the file users attach to bug reports.
 */
internal fun redactApiKey(url: String): String =
    API_KEY_PARAM.replace(url) { match -> "${match.groupValues[1]}api_key=***" }

private val API_KEY_PARAM = Regex("([?&])api_key=[^&]*")

/**
 * How long to wait before retrying a 429, given the attempt number and TMDB's `Retry-After`.
 *
 * The header wins when it is present and sane — the server knows its own budget better than any
 * schedule here does — and is clamped in both directions: it is server-controlled, so an absurd
 * value would otherwise wedge every TMDB request in the app behind it, and TMDB has been known to
 * answer `Retry-After: 0`, which would turn the retry into a hot loop against a limit that is
 * still in force.
 *
 * Without a usable header the wait is exponential from [TMDB_MIN_BACKOFF_MS]: 1s, 2s, 4s.
 */
internal fun tmdbRetryDelayMs(attempt: Int, retryAfterHeader: String?): Long {
    val fromHeader = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    if (fromHeader != null) {
        // Clamped to seconds *before* the multiply: the header is a string off the wire, and
        // `Long.MAX_VALUE * 1000` overflows to a negative that then clamps to the floor — the
        // shortest wait, from the most extreme request to wait longer.
        val seconds = fromHeader.coerceAtMost(TMDB_MAX_COOLDOWN_MS / 1_000)
        return (seconds * 1_000).coerceIn(TMDB_MIN_BACKOFF_MS, TMDB_MAX_COOLDOWN_MS)
    }
    val steps = attempt.coerceIn(0, 16)
    return (TMDB_MIN_BACKOFF_MS shl steps).coerceAtMost(TMDB_MAX_COOLDOWN_MS)
}
