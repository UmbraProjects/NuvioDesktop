package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Ratings refreshes for many titles in one request.
 *
 * MDBList answers `POST /{provider}/{type}` with `{"ids": [...]}` for up to [MAX_IDS] titles at the
 * cost of one request (measured 2026-10-18: 200 titles in ~0.3 s, one title ~0.1 s). The answer is
 * the single lookup's minus `keywords`, `released_digital` and `next_episode`/`last_episode`, so it
 * only refreshes titles whose extras are already cached; see [MdbListMetadataService].
 *
 * Lookups arriving within [FLUSH_DELAY_MS] of each other for the same provider, type and key share
 * a request; the hero prefetch launches its lookups together, so a carousel becomes one call.
 * Unknown ids are left out of the answer, which comes back in no particular order, so titles are
 * matched by their `ids`. Any failure other than a rate limit turns bulk off for [DISABLE_MS] and
 * the caller falls back to the single lookup.
 */
internal object MdbListBulkRatings {
    const val MAX_IDS = 200
    const val FLUSH_DELAY_MS = 150L
    private const val DISABLE_MS = 60L * 60L * 1000L
    private const val CALL_TIMEOUT_MS = 30_000L

    /** Routes the bulk endpoint is known to answer (checked 2026-10-18). */
    val SUPPORTED_PROVIDERS = setOf(
        MdbListMetadataService.PROVIDER_IMDB,
        MdbListMetadataService.PROVIDER_TMDB,
        MdbListMetadataService.PROVIDER_MAL,
    )

    sealed interface Outcome {
        data class Found(val title: MdbListRatingsResponse) : Outcome
        /** Not in the answer: MDBList doesn't know the id under this route. */
        data object Missing : Outcome
        data object RateLimited : Outcome
        data object Failed : Outcome
    }

    private data class GroupKey(val provider: String, val mediaType: String, val apiKey: String)
    private class Waiter(val id: String, val result: CompletableDeferred<Outcome>)

    private val log = Logger.withTag("MdbListBulk")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val pending = mutableMapOf<GroupKey, MutableList<Waiter>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @kotlin.concurrent.Volatile
    private var disabledUntilMs = 0L

    /** The HTTP call; replaced in tests. */
    internal var transport: suspend (url: String, body: String) -> RawHttpResponse = { url, body ->
        httpRequestRaw(
            method = "POST",
            url = url,
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            body = body,
            // 200 shows with their season lists run past the default 1 MB cap.
            allowLargeResponse = true,
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
    }

    fun isAvailable(nowMs: Long = LibraryClock.nowEpochMs()): Boolean = nowMs >= disabledUntilMs

    fun supports(provider: String): Boolean = provider in SUPPORTED_PROVIDERS

    suspend fun fetch(provider: String, mediaType: String, id: String, apiKey: String): Outcome {
        val waiter = Waiter(id, CompletableDeferred())
        val key = GroupKey(provider, mediaType, apiKey)
        val full = mutex.withLock {
            val group = pending.getOrPut(key) {
                scope.launch {
                    delay(FLUSH_DELAY_MS)
                    mutex.withLock { pending.remove(key) }?.let { send(key, it) }
                }
                mutableListOf()
            }
            group += waiter
            if (group.size >= MAX_IDS) pending.remove(key) else null
        }
        if (full != null) scope.launch { send(key, full) }
        return waiter.result.await()
    }

    private suspend fun send(key: GroupKey, waiters: List<Waiter>) {
        val ids = waiters.map { it.id }.distinct()
        val body = JsonObject(mapOf("ids" to JsonArray(ids.map(::JsonPrimitive)))).toString()
        val url = "https://api.mdblist.com/${key.provider}/${key.mediaType}?apikey=${key.apiKey}"
        val startedMs = LibraryClock.nowEpochMs()
        val outcomes: Map<String, Outcome> = try {
            val response = transport(url, body)
            MdbListQuota.record(response.headers)
            when {
                response.status == 429 -> ids.associateWith { Outcome.RateLimited }
                response.status !in 200..299 -> fail(ids, "HTTP ${response.status}")
                else -> {
                    val titles = json.decodeFromString(ListSerializer(MdbListRatingsResponse.serializer()), response.body)
                    val byId = titles.mapNotNull { title -> title.idFor(key.provider)?.let { it to title } }.toMap()
                    ids.associateWith { id -> byId[id]?.let(Outcome::Found) ?: Outcome.Missing }
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(ids, error.message ?: error::class.simpleName.orEmpty())
        }
        val found = outcomes.values.count { it is Outcome.Found }
        log.i {
            "${key.provider}/${key.mediaType}: ${ids.size} ids, $found found in " +
                "${LibraryClock.nowEpochMs() - startedMs} ms (remaining ${MdbListQuota.remaining ?: "?"})"
        }
        waiters.forEach { it.result.complete(outcomes[it.id] ?: Outcome.Failed) }
    }

    private fun fail(ids: List<String>, reason: String): Map<String, Outcome> {
        disabledUntilMs = LibraryClock.nowEpochMs() + DISABLE_MS
        log.w { "Bulk request failed ($reason); single lookups for the next hour" }
        return ids.associateWith { Outcome.Failed }
    }

    private fun MdbListRatingsResponse.idFor(provider: String): String? {
        val ids = ids ?: return null
        val value = when (provider) {
            MdbListMetadataService.PROVIDER_IMDB -> ids.imdb
            MdbListMetadataService.PROVIDER_TMDB -> ids.tmdb
            MdbListMetadataService.PROVIDER_MAL -> ids.mal
            else -> null
        }
        return value?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
    }

    /** For tests. */
    internal fun resetForTest() {
        disabledUntilMs = 0L
    }
}

/** The key's daily request budget, from the `x-ratelimit-*` headers of the latest MDBList answer. */
internal object MdbListQuota {
    /** A key allowed more than this a day is a paid one. */
    private const val FREE_DAILY_LIMIT = 1_000

    @kotlin.concurrent.Volatile
    var dailyLimit: Int? = null
        private set

    @kotlin.concurrent.Volatile
    var remaining: Int? = null
        private set

    fun record(headers: Map<String, String>) {
        headers["x-ratelimit-limit"]?.trim()?.toIntOrNull()?.let { dailyLimit = it }
        headers["x-ratelimit-remaining"]?.trim()?.toIntOrNull()?.let { remaining = it }
    }

    /** [MdbListRefreshPreset.Auto]: the defaults on a free key, half the wait on a paid one. */
    fun autoScale(): Double = if ((dailyLimit ?: 0) > FREE_DAILY_LIMIT) 0.5 else 1.0

    internal fun setForTest(limit: Int?, left: Int?) {
        dailyLimit = limit
        remaining = left
    }
}
