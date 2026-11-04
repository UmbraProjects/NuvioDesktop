package com.nuvio.app.features.music

import com.nuvio.app.features.streams.epochMs
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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

/**
 * fork:youtube-caller - who a YouTube request is for, carried in the coroutine context so the extractor can name it in its logs
 * ("music", "hero trailer", ...) and decide whether it may go out while YouTube is refusing us. [urgent] is a lookup the person
 * is waiting on (a track they chose): it still tries once during a pause, but never retries or looks for replacements.
 * Everything else (background scans, trailer prefetch) waits the pause out.
 */
/** [preferHdr]: choose a video's HDR encode when it has one (the YouTube tab; trailers and music do not ask). */
/** [skipManifestLadder]: the adaptive pair is what plays, so the HLS ladder is not fetched just to measure it (the YouTube tab). */
/** [parallelClients]: ask the player clients (and read the HLS ladders) at once; only the YouTube tab does. Everything else asks one after another so a refusal can stop the rest. */
/** [maxShortSide]: the sharpest encode whose short side is at most this (the YouTube tab's max quality); null picks as trailers do. */
internal class YouTubeCaller(
    val label: String,
    val urgent: Boolean = false,
    val preferHdr: Boolean = false,
    val skipManifestLadder: Boolean = false,
    val parallelClients: Boolean = false,
    val maxShortSide: Int? = null,
    /** fork:youtube-languages - also the video's other language tracks (dubs) beside the original. */
    val allAudioTracks: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<YouTubeCaller>
}

internal suspend fun youTubeCaller(): YouTubeCaller? = coroutineContext[YouTubeCaller]

/** The label the logs use for the current request's caller. */
internal suspend fun youTubeCallerLabel(): String = youTubeCaller()?.label ?: "other"

/** Whether a request may go to YouTube now: always when there is no pause, during one only for a lookup someone waits on. */
internal fun mayAskYouTube(paused: Boolean, urgent: Boolean): Boolean = !paused || urgent

/** How many player clients one lookup tries: all of them normally, only the first while YouTube is refusing us. */
internal fun playerClientsToTry(paused: Boolean, total: Int): Int = if (paused) minOf(1, total) else total
