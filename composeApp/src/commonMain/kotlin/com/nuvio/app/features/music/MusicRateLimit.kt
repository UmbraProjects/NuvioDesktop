package com.nuvio.app.features.music

import com.nuvio.app.features.streams.epochMs

/**
 * fork:music-ratelimit - YouTube Music answers a burst of requests from one address with Google's "Sorry..." page (HTTP 403) or a 429,
 * and keeps doing so for a while. Every request made during the block only lengthens it, so after the first refusal the requests
 * pause for [COOLDOWN_MS]: they fail at once instead, and a screen can say what is going on rather than "couldn't load".
 */
internal object MusicRateLimit {
    private const val COOLDOWN_MS = 45_000L
    private var blockedUntilMs = 0L

    /** A request was refused with [status]; [body] is what came back. Returns true when that was the rate limit. */
    fun noteRefusal(status: Int, body: String): Boolean {
        val limited = status == 429 || (status == 403 && body.contains("Sorry", ignoreCase = true))
        if (limited) blockedUntilMs = epochMs() + COOLDOWN_MS
        return limited
    }

    /** True while requests are paused. */
    fun isBlocked(now: Long = epochMs()): Boolean = now < blockedUntilMs

    /** Seconds until requests resume, or 0. */
    fun secondsLeft(now: Long = epochMs()): Int = ((blockedUntilMs - now + 999) / 1000).toInt().coerceAtLeast(0)
}
