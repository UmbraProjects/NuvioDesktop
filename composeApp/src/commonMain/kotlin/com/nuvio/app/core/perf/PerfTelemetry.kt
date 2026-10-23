package com.nuvio.app.core.perf

import co.touchlab.kermit.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val netLog = Logger.withTag("PerfNet")
private val screenLog = Logger.withTag("PerfScreen")
private val storeLog = Logger.withTag("PerfStore")
private val workLog = Logger.withTag("PerfWork")

/** Same cadence as the `(Artwork)` summaries, so the two read side by side. */
private const val SummaryIntervalMs = 30_000L

/** A URL fetched again inside this long is counted as a duplicate, even across windows. */
private const val DuplicateWindowMs = 60_000L

/** A request slower than this is named individually rather than averaged away. */
private const val SlowRequestMs = 2_000L

private const val TopEndpoints = 8
private const val TopDuplicates = 6
private const val TopSlow = 5
private const val TopStoreKeys = 8
private const val TopWork = 20
private const val TopCallers = 4
private const val MaxDisplayLength = 140

/**
 * Optimisation-pass telemetry: what the app *did* (requests, disk writes, repository refreshes) and
 * on which screen, so "this ran twice" and "this fired on every slider tick" can be read off a log of
 * ordinary use rather than reasoned out of the source.
 *
 * Off unless asked for (a `perf-log` file in the Nuvio data directory, or `NUVIO_PERF_LOG`): every
 * `record*` call is a single volatile read while disabled. When on, events are counters behind one
 * monitor; a single daemon thread writes one summary per section every [SummaryIntervalMs] and on
 * every screen change, so each window belongs to exactly one screen. Sections with nothing in them
 * write nothing, so an idle app is silent.
 *
 * Tags: `(PerfScreen)` screen changes, `(PerfNet)` HTTP, `(PerfStore)` settings writes, `(PerfWork)`
 * repository refresh/sync/load entry points with the call sites that triggered them.
 *
 * URLs are never logged raw: credential-looking query values and opaque path segments (addon
 * configs carry debrid keys in the path) are masked before anything is formatted.
 */
object PerfTelemetry {
    @Volatile
    var enabled: Boolean = false
        private set

    private val lock = Any()
    private var executor: ScheduledExecutorService? = null
    private var screen = "startup"
    private var windowStartedAtMs = System.currentTimeMillis()

    private val inFlight = AtomicInteger(0)

    /** The screen the current telemetry window belongs to (lags a screen change by a hop). */
    val currentScreen: String
        get() = synchronized(lock) { screen }

    fun start(source: String) {
        synchronized(lock) {
            if (enabled) return
            windowStartedAtMs = System.currentTimeMillis()
            enabled = true
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "nuvio-perf-telemetry").apply { isDaemon = true }
            }
            scheduler.scheduleAtFixedRate(
                { runCatching { flush() } },
                SummaryIntervalMs,
                SummaryIntervalMs,
                TimeUnit.MILLISECONDS,
            )
            executor = scheduler
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { flush() } }, "nuvio-perf-telemetry-exit"))
        screenLog.i {
            "perf telemetry on ($source): PerfNet/PerfStore/PerfWork summaries every ${SummaryIntervalMs / 1000}s " +
                "and on each screen change"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Screens

    /**
     * Closes the current window (so its summary is attributed to the screen it happened on) and
     * opens one for [name]. Repeated marks of the same screen are ignored.
     */
    fun markScreen(name: String) {
        if (!enabled) return
        val previous: String
        val spentMs: Long
        synchronized(lock) {
            if (name == screen) return
            previous = screen
            spentMs = System.currentTimeMillis() - windowStartedAtMs
        }
        UiStallSampler.arm("screen $name")
        // Off the caller's thread: this is reached from composition.
        executor?.execute {
            flush()
            synchronized(lock) { screen = name }
            screenLog.i { "-> $name (from $previous, window ${spentMs / 1000.0}s)" }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Network

    private class EndpointStats {
        var count = 0
        var totalMs = 0L
        var maxMs = 0L
        var bytes = 0L
        var errors = 0
    }

    private class DuplicateStats(val display: String) {
        var count = 0
    }

    private var requests = 0
    private var requestMs = 0L
    private var requestBytes = 0L
    private var requestErrors = 0
    private var maxInFlight = 0
    private val clients = HashMap<String, Int>()
    private val endpoints = HashMap<String, EndpointStats>()
    private val errorKinds = HashMap<String, Int>()
    private val duplicates = HashMap<String, DuplicateStats>()
    private val slow = ArrayList<Pair<Long, String>>()

    /** Raw URL -> last time it was requested. In memory only; never logged. */
    private val recentUrls = LinkedHashMap<String, Long>()

    /** Returns a start stamp for [endHttp]; cheap and meaningless while disabled. */
    fun beginHttp(): Long {
        if (!enabled) return 0L
        val now = inFlight.incrementAndGet()
        synchronized(lock) { if (now > maxInFlight) maxInFlight = now }
        return System.nanoTime()
    }

    /**
     * [status] 0 when no response arrived; [bytes] negative when unknown. A call is an error when it
     * threw or answered 400+.
     */
    fun endHttp(
        startNanos: Long,
        client: String,
        method: String,
        url: String,
        status: Int,
        bytes: Long = -1L,
        failure: Throwable? = null,
    ) {
        if (!enabled || startNanos == 0L) return
        inFlight.decrementAndGet()
        recordHttp(client, method, url, status, (System.nanoTime() - startNanos) / 1_000_000, bytes, failure)
    }

    /** For callers that timed the request themselves (the image fetcher does). */
    fun recordHttp(
        client: String,
        method: String,
        url: String,
        status: Int,
        durationMs: Long,
        bytes: Long = -1L,
        failure: Throwable? = null,
    ) {
        if (!enabled) return
        val redacted = PerfUrls.redact(url)
        val verb = method.uppercase()
        val nowMs = System.currentTimeMillis()
        val isError = failure != null || status >= 400
        synchronized(lock) {
            requests++
            requestMs += durationMs
            if (bytes > 0) requestBytes += bytes
            clients[client] = (clients[client] ?: 0) + 1

            val endpoint = endpoints.getOrPut("$client $verb ${redacted.pattern}") { EndpointStats() }
            endpoint.count++
            endpoint.totalMs += durationMs
            if (durationMs > endpoint.maxMs) endpoint.maxMs = durationMs
            if (bytes > 0) endpoint.bytes += bytes

            if (isError) {
                requestErrors++
                endpoint.errors++
                val kind = failure?.let { it::class.simpleName ?: "Exception" } ?: status.toString()
                errorKinds[kind] = (errorKinds[kind] ?: 0) + 1
            }

            // Duplicates: the same exact URL fetched again inside the window. GET/HEAD only - a POST
            // is identified by its body (GraphQL, scrobbles), which none of the hooks can see, so
            // URL equality there is not repetition. Pruned from the head, which holds the oldest
            // entries because every hit is re-inserted at the tail.
            val iterator = recentUrls.entries.iterator()
            while (iterator.hasNext()) {
                if (nowMs - iterator.next().value > DuplicateWindowMs) iterator.remove() else break
            }
            if (verb == "GET" || verb == "HEAD") {
                val rawKey = "$client $verb $url"
                if (recentUrls.remove(rawKey) != null) {
                    duplicates.getOrPut(rawKey) { DuplicateStats("$client $verb ${redacted.display}") }.count++
                }
                recentUrls[rawKey] = nowMs
            }

            if (durationMs >= SlowRequestMs) {
                slow += durationMs to "$client $verb ${redacted.display}" + if (isError) " (failed)" else ""
                slow.sortByDescending { it.first }
                if (slow.size > TopSlow) slow.removeAt(slow.lastIndex)
            }
        }
    }

    /** Wraps a blocking request whose client has no listener hook (java.net.http). */
    inline fun <T> timedHttp(
        client: String,
        method: String,
        url: String,
        status: (T) -> Int,
        block: () -> T,
    ): T {
        if (!enabled) return block()
        val start = beginHttp()
        val result = try {
            block()
        } catch (error: Throwable) {
            endHttp(start, client, method, url, status = 0, failure = error)
            throw error
        }
        endHttp(start, client, method, url, status(result))
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Settings stores

    private class KeyWrites {
        var writes = 0
        var unchanged = 0
    }

    private class FlushStats {
        var count = 0
        var bytes = 0L
        var ms = 0L
    }

    private val storeWrites = HashMap<String, KeyWrites>()
    private val storeFlushes = HashMap<String, FlushStats>()

    /** [changed] false when the stored value was already the one being written. */
    fun recordStoreWrite(store: String, key: String, changed: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            val stats = storeWrites.getOrPut("$store/$key") { KeyWrites() }
            stats.writes++
            if (!changed) stats.unchanged++
        }
    }

    fun recordStoreFlush(store: String, bytes: Long, durationMs: Long) {
        if (!enabled) return
        synchronized(lock) {
            val stats = storeFlushes.getOrPut(store) { FlushStats() }
            stats.count++
            if (bytes > 0) stats.bytes += bytes
            stats.ms += durationMs
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Work

    private class WorkStats {
        var count = 0
        val callers = HashMap<String, Int>()
    }

    private val work = HashMap<String, WorkStats>()

    private val stackWalker: StackWalker by lazy { StackWalker.getInstance() }

    /**
     * Counts one run of a refresh/sync/load entry point, attributed to the first app frame *above*
     * the function that called this - the place that asked for the work. Two different callers
     * for the same work in one window is usually the finding.
     */
    fun count(name: String) {
        if (!enabled) return
        val caller = stackWalker.walk { frames ->
            frames
                .filter { !it.className.startsWith(PerfTelemetry::class.java.name) }
                .skip(1)
                .filter { it.className.startsWith("com.nuvio.") }
                .findFirst()
                .map { "${it.fileName ?: it.className.substringAfterLast('.')}:${it.lineNumber}" }
                .orElse("?")
        }
        synchronized(lock) {
            val stats = work.getOrPut(name) { WorkStats() }
            stats.count++
            stats.callers[caller] = (stats.callers[caller] ?: 0) + 1
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Summary

    fun flush() {
        if (!enabled) return
        val lines = ArrayList<Pair<Logger, String>>()
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            val prefix = "$screen ${(nowMs - windowStartedAtMs) / 1000}s:"
            windowStartedAtMs = nowMs
            collectNetwork(prefix, lines)
            collectStores(prefix, lines)
            collectWork(prefix, lines)
        }
        lines.forEach { (logger, line) -> logger.i { line } }
    }

    private fun collectNetwork(prefix: String, out: MutableList<Pair<Logger, String>>) {
        if (requests > 0) {
            val byClient = clients.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
            out += netLog to "$prefix requests=$requests ($byClient) time=${seconds(requestMs)} " +
                "bytes=${size(requestBytes)} errors=$requestErrors maxInFlight=$maxInFlight"
            endpoints.entries
                .sortedWith(compareByDescending<Map.Entry<String, EndpointStats>> { it.value.count }.thenByDescending { it.value.totalMs })
                .take(TopEndpoints)
                .forEach { (key, stats) ->
                    out += netLog to "  top ${stats.count}x $key avg=${stats.totalMs / stats.count}ms " +
                        "max=${stats.maxMs}ms" +
                        (if (stats.bytes > 0) " ${size(stats.bytes)}" else "") +
                        (if (stats.errors > 0) " errors=${stats.errors}" else "")
                }
            duplicates.values
                .sortedByDescending { it.count }
                .take(TopDuplicates)
                .forEach { out += netLog to "  dup +${it.count} ${it.display}" }
            if (duplicates.size > TopDuplicates) {
                out += netLog to "  dup ... ${duplicates.size - TopDuplicates} more URLs refetched " +
                    "(${duplicates.values.sumOf { it.count }} refetches total)"
            }
            slow.forEach { (ms, display) -> out += netLog to "  slow ${ms}ms $display" }
            if (errorKinds.isNotEmpty()) {
                out += netLog to "  errors " + errorKinds.entries.sortedByDescending { it.value }
                    .joinToString(" ") { "${it.key}x${it.value}" }
            }
        }
        requests = 0
        requestMs = 0
        requestBytes = 0
        requestErrors = 0
        maxInFlight = inFlight.get()
        clients.clear()
        endpoints.clear()
        errorKinds.clear()
        duplicates.clear()
        slow.clear()
    }

    private fun collectStores(prefix: String, out: MutableList<Pair<Logger, String>>) {
        if (storeWrites.isNotEmpty() || storeFlushes.isNotEmpty()) {
            val writes = storeWrites.values.sumOf { it.writes }
            val unchanged = storeWrites.values.sumOf { it.unchanged }
            val flushes = storeFlushes.values.sumOf { it.count }
            out += storeLog to "$prefix writes=$writes unchanged=$unchanged keys=${storeWrites.size} " +
                "flushes=$flushes (${size(storeFlushes.values.sumOf { it.bytes })}, " +
                "${storeFlushes.values.sumOf { it.ms }}ms)"
            storeWrites.entries
                .sortedByDescending { it.value.writes }
                .take(TopStoreKeys)
                .forEach { (key, stats) ->
                    out += storeLog to "  key ${stats.writes}x $key" +
                        if (stats.unchanged > 0) " (unchanged ${stats.unchanged})" else ""
                }
            storeFlushes.entries
                .sortedByDescending { it.value.count }
                .forEach { (store, stats) ->
                    out += storeLog to "  flush ${stats.count}x $store ${size(stats.bytes)} ${stats.ms}ms"
                }
        }
        storeWrites.clear()
        storeFlushes.clear()
    }

    private fun collectWork(prefix: String, out: MutableList<Pair<Logger, String>>) {
        if (work.isNotEmpty()) {
            out += workLog to "$prefix runs=${work.values.sumOf { it.count }} distinct=${work.size}"
            work.entries
                .sortedByDescending { it.value.count }
                .take(TopWork)
                .forEach { (name, stats) ->
                    val callers = stats.callers.entries.sortedByDescending { it.value }
                    val shown = callers.take(TopCallers).joinToString(", ") { "${it.key} x${it.value}" }
                    val more = if (callers.size > TopCallers) ", +${callers.size - TopCallers} more" else ""
                    out += workLog to "  ${stats.count}x $name [$shown$more]"
                }
        }
        work.clear()
    }

    private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}s"

    private fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)}MB"
        bytes >= 1024L -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}

/**
 * URL masking for the perf log. Two forms: [Redacted.display] keeps ids (so a duplicate names the
 * item that was refetched) and [Redacted.pattern] also generalises them (so 40 episode lookups
 * group as one endpoint). Neither ever carries a query value that looks like a credential, nor a
 * path segment that looks like an addon config or token.
 */
internal object PerfUrls {
    data class Redacted(val display: String, val pattern: String)

    private val SecretKey = Regex("key|token|secret|password|auth|session|sig|credential", RegexOption.IGNORE_CASE)
    private val Uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val Opaque = Regex("[A-Za-z0-9_-]{24,}")
    private val ImdbId = Regex("tt\\d+")
    private val Digits = Regex("\\d+")

    fun redact(url: String): Redacted {
        // Xtream (live TV) puts the login in the stream path, where no segment rule would catch it.
        val withoutFragment = com.nuvio.app.features.iptv.redactIptvCredentials(url.substringBefore('#'))
        val afterScheme = withoutFragment.substringAfter("://", missingDelimiterValue = withoutFragment)
        val hostAndPath = afterScheme.substringBefore('?')
        val query = afterScheme.substringAfter('?', missingDelimiterValue = "")
        val host = hostAndPath.substringBefore('/').substringAfterLast('@')
        val segments = hostAndPath.substringAfter('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotEmpty() }
            .map(::maskSegment)

        val displayPath = segments.joinToString("/")
        val patternPath = segments.joinToString("/") { generalise(it) }

        val params = query.split('&').filter { it.isNotEmpty() }
        val displayQuery = params.joinToString("&") { param ->
            val name = param.substringBefore('=')
            val value = param.substringAfter('=', missingDelimiterValue = "")
            when {
                !param.contains('=') -> name
                SecretKey.containsMatchIn(name) -> "$name=***"
                value.length > 32 -> "$name=${value.take(24)}..."
                else -> "$name=$value"
            }
        }
        val patternQuery = params.map { it.substringBefore('=') }.distinct().sorted().joinToString("&")

        val display = buildString {
            append(host).append('/').append(displayPath)
            if (displayQuery.isNotEmpty()) append('?').append(displayQuery)
        }
        val pattern = buildString {
            append(host).append('/').append(patternPath)
            if (patternQuery.isNotEmpty()) append('?').append(patternQuery)
        }
        return Redacted(
            display = if (display.length > MaxDisplayLength) display.take(MaxDisplayLength - 3) + "..." else display,
            pattern = if (pattern.length > MaxDisplayLength) pattern.take(MaxDisplayLength - 3) + "..." else pattern,
        )
    }

    /** Addon configs (`realdebrid=KEY`, base64 blobs, per-user UUIDs) live in the path. */
    private fun maskSegment(segment: String): String {
        if (segment.contains('=') || segment.contains('%') || segment.contains('|') || segment.contains(',')) return "{cfg}"
        if (segment.length > 48 || Uuid.matches(segment)) return "{cfg}"
        if (Opaque.matches(segment) && segment.any(Char::isDigit) && segment.any(Char::isLetter)) return "{cfg}"
        return segment
    }

    private fun generalise(segment: String): String =
        Digits.replace(ImdbId.replace(segment, "{tt}"), "{n}")
}
