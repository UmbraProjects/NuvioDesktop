package com.nuvio.app.features.music.visualiser

import com.nuvio.app.features.music.LyricLine
import com.nuvio.app.features.music.chorusLineIndices

/*
 * fork:visualiser - the parts of a song for the Song Map, read from its synced lyrics: the hook lines (sung three or more
 * times, as the lyric view already tints them) make the choruses, the other sung lines the verses, a long gap between
 * lines an instrumental, and the time before the first line and after the last the intro and outro. No lyrics, no parts.
 */

internal enum class SectionKind(val label: String) {
    Intro("Intro"),
    Verse("Verse"),
    Chorus("Chorus"),
    Instrumental("Instrumental"),
    Outro("Outro"),
}

internal data class SongSection(val startMs: Long, val endMs: Long, val kind: SectionKind)

/** A gap between two sung lines longer than this is an instrumental. */
private const val INSTRUMENTAL_GAP_MS = 15_000L

/** How long the last line of a run is taken to be sung for. */
private const val LINE_TAIL_MS = 5_000L

/** An intro or outro shorter than this joins the part next to it. */
private const val MIN_EDGE_MS = 4_000L

internal fun songSections(lines: List<LyricLine>, durationMs: Long): List<SongSection> {
    if (durationMs <= 0L) return emptyList()
    val chorus = chorusLineIndices(lines)
    val sung = lines.withIndex().filter { it.value.text.isNotBlank() && it.value.timeMs in 0 until durationMs }
    if (sung.isEmpty()) return emptyList()

    val out = mutableListOf<SongSection>()
    fun add(start: Long, end: Long, kind: SectionKind) {
        if (end <= start) return
        val last = out.lastOrNull()
        if (last != null && last.kind == kind && last.endMs >= start) {
            out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, end))
        } else {
            out += SongSection(start, end, kind)
        }
    }

    val first = sung.first().value.timeMs
    if (first >= MIN_EDGE_MS) add(0L, first, SectionKind.Intro)
    for (k in sung.indices) {
        val (index, line) = sung[k]
        val kind = if (index in chorus) SectionKind.Chorus else SectionKind.Verse
        val next = sung.getOrNull(k + 1)?.value?.timeMs
        if (next == null) {
            val end = minOf(durationMs, line.timeMs + LINE_TAIL_MS)
            add(line.timeMs, end, kind)
            if (durationMs - end >= MIN_EDGE_MS) add(end, durationMs, SectionKind.Outro)
            else if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(endMs = durationMs)
        } else if (next - line.timeMs > INSTRUMENTAL_GAP_MS) {
            add(line.timeMs, line.timeMs + LINE_TAIL_MS, kind)
            add(line.timeMs + LINE_TAIL_MS, next, SectionKind.Instrumental)
        } else {
            add(line.timeMs, next, kind)
        }
    }
    // An intro too short to stand alone belongs to the first part.
    if (out.isNotEmpty() && out.first().startMs > 0L) out[0] = out.first().copy(startMs = 0L)
    return out
}

internal fun sectionAt(sections: List<SongSection>, atMs: Long): SongSection? =
    sections.firstOrNull { atMs >= it.startMs && atMs < it.endMs }

/** When the next chorus after [atMs] starts, or null when no chorus is still to come. */
internal fun nextChorusStartMs(sections: List<SongSection>, atMs: Long): Long? =
    sections.firstOrNull { it.kind == SectionKind.Chorus && it.startMs > atMs }?.startMs

/** "m:ss" for a time in milliseconds. */
internal fun clockLabel(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 999L) / 1_000L
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
