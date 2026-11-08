package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * fork:lenses - Lineage: the records an album grew out of (left) and the later records in the same family (right), five
 * each, from one AI request per album. Each record is looked up in the person's library; only one found there can be
 * opened or played. Pure functions, so they are tested without Compose.
 */

/** How many records each side shows; the panels always lay out this many rows. */
internal const val LINEAGE_ROWS = 5

/** One record in the lineage, as the AI named it. */
internal data class LineageRecord(val artist: String, val title: String, val year: Int, val why: String)

/** The album's lineage: [before] oldest last (nearest first), [after] nearest first, each with a one-line [thread]. */
internal data class Lineage(
    val before: List<LineageRecord>,
    val after: List<LineageRecord>,
    val beforeThread: String?,
    val afterThread: String?,
    /** False for the open-data lineage ([OpenLineage]): Wikidata and YouTube Music, no AI. */
    val byAi: Boolean = true,
    /** Where an open-data lineage came from ("From Wikidata and YouTube Music"), for the panels' footer. */
    val sources: String? = null,
)

/** "−18 yrs" for a record before the album, "+7 yrs" after it, "Same year" for neither. */
internal fun lineageGap(albumYear: Int, recordYear: Int): String {
    val d = recordYear - albumYear
    return when {
        d == 0 -> "Same year"
        d < 0 -> "−${-d} yr${if (d == -1) "" else "s"}"
        else -> "+$d yr${if (d == 1) "" else "s"}"
    }
}

/** A record found in the library: its artist and release (with the id that opens it). */
internal data class LineageMatch(val artist: LibraryArtist, val release: LibraryRelease)

/**
 * The library's copy of [record]: an artist whose name matches (brackets and "The" aside) with a release of the same title
 * that YouTube Music can open. Albums and EPs are preferred over singles of the same name.
 */
internal fun findInLibrary(record: LineageRecord, artists: List<LibraryArtist>): LineageMatch? {
    val who = artistKey(record.artist)
    val what = songKey(record.title)
    if (who.isEmpty() || what.isEmpty()) return null
    for (artist in artists) {
        if (artistKey(artist.name) != who) continue
        val hits = artist.releases.filter { it.ytBrowseId != null && songKey(it.title) == what }
        val best = hits.minByOrNull { if (it.kind == ReleaseKind.Single) 1 else 0 } ?: continue
        return LineageMatch(artist, best)
    }
    return null
}

private fun artistKey(name: String): String = songKey(name.trim().removePrefix("The ").removePrefix("the "))

internal const val LINEAGE_SYSTEM_PROMPT =
    "You are a music historian for a listening app. You name real, well-known records only, with their real release " +
        "years, and keep every line short. Reply with one JSON object and nothing else."

/** The prompt for [album] by [artist], released in [year]. */
internal fun lineagePrompt(artist: String, album: String, year: Int): String = buildString {
    append("Album: ").append(album).append(" by ").append(artist).append(" (").append(year).append(").\n")
    append("Name ").append(LINEAGE_ROWS).append(" earlier records by other artists that this album's sound grew out of, ")
    append("and ").append(LINEAGE_ROWS).append(" later records by other artists in the same family of sound.\n")
    append("Return JSON of this shape:\n")
    append("{\"before\": [{\"artist\": \"...\", \"title\": \"album or single title\", \"year\": 1975, \"why\": \"at most 6 words on what it shares\"}],\n")
    append(" \"after\": [same shape],\n")
    append(" \"before_thread\": \"one plain sentence on what joins the earlier records to this album\",\n")
    append(" \"after_thread\": \"one plain sentence on what joins the later records to it; say it is about sound, not a claim of influence\"}\n")
    append("List the nearest in time first on each side. Earlier records must be from before ").append(year)
    append(", later ones from after it.")
}

private val LINEAGE_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.text(key: String, max: Int): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("""\s+"""), " ")?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { if (it.length <= max) it else it.take(max).substringBeforeLast(' ') + "…" }

private fun JsonObject.year(): Int? {
    val p = this["year"] as? JsonPrimitive ?: return null
    return p.content.trim().take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
}

/**
 * The model's [reply] as the lineage of an album from [albumYear]: records on the wrong side of the year, repeats, the
 * album's own [artist], and lines missing a title or year are dropped; at most [LINEAGE_ROWS] a side. Null when neither
 * side has a record left.
 */
internal fun parseLineage(reply: String, albumYear: Int, artist: String): Lineage? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { LINEAGE_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val own = artistKey(artist)
    fun side(key: String, keep: (Int) -> Boolean): List<LineageRecord> =
        (root[key] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val who = o.text("artist", 48) ?: return@mapNotNull null
            val what = o.text("title", 60) ?: return@mapNotNull null
            val year = o.year()?.takeIf(keep) ?: return@mapNotNull null
            if (artistKey(who) == own) return@mapNotNull null
            LineageRecord(who, what, year, o.text("why", 60).orEmpty())
        }.distinctBy { artistKey(it.artist) + "|" + songKey(it.title) }.take(LINEAGE_ROWS)
    val before = side("before") { it < albumYear }
    val after = side("after") { it > albumYear }
    if (before.isEmpty() && after.isEmpty()) return null
    return Lineage(before, after, root.text("before_thread", 200), root.text("after_thread", 200))
}

/** The album's year from its page ("1994", "2011-01-19"), or null. */
internal fun lineageYear(year: String?): Int? = year?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
