package com.nuvio.app.features.usenet

import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Settings > Usenet > Usenet info: what each indexer and provider has done since the counts were
 * last reset. Indexers are timed per API request and per search; NZB grabs and how the releases
 * turned out (played, missing articles, other failures) are credited to the indexer the NZB came
 * from. Provider counts are the engine's per-session diagnostics, added up as they grow. Only
 * counts and timings are kept, keyed by the provider's or indexer's id: no URLs, keys or titles.
 */

@Serializable
data class IndexerStats(
    /** API requests answered or failed (caps, search pages). */
    val requests: Long = 0,
    val requestMs: Long = 0,
    val slowestMs: Long = 0,
    val failedRequests: Long = 0,
    /** Times the indexer throttled or reported a spent quota. */
    val limits: Long = 0,
    /** Searches for a title, each of which can take several requests. */
    val searches: Long = 0,
    val searchMs: Long = 0,
    val failedSearches: Long = 0,
    val emptySearches: Long = 0,
    val releases: Long = 0,
    /** NZBs downloaded from this indexer (not ones read from the NZB cache). */
    val grabs: Long = 0,
    /** Its releases asked to play, and how they turned out. */
    val opens: Long = 0,
    val played: Long = 0,
    val incomplete: Long = 0,
    val failed: Long = 0,
    val lastUsed: Long = 0,
) {
    val averageRequestMs: Long? get() = if (requests > 0) requestMs / requests else null
    val averageSearchMs: Long? get() = if (searches > 0) searchMs / searches else null
    /** Share of its releases that were missing articles, of those asked to play. */
    val incompletePercent: Double? get() = percent(incomplete, opens)
    val failedRequestPercent: Double? get() = percent(failedRequests, requests)
}

@Serializable
data class ProviderStats(
    val articles: Long = 0,
    val bytes: Long = 0,
    /** Article requests the provider answered "no such article" (430). */
    val missing: Long = 0,
    /** Other failed article requests (timeouts, broken connections). */
    val errors: Long = 0,
    val firstByteMsTotal: Double = 0.0,
    val firstByteSamples: Long = 0,
    val connects: Long = 0,
    val connectMs: Double = 0.0,
    val sessions: Long = 0,
    val lastUsed: Long = 0,
) {
    val attempts: Long get() = articles + missing + errors
    val missingPercent: Double? get() = percent(missing, attempts)
    val errorPercent: Double? get() = percent(errors, attempts)
    val averageFirstByteMs: Long? get() = if (firstByteSamples > 0) (firstByteMsTotal / firstByteSamples).toLong() else null
    val averageConnectMs: Long? get() = if (connects > 0) (connectMs / connects).toLong() else null
}

@Serializable
data class UsenetStatsBook(
    val since: Long = 0,
    val indexers: Map<String, IndexerStats> = emptyMap(),
    val providers: Map<String, ProviderStats> = emptyMap(),
) {
    fun indexer(id: String, change: (IndexerStats) -> IndexerStats) =
        copy(indexers = indexers + (id to change(indexers[id] ?: IndexerStats())))

    fun provider(id: String, change: (ProviderStats) -> ProviderStats) =
        copy(providers = providers + (id to change(providers[id] ?: ProviderStats())))
}

/** One provider's counters in one engine session diagnostics answer. */
internal data class ProviderCounters(
    val articles: Long,
    val bytes: Long,
    val missing: Long,
    val errors: Long,
    val firstByteMs: Double?,
    val connects: Long,
    val connectMs: Double,
)

/** The engine's per-provider counters, by the provider's index in the session's server list. */
internal fun providerCounters(diagnostics: JsonObject?): Map<Int, ProviderCounters> =
    (diagnostics?.get("providers") as? JsonArray).orEmpty().mapNotNull { element ->
        val p = element as? JsonObject ?: return@mapNotNull null
        fun long(key: String) = runCatching { p[key]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
        fun double(key: String) = runCatching { p[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        val server = runCatching { p["server"]?.jsonPrimitive?.longOrNull }.getOrNull()?.toInt() ?: return@mapNotNull null
        server to ProviderCounters(
            articles = long("articles"),
            bytes = long("bytes"),
            missing = long("missing"),
            errors = long("errors"),
            firstByteMs = double("ttfbMs")?.takeIf { it > 0 },
            connects = long("connects"),
            connectMs = double("connectMs") ?: 0.0,
        )
    }.toMap()

/**
 * Adds what each provider did since [previous] (the same session's last answer, null for its
 * first) to [book]. Session counters start at zero, so a first answer counts whole; connections
 * are the engine's lifetime counts per host, so they count only from a session's second answer.
 */
internal fun UsenetStatsBook.addSession(
    providerIds: List<String>,
    previous: Map<Int, ProviderCounters>?,
    current: Map<Int, ProviderCounters>,
    now: Long,
): UsenetStatsBook {
    var book = this
    for ((server, counts) in current) {
        val id = providerIds.getOrNull(server) ?: continue
        val before = previous?.get(server)
        val articles = (counts.articles - (before?.articles ?: 0)).coerceAtLeast(0)
        val bytes = (counts.bytes - (before?.bytes ?: 0)).coerceAtLeast(0)
        val missing = (counts.missing - (before?.missing ?: 0)).coerceAtLeast(0)
        val errors = (counts.errors - (before?.errors ?: 0)).coerceAtLeast(0)
        val connects = if (before == null) 0 else (counts.connects - before.connects).coerceAtLeast(0)
        val connectMs = if (before == null || connects == 0L) 0.0 else (counts.connectMs - before.connectMs).coerceAtLeast(0.0)
        val firstByte = counts.firstByteMs?.takeIf { articles > 0 }
        val newSession = previous == null
        if (articles + missing + errors + connects == 0L && !newSession) continue
        book = book.provider(id) {
            it.copy(
                articles = it.articles + articles,
                bytes = it.bytes + bytes,
                missing = it.missing + missing,
                errors = it.errors + errors,
                firstByteMsTotal = it.firstByteMsTotal + (firstByte ?: 0.0),
                firstByteSamples = it.firstByteSamples + if (firstByte != null) 1 else 0,
                connects = it.connects + connects,
                connectMs = it.connectMs + connectMs,
                sessions = it.sessions + if (newSession) 1 else 0,
                lastUsed = now,
            )
        }
    }
    return book
}

/** How a release asked to play turned out, for its indexer's counts. */
enum class UsenetOpenOutcome { PLAYED, INCOMPLETE, FAILED }

/** The indexer a [nzbUrl] most likely came from: the one whose API host it shares. */
internal fun indexerByHost(nzbUrl: String, indexers: List<UsenetIndexer>): String? {
    val host = hostOf(nzbUrl) ?: return null
    return indexers.firstOrNull { hostOf(it.apiUrl)?.let { api -> sameSite(api, host) } == true }?.id
}

private fun hostOf(url: String): String? =
    runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * api.indexer.example and indexer.example are one site: the last two labels decide, three under a
 * country's second level (althub.co.za and ninjacentral.co.za are two sites, not "co.za").
 */
private fun sameSite(a: String, b: String): Boolean {
    fun site(host: String): String {
        val labels = host.split('.')
        val keep = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }
    return a == b || site(a) == site(b)
}

private val SECOND_LEVEL = setOf("co", "com", "net", "org", "ac", "gov", "edu", "or", "ne", "go")

internal fun percent(part: Long, whole: Long): Double? = if (whole > 0) part * 100.0 / whole else null

/** Usenet info's units: the same in every language, so formatted here rather than translated. */
object UsenetInfoFormat {
    const val NONE = "—"

    fun duration(ms: Long?): String = when {
        ms == null -> NONE
        ms < 1_000 -> "$ms ms"
        ms < 10_000 -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
        else -> "${ms / 1000} s"
    }

    fun percent(value: Double?): String = when {
        value == null -> NONE
        value == 0.0 -> "0%"
        value < 0.1 -> "<0.1%"
        value < 10 -> String.format(java.util.Locale.ROOT, "%.1f%%", value)
        else -> "${Math.round(value)}%"
    }

    fun bytes(value: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
        return if (unit == 0) "$value B" else String.format(java.util.Locale.ROOT, "%.1f %s", size, units[unit])
    }

    fun count(value: Long): String = String.format(java.util.Locale.ROOT, "%,d", value)
}

/** Fewer than this many searches (or plays) is too little to single an indexer out. */
internal const val USENET_INFO_MIN_SAMPLES = 3

/**
 * The enabled indexer slowest to search, with its average, when there are two or more to compare
 * and it is clearly slower (half again the next one): the one holding every search up while the
 * others wait for it.
 */
internal fun slowestIndexer(book: UsenetStatsBook, indexers: List<UsenetIndexer>): Pair<UsenetIndexer, Long>? {
    val timed = indexers.filter { it.enabled }.mapNotNull { indexer ->
        val stats = book.indexers[indexer.id]?.takeIf { it.searches >= USENET_INFO_MIN_SAMPLES } ?: return@mapNotNull null
        indexer to (stats.averageSearchMs ?: return@mapNotNull null)
    }.sortedByDescending { it.second }
    if (timed.size < 2) return null
    val (slowest, next) = timed[0] to timed[1]
    return slowest.takeIf { it.second >= next.second * 3 / 2 && it.second >= 1_000 }
}

/** The indexer whose releases most often lacked articles, when it is notably bad (a quarter or more). */
internal fun mostIncompleteIndexer(book: UsenetStatsBook, indexers: List<UsenetIndexer>): Pair<UsenetIndexer, Double>? =
    indexers.mapNotNull { indexer ->
        val stats = book.indexers[indexer.id]?.takeIf { it.opens >= USENET_INFO_MIN_SAMPLES } ?: return@mapNotNull null
        indexer to (stats.incompletePercent ?: return@mapNotNull null)
    }.maxByOrNull { it.second }?.takeIf { it.second >= 25.0 }

object UsenetStats {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(UsenetStatsBook())
    private var loaded = false
    private var saveJob: Job? = null
    /** NZB link -> indexer id, from the latest searches (bounded). */
    private val origins = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_ORIGINS
    }
    /** Session id -> provider ids in the order the engine was given them, and its last counters. */
    private val sessions = HashMap<String, Pair<List<String>, Map<Int, ProviderCounters>?>>()

    val book: StateFlow<UsenetStatsBook>
        get() {
            ensureLoaded()
            return state.asStateFlow()
        }

    private fun ensureLoaded() = synchronized(this) {
        if (loaded) return
        loaded = true
        val stored = runCatching { UsenetStorage.loadStats()?.let { json.decodeFromString(UsenetStatsBook.serializer(), it) } }.getOrNull()
        state.value = stored ?: UsenetStatsBook(since = now())
    }

    private fun change(transform: (UsenetStatsBook) -> UsenetStatsBook) {
        ensureLoaded()
        state.update(transform)
        synchronized(this) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                runCatching { UsenetStorage.saveStats(json.encodeToString(UsenetStatsBook.serializer(), state.value)) }
            }
        }
    }

    fun reset() {
        ensureLoaded()
        state.value = UsenetStatsBook(since = now())
        runCatching { UsenetStorage.saveStats(json.encodeToString(UsenetStatsBook.serializer(), state.value)) }
    }

    // --- indexers ---

    internal fun request(indexerId: String, elapsedMs: Long, ok: Boolean) = change { book ->
        book.indexer(indexerId) {
            it.copy(
                requests = it.requests + 1,
                requestMs = it.requestMs + elapsedMs,
                slowestMs = maxOf(it.slowestMs, elapsedMs),
                failedRequests = it.failedRequests + if (ok) 0 else 1,
                lastUsed = now(),
            )
        }
    }

    internal fun limited(indexerId: String) = change { book -> book.indexer(indexerId) { it.copy(limits = it.limits + 1) } }

    internal fun search(indexerId: String, elapsedMs: Long, releases: Int?) = change { book ->
        book.indexer(indexerId) {
            it.copy(
                searches = it.searches + 1,
                searchMs = it.searchMs + elapsedMs,
                failedSearches = it.failedSearches + if (releases == null) 1 else 0,
                emptySearches = it.emptySearches + if (releases == 0) 1 else 0,
                releases = it.releases + (releases ?: 0),
                lastUsed = now(),
            )
        }
    }

    /** Remembers which indexer each found NZB came from, for its grab and play counts. */
    internal fun found(releases: List<UsenetRelease>) = synchronized(origins) {
        releases.forEach { release -> if (release.indexerId.isNotEmpty()) origins[release.nzbUrl] = release.indexerId }
    }

    private fun origin(nzbUrl: String): String? =
        synchronized(origins) { origins[nzbUrl] } ?: indexerByHost(nzbUrl, UsenetSettingsRepository.sources.indexers)

    internal fun opened(nzbUrl: String, grabbed: Boolean, outcome: UsenetOpenOutcome) {
        val id = origin(nzbUrl) ?: return
        change { book ->
            book.indexer(id) {
                it.copy(
                    grabs = it.grabs + if (grabbed) 1 else 0,
                    opens = it.opens + 1,
                    played = it.played + if (outcome == UsenetOpenOutcome.PLAYED) 1 else 0,
                    incomplete = it.incomplete + if (outcome == UsenetOpenOutcome.INCOMPLETE) 1 else 0,
                    failed = it.failed + if (outcome == UsenetOpenOutcome.FAILED) 1 else 0,
                    lastUsed = now(),
                )
            }
        }
    }

    /** A release that opened and then failed while playing (missing articles further in, say). */
    internal fun failedWhilePlaying(nzbUrl: String, incomplete: Boolean) {
        val id = origin(nzbUrl) ?: return
        change { book ->
            book.indexer(id) {
                if (incomplete) it.copy(incomplete = it.incomplete + 1) else it.copy(failed = it.failed + 1)
            }
        }
    }

    // --- providers ---

    internal fun sessionOpened(sessionId: String, providerIds: List<String>) = synchronized(sessions) {
        sessions[sessionId] = providerIds to null
    }

    internal fun sessionCounters(sessionId: String, diagnostics: JsonObject?) {
        val current = providerCounters(diagnostics).takeIf { it.isNotEmpty() } ?: return
        // The failure watcher and the seek probe both read diagnostics, so answers can cross:
        // each is swapped in under the lock, and one older than the last is dropped.
        val (ids, previous) = synchronized(sessions) {
            val (ids, previous) = sessions[sessionId] ?: return
            if (ids.isEmpty() || (previous != null && progress(current) < progress(previous))) return
            sessions[sessionId] = ids to current
            ids to previous
        }
        change { it.addSession(ids, previous, current, now()) }
    }

    internal fun sessionClosed(sessionId: String) {
        synchronized(sessions) { sessions.remove(sessionId) }
    }

    private fun progress(counters: Map<Int, ProviderCounters>) = counters.values.sumOf { it.articles + it.missing + it.errors }

    private fun now() = System.currentTimeMillis()

    private const val SAVE_DELAY_MS = 3_000L
    private const val MAX_ORIGINS = 5_000
}
