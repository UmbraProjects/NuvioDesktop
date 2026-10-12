package com.nuvio.app.features.player.autosync

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Applies an [AutoSyncTimelineRetimeResult] to a subtitle file's text so mpv can load the synced
 * copy as an ordinary external subtitle.
 *
 * Desktop counterpart of NuvioTV's sidecar retime (AutoSyncSidecarBridge): each original timestamp
 * maps to the retimed boundary of the matching cue, falling back to the whole-film affine
 * transform for lines the matcher skipped. SRT/WebVTT timing lines and ASS/SSA Dialogue
 * Start/End fields are rewritten in place, so styling, positioning and cue settings survive.
 * Anything else (TTML…) is re-emitted as SRT from the parsed cues.
 */
internal object AutoSyncSubtitleRewriter {
    private const val BOUNDARY_TOLERANCE_MS = 500L

    data class Rewritten(val text: String, val extension: String)

    private val timingLine = Regex(
        """^([ \t]*)((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})([ \t]*-->[ \t]*)((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})(.*)$""",
        RegexOption.MULTILINE,
    )
    private val assEventsHeader = Regex("""^\s*\[events]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun rewrite(body: String, sourceUrl: String, timeline: AutoSyncTimelineRetimeResult): Rewritten {
        val mapper = TimeMapper(timeline)
        val path = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".ass") || path.endsWith(".ssa") || assEventsHeader.containsMatchIn(body) ->
                Rewritten(rewriteAss(body, mapper), if (path.endsWith(".ssa")) "ssa" else "ass")
            timingLine.containsMatchIn(body) -> {
                val isVtt = body.trimStart('﻿').trimStart().startsWith("WEBVTT")
                Rewritten(rewriteTimingLines(body, mapper), if (isVtt) "vtt" else "srt")
            }
            else -> Rewritten(emitSrt(body, sourceUrl, mapper), "srt")
        }
    }

    private fun rewriteTimingLines(body: String, mapper: TimeMapper): String {
        val matches = timingLine.findAll(body).toList()
        val originals = matches.map { match ->
            val start = parseTimestamp(match.groupValues[2]) ?: return body
            val end = parseTimestamp(match.groupValues[4]) ?: return body
            start to end.coerceAtLeast(start + 1)
        }
        val retimed = originals.map { (start, end) -> mapper.map(start, end) }.toMutableList()
        clampIntroducedOverlaps(originals, retimed)

        val out = StringBuilder(body.length + 64)
        var last = 0
        matches.forEachIndexed { index, match ->
            out.append(body, last, match.range.first)
            val (start, end) = retimed[index]
            out.append(match.groupValues[1])
            out.append(formatLike(match.groupValues[2], start))
            out.append(match.groupValues[3])
            out.append(formatLike(match.groupValues[4], end))
            out.append(match.groupValues[5])
            last = match.range.last + 1
        }
        out.append(body, last, body.length)
        return out.toString()
    }

    private fun rewriteAss(body: String, mapper: TimeMapper): String {
        var inEvents = false
        var startField = 1
        var endField = 2
        val lineBreak = if (body.contains("\r\n")) "\r\n" else "\n"
        return body.split(lineBreak).joinToString(lineBreak) { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    inEvents = trimmed.equals("[Events]", ignoreCase = true)
                    line
                }
                inEvents && trimmed.startsWith("Format:", ignoreCase = true) -> {
                    val fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                    fields.indexOf("start").takeIf { it >= 0 }?.let { startField = it }
                    fields.indexOf("end").takeIf { it >= 0 }?.let { endField = it }
                    line
                }
                inEvents && trimmed.startsWith("Dialogue:", ignoreCase = true) ->
                    rewriteAssDialogue(line, startField, endField, mapper) ?: line
                else -> line
            }
        }
    }

    private fun rewriteAssDialogue(line: String, startField: Int, endField: Int, mapper: TimeMapper): String? {
        val colon = line.indexOf(':')
        val prefix = line.substring(0, colon + 1)
        // Text is the last field and may itself contain commas, so split only the leading fields.
        val fields = line.substring(colon + 1).split(',', limit = maxOf(startField, endField) + 2).toMutableList()
        if (fields.size <= maxOf(startField, endField)) return null
        val start = parseAssTime(fields[startField].trim()) ?: return null
        val end = parseAssTime(fields[endField].trim()) ?: return null
        val (newStart, newEnd) = mapper.map(start, end.coerceAtLeast(start + 1))
        fields[startField] = leadingSpace(fields[startField]) + formatAssTime(newStart)
        fields[endField] = leadingSpace(fields[endField]) + formatAssTime(newEnd)
        return prefix + fields.joinToString(",")
    }

    private fun emitSrt(body: String, sourceUrl: String, mapper: TimeMapper): String {
        val cues = AutoSyncSubtitleCueParser.parse(body, sourceUrl).sortedBy { it.startTimeMs }
        val originals = cues.map { it.startTimeMs to it.endTimeMs.coerceAtLeast(it.startTimeMs + 1) }
        val retimed = originals.map { (start, end) -> mapper.map(start, end) }.toMutableList()
        clampIntroducedOverlaps(originals, retimed)
        return buildString {
            cues.forEachIndexed { index, cue ->
                val (start, end) = retimed[index]
                append(index + 1).append('\n')
                append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
                append(cue.text).append("\n\n")
            }
        }
    }

    /** A gap the source had between consecutive lines must not turn into an overlap. */
    private fun clampIntroducedOverlaps(original: List<Pair<Long, Long>>, retimed: MutableList<Pair<Long, Long>>) {
        for (index in 0 until retimed.lastIndex) {
            if (original[index].second > original[index + 1].first) continue
            val (start, end) = retimed[index]
            val nextStart = retimed[index + 1].first
            if (end > nextStart && nextStart > start) retimed[index] = start to nextStart
        }
    }

    private class TimeMapper(private val timeline: AutoSyncTimelineRetimeResult) {
        private val starts = timeline.cues.map { it.originalStartTimeMs to it.startTimeMs }.sortedBy { it.first }
        private val ends = timeline.cues.map { it.originalEndTimeMs to it.endTimeMs }.sortedBy { it.first }

        fun map(startMs: Long, endMs: Long): Pair<Long, Long> {
            val start = mapTime(startMs, starts)
            val end = mapTime(endMs, ends).coerceAtLeast(start + 1)
            return start to end
        }

        private fun mapTime(originalMs: Long, boundaries: List<Pair<Long, Long>>): Long {
            if (boundaries.isNotEmpty()) {
                var low = 0
                var high = boundaries.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (boundaries[mid].first < originalMs) low = mid + 1 else high = mid
                }
                var best: Pair<Long, Long>? = null
                var bestError = Long.MAX_VALUE
                for (index in (low - 2).coerceAtLeast(0)..(low + 2).coerceAtMost(boundaries.lastIndex)) {
                    val error = abs(boundaries[index].first - originalMs)
                    if (error < bestError) {
                        best = boundaries[index]
                        bestError = error
                    }
                }
                if (best != null && bestError <= BOUNDARY_TOLERANCE_MS) return best.second.coerceAtLeast(0L)
            }
            return (originalMs * timeline.alignmentScale + timeline.alignmentInterceptMs)
                .roundToLong()
                .coerceAtLeast(0L)
        }
    }

    private fun parseTimestamp(raw: String): Long? {
        val parts = raw.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondsPart = parts.last()
        val seconds = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', "0")
        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    /** Formats [ms] the way [template] was written: same decimal separator, hours kept if present. */
    private fun formatLike(template: String, ms: Long): String {
        val separator = if (template.contains(',')) ',' else '.'
        val hasHours = template.count { it == ':' } == 2
        val hours = ms / 3_600_000
        val minutes = (ms / 60_000) % 60
        val seconds = (ms / 1000) % 60
        val millis = ms % 1000
        val clock = if (hasHours || hours > 0) {
            fmt("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            fmt("%02d:%02d", minutes, seconds)
        }
        return "$clock$separator${fmt("%03d", millis)}"
    }

    private fun formatSrtTime(ms: Long): String = formatLike("00:00:00,000", ms)

    private fun parseAssTime(raw: String): Long? {
        val parts = raw.split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].substringBefore('.').toLongOrNull() ?: return null
        val centis = parts[2].substringAfter('.', "0").padEnd(2, '0').take(2).toLongOrNull() ?: return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + centis * 10
    }

    private fun formatAssTime(ms: Long): String {
        val centis = (ms + 5) / 10
        return fmt("%d:%02d:%02d.%02d", centis / 360_000, (centis / 6000) % 60, (centis / 100) % 60, centis % 100)
    }

    private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

    private fun leadingSpace(field: String): String = field.takeWhile { it == ' ' }
}
