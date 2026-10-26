package com.nuvio.app.features.music

import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Lyric auto sync. LRC timing belongs to the album recording. YouTube's official video is often a
 * different cut (an intro, a skit, a cold open), so every line lands late by the length of that
 * extra. Media time of a line = its LRC stamp + an offset; this finds the offset.
 *
 * Cheap first: when the played stream is as long as the album track, the cut is the same and nothing
 * runs. Only a stream of another length is analysed, and the length difference bounds where the
 * offset can be. The analysis itself (vocal activity of the audio against the lyric timing) is the
 * platform's, registered as [MusicLyricSync.audioEstimator]; everything that decides what to do with
 * its answer lives here so it can be tested.
 */

/** Stream and album track lengths closer than this are the same cut. */
internal const val LYRIC_SAME_CUT_MS = 2_500L

/** Slack added each side of the range the length difference allows. */
private const val RANGE_SLACK_MS = 4_000L

/** Largest shift searched either way, however different the lengths. */
private const val MAX_SHIFT_MS = 120_000L

/** With no usable lengths: a stream may start late by this much, or early by [UNKNOWN_EARLY_MS]. */
private const val UNKNOWN_LATE_MS = 60_000L
private const val UNKNOWN_EARLY_MS = 20_000L

/** A shift this close to nothing is not a shift (the detector is not that exact). */
private const val SNAP_TO_ZERO_MS = 400L

/** Lines needed in the analysed stretch, and seconds of sound that looked like a voice. */
private const val MIN_ALIGNED_LINES = 6
private const val MIN_VOICE_SECONDS = 8.0
private const val MIN_PEAK = 0.12
private const val MIN_PROMINENCE = 0.08

/** The analysed audio is this long at most, and reaches this far past where the lyrics begin. */
private const val MAX_WINDOW_MS = 120_000L
private const val WINDOW_LEAD_MS = 4_000L
private const val WINDOW_PAST_LYRICS_MS = 60_000L
/** Less than this and there is too little singing in it to line anything up. */
private const val MIN_WINDOW_MS = 15_000L

/** Result of matching lyric timing against audio, in the aligner's terms. */
internal data class LyricAlignment(
    /** Media time = LRC time + [shiftMs]. */
    val shiftMs: Double,
    /** Pearson correlation at the best shift. */
    val peak: Double,
    /** How far the best shift stands above the best one more than a couple of seconds away. */
    val prominence: Double,
    val lineCount: Int,
    val voiceSeconds: Double,
    /** The best shift is at the edge of the searched range, so it may not be a real maximum. */
    val atSearchEdge: Boolean,
)

/**
 * The range of offsets (inclusive, ms) worth searching, or null when the stream is the album's own
 * cut and needs no sync. A longer stream starts late (offset up to the extra length); a shorter one
 * mostly lost its tail, so the offset stays near zero or slightly negative.
 */
internal fun lyricSearchRange(trackDurationMs: Long, playedDurationMs: Long): LongRange? {
    if (trackDurationMs > 0L && playedDurationMs > 0L) {
        val extra = playedDurationMs - trackDurationMs
        if (abs(extra) <= LYRIC_SAME_CUT_MS) return null
        val low = if (extra > 0L) 0L else extra
        val high = if (extra > 0L) extra else 0L
        return (low - RANGE_SLACK_MS).coerceAtLeast(-MAX_SHIFT_MS)..(high + RANGE_SLACK_MS).coerceAtMost(MAX_SHIFT_MS)
    }
    return -UNKNOWN_EARLY_MS..UNKNOWN_LATE_MS
}

/** A line that is sung: it has words, and is not just a `[Chorus]` style label. */
internal fun isSungLyric(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.none { it.isLetterOrDigit() }) return false
    return !(trimmed.startsWith("[") && trimmed.endsWith("]"))
}

/**
 * (startMs, endMs, text) per sung line, in LRC time: the stretch each line is thought to be sung
 * (see [sungEndMs]). Text is a stand-in; only the interval matters.
 */
internal fun lyricSungCues(lines: List<LyricLine>): List<Triple<Long, Long, String>> =
    lines.indices.mapNotNull { index ->
        val line = lines[index]
        if (!isSungLyric(line.text)) return@mapNotNull null
        Triple(line.timeMs, sungEndMs(lines, index), "la")
    }

/** Which part of the stream to read: media time [fromMs], [lengthMs] long. */
internal data class LyricWindow(val fromMs: Long, val lengthMs: Long)

/**
 * The stretch of audio that has to hold the lyrics wherever in [range] they sit: from just before
 * the first line could start to a minute of singing after the latest it could start. Null when the
 * lyrics have no sung line.
 */
internal fun lyricDecodeWindow(lines: List<LyricLine>, range: LongRange, playedDurationMs: Long): LyricWindow? {
    val first = lines.firstOrNull { isSungLyric(it.text) }?.timeMs ?: return null
    val from = (first + range.first - WINDOW_LEAD_MS).coerceAtLeast(0L)
    var to = first + range.last + WINDOW_PAST_LYRICS_MS
    if (playedDurationMs > 0L) to = minOf(to, playedDurationMs)
    val length = minOf(to - from, MAX_WINDOW_MS)
    return if (length >= MIN_WINDOW_MS) LyricWindow(from, length) else null
}

/**
 * The offset to apply for [alignment], or null when it is not convincing enough to trust: moving
 * good lyrics to a wrong place is worse than leaving them. A shift within [SNAP_TO_ZERO_MS] of
 * nothing counts as none.
 */
internal fun acceptLyricAlignment(alignment: LyricAlignment): Long? {
    if (alignment.atSearchEdge) return null
    if (alignment.lineCount < MIN_ALIGNED_LINES || alignment.voiceSeconds < MIN_VOICE_SECONDS) return null
    if (alignment.peak < MIN_PEAK || alignment.prominence < MIN_PROMINENCE) return null
    val shift = alignment.shiftMs.roundToLong()
    return if (abs(shift) <= SNAP_TO_ZERO_MS) 0L else shift
}

/** The lyrics as they fall on the played stream: every line [offsetMs] later. */
internal fun List<LyricLine>.shiftedBy(offsetMs: Long): List<LyricLine> =
    if (offsetMs == 0L) this else map { it.copy(timeMs = (it.timeMs + offsetMs).coerceAtLeast(0L)) }

/** "+12.4s" / "-0.5s", for the label. */
internal fun formatLyricOffset(offsetMs: Long): String {
    val tenths = (abs(offsetMs) + 50L) / 100L
    val sign = if (offsetMs < 0L) "-" else "+"
    return "$sign${tenths / 10}.${tenths % 10}s"
}
