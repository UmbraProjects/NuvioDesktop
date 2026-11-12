package com.nuvio.app.features.music.home

/*
 * fork:music-search - a guess at what a music search is for, so the row it is after comes first: "Eminem" is the artist,
 * "Eminem without me" a song, "Eminem encore" an album. A result whose title is the search (with or without its artist's
 * name before or after it) is a near-certain hit; the artist named exactly is a certain one. Only exact matches count
 * (once case, punctuation and bracketed notes like "(Remastered 2023)" are set aside), so an ordinary search keeps the
 * usual order.
 */

/** A row of results as the ranking sees it: its kind and, for each result, the name it would be searched by. */
internal class IntentRow(val kind: IntentKind, val hits: List<IntentHit>)

/** [popularity] is [popularityTier] of the artist's fans: inside a row, equally good matches go most popular first. */
internal class IntentHit(val title: String, val artists: List<String>, val popularity: Int = POPULARITY_UNKNOWN)

/** An artist nothing is known of: in the middle, below the well known and above the near unknown. */
internal const val POPULARITY_UNKNOWN = 1

/**
 * How well known an artist with [fans] (Deezer's count) is, in four broad steps: under 100 (an upload nobody follows), under
 * 10 thousand, under half a million, and more. Broad on purpose: it lifts real bands over unknown uploads without
 * reshuffling a row over small differences, and inside a step YouTube Music's own order stands.
 */
internal fun popularityTier(fans: Long?): Int = when {
    fans == null -> POPULARITY_UNKNOWN
    fans < 100 -> 0
    fans < 10_000 -> 1
    fans < 500_000 -> 2
    else -> 3
}

/**
 * In the order a tie is settled: an album over the song of the same name ("Encore"), then an EP (it holds the song of
 * its name too, so it covers both), then the song, then its single.
 */
internal enum class IntentKind { Artists, Albums, EPs, Songs, Singles, Videos }

/** The search names the artist exactly. */
internal const val MATCH_ARTIST = 100

/** The search is the artist's name and the title, either way round. */
internal const val MATCH_ARTIST_AND_TITLE = 95

/** The search is the title alone. */
internal const val MATCH_TITLE = 90

/** How well [query] names [hit] in a row of [kind]: one of the MATCH_ values, or 0. */
internal fun intentMatch(query: String, kind: IntentKind, hit: IntentHit): Int {
    val q = intentNormal(query)
    if (q.isEmpty()) return 0
    if (kind == IntentKind.Artists) return if (q == intentNormal(hit.title)) MATCH_ARTIST else 0
    val title = intentNormal(stripBracketed(hit.title))
    if (title.isEmpty()) return 0
    if (hit.artists.map(::intentNormal).any { a -> a.isNotEmpty() && (q == "$a $title" || q == "$title $a") }) return MATCH_ARTIST_AND_TITLE
    return if (q == title) MATCH_TITLE else 0
}

/**
 * The order the rows should be shown in for [query]: rows with a match first (best match first, ties in [IntentKind]
 * order), then the rest as they came. Inside each row the matching results move to the front. Returns indices into
 * [rows], each with the order of its hits.
 */
internal fun intentOrder(query: String, rows: List<IntentRow>): List<Pair<Int, List<Int>>> {
    val scored = rows.mapIndexed { i, row ->
        val scores = row.hits.map { intentMatch(query, row.kind, it) }
        // Best match first, then the better known artist; equal in both, YouTube Music's own order (the sort is stable).
        val hitOrder = row.hits.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenByDescending { row.hits[it].popularity })
        Triple(i, scores.maxOrNull() ?: 0, hitOrder)
    }
    val matched = scored.filter { it.second > 0 }
        .sortedWith(compareByDescending<Triple<Int, Int, List<Int>>> { it.second }.thenBy { rows[it.first].kind.ordinal })
    val rest = scored.filter { it.second == 0 }
    return (matched + rest).map { it.first to it.third }
}

/** Lower case, "&" as "and", everything but letters and digits as single spaces. */
internal fun intentNormal(text: String): String =
    text.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The title without bracketed notes: "Gun (Remastered 2023)" is "Gun". */
private fun stripBracketed(title: String): String =
    title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { title }
