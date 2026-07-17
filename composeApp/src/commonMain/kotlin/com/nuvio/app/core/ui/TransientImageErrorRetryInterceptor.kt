package com.nuvio.app.core.ui

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.IOException

/**
 * Retries image loads that fail transiently instead of leaving the poster/backdrop blank
 * until the composable is rebuilt: connection failures and timeouts (no response at all),
 * HTTP 408/429, and 5xx responses. TMDB throttles bursts on a ~10 second window, so the
 * backoff waits that window out before re-requesting. Permanent failures (404 for art that
 * doesn't exist, decode errors, bad request data) are returned immediately and never retried.
 *
 * While the backoff is pending the request stays in Coil's loading state, so scrolling the
 * image off screen cancels the wait like any other in-flight request.
 */
internal class TransientImageErrorRetryInterceptor(
    private val maxRetries: Int = 2,
    private val retryDelayMillis: Long = 10_000L,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var attempt = 0
        while (true) {
            // Depending on the engine path, failures surface either as a thrown exception or
            // as an ErrorResult — handle both so no transient failure slips through un-retried.
            val result = try {
                chain.proceed()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= maxRetries || !isTransient(error)) throw error
                null
            }
            if (result != null) {
                if (result !is ErrorResult) return result
                if (attempt >= maxRetries || !isTransient(result.throwable)) return result
            }
            attempt++
            delay(retryDelayMillis)
        }
    }

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is HttpException -> {
            val code = error.response.code
            code == 408 || code == 429 || code >= 500
        }
        // Covers connect/read timeouts, resets, and DNS failures — the "never got a
        // response" cases. Anything else (decode failures, null request data) is permanent.
        is IOException -> true
        else -> false
    }
}
