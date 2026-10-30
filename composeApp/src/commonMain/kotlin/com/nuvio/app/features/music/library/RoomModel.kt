package com.nuvio.app.features.music.library

import kotlin.math.max
import kotlin.math.min

/** Phase 4: pure helpers behind the artist room and the shelf strip. No Compose, so they are unit-tested. */

/** "Solo artist · Ireland · born 1990", "Group · United States · formed 1981", "Group · UK · 1990–2004". Missing parts are left out. */
internal fun passportLine(artist: LibraryArtist): String = listOfNotNull(
    // MusicBrainz's "Person" is jargon next to a country; say what it means on a music page.
    artist.type?.takeIf { it.isNotBlank() }?.let { if (it.equals("Person", ignoreCase = true)) "Solo artist" else it },
    (artist.area ?: artist.country)?.takeIf { it.isNotBlank() },
    artist.beginYear?.let { begin ->
        when {
            artist.ended && artist.endYear != null -> "$begin–${artist.endYear}"
            artist.type.equals("Person", ignoreCase = true) -> "born $begin"
            artist.type?.lowercase() in setOf("group", "orchestra", "choir") -> "formed $begin"
            else -> "since $begin"
        }
    },
).joinToString(" · ")

/** The first sentence of [text] when it is at most [max] characters, else the text cut at [max] with an ellipsis. Null for blank text. */
internal fun firstSentence(text: String, max: Int = 90): String? {
    val t = text.trim().replace(Regex("\\s+"), " ")
    if (t.isEmpty()) return null
    val end = Regex("[.!?](\\s|$)").find(t)?.range?.first?.plus(1)
    if (end != null && end <= max) return t.substring(0, end)
    return if (t.length <= max) t else t.take(max).trimEnd() + "…"
}

/** The room's one-line tagline: MusicBrainz disambiguation ("Irish singer/songwriter"), else the biography's first sentence. */
internal fun artistTagline(artist: LibraryArtist): String? =
    artist.disambiguation?.takeIf { it.isNotBlank() }?.trim()
        ?: artist.biography?.let { firstSentence(it) }

/** The accent-filled mood chip and up to five outlined genre chips (a genre equal to the mood is not repeated). */
internal fun passportChips(artist: LibraryArtist): Pair<String?, List<String>> {
    val mood = artist.mood?.takeIf { it.isNotBlank() }
    val genres = artist.genres.filterNot { it.equals(mood, ignoreCase = true) }.distinctBy { it.lowercase() }.take(5)
    return mood to genres
}

/** Where one era block sits on the ruler, in the same unit as the ruler width. */
internal data class EraBlockSlot(val left: Float, val width: Float)

/** True when [count] blocks of at least [minWidth] cannot sit side by side in [totalWidth]; the strip then scrolls. */
internal fun eraBlocksOverflow(count: Int, totalWidth: Float, minWidth: Float, gap: Float = 4f): Boolean =
    count * (minWidth + gap) > totalWidth

/**
 * Era blocks under the ruler: each spans its years, at least [minWidth] wide, pushed right so two
 * blocks never overlap, and pulled back inside [totalWidth]. If they cannot all fit they share the width equally.
 */
internal fun layoutEraBlocks(
    eras: List<TimelineEra>,
    startYear: Int,
    spanYears: Int,
    totalWidth: Float,
    minWidth: Float,
    gap: Float = 4f,
): List<EraBlockSlot> {
    if (eras.isEmpty()) return emptyList()
    if (eraBlocksOverflow(eras.size, totalWidth, minWidth, gap)) {
        val each = (totalWidth - gap * (eras.size - 1)) / eras.size
        return eras.indices.map { EraBlockSlot(it * (each + gap), each) }
    }
    var cursor = 0f
    return eras.map { era ->
        val from = (era.fromYear - startYear).toFloat() / spanYears * totalWidth
        val to = (era.toYear + 1 - startYear).toFloat() / spanYears * totalWidth
        val left = max(from, cursor)
        val width = min(max(to - from, minWidth), totalWidth - left).coerceAtLeast(0f)
        cursor = left + width + gap
        EraBlockSlot(left, width)
    }
}

/** X position of a fractional year on a ruler [totalWidth] wide that starts at [startYear] and runs [spanYears] years. */
internal fun rulerX(position: Double, startYear: Int, spanYears: Int, totalWidth: Float): Float =
    (((position - startYear) / spanYears) * totalWidth).toFloat()

/** Years to label on the ruler: every year for a short span, every second, else every fifth. */
internal fun rulerTicks(startYear: Int, endYear: Int): List<Int> {
    val span = endYear - startYear + 1
    val step = when {
        span <= 8 -> 1
        span <= 16 -> 2
        else -> 5
    }
    return (startYear..endYear + 1 step step).toList()
}

/** The UTC calendar date (year, month, day) of an epoch-millisecond timestamp. */
internal fun civilDate(epochMs: Long): Triple<Int, Int, Int> {
    val z = epochMs.floorDiv(86_400_000L) + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return Triple(y, m, d)
}

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2025-10-24" as "24 Oct 2025"; "2025-10" as "Oct 2025"; anything else unchanged. */
internal fun formatReleaseDay(date: String): String {
    val p = date.split("-")
    val y = p.getOrNull(0)?.takeIf { it.length == 4 } ?: return date
    val m = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return y
    val d = p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 } ?: return "${monthNames[m - 1]} $y"
    return "$d ${monthNames[m - 1]} $y"
}
