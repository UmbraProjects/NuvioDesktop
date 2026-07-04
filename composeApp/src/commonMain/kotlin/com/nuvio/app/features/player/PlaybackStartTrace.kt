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

    @Volatile
    private var origin: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var entries: List<String> = emptyList()

    @Volatile
    private var completed = true

    /** Starts a new trace, discarding any unfinished one (e.g. an abandoned scrape). */
    fun begin(reason: String) {
        origin = timeSource.markNow()
        entries = listOf("$reason +0ms")
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
