package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random

/*
 * fork:youtube-mode
 *
 * Tells YouTube what was watched in Nuvio, the way its own web player does, so a video played here
 * lands in the account's watch history, gets its red progress bar, resumes on other devices and
 * feeds the recommendations.
 *
 * A signed WEB `player` request hands out two stats URLs tied to the account and the chosen channel:
 * `videostatsPlaybackUrl`, pinged once when playback starts (that creates the history entry), and
 * `videostatsWatchtimeUrl`, pinged with each stretch watched (`st`..`et`, in seconds) and where the
 * playhead is (`cmt`). Pings go out every [WATCHTIME_INTERVAL_MS] while playing, on pause, on a seek
 * and when the player closes.
 */

internal const val YOUTUBE_META_TYPE = "youtube"
internal const val YOUTUBE_META_ID_PREFIX = "yt:"

private const val WATCHTIME_INTERVAL_MS = 30_000L

/** A jump further than this from where playback should be is a seek, not drift. */
private const val SEEK_TOLERANCE_MS = 5_000L

private const val STATS_URLS_WAIT_MS = 30_000L

data class YouTubeTrackingUrls(val playbackUrl: String, val watchtimeUrl: String)

/** The account's stats URLs for [videoId], acting as the brand channel [pageId]; null when YouTube gives none. */
internal expect suspend fun youTubePlaybackTracking(videoId: String, pageId: String?): YouTubeTrackingUrls?

/** One signed GET to a stats URL. True when YouTube took it. */
internal expect suspend fun youTubeStatsPing(url: String): Boolean

private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** The client playback nonce: 16 characters, one per playback, which ties its pings together. */
internal fun newYouTubeCpn(random: Random = Random.Default): String =
    buildString { repeat(16) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) } }

internal fun youTubePlaybackPingUrl(base: String, cpn: String): String =
    base + separator(base) + "ver=2&cpn=$cpn"

internal fun youTubeWatchtimePingUrl(
    base: String,
    cpn: String,
    segmentStartSec: Double,
    positionSec: Double,
    lengthSec: Double,
    playing: Boolean,
): String {
    val st = seconds(segmentStartSec)
    val et = seconds(positionSec)
    return base + separator(base) +
        "ver=2&cpn=$cpn&el=detailpage&cmt=$et&st=$st&et=$et&len=${seconds(lengthSec)}" +
        "&state=${if (playing) "playing" else "paused"}"
}

private fun separator(url: String) = if ('?' in url) "&" else "?"

/** Seconds with three decimals, as the web player sends them. */
private fun seconds(value: Double): String {
    val millis = (value.coerceAtLeast(0.0) * 1000).toLong()
    val whole = millis / 1000
    val fraction = (millis % 1000).toString().padStart(3, '0')
    return "$whole.$fraction"
}

/**
 * One playback of one video. Fed by the player ([onProgress]) and closed when it goes ([close]).
 * The calls are cheap and never wait: the requests run on the object's own scope, so the last ping
 * still goes out after the player screen has gone.
 */
internal class YouTubeWatchSession(
    private val videoId: String,
    private val pageId: String?,
    private val clock: () -> Long,
) {
    private val log = Logger.withTag("YouTubeWatchTime")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cpn = newYouTubeCpn()

    /** The stats URLs, once the `player` request has answered (null when it gave none). */
    private val urls = CompletableDeferred<YouTubeTrackingUrls?>()
    private var started = false
    private var closed = false
    private var segmentStartMs = 0L
    private var lastPositionMs = 0L
    private var lastSampleAt = 0L
    private var lastPingAt = 0L
    private var wasPlaying = false
    private var durationMs = 0L

    fun onProgress(positionMs: Long, durationMs: Long, playing: Boolean) {
        if (closed || positionMs < 0L) return
        if (durationMs > 0L) this.durationMs = durationMs
        val now = clock()
        if (!started) {
            if (!playing || positionMs <= 0L) return
            started = true
            segmentStartMs = positionMs
            lastPingAt = now
            scope.launch { begin() }
        } else {
            val expected = lastPositionMs + if (wasPlaying) now - lastSampleAt else 0L
            when {
                abs(positionMs - expected) > SEEK_TOLERANCE_MS -> {
                    // Close the stretch that ended where the seek left from, then start a new one.
                    report(segmentStartMs, lastPositionMs, playing = wasPlaying)
                    segmentStartMs = positionMs
                    lastPingAt = now
                }
                wasPlaying && !playing -> {
                    report(segmentStartMs, positionMs, playing = false)
                    segmentStartMs = positionMs
                    lastPingAt = now
                }
                playing && now - lastPingAt >= WATCHTIME_INTERVAL_MS -> {
                    report(segmentStartMs, positionMs, playing = true)
                    segmentStartMs = positionMs
                    lastPingAt = now
                }
                !wasPlaying && playing -> segmentStartMs = positionMs
            }
        }
        lastPositionMs = positionMs
        lastSampleAt = now
        wasPlaying = playing
    }

    /** The player is going; [positionMs] is the last position it showed. Returns the watched percentage. */
    fun close(positionMs: Long = lastPositionMs): Int? {
        if (closed) return null
        closed = true
        if (!started) return null
        report(segmentStartMs, positionMs, playing = false)
        val length = durationMs.takeIf { it > 0L } ?: return null
        return ((positionMs * 100) / length).toInt().coerceIn(1, 100)
    }

    private suspend fun begin() {
        val tracking = runCatching { youTubePlaybackTracking(videoId, pageId) }.getOrNull()
        urls.complete(tracking)
        if (tracking == null) {
            log.w { "$videoId: YouTube gave no stats URLs; this playback will not reach the watch history" }
            return
        }
        val ok = youTubeStatsPing(youTubePlaybackPingUrl(tracking.playbackUrl, cpn))
        log.i { "$videoId: playback reported to YouTube (ok=$ok)" }
    }

    private fun report(startMs: Long, endMs: Long, playing: Boolean) {
        if (endMs <= 0L) return
        val length = durationMs
        scope.launch {
            // The stats URLs may still be on their way for the first stretch.
            val tracking = withTimeoutOrNull(STATS_URLS_WAIT_MS) { urls.await() } ?: return@launch
            val url = youTubeWatchtimePingUrl(
                base = tracking.watchtimeUrl,
                cpn = cpn,
                segmentStartSec = startMs / 1000.0,
                positionSec = endMs / 1000.0,
                lengthSec = length / 1000.0,
                playing = playing,
            )
            if (!youTubeStatsPing(url)) log.w { "$videoId: watch time ping refused" }
        }
    }
}
