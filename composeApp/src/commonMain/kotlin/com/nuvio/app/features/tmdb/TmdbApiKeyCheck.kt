package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What TMDB made of a key, as shown under the key field. */
enum class TmdbApiKeyStatus {
    Checking,
    Valid,

    /** TMDB answered 401: a typo, a revoked key, or a key from somewhere else. */
    Rejected,

    /**
     * The key is TMDB's v4 "API Read Access Token" (a JWT), not the v3 "API Key". Every request
     * here sends the key as `?api_key=`, which only takes the v3 key, so a token fails every call
     * with 401 — and both sit on the same TMDB settings page, so pasting the wrong one is easy.
     */
    WrongKeyType,

    /** No answer from TMDB (offline, DNS, timeout, 5xx). Says nothing about the key itself. */
    Unreachable,
}

/**
 * Checks a TMDB key against TMDB itself, so a bad key is reported where it is entered instead of
 * surfacing later as empty collections, missing logos and a log full of 401s.
 *
 * The key is still saved whatever the answer: TMDB may simply be unreachable right now, and the
 * status line is there to tell the user, not to stop them.
 */
object TmdbApiKeyCheck {
    private val log = Logger.withTag("TmdbApiKeyCheck")
    private val mutex = Mutex()

    /** Definite answers only; [TmdbApiKeyStatus.Unreachable] is worth asking again. */
    private val results = mutableMapOf<String, TmdbApiKeyStatus>()

    suspend fun check(rawKey: String): TmdbApiKeyStatus? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        if (looksLikeReadAccessToken(key)) return TmdbApiKeyStatus.WrongKeyType
        mutex.withLock { results[key] }?.let { return it }

        val status = try {
            val response = httpRequestRaw(
                method = "GET",
                url = "https://api.themoviedb.org/3/configuration?api_key=$key",
                headers = mapOf("Accept" to "application/json"),
                body = "",
                callTimeoutMs = CHECK_TIMEOUT_MS,
            )
            when (response.status) {
                in 200..299 -> TmdbApiKeyStatus.Valid
                401 -> TmdbApiKeyStatus.Rejected
                else -> {
                    log.w { "TMDB key check got HTTP ${response.status}" }
                    TmdbApiKeyStatus.Unreachable
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "TMDB key check failed: ${error.message}" }
            TmdbApiKeyStatus.Unreachable
        }
        if (status != TmdbApiKeyStatus.Unreachable) {
            mutex.withLock { results[key] = status }
        }
        log.i { "TMDB key check: $status" }
        return status
    }

    /**
     * A v4 Read Access Token is a JWT: three dot-separated base64url parts, the first of which
     * always starts `eyJ` (`{"` encoded). A v3 key is 32 hex characters.
     */
    internal fun looksLikeReadAccessToken(key: String): Boolean =
        key.startsWith("eyJ") && key.count { it == '.' } == 2

    private const val CHECK_TIMEOUT_MS = 10_000L
}
