package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:history - per-song listening history for the Your history lens. Nuvio counted only albums (the facts filler's play
 * counts); this counts each song. A song counts as played once it has been heard for 30 seconds or half its length,
 * whichever comes first; one left before that, after at least a second, counts as skipped. Time heard is measured while it
 * plays, so seeking ahead does not count as listening. Songs are keyed by album and whole title, so each version (a remix,
 * a live take) counts on its own. Pure, so it is tested without Compose or storage.
 */

/** One song's history. */
internal data class SongStat(val plays: Int, val skips: Int, val firstAtMs: Long, val lastAtMs: Long)

/** The stored form: "plays|skips|first|last". */
internal fun encodeSongStat(s: SongStat): String = "${s.plays}|${s.skips}|${s.firstAtMs}|${s.lastAtMs}"

internal fun decodeSongStat(raw: String?): SongStat? {
    val f = raw?.split('|')?.takeIf { it.size == 4 } ?: return null
    val p = f[0].toIntOrNull() ?: return null
    val s = f[1].toIntOrNull() ?: return null
    return SongStat(p.coerceAtLeast(0), s.coerceAtLeast(0), f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: 0L)
}

/**
 * The song's key in the history: its album and its whole title, case and punctuation aside. The brackets are kept, so a
 * remix, a live take or a demo ("Virtual Insanity (Salaam Remi Remix)") counts apart from the song itself. A title with no
 * brackets keys exactly as before this rule, so its counts carry over.
 */
internal fun songHistoryKey(albumId: String, title: String): String =
    "song1|$albumId|${title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { songKey(title) }}"

/** [stat] with one more play or skip at [atMs]. */
internal fun SongStat?.plus(played: Boolean, atMs: Long): SongStat {
    val old = this ?: SongStat(0, 0, 0L, 0L)
    return SongStat(
        plays = old.plays + if (played) 1 else 0,
        skips = old.skips + if (played) 0 else 1,
        firstAtMs = if (played && old.firstAtMs == 0L) atMs else old.firstAtMs,
        lastAtMs = if (played) atMs else old.lastAtMs,
    )
}

/** How long a song must be heard to count as played. */
internal fun playThresholdMs(durationMs: Long): Long = if (durationMs > 0) minOf(30_000L, durationMs / 2) else 30_000L

/** What the player is doing at one moment: [key] is the song's history key, null when nothing is loaded. */
internal data class ListenSample(val key: String?, val positionMs: Long, val durationMs: Long, val playing: Boolean, val atMs: Long)

/** A song's listen ended or crossed the line: [played] true for a play, false for a skip. */
internal data class ListenVerdict(val key: String, val played: Boolean)

/**
 * Turns the player's state into plays and skips. A play is reported the moment the line is crossed (so closing the app
 * afterwards still counts it); a skip when the song is left before it. Playing the same song again from the start, after it
 * counted, is a new listen.
 */
internal class ListenTracker {
    private var key: String? = null
    private var heardMs = 0L
    private var counted = false
    private var lastAtMs = 0L
    private var lastPosMs = 0L

    fun feed(s: ListenSample): List<ListenVerdict> {
        val out = ArrayList<ListenVerdict>()
        val restarted = s.key == key && counted && s.positionMs < 2_000 && lastPosMs > s.positionMs + 10_000
        if (s.key != key || restarted) {
            val old = key
            if (old != null && !counted && heardMs >= 1_000) out += ListenVerdict(old, played = false)
            key = s.key
            heardMs = 0
            counted = false
            lastAtMs = s.atMs
        } else if (s.playing) {
            // Wall time while playing, a gap of more than 2 s (sleep, a stall) counted as 2 s.
            heardMs += (s.atMs - lastAtMs).coerceIn(0L, 2_000L)
        }
        lastAtMs = s.atMs
        lastPosMs = s.positionMs
        val k = key
        if (k != null && !counted && heardMs >= playThresholdMs(s.durationMs)) {
            counted = true
            out += ListenVerdict(k, played = true)
        }
        return out
    }
}

/** What the Your history panels show for one album. */
internal data class AlbumHistory(
    val totalPlays: Int,
    /** Plays times each song's length: about how long the album has been listened to. */
    val listenedSeconds: Long,
    val firstAtMs: Long?,
    val lastAtMs: Long?,
    /** Track indices, most played first (played songs only). */
    val top: List<Int>,
    /** Track indices never played, in album order. */
    val never: List<Int>,
    val playedOnce: Int,
    /** Songs skipped at least twice and more often than played. */
    val oftenSkipped: List<Int>,
    val neverSeconds: Int,
) {
    val isEmpty: Boolean get() = totalPlays == 0 && oftenSkipped.isEmpty()
}

/** The album's history from [stats] (one per track index, null for none), for tracks of [durations] seconds. */
internal fun albumHistory(stats: List<SongStat?>, durations: List<Int>, available: List<Boolean>): AlbumHistory {
    val idx = stats.indices
    val plays = idx.map { stats[it]?.plays ?: 0 }
    val firsts = idx.mapNotNull { stats[it]?.firstAtMs?.takeIf { t -> t > 0 } }
    val lasts = idx.mapNotNull { stats[it]?.lastAtMs?.takeIf { t -> t > 0 } }
    val never = idx.filter { plays[it] == 0 && available.getOrElse(it) { true } }
    return AlbumHistory(
        totalPlays = plays.sum(),
        listenedSeconds = idx.sumOf { plays[it].toLong() * durations.getOrElse(it) { 0 } },
        firstAtMs = firsts.minOrNull(),
        lastAtMs = lasts.maxOrNull(),
        top = idx.filter { plays[it] > 0 }.sortedWith(compareByDescending<Int> { plays[it] }.thenBy { it }),
        never = never,
        playedOnce = idx.count { plays[it] == 1 },
        oftenSkipped = idx.filter { val s = stats[it]; s != null && s.skips >= 2 && s.skips > s.plays },
        neverSeconds = never.sumOf { durations.getOrElse(it) { 0 } },
    )
}

/** "14 hours", "52 min", "Under a minute". */
internal fun listenedText(seconds: Long): String = when {
    seconds >= 7_200 -> "${seconds / 3600} hours"
    seconds >= 3_600 -> "1 hour"
    seconds >= 60 -> "${seconds / 60} min"
    seconds > 0 -> "Under a minute"
    else -> "None yet"
}

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** "March 2026" for an epoch time (UTC). */
internal fun monthYear(epochMs: Long): String {
    // Days to a civil date (Howard Hinnant's algorithm).
    val z = (epochMs).floorDiv(86_400_000L) + 719_468L
    val era = (z).floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${MONTHS[month - 1]} $year"
}

/** "Today", "Yesterday", "5 days ago", else the month: when something last happened, seen from [nowMs]. */
internal fun whenText(epochMs: Long, nowMs: Long): String {
    val days = (nowMs).floorDiv(86_400_000L) - (epochMs).floorDiv(86_400_000L)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 14 -> "$days days ago"
        else -> monthYear(epochMs)
    }
}
