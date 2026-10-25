package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

private val log = Logger.withTag("SimklHttp")

/** SIMKL allows 1 POST/sec per token; a sustained overage gets the client_id throttled. */
private const val MIN_WRITE_SPACING_MS = 1_000L

/** Retry policy from SIMKL's API rules: double the wait, cap at 60s, give up after 5 attempts. */
private const val MAX_ATTEMPTS = 5
private const val MAX_BACKOFF_MS = 60_000L

/**
 * `400 {"error":"rate_limit"}` is a ~20s per-user write lock, not a quota: retry shortly. Matched
 * case-insensitively — the sync guide shows it lower-case, older notes here had it upper-case.
 */
private const val WRITE_LOCK_RETRY_MS = 2_000L
private const val WRITE_LOCK_MAX_RETRIES = 3

/**
 * Requests sent to SIMKL since launch, retries included — every one counts against the account's
 * daily quota. Logged per request so a run's real cost is in the log rather than estimated.
 */
private val requestCount = AtomicInteger(0)

private val writeMutex = Mutex()
private var lastWriteAtMs = 0L

/**
 * How long a finished public catalog read stays reusable. At startup the calendar loader and the
 * episode backfill ask for the same `/tv/episodes/{id}` and `/movies/{id}` URLs, and a load that is
 * cancelled and restarted (profile switch) re-asks for all of them; each repeat is a request on the
 * account's daily quota for bytes already in hand.
 */
private const val CATALOG_REUSE_MS = 60_000L

private class SharedCatalogRead(val response: CompletableDeferred<RawHttpResponse>) {
    @Volatile var finishedAtMs = 0L
}

private val catalogMutex = Mutex()
private val catalogReads = HashMap<String, SharedCatalogRead>()

/** SIMKL's answer when the client_id needs a user token on every call; see [simklRequest]. */
private const val USER_TOKEN_REQUIRED = "user_token_required"

/** Set once SIMKL has said this client_id needs the user's token even on public reads. */
@Volatile
private var clientIdRequiresUserToken = false

/** Owns the shared fetches, so one caller cancelling does not abort the others waiting on it. */
private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Every SIMKL API call goes through here, so SIMKL's rules live in one place:
 *
 * - **Auth.** [authenticated] calls carry the user's token (refreshed ahead of expiry). A 401
 *   triggers one refresh-and-retry; a call with no usable token returns a synthetic 401 without
 *   touching the network. Public catalog calls must pass `authenticated = false` — an
 *   Authorization header stops Cloudflare serving them from the edge.
 * - **Writes are serialised and spaced** at most one per second, which also keeps a scrobble
 *   stop/start pair in order.
 * - **Transient failures back off**: `429 rate_limit` and 500/502/503 retry with doubling waits.
 *   A daily-quota 429 (`user_limit_exceeded` / `app_limit_exceeded`) is returned as-is — it does
 *   not clear for hours, and retrying only spends more.
 *
 * Network exceptions propagate exactly as [httpRequestRaw]'s do. [url] must already carry the
 * required query parameters ([SimklAuthRepository.appendParams]).
 */
internal suspend fun simklRequest(
    method: String,
    url: String,
    body: String = "",
    authenticated: Boolean = true,
    followRedirects: Boolean = true,
    allowLargeResponse: Boolean = false,
): RawHttpResponse {
    val isWrite = !method.equals("GET", ignoreCase = true)
    return if (isWrite) {
        writeMutex.withLock {
            val wait = lastWriteAtMs + MIN_WRITE_SPACING_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                sendWithRetries(method, url, body, authenticated, followRedirects, allowLargeResponse, isWrite)
            } finally {
                lastWriteAtMs = System.currentTimeMillis()
            }
        }
    } else if (!authenticated && body.isEmpty() && !clientIdRequiresUserToken) {
        val response = sharedCatalogRead(url, method, followRedirects, allowLargeResponse)
        if (response.status == 401 && response.body.contains(USER_TOKEN_REQUIRED) && SimklAuthRepository.isAuthenticated.value) {
            // An AUTH V2 client_id refuses every request without the user's token, public catalog
            // reads included, so More Like This, the calendar and episode lookups all came back
            // empty. Sign them from now on; the edge caching they lose was never available.
            clientIdRequiresUserToken = true
            sendWithRetries(method, url, body, true, followRedirects, allowLargeResponse, isWrite)
        } else {
            response
        }
    } else if (!authenticated && clientIdRequiresUserToken && SimklAuthRepository.isAuthenticated.value) {
        sendWithRetries(method, url, body, true, followRedirects, allowLargeResponse, isWrite)
    } else {
        sendWithRetries(method, url, body, authenticated, followRedirects, allowLargeResponse, isWrite)
    }
}

/**
 * One request per URL per [CATALOG_REUSE_MS] for public catalog reads: callers that arrive while a
 * read is in flight, or shortly after it finished, share its answer. A failed or non-2xx read is
 * forgotten at once so the next caller retries it.
 */
private suspend fun sharedCatalogRead(
    url: String,
    method: String,
    followRedirects: Boolean,
    allowLargeResponse: Boolean,
): RawHttpResponse {
    val now = System.currentTimeMillis()
    var owner = false
    val read = catalogMutex.withLock {
        catalogReads.values.removeAll { it.finishedAtMs != 0L && now - it.finishedAtMs > CATALOG_REUSE_MS }
        catalogReads.getOrPut(url) {
            owner = true
            SharedCatalogRead(CompletableDeferred())
        }
    }
    if (owner) {
        catalogScope.async {
            try {
                read.response.complete(
                    sendWithRetries(method, url, "", false, followRedirects, allowLargeResponse, false),
                )
            } catch (failure: CancellationException) {
                read.response.cancel(failure)
            } catch (failure: Throwable) {
                read.response.completeExceptionally(failure)
            } finally {
                read.finishedAtMs = System.currentTimeMillis()
            }
        }
    }
    val result = runCatching { read.response.await() }
    if (result.getOrNull()?.status?.let { it in 200..299 } != true) {
        catalogMutex.withLock { if (catalogReads[url] === read) catalogReads.remove(url) }
    }
    return result.getOrThrow()
}

private suspend fun sendWithRetries(
    method: String,
    url: String,
    body: String,
    authenticated: Boolean,
    followRedirects: Boolean,
    allowLargeResponse: Boolean,
    isWrite: Boolean,
): RawHttpResponse {
    var backoffMs = 1_000L
    var attempt = 0
    var writeLockRetries = 0
    var refreshed = false
    while (true) {
        attempt++
        val headers = if (authenticated) {
            SimklAuthRepository.authorizedHeaders() ?: return unauthorizedResponse(url)
        } else {
            SimklAuthRepository.publicHeaders()
        }
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
            followRedirects = followRedirects,
            allowLargeResponse = allowLargeResponse,
        )
        val count = requestCount.incrementAndGet()
        log.i { "SIMKL request #$count: $method ${redactPath(url)} -> ${response.status}" }
        when {
            response.status == 401 && authenticated && !refreshed -> {
                refreshed = true
                val tokenUsed = headers["Authorization"]?.removePrefix("Bearer ")
                if (SimklAuthRepository.recoverFromUnauthorized(tokenUsed)) continue
                return response
            }
            response.status == 400 && isWrite && response.body.contains("\"rate_limit\"", ignoreCase = true) &&
                writeLockRetries < WRITE_LOCK_MAX_RETRIES -> {
                writeLockRetries++
                delay(WRITE_LOCK_RETRY_MS)
                continue
            }
            response.status == 429 && isDailyQuota(response.body) -> {
                log.w { "SIMKL daily quota exhausted (${response.headers["retry-after"] ?: "?"}s to reset): ${redact(url)}" }
                return response
            }
            response.status in RETRYABLE_STATUSES && attempt < MAX_ATTEMPTS -> {
                log.d { "SIMKL ${response.status} on ${redact(url)}, retrying in ${backoffMs}ms" }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            else -> return response
        }
    }
}

private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503)

private fun isDailyQuota(body: String): Boolean =
    body.contains("user_limit_exceeded") || body.contains("app_limit_exceeded")

private fun unauthorizedResponse(url: String) = RawHttpResponse(
    status = 401,
    statusText = "SIMKL not connected",
    url = url,
    body = "",
    headers = emptyMap(),
)

private fun redact(url: String): String = url.substringBefore('?')

/** [redact], plus the query parameters that say what kind of read it was. Never the client_id. */
private fun redactPath(url: String): String {
    val path = redact(url).removePrefix("https://api.simkl.com")
    val shown = url.substringAfter('?', "").split('&')
        .filter { it.substringBefore('=') in LOGGED_QUERY_PARAMS }
    return if (shown.isEmpty()) path else "$path?${shown.joinToString("&")}"
}

private val LOGGED_QUERY_PARAMS = setOf("date_from", "extended", "allow_rewatch")
