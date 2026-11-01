package com.nuvio.app.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/*
 * A host that answers HTTP 429 is telling the app to slow down. Until now every request in a burst went out anyway and
 * failed the same way: a day's log held 4,582 of them against one shared metadata host (about 190 a minute at the
 * worst), each written out with a stack trace, over half the log by size, and the rows and posters they were for never
 * came. This gate remembers when a host last said "slow down", makes later requests to it wait that long, tries a
 * request again after waiting, and lets only a couple through at once for a minute after the last 429, so the end of
 * a wait does not become a second burst. A host that never answers 429 sees no difference at all.
 */

/**
 * A non-2xx answer. It is an [IllegalStateException] with the same message as before, so every place that already
 * catches that keeps working; [status] and [retryAfterSeconds] (the answer's `Retry-After`, when it gave a number of
 * seconds) are what the gate reads.
 */
class HttpStatusException(
    val status: Int,
    val retryAfterSeconds: Long?,
    message: String,
) : IllegalStateException(message)

/** The seconds in a `Retry-After` header, or null when it is missing or a date (which is not used). */
internal fun parseRetryAfterSeconds(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

/** The host part of [url], lower case; the whole text when it has none. */
internal fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("://", url)
    return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').lowercase()
}

/** What the gate knows about each host, with the clock passed in so the rules can be tested. */
internal class HostCooldowns(
    private val clock: () -> Long,
    private val defaultWaitMs: Long = DEFAULT_WAIT_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val quietAfterMs: Long = SLOW_MODE_MS,
) {
    private class State(var until: Long = 0L, var lastLimited: Long = 0L, var streak: Int = 0)

    private val hosts = ConcurrentHashMap<String, State>()

    /** How long a request to [host] should wait before it goes out; 0 when the host has not asked for a pause. */
    fun waitMs(host: String): Long {
        val state = hosts[host] ?: return 0L
        return synchronized(state) { max(0L, state.until - clock()) }
    }

    /** Whether [host] said 429 recently enough that requests to it should go through one or two at a time. */
    fun slowMode(host: String): Boolean {
        val state = hosts[host] ?: return false
        return synchronized(state) { state.lastLimited > 0L && clock() - state.lastLimited < quietAfterMs }
    }

    /**
     * [host] answered 429. It is left alone for what it asked ([retryAfterSeconds]) or, when it did not say, a wait that
     * doubles with each 429 in a row (5 s, 10 s, 20 s, 30 s), never more than [maxWaitMs]. Many requests that fail
     * together count as one 429: a pause already running is not restarted by them.
     */
    fun rateLimited(host: String, retryAfterSeconds: Long?) {
        val state = hosts.getOrPut(host) { State() }
        synchronized(state) {
            val now = clock()
            state.lastLimited = now
            if (state.until > now) return
            state.streak += 1
            val asked = retryAfterSeconds?.let { it * 1_000L }
            val wait = asked ?: (defaultWaitMs shl min(state.streak - 1, 4))
            state.until = now + wait.coerceIn(500L, maxWaitMs)
        }
    }

    /** [host] answered without 429: the doubling starts over. */
    fun succeeded(host: String) {
        val state = hosts[host] ?: return
        synchronized(state) { if (state.until <= clock()) state.streak = 0 }
    }

    companion object {
        const val DEFAULT_WAIT_MS = 5_000L
        const val MAX_WAIT_MS = 30_000L
        const val SLOW_MODE_MS = 60_000L
    }
}

class RateLimitGate internal constructor(
    private val cooldowns: HostCooldowns,
    private val sleep: suspend (Long) -> Unit,
    private val attempts: Int,
    private val longestWaitMs: Long,
) {
    private val slowModePermits = ConcurrentHashMap<String, Semaphore>()

    /**
     * Runs [block], a request to [url]. It waits first if the host asked for a pause, and when the answer is a
     * [HttpStatusException] with status 429 it waits what the host asked and tries again, up to [attempts] times in all.
     * Any other failure, and a 429 that is still there on the last attempt, is thrown as it came.
     */
    suspend fun <T> run(url: String, block: suspend () -> T): T {
        val host = hostOf(url)
        var attempt = 1
        while (true) {
            val wait = cooldowns.waitMs(host)
            // A caller waits no longer than this for a pause; past it the request goes out and may be told to wait again.
            if (wait > 0L) sleep(min(wait, longestWaitMs))
            try {
                val result = if (cooldowns.slowMode(host)) {
                    slowModePermits.getOrPut(host) { Semaphore(SLOW_MODE_PERMITS) }.withPermit { block() }
                } else {
                    block()
                }
                cooldowns.succeeded(host)
                return result
            } catch (error: HttpStatusException) {
                if (error.status != HTTP_TOO_MANY_REQUESTS) throw error
                cooldowns.rateLimited(host, error.retryAfterSeconds)
                if (attempt >= attempts) throw error
                attempt += 1
            }
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val SLOW_MODE_PERMITS = 2

        /** The one the app uses: two retries, and no caller waits more than ten seconds for a pause. */
        val shared = RateLimitGate(
            cooldowns = HostCooldowns(clock = { System.currentTimeMillis() }),
            sleep = { delay(it) },
            attempts = 3,
            longestWaitMs = 10_000L,
        )
    }
}
