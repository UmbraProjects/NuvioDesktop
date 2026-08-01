package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Timeline of a single playback start: from the stream scrape kicking off, through addon
 * responses and auto-select, to the native player attach and the first rendered frame.
 *
 * Marks are logged individually as they happen and [complete] emits a one-line summary, so a
 * single log line answers "where did the time go" for any playback (selection vs player mount
 * vs mpv open/buffer). A new trace begins on each [StreamsRepository.loadSources]-style scrape;
 * a [mark] arriving after the previous trace completed (e.g. an in-player episode switch that
 * never goes through the streams screen) implicitly starts a fresh trace at that mark.
 *
 * Diagnostics only: writers race benignly (an entry can be lost under concurrent marks) rather
 * than taking a lock on playback-critical paths.
 */
object PlaybackStartTrace {
    private val log = Logger.withTag("PlaybackStartTrace")
    private val timeSource = TimeSource.Monotonic

    /** How long a [beginPending] anchor stays adoptable by the [begin] that follows it. */
    private const val PendingWindowMillis = 30_000L

    @Volatile
    private var origin: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var entries: List<String> = emptyList()

    @Volatile
    private var completed = true

    @Volatile
    private var pendingOrigin: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var pendingEntries: List<String> = emptyList()

    /**
     * Anchors the next [begin] at *this* moment instead of at the scrape.
     *
     * The stream scrape is not the start of a playback from the user's point of view — the click
     * is. Everything between the two (navigation, the streams screen's first composition, its
     * first rendered frame) is dead time the old trace could not see, which is exactly where a
     * "sat on the previous page for a few seconds" stall lives. Callers on the click path record
     * that intent here; the [begin] that follows within [PendingWindowMillis] adopts this origin
     * so the summary spans click → first frame. A pending anchor that never gets a [begin]
     * (navigation cancelled, cached link reused) simply expires.
     */
    fun beginPending(reason: String) {
        pendingOrigin = timeSource.markNow()
        pendingEntries = listOf("$reason +0ms")
        log.i { "beginPending: $reason" }
    }

    /**
     * Records a mark against the pending anchor before the trace proper has begun. No-ops when no
     * anchor is live, so pre-scrape marks never start a trace of their own.
     */
    fun markPending(label: String) {
        val start = pendingOrigin ?: return
        val line = "$label +${start.elapsedNow().inWholeMilliseconds}ms"
        pendingEntries = pendingEntries + line
        log.i { line }
    }

    /**
     * Records a mark on whichever trace is live — the pending anchor, or the real trace once
     * [begin] has adopted it. Marks on the click path can land on either side of the scrape
     * depending on frame timing, and unlike [mark] this never *starts* a trace, so one arriving
     * with nothing in flight is dropped rather than logged as a bogus playback start.
     */
    fun markPendingOrActive(label: String) {
        if (pendingOrigin != null) {
            markPending(label)
            return
        }
        if (!completed && origin != null) mark(label)
    }

    /** Starts a new trace, discarding any unfinished one (e.g. an abandoned scrape). */
    fun begin(reason: String) {
        val pending = pendingOrigin
        val adoptPending = pending != null &&
            pending.elapsedNow().inWholeMilliseconds <= PendingWindowMillis
        origin = if (adoptPending) pending else timeSource.markNow()
        entries = if (adoptPending) {
            pendingEntries + "$reason +${pending!!.elapsedNow().inWholeMilliseconds}ms"
        } else {
            listOf("$reason +0ms")
        }
        pendingOrigin = null
        pendingEntries = emptyList()
        completed = false
        log.i { "begin: $reason" }
    }

    /**
     * Records a labeled point on the active trace. If the previous trace already completed (or
     * none was started), begins a new one anchored at this mark instead — this is what makes
     * in-player source/episode switches show up as their own traces.
     */
    fun mark(label: String) {
        val start = origin
        if (completed || start == null) {
            begin(label)
            return
        }
        val line = "$label +${start.elapsedNow().inWholeMilliseconds}ms"
        entries = entries + line
        log.i { line }
    }

    /** Records the final mark (first rendered frame) and logs the whole timeline as one line. */
    fun complete(label: String) {
        val start = origin
        if (completed || start == null) return
        val line = "$label +${start.elapsedNow().inWholeMilliseconds}ms"
        entries = entries + line
        completed = true
        log.i { "summary: ${entries.joinToString(" | ")}" }
    }
}
