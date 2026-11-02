package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

/**
 * One signed GET to a stats URL, acting as the brand channel [pageId] like the request that handed
 * the URL out. The HTTP status, or 0 when no answer came.
 */
internal expect suspend fun youTubeStatsPing(url: String, pageId: String?): Int

private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** The client playback nonce: 16 characters, one per playback, which ties its pings together. */
internal fun newYouTubeCpn(random: Random = Random.Default): String =
    buildString { repeat(16) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) } }

internal fun youTubePlaybackPingUrl(base: String, cpn: String): String =
    base + separator(base) + "ver=2&cpn=$cpn"

/**
 * One watch-time ping: the stretch [segmentStartSec]..[positionSec] (media time), the playhead,
 * and [realTimeSec], the wall-clock seconds since playback began, which the web player sends as
 * `rt` and which is what sets media time against real time when the speed is not 1x. The stats
 * URL already carries `el` and `len`, and YouTube's `len` is the one kept: these were appended a
 * second time before, so YouTube may have read either copy.
 */
internal fun youTubeWatchtimePingUrl(
    base: String,
    cpn: String,
    segmentStartSec: Double,
    positionSec: Double,
    lengthSec: Double,
    playing: Boolean,
    realTimeSec: Double,
): String {
    val et = seconds(positionSec)
    return withYouTubeParams(
        base,
        listOfNotNull(
            "ver" to "2",
            "cpn" to cpn,
            "el" to "detailpage",
            "cmt" to et,
            "st" to seconds(segmentStartSec),
            "et" to et,
            ("len" to seconds(lengthSec)).takeIf { youTubeStatsLengthSec(base) == null && lengthSec > 0.0 },
            "rt" to seconds(realTimeSec),
            "state" to if (playing) "playing" else "paused",
        ),
    )
}

/**
 * The ping that marks a finished video fully watched, as yt-dlp's `--mark-watched` sends it: the
 * whole length as one stretch with the playhead at the end. Stretch-by-stretch pings alone left
 * finished videos at ~25% or ~70% on YouTube (Nov 2, played at 2x).
 */
internal fun youTubeFullWatchPingUrl(base: String, cpn: String, lengthSec: Double, realTimeSec: Double): String =
    youTubeWatchtimePingUrl(base, cpn, 0.0, lengthSec, lengthSec, playing = false, realTimeSec = realTimeSec)

/** Stopped within the last 5% (at most the last 15 s): an end card, or mpv stopping a hair short of the end. */
internal fun youTubeWatchFinished(positionMs: Long, lengthMs: Long): Boolean =
    lengthMs > 0L && positionMs >= lengthMs - minOf(15_000L, lengthMs / 20)

/** The video length YouTube put in a stats URL (`len`), in seconds. */
internal fun youTubeStatsLengthSec(url: String): Double? =
    url.substringAfter('?', "").split('&')
        .firstOrNull { it.substringBefore('=') == "len" }
        ?.substringAfter('=')?.toDoubleOrNull()?.takeIf { it > 0.0 }

/** [base] with [params] set: a name already in its query is replaced, the rest are appended in order. */
private fun withYouTubeParams(base: String, params: List<Pair<String, String>>): String {
    val names = params.mapTo(HashSet()) { it.first }
    val kept = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in names }
    return base.substringBefore('?') + "?" + (kept + params.map { (name, value) -> "$name=$value" }).joinToString("&")
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

    /** One pending ping; they go out one at a time, in order, so the last one is the last YouTube sees. */
    private class Ping(val startMs: Long, val endMs: Long, val playing: Boolean, val realTimeMs: Long, val last: Boolean, val finished: Boolean)

    private val pings = Channel<Ping>(Channel.UNLIMITED)
    private var startedAtMs = 0L

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
            startedAtMs = now
            segmentStartMs = positionMs
            lastPingAt = now
            scope.launch {
                begin()
                for (ping in pings) send(ping)
            }
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
        if (!started) {
            pings.close()
            return null
        }
        val length = durationMs.takeIf { it > 0L }
        val finished = length != null && youTubeWatchFinished(positionMs, length)
        report(segmentStartMs, positionMs, playing = false, last = true, finished = finished)
        pings.close()
        if (length == null) return null
        return if (finished) 100 else ((positionMs * 100) / length).toInt().coerceIn(1, 100)
    }

    private suspend fun begin() {
        val tracking = runCatching { youTubePlaybackTracking(videoId, pageId) }.getOrNull()
        urls.complete(tracking)
        if (tracking == null) {
            log.w { "$videoId: YouTube gave no stats URLs; this playback will not reach the watch history" }
            return
        }
        val status = youTubeStatsPing(youTubePlaybackPingUrl(tracking.playbackUrl, cpn), pageId)
        log.i {
            "$videoId: playback reported to YouTube (HTTP $status, as ${if (pageId != null) "brand channel" else "account profile"};" +
                " stats params ${queryNames(tracking.watchtimeUrl)})"
        }
    }

    private var pingsSent = 0
    private var pingsRefused = 0
    private var reportedSeconds = 0.0

    private fun report(startMs: Long, endMs: Long, playing: Boolean, last: Boolean = false, finished: Boolean = false) {
        if (endMs <= 0L && !last) return
        pings.trySend(Ping(startMs, endMs, playing, clock() - startedAtMs, last, finished))
    }

    /** Runs on the session's one sender coroutine, after [begin]. */
    private suspend fun send(ping: Ping) {
        // The stats URLs may still be on their way for the first stretch.
        val tracking = withTimeoutOrNull(STATS_URLS_WAIT_MS) { urls.await() } ?: return
        val lengthSec = youTubeStatsLengthSec(tracking.watchtimeUrl) ?: (durationMs / 1000.0)
        val realTimeSec = ping.realTimeMs / 1000.0
        val urlsToSend = buildList {
            if (ping.endMs > 0L) {
                add(
                    youTubeWatchtimePingUrl(
                        base = tracking.watchtimeUrl,
                        cpn = cpn,
                        segmentStartSec = ping.startMs / 1000.0,
                        positionSec = ping.endMs / 1000.0,
                        lengthSec = lengthSec,
                        playing = ping.playing,
                        realTimeSec = realTimeSec,
                    ),
                )
            }
            if (ping.finished && lengthSec > 0.0) add(youTubeFullWatchPingUrl(tracking.watchtimeUrl, cpn, lengthSec, realTimeSec))
        }
        for (url in urlsToSend) {
            val status = youTubeStatsPing(url, pageId)
            pingsSent++
            if (status !in 200..299) {
                pingsRefused++
                log.w { "$videoId: watch time ping refused (HTTP $status)" }
            }
        }
        reportedSeconds += (ping.endMs - ping.startMs).coerceAtLeast(0L) / 1000.0
        if (ping.last) {
            log.i {
                "$videoId: watch time reported: $pingsSent pings, $pingsRefused refused," +
                    " ${reportedSeconds.toInt()} s of ${lengthSec.toInt()} s, ended at ${ping.endMs / 1000} s" +
                    " after ${realTimeSec.toInt()} s${if (ping.finished) ", marked fully watched" else ""}"
            }
        }
    }

    /** The parameter names of a stats URL (never the values), for the log. */
    private fun queryNames(url: String): List<String> =
        url.substringAfter('?', "").split('&').map { it.substringBefore('=') }.filter { it.isNotEmpty() }.sorted()
}
