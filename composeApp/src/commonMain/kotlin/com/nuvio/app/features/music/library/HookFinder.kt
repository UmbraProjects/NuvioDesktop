package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.LyricLine
import com.nuvio.app.features.music.Lyrics
import com.nuvio.app.features.music.sungEndMs

/**
 * Ladder phase 8: the hook finder. Pure logic, no network and no UI.
 *
 * Auditioning a rung means dropping the listener into the song where it is most itself: the chorus. A synced lyric
 * (LRC) already says where each line is sung, so the chorus is the stretch of lines that comes back. [findHook] looks
 * for the block of consecutive lines that repeats and returns the time of its first occurrence. When there is nothing
 * to go on (no synced lyric, an instrumental, a song with no repeated lines) it falls back to a fixed offset into the
 * track.
 *
 * How the chorus is chosen:
 *  - Lines are compared by their letters and digits only, so case and punctuation never hide a repeat. A blank line, or
 *    one with no letters at all (a "♪" marker), is an instrumental gap and ends a run.
 *  - Every repeated run of consecutive lines is a candidate block, and each block is counted wherever it recurs
 *    (without overlapping itself).
 *  - A block scores *occurrences x letters*: the amount of repeated material. That favours a four-line chorus sung three
 *    times over a one-line tag sung twice, and a long run of "na na na" only when it really dominates. Blocks under
 *    [MIN_BLOCK_LETTERS] letters in total are ignored, so "oh oh" never wins. Ties go to the earlier block.
 *  - The hook starts at the block's first occurrence, a short [HOOK_LEAD_IN_MS] before the line so the singer is not cut
 *    off, but never earlier than the end of the line before it.
 */

/** Where a [Hook] time came from. */
internal enum class HookSource {
    /** A block of lines the song repeats. */
    Chorus,

    /** No usable repeat: a fixed offset into the track. */
    FixedOffset,
}

/**
 * The part of a song to play for an audition. [lineIndex] and [lineCount] say which lyric lines make up the chorus
 * (-1 and 0 for [HookSource.FixedOffset]); [lengthMs] is how much to play from [startMs].
 */
internal data class Hook(
    val startMs: Long,
    val lengthMs: Long,
    val source: HookSource,
    val lineIndex: Int = -1,
    val lineCount: Int = 0,
) {
    val endMs: Long get() = startMs + lengthMs
}

/** How far before the first chorus line the hook starts, so the line is heard from its first word. */
internal const val HOOK_LEAD_IN_MS = 1_200L

/** A chorus hook plays at least this long (a one-line chorus is only a few seconds) and at most [HOOK_MAX_LENGTH_MS]. */
internal const val HOOK_MIN_LENGTH_MS = 10_000L
internal const val HOOK_MAX_LENGTH_MS = 30_000L

/** The fixed-offset fallback starts this share of the way through the track, and plays [FALLBACK_LENGTH_MS]. */
internal const val FALLBACK_FRACTION = 0.30
internal const val FALLBACK_LENGTH_MS = 20_000L

/** With no known length the fallback starts here: past a typical intro, into the first chorus. */
internal const val FALLBACK_UNKNOWN_START_MS = 45_000L

/** A block needs this many letters and digits in all to count as a chorus. */
internal const val MIN_BLOCK_LETTERS = 8

/** Lyrics are read up to this many lines; a longer one is not a song. Keeps the repeat search cheap. */
private const val MAX_LINES = 600

/** A hook that would start this close to the end of the track means the lyric timing does not fit it. */
private const val END_MARGIN_MS = 3_000L

private fun hookKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

private data class ChorusBlock(val start: Int, val length: Int)

/**
 * The hook of a track from its synced [lines] and its length ([durationMs], 0 when unknown): the first occurrence of
 * the repeated block when there is one, else the fixed offset. Same input, same answer.
 */
internal fun findHook(lines: List<LyricLine>, durationMs: Long = 0L): Hook {
    val block = bestChorusBlock(lines)
    return (block?.let { chorusHook(lines, it, durationMs) }) ?: fixedOffsetHook(durationMs)
}

/** The hook for whatever lyrics a track has: synced lines are searched, anything else gets the fixed offset. */
internal fun hookFor(lyrics: Lyrics?, durationMs: Long = 0L): Hook =
    when (lyrics) {
        is Lyrics.Synced -> findHook(lyrics.lines, durationMs)
        else -> fixedOffsetHook(durationMs)
    }

/**
 * The fallback: [FALLBACK_FRACTION] of the way in (past the intro, about the first chorus of a pop song), or
 * [FALLBACK_UNKNOWN_START_MS] when the length is unknown. Always leaves room to play, and never starts before 0.
 */
internal fun fixedOffsetHook(durationMs: Long): Hook {
    if (durationMs <= 0L) return Hook(FALLBACK_UNKNOWN_START_MS, FALLBACK_LENGTH_MS, HookSource.FixedOffset)
    val latestStart = (durationMs - HOOK_MIN_LENGTH_MS).coerceAtLeast(0L)
    val start = (durationMs * FALLBACK_FRACTION).toLong().coerceIn(0L, latestStart)
    val length = (durationMs - start).coerceIn(0L, FALLBACK_LENGTH_MS)
    return Hook(start, length, HookSource.FixedOffset)
}

private fun chorusHook(lines: List<LyricLine>, block: ChorusBlock, durationMs: Long): Hook? {
    val first = lines[block.start]
    // Start just before the line, but never inside the line that comes before it. A gap (blank line or "♪") is
    // silence, so the lead-in may run back into it; a sung line is over where its words are estimated to end.
    val previous = lines.getOrNull(block.start - 1)
    val previousEnd = when {
        previous == null -> 0L
        hookKey(previous.text).isEmpty() -> previous.timeMs
        else -> sungEndMs(lines, block.start - 1)
    }
    val start = maxOf(first.timeMs - HOOK_LEAD_IN_MS, previousEnd, 0L).coerceAtMost(first.timeMs)
    if (durationMs > 0L && start >= durationMs - END_MARGIN_MS) return null

    val end = sungEndMs(lines, block.start + block.length - 1)
    var length = (end - start).coerceIn(HOOK_MIN_LENGTH_MS, HOOK_MAX_LENGTH_MS)
    if (durationMs > 0L) length = length.coerceAtMost(durationMs - start)
    return Hook(start, length, HookSource.Chorus, lineIndex = block.start, lineCount = block.length)
}

private fun bestChorusBlock(allLines: List<LyricLine>): ChorusBlock? {
    val keys = allLines.take(MAX_LINES).map { hookKey(it.text) }
    val n = keys.size
    val tried = HashSet<List<String>>()
    var best: ChorusBlock? = null
    var bestScore = 0

    for (i in 0 until n) {
        if (keys[i].isEmpty()) continue
        for (j in i + 1 until n) {
            if (keys[j] != keys[i]) continue
            // Only from the start of a repeated run: when the lines before also match, an earlier pair covers it.
            if (i > 0 && keys[i - 1].isNotEmpty() && keys[i - 1] == keys[j - 1]) continue
            // Extend while the two copies keep matching; the first copy must end before the second begins.
            var length = 1
            while (j + length < n && i + length < j && keys[i + length].isNotEmpty() && keys[i + length] == keys[j + length]) {
                length++
            }
            val sequence = keys.subList(i, i + length).toList()
            if (!tried.add(sequence)) continue
            val letters = sequence.sumOf { it.length }
            if (letters < MIN_BLOCK_LETTERS) continue

            val found = occurrences(keys, sequence)
            if (found.size < 2) continue
            val score = found.size * letters
            // First occurrence, not the pair's own start: a pair found late can describe a block sung earlier.
            if (score > bestScore || (score == bestScore && best != null && found.first() < best.start)) {
                bestScore = score
                best = ChorusBlock(found.first(), length)
            }
        }
    }
    return best
}

/** Start indices of [sequence] in [keys], left to right, a copy never overlapping the one before it. */
private fun occurrences(keys: List<String>, sequence: List<String>): List<Int> {
    val out = ArrayList<Int>()
    var p = 0
    while (p + sequence.size <= keys.size) {
        if (sequence.indices.all { keys[p + it] == sequence[it] }) {
            out += p
            p += sequence.size
        } else {
            p++
        }
    }
    return out
}
