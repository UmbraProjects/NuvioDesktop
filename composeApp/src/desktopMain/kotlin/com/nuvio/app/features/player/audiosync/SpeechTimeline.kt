// Ported from NuvioTV-Reshaped (github.com/DavidVamaiotu/NuvioTV-Reshaped, GPL-3.0),
// app/src/main/java/com/nuvio/tv/ui/screens/player/audiosync/SpeechTimeline.kt.
package com.nuvio.app.features.player.audiosync

/**
 * Speech probability per 32 ms frame on the media timeline (frame n covers [n * 32 ms, (n + 1) * 32 ms)).
 *
 * Values are stored as one byte on a square-root scale (keeping resolution for low probabilities),
 * so a two hour film needs about 225 KB. Frames that were never
 * analysed stay unknown and are excluded from alignment. Thread-safe: the decoder thread writes while
 * the aligner takes snapshots.
 */
internal class SpeechTimeline {
    private var values = ByteArray(INITIAL_CAPACITY)
    private var knownFrames = 0

    @Volatile
    var version: Long = 0L
        private set

    @Synchronized
    fun record(frame: Int, probability: Float) {
        if (frame < 0 || frame >= MAX_FRAMES) return
        ensureCapacity(frame + 1)
        val quantized = 1 + (kotlin.math.sqrt(probability.coerceIn(0f, 1f)) * 254f + 0.5f).toInt()
        if (values[frame].toInt() == 0) knownFrames++
        values[frame] = quantized.toByte()
        version++
    }

    @Synchronized
    fun clear() {
        values.fill(0)
        knownFrames = 0
        version++
    }

    @Synchronized
    fun knownFrameCount(): Int = knownFrames

    /** Returns probabilities for [fromFrame, toFrame); unknown frames are NaN. */
    @Synchronized
    fun snapshot(fromFrame: Int, toFrame: Int): FloatArray {
        val out = FloatArray((toFrame - fromFrame).coerceAtLeast(0)) { Float.NaN }
        val start = fromFrame.coerceAtLeast(0)
        val end = toFrame.coerceAtMost(values.size)
        for (frame in start until end) {
            val raw = values[frame].toInt() and 0xFF
            if (raw != 0) {
                val root = (raw - 1) / 254f
                out[frame - fromFrame] = root * root
            }
        }
        return out
    }

    /** First and last known frame (inclusive), or null when nothing was analysed yet. */
    @Synchronized
    fun knownRange(): IntRange? {
        if (knownFrames == 0) return null
        var first = -1
        for (i in values.indices) {
            if (values[i].toInt() != 0) {
                first = i
                break
            }
        }
        var last = first
        for (i in values.indices.reversed()) {
            if (values[i].toInt() != 0) {
                last = i
                break
            }
        }
        return first..last
    }

    @Synchronized
    fun knownFramesIn(fromFrame: Int, toFrame: Int): Int {
        var count = 0
        for (frame in fromFrame.coerceAtLeast(0) until toFrame.coerceAtMost(values.size)) {
            if (values[frame].toInt() != 0) count++
        }
        return count
    }

    /**
     * Known audio in [fromFrame, toFrame) as separate stretches: runs of known frames, with unknown
     * gaps shorter than [joinGapFrames] kept inside a stretch (as NaN). At most [maxFrames] frames
     * are returned, keeping the latest stretches. Scattered samples of a film stay cheap to align
     * this way, where one array spanning all of them would not.
     */
    @Synchronized
    fun segments(
        fromFrame: Int = 0,
        toFrame: Int = Int.MAX_VALUE,
        joinGapFrames: Int = DEFAULT_JOIN_GAP_FRAMES,
        maxFrames: Int = Int.MAX_VALUE,
    ): List<SpeechSegment> {
        if (knownFrames == 0) return emptyList()
        val start = fromFrame.coerceAtLeast(0)
        val end = toFrame.coerceAtMost(values.size)
        val runs = ArrayList<IntRange>()
        var runStart = -1
        var lastKnown = -1
        for (frame in start until end) {
            if (values[frame].toInt() == 0) continue
            if (runStart >= 0 && frame - lastKnown - 1 >= joinGapFrames) {
                runs += runStart..lastKnown
                runStart = -1
            }
            if (runStart < 0) runStart = frame
            lastKnown = frame
        }
        if (runStart >= 0) runs += runStart..lastKnown
        val kept = ArrayList<SpeechSegment>()
        var budget = maxFrames
        for (run in runs.asReversed()) {
            if (budget <= 0) break
            val first = maxOf(run.first, run.last + 1 - budget)
            kept += SpeechSegment(first, snapshot(first, run.last + 1))
            budget -= run.last + 1 - first
        }
        kept.reverse()
        return kept
    }

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var size = values.size
        while (size < required) size *= 2
        values = values.copyOf(size.coerceAtMost(MAX_FRAMES))
    }

    companion object {
        const val FRAME_DURATION_US = SileroVad.CHUNK_DURATION_US
        const val FRAME_DURATION_MS = FRAME_DURATION_US / 1_000.0
        private const val INITIAL_CAPACITY = 1 shl 15

        /** Two minutes: shorter gaps cost less to carry than a separate transform. */
        val DEFAULT_JOIN_GAP_FRAMES = (120_000 / FRAME_DURATION_MS).toInt()
        /** Six hours; anything beyond is ignored. */
        private const val MAX_FRAMES = (6L * 3_600_000_000L / FRAME_DURATION_US).toInt()

        fun frameForTimeUs(timeUs: Long): Int = Math.floorDiv(timeUs, FRAME_DURATION_US).toInt()
    }
}

/** A stretch of the speech timeline: probabilities from [fromFrame] on, NaN where unknown. */
internal class SpeechSegment(val fromFrame: Int, val probabilities: FloatArray) {
    val toFrame: Int get() = fromFrame + probabilities.size

    val knownFrames: Int get() = probabilities.count { !it.isNaN() }
}
