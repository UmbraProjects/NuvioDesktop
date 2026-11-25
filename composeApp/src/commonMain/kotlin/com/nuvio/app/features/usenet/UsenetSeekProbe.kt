package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.util.Locale
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Times every wait during Usenet playback (a seek past the buffer, or a stall) and logs what the
 * engine did meanwhile, so a slow seek can be traced to the provider, the archive or the player:
 *
 *     UsenetSeek: seek 0:12:03 → 1:05:44 waited 2140 ms [balanced]:
 *       18 articles fetched (12.6 MB, 5.9 MB/s), 0 cache hits, 2 joined, 3 cancelled;
 *       provider 0: 18 articles, 12.6 MB, first byte ~190 ms recently
 *
 * The wait is the player's loading state (mpv seeking or paused for cache), read from the
 * snapshots the player screen already polls. Counters are read from the engine at the start and
 * end of the wait. Only counts and times are logged.
 */
object UsenetSeekProbe {
    private val log = Logger.withTag("UsenetSeek")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The open session asked for one body per connection (the usenet-pipeline-1 experiment). */
    @Volatile
    var pipelineOne: Boolean = false

    /** The open session ramps its read-ahead (the usenet-readahead-ramp experiment). */
    @Volatile
    var ramp: Boolean = false

    /** The open session never locks the article grid (the usenet-no-grid-lock comparison). */
    @Volatile
    var noGrid: Boolean = false

    /** The open session keeps a wider read-ahead in flight (the usenet-wide-window experiment). */
    @Volatile
    var wide: Boolean = false

    /**
     * The engine's counters taken during playback, refreshed every [BASELINE_EVERY_MS]: the
     * player's request before a seek. The snapshot taken when the wait is first seen came too
     * late to tell the seek's own request apart (it had usually started already).
     */
    private var baseline: Deferred<JsonObject?>? = null
    private var baselineAt = 0L
    private var rangeBefore: Deferred<JsonObject?>? = null

    private var url: String? = null
    private var playing = false
    private var waitStartedAt = 0L
    private var positionBefore = 0L
    private var bufferedBefore = 0L
    private var before: Deferred<JsonObject?>? = null

    /** Called with each player snapshot; does nothing for anything but a Usenet session. */
    fun onSnapshot(sourceUrl: String?, snapshot: PlayerPlaybackSnapshot) {
        if (sourceUrl == null || !UsenetEngine.isSessionUrl(sourceUrl)) return
        synchronized(this) {
            if (sourceUrl != url) {
                url = sourceUrl; playing = false; before = null; baseline = null; rangeBefore = null
            }
            if (!snapshot.isLoading) {
                val waitBefore = before
                if (playing && waitBefore != null) {
                    val waited = System.currentTimeMillis() - waitStartedAt
                    report(sourceUrl, waitBefore, rangeBefore, waited, positionBefore, bufferedBefore, snapshot.positionMs)
                }
                before = null
                rangeBefore = null
                playing = true
                positionBefore = snapshot.positionMs
                bufferedBefore = snapshot.bufferedPositionMs
                val now = System.currentTimeMillis()
                if (now - baselineAt >= BASELINE_EVERY_MS) {
                    baselineAt = now
                    baseline = scope.async { UsenetEngine.diagnostics(sourceUrl) }
                }
            } else if (playing && before == null) {
                // The first loading snapshot after playback: a seek or a stall begins.
                waitStartedAt = System.currentTimeMillis()
                before = scope.async { UsenetEngine.diagnostics(sourceUrl) }
                rangeBefore = baseline
            }
        }
    }

    private fun report(
        sourceUrl: String,
        start: Deferred<JsonObject?>,
        playingBefore: Deferred<JsonObject?>?,
        waitedMs: Long,
        fromMs: Long,
        bufferedMs: Long,
        toMs: Long,
    ) {
        if (waitedMs < MIN_REPORTED_MS) return
        scope.launch {
            val first = start.await()
            val last = UsenetEngine.diagnostics(sourceUrl)
            val rangeBaseline = playingBefore?.await() ?: first
            log.i { describe(waitedMs, fromMs, bufferedMs, toMs, UsenetEngine.runningProfile, first, last, rangeBaseline) }
        }
    }

    internal fun describe(
        waitedMs: Long,
        fromMs: Long,
        bufferedMs: Long,
        toMs: Long,
        profile: String?,
        first: JsonObject?,
        last: JsonObject?,
        /** Counters from before the wait began, for telling the seek's own request apart. */
        rangeBaseline: JsonObject? = first,
    ): String {
        val jump = toMs - fromMs
        // No inside/outside-buffer label: the player's buffered figure counts cache ranges kept
        // from earlier positions, so it called 17-minute jumps "inside". A logged wait is a fetch.
        val kind = if (jump > SEEK_THRESHOLD_MS || jump < -SEEK_THRESHOLD_MS) {
            "seek ${clock(fromMs)} → ${clock(toMs)}"
        } else {
            "stall at ${clock(toMs)}"
        }
        val drain = (if (usenetSeekDrain()) "" else ", no drain") + (if (pipelineOne) ", pipeline 1" else "") +
            (if (ramp) ", ramp" else "") + (if (noGrid) ", no grid" else "") + if (wide) ", wide" else ""
        val head = "$kind waited $waitedMs ms [${profile ?: "?"}$drain]"
        if (first == null || last == null) return "$head: engine counters unavailable"

        fun store(o: JsonObject, key: String) = o["store"]?.jsonObject?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
        val downloads = store(last, "downloads") - store(first, "downloads")
        val hits = store(last, "cacheHits") - store(first, "cacheHits")
        val joins = store(last, "sharedJoins") - store(first, "sharedJoins")
        val cancels = store(last, "cancellations") - store(first, "cancellations")
        val misses = store(last, "locateMisses") - store(first, "locateMisses")

        val firstProviders = providers(first)
        val providerLines = providers(last).map { (server, now) ->
            val then = firstProviders[server]
            val articles = now.articles - (then?.articles ?: 0)
            val bytes = now.bytes - (then?.bytes ?: 0)
            val errors = now.errors - (then?.errors ?: 0)
            val missing = now.missing - (then?.missing ?: 0)
            val connects = now.connects - (then?.connects ?: now.connects)
            val connectMs = now.connectMs - (then?.connectMs ?: now.connectMs)
            buildString {
                append("provider $server: $articles articles, ${mb(bytes)}")
                now.ttfbMs?.let { append(", first byte ~${it.toLong()} ms recently") }
                if (errors > 0) append(", $errors errors")
                if (missing > 0) append(", $missing missing")
                // A seek that closed sockets reopens them here, each a TCP + TLS + login.
                if (connects > 0) append(", $connects connections reopened (${connectMs.toLong()} ms connecting)")
            }
        }
        fun number(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        val archiveWait = number(last, "archiveWaitMs")?.let { it - (number(first, "archiveWaitMs") ?: 0.0) }
        val archive = archiveWait?.takeIf { it >= 1 }?.let { "; archive lookup ${it.toLong()} ms" }.orEmpty()
        val bytes = providers(last).values.sumOf { it.bytes } - firstProviders.values.sumOf { it.bytes }
        val rate = if (waitedMs > 0) " ${mb(bytes * 1000 / waitedMs)}/s" else ""
        return "$head: $downloads articles fetched (${mb(bytes)},$rate), $hits cache hits, $joins joined, " +
            "$cancels cancelled, $misses wrong article guesses; " + providerLines.joinToString("; ").ifEmpty { "no provider counts" } + archive +
            latestRange(rangeBaseline ?: first, last)
    }

    /**
     * The player's Range request opened during the wait: how long the engine took to its first
     * byte, and how much it had served when the wait was read. The rest of the wait is the player
     * reading and decoding up to the seek target.
     */
    private fun latestRange(first: JsonObject, last: JsonObject): String {
        val range = last["latestRange"] as? JsonObject ?: return ""
        fun num(o: JsonObject?, key: String) = o?.get(key)?.jsonPrimitive?.doubleOrNull
        val start = num(range, "startMs") ?: return ""
        // The same request as before the wait: no new range was opened (a stall, not a seek).
        if (start == num(first["latestRange"] as? JsonObject, "startMs")) return ""
        val firstByte = num(range, "firstByteMs")?.takeIf { it >= 0 }?.let { "${(it - start).toLong()} ms" } ?: "none yet"
        val fourMb = num(range, "fourMiBMs")?.takeIf { it >= 0 }?.let { ", first 4 MB at ${(it - start).toLong()} ms" }.orEmpty()
        val served = range["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
        return "; new range: engine first byte $firstByte$fourMb, ${mb(served)} served"
    }

    private class ProviderCount(
        val articles: Long,
        val bytes: Long,
        val errors: Long,
        val missing: Long,
        val ttfbMs: Double?,
        val connects: Long,
        val connectMs: Double,
    )

    private fun providers(o: JsonObject): Map<Long, ProviderCount> =
        (o["providers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val p = element as? JsonObject ?: return@mapNotNull null
            fun long(key: String) = p[key]?.jsonPrimitive?.longOrNull ?: 0L
            long("server") to ProviderCount(
                articles = long("articles"),
                bytes = long("bytes"),
                errors = long("errors"),
                missing = long("missing"),
                ttfbMs = p["ttfbMs"]?.jsonPrimitive?.doubleOrNull,
                connects = long("connects"),
                connectMs = p["connectMs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }.toMap()

    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    private fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /** Shorter waits are mpv's own seek inside its cache, not worth a line. */
    private const val MIN_REPORTED_MS = 300L
    // 5 s (was 1 s): still recent enough to distinguish a new seek range without polling
    // localhost diagnostics HTTP + JSON 60 times a minute during smooth playback.
    private const val BASELINE_EVERY_MS = 5_000L
    private const val SEEK_THRESHOLD_MS = 5_000L
}
