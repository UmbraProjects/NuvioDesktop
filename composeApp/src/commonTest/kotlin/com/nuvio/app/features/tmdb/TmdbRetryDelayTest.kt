package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbRetryDelayTest {

    @Test
    fun `backs off exponentially when the server sends no header`() {
        assertEquals(1_000L, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = null))
        assertEquals(2_000L, tmdbRetryDelayMs(attempt = 1, retryAfterHeader = null))
        assertEquals(4_000L, tmdbRetryDelayMs(attempt = 2, retryAfterHeader = null))
    }

    @Test
    fun `honours Retry-After in preference to the schedule`() {
        // The server knows its own budget: a 3s header on the first attempt waits 3s, not 1s.
        assertEquals(3_000L, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = "3"))
        // …and shortens as readily as it lengthens.
        assertEquals(2_000L, tmdbRetryDelayMs(attempt = 2, retryAfterHeader = "2"))
    }

    @Test
    fun `tolerates whitespace around the header value`() {
        assertEquals(3_000L, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = " 3 "))
    }

    @Test
    fun `never returns a zero wait, however the server asks`() {
        // `Retry-After: 0` against a limit that is still in force would be a hot retry loop.
        assertEquals(TMDB_MIN_BACKOFF_MS, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = "0"))
    }

    @Test
    fun `clamps an absurd header rather than wedging every TMDB request behind it`() {
        assertEquals(TMDB_MAX_COOLDOWN_MS, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = "86400"))
        // Overflow guard: seconds are clamped before the multiply, so this must not wrap negative
        // and land on the floor — the most extreme "wait longer" must not become the shortest wait.
        assertEquals(TMDB_MAX_COOLDOWN_MS, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = "${Long.MAX_VALUE}"))
    }

    @Test
    fun `falls back to the schedule when the header is unusable`() {
        // HTTP allows Retry-After as a date; we only parse the seconds form, so anything else
        // has to degrade to backoff rather than to no wait at all.
        assertEquals(1_000L, tmdbRetryDelayMs(attempt = 0, retryAfterHeader = "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(2_000L, tmdbRetryDelayMs(attempt = 1, retryAfterHeader = ""))
        assertEquals(2_000L, tmdbRetryDelayMs(attempt = 1, retryAfterHeader = "-5"))
    }

    @Test
    fun `stays bounded at high attempt counts`() {
        // The shift would overflow long before this if it were unguarded.
        for (attempt in 0..64) {
            val delay = tmdbRetryDelayMs(attempt, retryAfterHeader = null)
            assertTrue(
                delay in TMDB_MIN_BACKOFF_MS..TMDB_MAX_COOLDOWN_MS,
                "attempt $attempt produced $delay",
            )
        }
    }
}
