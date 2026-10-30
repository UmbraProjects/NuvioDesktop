package com.nuvio.app.features.music.nebula

/*
 * Pure matching between a YouTube Music album (the catalogue the app uses) and a music addon's
 * album: which of the addon's search results is the same record, and which of its tracks is which of
 * ours. No network and no UI, so it is all unit tested.
 */

/** Words that say which edition of an album this is, not which album. */
private val EDITION_WORDS = setOf(
    "deluxe", "edition", "remastered", "remaster", "expanded", "anniversary", "version", "bonus",
    "explicit", "ep", "single", "album", "the",
)

internal fun isVariousArtists(artist: String): Boolean =
    artist.trim().lowercase().let { it == "various artists" || it == "various" || it == "va" }

/** Lower-case letters and digits, split on anything else. */
internal fun searchTokens(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (c in text.lowercase()) {
        if (c.isLetterOrDigit()) current.append(c)
        else if (current.isNotEmpty()) { out += current.toString(); current.clear() }
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

/** [text] without its (bracketed) and [bracketed] groups: "Abbey Road (Remastered)" gives "Abbey Road". */
internal fun withoutBrackets(text: String): String {
    val sb = StringBuilder()
    var depth = 0
    for (c in text) {
        when {
            c == '(' || c == '[' -> depth++
            c == ')' || c == ']' -> if (depth > 0) depth--
            depth == 0 -> sb.append(c)
        }
    }
    return sb.toString().trim()
}

/** The tokens that identify the album itself: no edition words, no bare years. */
internal fun albumCoreTokens(albumTitle: String): List<String> {
    val tokens = searchTokens(withoutBrackets(albumTitle).ifBlank { albumTitle })
    return tokens.filter { it !in EDITION_WORDS && !(it.length == 4 && it.all(Char::isDigit)) }
}

/** What to type into the addon's search for [albumTitle] by [artist]: both, then just the album as a second try. */
internal fun nebulaSearchQueries(albumTitle: String, artist: String): List<String> {
    val core = albumCoreTokens(albumTitle).joinToString(" ")
    if (core.isBlank()) return emptyList()
    val artistPart = if (isVariousArtists(artist)) "" else searchTokens(artist).filter { it != "the" }.joinToString(" ")
    return listOf("$artistPart $core".trim(), core).distinct()
}

/**
 * The addon's albums that are this album, best first. The title's core words must match (equal is
 * best, one containing the other is allowed), and the artist must share a word. A matching year and
 * track count break ties, and the addon's own order (its relevance) breaks the rest.
 */
internal fun rankNebulaAlbums(
    candidates: List<NebulaAlbumSummary>,
    title: String,
    artist: String,
    year: String?,
    trackCount: Int,
): List<NebulaAlbumSummary> = scoreNebulaAlbums(candidates, title, artist, year, trackCount).map { it.album }

/** A candidate with the score [rankNebulaAlbums] gave it; [exactTitle] is true when its core title words are the same set as ours. */
internal data class ScoredNebulaAlbum(val album: NebulaAlbumSummary, val score: Int, val exactTitle: Boolean)

/** [rankNebulaAlbums] with the scores kept, best first (the addon's own order breaks ties). */
internal fun scoreNebulaAlbums(
    candidates: List<NebulaAlbumSummary>,
    title: String,
    artist: String,
    year: String?,
    trackCount: Int,
): List<ScoredNebulaAlbum> {
    val wantTokens = albumCoreTokens(title).toSet()
    if (wantTokens.isEmpty()) return emptyList()
    val wantArtist = if (isVariousArtists(artist)) emptySet() else searchTokens(artist).filter { it != "the" }.toSet()
    val wantYear = year?.trim()?.take(4)?.toIntOrNull()
    return candidates.mapNotNull { c ->
        val have = albumCoreTokens(c.title).toSet()
        if (have.isEmpty()) return@mapNotNull null
        val titleScore = when {
            have == wantTokens -> 50
            have.containsAll(wantTokens) || wantTokens.containsAll(have) -> 25
            else -> return@mapNotNull null
        }
        if (wantArtist.isNotEmpty() && searchTokens(c.artist).none { it in wantArtist }) return@mapNotNull null
        var score = titleScore
        if (wantYear != null && c.year == wantYear) score += 15
        if (trackCount > 0 && c.trackCount == trackCount) score += 15
        ScoredNebulaAlbum(c, score, exactTitle = titleScore == 50)
    }.sortedByDescending { it.score }
}

/** Score a various-artists match needs: an exact title alone is too common ("Greatest Hits"), so the year or the track count must agree too. */
private const val VARIOUS_ARTISTS_MIN_SCORE = 65

/**
 * The one addon album that is this release, or null when none is clearly it. Stricter than [rankNebulaAlbums], because
 * a release that YouTube Music does not have is un-dimmed and opened on this answer alone: a wrong match is worse than
 * a missing one. The core title must be equal (not merely contained), the artist must share a word (a various-artists
 * release needs its year or track count to agree as well), and a candidate whose year AND track count both differ from
 * ours is another record. A remaster (year differs, count the same) and an unknown count still match.
 */
internal fun strictNebulaMatch(
    candidates: List<NebulaAlbumSummary>,
    title: String,
    artist: String,
    year: String?,
    trackCount: Int,
): NebulaAlbumSummary? {
    val wantYear = year?.trim()?.take(4)?.toIntOrNull()
    val various = isVariousArtists(artist)
    return scoreNebulaAlbums(candidates, title, artist, year, trackCount).firstOrNull { s ->
        val c = s.album
        val yearDiffers = wantYear != null && c.year != null && c.year != wantYear
        val countDiffers = trackCount > 0 && c.trackCount != null && c.trackCount != trackCount
        s.exactTitle && !(yearDiffers && countDiffers) && (!various || s.score >= VARIOUS_ARTISTS_MIN_SCORE)
    }?.album
}

/** An album track as the matcher needs it: its place in our list, its number, title and length. */
internal data class NebulaTrackKey(val index: Int, val number: Int, val title: String, val durationSeconds: Int)

private fun titleTokens(title: String): List<String> =
    searchTokens(withoutBrackets(title)).ifEmpty { searchTokens(title) }

/**
 * Pairs each of our tracks with a track of the addon's album: by title first (equal, or nearly), then
 * by position when both albums have the same number of tracks, with the length as a tie-break. Never
 * the same track twice. The result has one entry per wanted track, in order: the addon's track id, or
 * null when that track has no counterpart. Null overall when under 60% matched, which is not this album.
 */
internal fun mapNebulaTracks(addon: List<NebulaTrack>, wanted: List<NebulaTrackKey>): List<String?>? {
    if (addon.isEmpty() || wanted.isEmpty()) return null
    val samePositions = addon.size == wanted.size
    val tokens = addon.map { titleTokens(it.title).toSet() }
    val used = HashSet<Int>()
    val result = arrayOfNulls<String>(wanted.size)
    for (key in wanted.sortedBy { it.index }) {
        val want = titleTokens(key.title)
        var best = -1
        var bestScore = 0
        for (i in addon.indices) {
            if (i in used) continue
            val coverage = if (want.isEmpty()) 0.0 else want.count { it in tokens[i] }.toDouble() / want.size
            val equal = want.isNotEmpty() && tokens[i] == want.toSet()
            var score = when {
                equal -> 10
                coverage >= 0.75 && tokens[i].size <= want.size + 2 -> 7
                samePositions && i + 1 == key.number -> 3
                else -> 0
            }
            if (score == 0) continue
            if (i + 1 == key.number) score += 1
            if (key.durationSeconds > 0 && addon[i].durationSeconds > 0 &&
                kotlin.math.abs(key.durationSeconds - addon[i].durationSeconds) <= 3
            ) score += 1
            if (score > bestScore) { best = i; bestScore = score }
        }
        if (best >= 0) {
            used += best
            if (key.index in result.indices) result[key.index] = addon[best].id
        }
    }
    val matched = result.count { it != null }
    val needed = (wanted.size * 3 + 4) / 5
    return result.toList().takeIf { matched >= needed }
}
