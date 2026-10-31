package com.nuvio.app.features.music

import kotlin.math.abs

/*
 * The maths behind the lyric effects, kept free of Compose so it can be tested. LRC only stamps
 * where a line STARTS, so everything here is an estimate: when the singer actually finishes the
 * line, and where inside it each word falls.
 */

/** Longest a line's words are assumed to take when the next line is far off (a long instrumental gap). */
private const val MAX_SUNG_MS = 9_000L
private const val MIN_SUNG_MS = 700L
private const val MS_PER_CHAR = 95L

/** The last line has no successor to bound it. */
private const val LAST_LINE_MS = 4_000L

/** The instant [index]'s words are assumed to be finished: before the next stamp when there is a long rest. */
internal fun sungEndMs(lines: List<LyricLine>, index: Int): Long {
    val line = lines[index]
    val nextStart = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + LAST_LINE_MS)
    if (line.text.isBlank()) return nextStart
    // The source timed every word: the line is finished when its last word is, not after a guess from its length.
    if (line.words.isNotEmpty()) {
        val last = line.words.last().endMs
        return minOf(if (lines.getOrNull(index + 1) != null) nextStart else Long.MAX_VALUE, last).coerceAtLeast(line.timeMs + 1)
    }
    val estimate = (line.text.length * MS_PER_CHAR + 400L).coerceIn(MIN_SUNG_MS, MAX_SUNG_MS)
    // Never past the next line, and never so short the sweep is a blink.
    return minOf(nextStart, line.timeMs + estimate).coerceAtLeast(line.timeMs + 1)
}

/** 0..1 through the sung part of line [index] at [positionMs]. */
internal fun lineProgress(lines: List<LyricLine>, index: Int, positionMs: Long): Float {
    if (index !in lines.indices) return 0f
    val start = lines[index].timeMs
    val end = sungEndMs(lines, index)
    if (end <= start) return 1f
    return ((positionMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

/** 0..1 through the whole stretch from line [index]'s stamp to the next one: the gap countdown. */
internal fun gapProgress(lines: List<LyricLine>, index: Int, positionMs: Long): Float {
    if (index !in lines.indices) return 0f
    val start = lines[index].timeMs
    val end = lines.getOrNull(index + 1)?.timeMs ?: (start + LAST_LINE_MS)
    if (end <= start) return 1f
    return ((positionMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

/** A word of a line and the share of the line's sweep (0..1) it occupies. */
internal data class LyricWord(val text: String, val from: Float, val to: Float)

/**
 * Splits [text] on spaces and spreads the sweep across the words by length, so a long word holds the
 * highlight longer than "a". Every word keeps its trailing space-free text; the view puts the gap back.
 */
internal fun lyricWords(text: String): List<LyricWord> {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    // +1 per word: even a one-letter word takes a beat.
    val weights = words.map { it.length + 1 }
    val total = weights.sum().toFloat()
    var cursor = 0f
    return words.mapIndexed { i, word ->
        val from = cursor
        cursor += weights[i] / total
        LyricWord(word, from, if (i == words.lastIndex) 1f else cursor)
    }
}

/**
 * The words of line [index] with the share of the line's sweep each occupies: the times the source gave for each word
 * when it has them (so a held word holds the highlight and a rest between words is a rest), otherwise [lyricWords]'s
 * estimate from word length.
 */
internal fun lineWords(lines: List<LyricLine>, index: Int): List<LyricWord> {
    val line = lines.getOrNull(index) ?: return emptyList()
    if (line.words.isEmpty()) return lyricWords(line.text)
    val start = line.timeMs
    val span = (sungEndMs(lines, index) - start).coerceAtLeast(1L).toFloat()
    return line.words.map { word ->
        val from = ((word.startMs - start) / span).coerceIn(0f, 1f)
        val to = ((word.endMs - start) / span).coerceIn(from, 1f)
        LyricWord(word.text, from, to)
    }
}

/** How far through its own slice of the sweep a word is, 0..1. */
internal fun wordProgress(word: LyricWord, lineProgress: Float): Float {
    val span = word.to - word.from
    if (span <= 0f) return if (lineProgress >= word.to) 1f else 0f
    return ((lineProgress - word.from) / span).coerceIn(0f, 1f)
}

private const val CHORUS_MIN_REPEATS = 3
private const val CHORUS_MIN_LETTERS = 6

private fun chorusKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/**
 * Indices of lines that are sung again and again: the hook. A line counts when its words occur at
 * least [CHORUS_MIN_REPEATS] times in the song, which separates a chorus from a one-off echo.
 */
internal fun chorusLineIndices(lines: List<LyricLine>): Set<Int> {
    val keys = lines.map { chorusKey(it.text) }
    val counts = HashMap<String, Int>()
    for (key in keys) if (key.length >= CHORUS_MIN_LETTERS) counts[key] = (counts[key] ?: 0) + 1
    val out = HashSet<Int>()
    keys.forEachIndexed { i, key -> if ((counts[key] ?: 0) >= CHORUS_MIN_REPEATS) out += i }
    return out
}

/**
 * A line the singer lingers on or shouts: few words held for a long time, or ending in `!`. These get
 * the big "held note" treatment.
 */
internal fun isEmphasisLine(lines: List<LyricLine>, index: Int): Boolean {
    val line = lines.getOrNull(index) ?: return false
    val text = line.text.trim()
    if (text.isEmpty()) return false
    if (text.endsWith("!")) return true
    val words = text.split(' ').count { it.isNotEmpty() }
    val gap = (lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + LAST_LINE_MS)) - line.timeMs
    return words <= 3 && gap >= 4_000L
}

/** A hue 0..360 that is the same for the same track every time, so a song keeps its colour. */
internal fun moodHue(seed: String): Float {
    var hash = 7
    for (c in seed.lowercase()) hash = hash * 31 + c.code
    return (abs(hash % 360)).toFloat()
}

/** Where the dim-to-bright sweep of a gap sits: how many of [count] dots are lit at [progress]. */
internal fun litDots(progress: Float, count: Int = 3): Float = (progress.coerceIn(0f, 1f) * count)
