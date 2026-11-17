package com.nuvio.app.features.usenet

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/*
 * NuvioTV's NewznabClient (BuiltInUsenetService.kt), ported: the same paging, season-search reuse
 * and quota cooldowns. Changes: JDK HttpClient for OkHttp, and indexer state (caps, cooldowns,
 * problems, hits left) kept in memory for the session rather than in SharedPreferences, and no
 * usage statistics.
 */

class IndexerCooldownException(val until: Long) : IllegalStateException("Indexer limit reached")

/** Why the last request to an indexer failed. Categories only: raw errors could echo keys. */
enum class IndexerProblem { AUTH, UNREACHABLE, RESPONSE }

/** What settings show for an indexer: a limit pause, the last failure, and the hits and grabs left today. */
data class IndexerStatus(
    val pausedUntil: Long = 0,
    val problem: IndexerProblem? = null,
    val apiHitsLeft: Int? = null,
    val grabsLeft: Int? = null,
)

private data class IndexerState(
    val caps: NewznabCapabilities? = null,
    val capsFetchedAt: Long = 0,
    val blockedUntil: Long = 0,
    val problem: IndexerProblem? = null,
    val apiHitsLeft: Int? = null,
    val grabsLeft: Int? = null,
    val leftAt: Long = 0,
)

class NewznabClient(
    /** The User-Agent of the next request, null for the JDK's own; settings can change it at any time. */
    private val agent: () -> String? = { null },
) {
    // API keys are in query strings: a client of its own, never logged.
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val states = ConcurrentHashMap<String, IndexerState>()
    private val revision = MutableStateFlow(0)
    private val seasons = object : LinkedHashMap<String, Pair<Long, List<UsenetRelease>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<UsenetRelease>>>) =
            size > MAX_SEASONS
    }

    /** Increments whenever an indexer status may have changed. */
    val statusChanges: StateFlow<Int> = revision.asStateFlow()

    /** States are keyed by the endpoint and key, which identify the account behind the indexer. */
    private fun key(indexer: UsenetIndexer) = indexer.endpoint + "|" + indexer.apiKey

    private fun update(indexer: UsenetIndexer, change: (IndexerState) -> IndexerState) {
        states.compute(key(indexer)) { _, old -> change(old ?: IndexerState()) }
        revision.value += 1
    }

    /** Caps cost an API hit on most indexers and rarely change; [live] skips the cache and any cooldown. */
    suspend fun capabilities(indexer: UsenetIndexer, live: Boolean = false): NewznabCapabilities {
        val cached = states[key(indexer)]?.takeIf { it.caps != null }
        if (!live && cached != null && now() - cached.capsFetchedAt < CAPS_TTL_MS) return cached.caps!!
        val caps = try {
            request(indexer, NewznabProtocol.apiUrl(indexer, "caps").build(), live, parse = NewznabProtocol::capabilities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An expired copy still describes the indexer better than failing the search.
            if (!live && cached != null) return cached.caps!! else throw e
        }
        update(indexer) { it.copy(caps = caps, capsFetchedAt = now()) }
        return caps
    }

    /**
     * Reads up to [results] releases. An episode is searched on its own, and its season alongside:
     * the season search adds the packs, and the episode's releases an indexer filed without it.
     */
    suspend fun search(
        indexer: UsenetIndexer,
        request: UsenetSearchRequest,
        caps: NewznabCapabilities,
        results: Int = UsenetSourceConfiguration.DEFAULT_INDEXER_RESULTS,
    ): List<UsenetRelease> = coroutineScope {
        val season = async {
            try {
                Result.success(seasonReleases(indexer, request, caps, results).orEmpty()
                    .filter { NewznabProtocol.matches(it, request, idSearch = true, requireNumbering = true) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val url = NewznabProtocol.searchUrl(indexer, request, caps) ?: return@coroutineScope season.await().getOrThrow()
        val episode = try {
            pages(indexer, url, request, caps, results)
                .filter { NewznabProtocol.matches(it, request, NewznabProtocol.isIdSearch(url)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // When the season answered, a failed or paused episode search still shows its releases.
            return@coroutineScope season.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: throw e
        }
        (episode + season.await().getOrDefault(emptyList())).distinctBy { it.nzbUrl }
    }

    /** Checks the endpoint and the key: caps alone prove nothing, a one-result search uses the key. */
    suspend fun verify(indexer: UsenetIndexer) {
        capabilities(indexer, live = true)
        val latest = NewznabProtocol.apiUrl(indexer, "search").setQueryParameter("limit", "1").build()
        request(indexer, latest, ignoreCooldown = true, parse = NewznabProtocol::requireSearchAnswer)
    }

    fun status(indexer: UsenetIndexer): IndexerStatus = states[key(indexer)].let { state ->
        val fresh = state != null && now() - state.leftAt < LEFT_TTL_MS
        IndexerStatus(
            pausedUntil = state?.blockedUntil ?: 0,
            problem = state?.problem,
            apiHitsLeft = state?.apiHitsLeft?.takeIf { fresh },
            grabsLeft = state?.grabsLeft?.takeIf { fresh },
        )
    }

    private suspend fun seasonReleases(
        indexer: UsenetIndexer,
        request: UsenetSearchRequest,
        caps: NewznabCapabilities,
        results: Int,
    ): List<UsenetRelease>? {
        val url = NewznabProtocol.searchUrl(indexer, request, caps, wholeSeason = true) ?: return null
        val cacheKey = "$url#$results"
        synchronized(seasons) { seasons[cacheKey] }?.takeIf { now() - it.first < SEASON_TTL_MS }?.let { cached ->
            return cached.second.map { it.copy(indexerId = indexer.id, indexerName = indexer.name) }
        }
        val releases = try {
            pages(indexer, url, request, caps, results, wholeSeason = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexerCooldownException) {
            throw e
        } catch (_: Exception) {
            // Some indexers reject season-only searches; remember it so each episode does not retry.
            emptyList()
        }
        synchronized(seasons) { seasons[cacheKey] = now() to releases }
        return releases
    }

    /** Bounds API use and memory to [results]; never downloads NZBs during a search. */
    private suspend fun pages(
        indexer: UsenetIndexer,
        url: UsenetUrl,
        request: UsenetSearchRequest,
        caps: NewznabCapabilities,
        results: Int,
        wholeSeason: Boolean = false,
    ): List<UsenetRelease> {
        val limit = caps.limit.coerceIn(1, 100)
        var page = request(indexer, url) { NewznabProtocol.releases(it, indexer) }
        val releases = page.releases.toMutableList()
        var offset = limit
        while (offset < results && page.items > 0 && (page.total?.let { offset < it } ?: (page.items >= limit))) {
            page = try {
                NewznabProtocol.searchUrl(indexer, request, caps, offset, wholeSeason)?.let { next ->
                    request(indexer, next) { NewznabProtocol.releases(it, indexer) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // The pages read so far still answer the search.
            } ?: break
            releases += page.releases
            offset += limit
        }
        return releases
    }

    /** Skips indexers in a cooldown, and opens one when an indexer throttles or reports a spent quota. */
    private suspend fun <T> request(
        indexer: UsenetIndexer,
        url: UsenetUrl,
        ignoreCooldown: Boolean = false,
        parse: (String) -> T,
    ): T {
        val blockedUntil = states[key(indexer)]?.blockedUntil ?: 0
        if (!ignoreCooldown && blockedUntil > now()) throw IndexerCooldownException(blockedUntil)
        val response = try {
            get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            note(indexer, IndexerProblem.UNREACHABLE); throw e
        } catch (e: Exception) {
            note(indexer, IndexerProblem.RESPONSE); throw e
        }
        val retryAfter = retryAfterMs(response.headers.firstValue("Retry-After").orElse(null))
        if (response.statusCode() == 429) {
            throw cooldown(indexer, retryAfter?.coerceAtMost(MAX_THROTTLE_MS) ?: THROTTLE_MS)
        }
        val body = response.body()
        return try {
            if (response.statusCode() !in 200..299) {
                NewznabProtocol.throwIfKnownError(body)
                if (response.statusCode() == 401 || response.statusCode() == 403) throw NewznabAuthException()
                error("Indexer HTTP ${response.statusCode()}")
            }
            parse(body).also {
                val apiHitsLeft = left(response, API_LEFT_HEADERS)
                val grabsLeft = left(response, GRABS_LEFT_HEADERS)
                // Indexers announce a spent daily quota before they start refusing requests.
                if (apiHitsLeft == 0 || grabsLeft == 0) cooldown(indexer, EXHAUSTED_MS)
                if (apiHitsLeft != null || grabsLeft != null) {
                    update(indexer) { it.copy(problem = null, apiHitsLeft = apiHitsLeft, grabsLeft = grabsLeft, leftAt = now()) }
                } else {
                    note(indexer, null)
                }
            }
        } catch (_: NewznabQuotaException) {
            throw cooldown(indexer, retryAfter ?: QUOTA_MS)
        } catch (e: NewznabAuthException) {
            note(indexer, IndexerProblem.AUTH); throw e
        } catch (e: Exception) {
            note(indexer, IndexerProblem.RESPONSE); throw e
        }
    }

    private fun note(indexer: UsenetIndexer, problem: IndexerProblem?) {
        if (states[key(indexer)]?.problem != problem) update(indexer) { it.copy(problem = problem) }
    }

    private fun left(response: HttpResponse<String>, names: List<String>) =
        names.firstNotNullOfOrNull { response.headers().firstValue(it).orElse(null)?.trim()?.toIntOrNull() }?.coerceAtLeast(0)

    private val HttpResponse<String>.headers get() = headers()

    /** Extends, never shortens, the cooldown. */
    private fun cooldown(indexer: UsenetIndexer, durationMs: Long): IndexerCooldownException {
        update(indexer) { it.copy(blockedUntil = maxOf(it.blockedUntil, now() + durationMs)) }
        return IndexerCooldownException(states[key(indexer)]?.blockedUntil ?: (now() + durationMs))
    }

    private fun retryAfterMs(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return if (it > 0) it * 1000 else null }
        return runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun now() = System.currentTimeMillis()

    private suspend fun get(url: UsenetUrl): HttpResponse<String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url.toString()))
            .timeout(Duration.ofSeconds(25))
            .apply { agent()?.let { header(USER_AGENT, it) } }
            .header("Cache-Control", "no-store")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            throw IOException("Could not reach indexer")
        } catch (e: IOException) {
            throw IOException("Could not reach indexer") // Never the URL: it carries the key.
        }
        check(response.body().length <= MAX_XML_CHARS) { "Indexer response is too large" }
        response
    }

    private companion object {
        const val MAX_XML_CHARS = 4 * 1024 * 1024
        const val CAPS_TTL_MS = 7L * 24 * 3600 * 1000
        const val THROTTLE_MS = 60_000L
        const val MAX_THROTTLE_MS = 15 * 60_000L
        const val QUOTA_MS = 30 * 60_000L
        const val EXHAUSTED_MS = 15 * 60_000L
        const val SEASON_TTL_MS = 2 * 3600_000L
        const val MAX_SEASONS = 50
        const val LEFT_TTL_MS = 24 * 3600_000L
        val API_LEFT_HEADERS = listOf("X-RateLimit-Daily-Remaining", "x-api-remaining")
        val GRABS_LEFT_HEADERS = listOf("X-DNZBLimit-Daily-Remaining", "x-grab-remaining")
    }
}
