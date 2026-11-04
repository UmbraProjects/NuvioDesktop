package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * fork:facts-filler - the rules of the background filler, kept free of storage and network so they can be tested: what to
 * ask for and in what order, how many lookups a day, and how long to wait between them. The runtime that follows these
 * rules is FactsFiller.kt.
 */

/** What the cache knows about one fact: [Have] it, [Missed] it a moment ago (a failed or empty ask, kept for two hours), or [Missing]. */
internal enum class FactState { Have, Missed, Missing }

/** The three kinds of fact the filler writes, in the order it asks for them for one album. */
internal enum class FillKind(val label: String) { Notes("Liner Notes"), TrackNotes("Track notes"), Story("Song story") }

/** One lookup for the filler to make: [song] is set for a [FillKind.Story]. */
internal data class FillStep(val kind: FillKind, val artist: String, val album: String, val albumId: String, val song: String? = null) {
    /** The line the retry list shows. */
    val title: String get() = when (kind) {
        FillKind.Story -> "$artist - $song"
        else -> "$artist - $album (${kind.label})"
    }
}

/** What the cache holds for one album: its notes, its track notes, and each song's story in track order. */
internal data class AlbumFactStates(val notes: FactState, val trackNotes: FactState, val stories: List<Pair<String, FactState>>)

/** What is left to ask for [states] (still [FactState.Missing]) and what failed a moment ago ([FactState.Missed], for the retry list). */
internal data class FillPlan(val steps: List<FillStep>, val missed: List<FillStep>) {
    val isDone: Boolean get() = steps.isEmpty()
}

/** The plan for one album: Liner Notes first (they ground the track notes), then the track notes, then each song's story. */
internal fun planAlbum(artist: String, album: String, albumId: String, states: AlbumFactStates): FillPlan {
    val all = buildList {
        add(FillStep(FillKind.Notes, artist, album, albumId) to states.notes)
        add(FillStep(FillKind.TrackNotes, artist, album, albumId) to states.trackNotes)
        states.stories.forEach { (song, state) -> add(FillStep(FillKind.Story, artist, album, albumId, song) to state) }
    }
    return FillPlan(
        steps = all.filter { it.second == FactState.Missing }.map { it.first },
        missed = all.filter { it.second == FactState.Missed }.map { it.first },
    )
}

/** How many requests a step costs the free AI key: the notes ask twice (the notes, then the trivia), the others once. */
internal fun requestsFor(kind: FillKind): Int = if (kind == FillKind.Notes) 2 else 1

/** An album the filler may work on, as the library knows it. */
internal data class FillCandidate(val albumId: String, val artistId: String, val artist: String, val album: String, val favourite: Boolean, val year: Int?)

/** How often and how lately an album was played. */
internal data class PlayStat(val count: Int, val lastAtMs: Long)

/**
 * The order the filler works through the library in: the albums played most first, then the most recently played, then the
 * favourite artists' albums, then the rest by artist and year. Albums never played still get their turn, in a stable order.
 */
internal fun fillOrder(albums: List<FillCandidate>, plays: Map<String, PlayStat>): List<FillCandidate> =
    albums.sortedWith(
        compareByDescending<FillCandidate> { plays[it.albumId]?.count ?: 0 }
            .thenByDescending { plays[it.albumId]?.lastAtMs ?: 0L }
            .thenByDescending { it.favourite }
            .thenBy { it.artist.lowercase() }
            .thenBy { it.year ?: Int.MAX_VALUE }
            .thenBy { it.album.lowercase() },
    )

// region the day's allowance

internal const val FILL_WINDOW_MS = 24L * 60L * 60L * 1_000L

/** Lookups made since [windowStartMs]; the window is 24 hours long and starts at the first lookup after the last one ended. */
internal data class FillBudget(val windowStartMs: Long = 0L, val used: Int = 0) {
    /** This budget as of [nowMs]: a window that has run its 24 hours starts again empty. */
    fun at(nowMs: Long): FillBudget = if (windowStartMs == 0L || nowMs - windowStartMs >= FILL_WINDOW_MS) FillBudget(nowMs, 0) else this

    fun left(cap: Int, nowMs: Long): Int = (cap - at(nowMs).used).coerceAtLeast(0)

    fun spend(requests: Int, nowMs: Long): FillBudget = at(nowMs).let { it.copy(used = it.used + requests) }

    /** When more may be asked for after the cap was reached; null while there is allowance left. */
    fun resumesAtMs(cap: Int, nowMs: Long): Long? = at(nowMs).let { if (it.used >= cap) it.windowStartMs + FILL_WINDOW_MS else null }
}

internal const val FILL_CAP_DEFAULT = 150
internal val FILL_CAP_CHOICES = listOf(50, 100, 150, 300, 600)

// endregion

// region pacing

internal const val FILL_GAP_MS = 4_000L
internal const val FILL_FAIL_GAP_MS = 10_000L
internal const val FILL_PAUSE_MS = 30L * 60L * 1_000L
internal const val FILL_FAILS_BEFORE_PAUSE = 3

/**
 * How long to wait before the next lookup: a few seconds when the last one worked (the free key has a per-minute limit),
 * longer after one that failed, and half an hour once [failStreak] lookups in a row failed, which is what a rate limit or a
 * dead key looks like. The cache already forgets a failure after two hours, so nothing is lost by waiting.
 */
internal fun nextGapMs(failStreak: Int): Long = when {
    failStreak >= FILL_FAILS_BEFORE_PAUSE -> FILL_PAUSE_MS
    failStreak > 0 -> FILL_FAIL_GAP_MS
    else -> FILL_GAP_MS
}

// endregion

// region play counts and saved state (both small, so one text value each)

private val FILL_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun encodePlayStat(stat: PlayStat): String = buildJsonObject { put("n", stat.count); put("at", stat.lastAtMs) }.toString()

internal fun decodePlayStat(text: String?): PlayStat? {
    val obj = text?.let { runCatching { FILL_JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    val count = (obj["n"] as? JsonPrimitive)?.intOrNull ?: return null
    val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: 0L
    return PlayStat(count.coerceAtLeast(0), at)
}

internal fun encodeBudget(budget: FillBudget): String = buildJsonObject { put("start", budget.windowStartMs); put("used", budget.used) }.toString()

internal fun decodeBudget(text: String?): FillBudget {
    val obj = text?.let { runCatching { FILL_JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return FillBudget()
    val start = (obj["start"] as? JsonPrimitive)?.longOrNull ?: 0L
    val used = (obj["used"] as? JsonPrimitive)?.intOrNull ?: 0
    return FillBudget(start, used.coerceAtLeast(0))
}

/** A cap read back from storage: one of the offered choices, else the default. */
internal fun decodeCap(text: String?): Int = text?.toIntOrNull()?.takeIf { it in FILL_CAP_CHOICES } ?: FILL_CAP_DEFAULT

// endregion

/** A short line for the settings page: how far through the library the filler is. */
internal fun fillProgressLine(albumsDone: Int, albumsTotal: Int, songsKept: Int): String = when {
    albumsTotal <= 0 -> "Nothing to fill yet"
    albumsDone >= albumsTotal -> "All $albumsTotal albums are filled ($songsKept songs)"
    else -> "$albumsDone of $albumsTotal albums filled ($songsKept songs)"
}
