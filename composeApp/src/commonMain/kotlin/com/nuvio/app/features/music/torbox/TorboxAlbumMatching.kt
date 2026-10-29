package com.nuvio.app.features.music.torbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Pure logic for playing an album from TorBox: turning an album into a search, picking the right
 * cached torrent from the results, and matching the torrent's files to the album's tracks. No
 * network and no UI, so it is all covered by unit tests; TorboxMusicSource does the calling.
 */

/** One row of a TorBox torrent search. [cached] is TorBox's own answer, so a cached hit starts at once. */
internal data class TorboxSearchHit(
    val hash: String,
    val title: String,
    val magnet: String?,
    val seeders: Int,
    val sizeBytes: Long,
    val cached: Boolean,
)

/**
 * Reads a TorBox search response. Tolerant on purpose: the results are either `data.torrents` or a
 * bare `data` array, every field but the hash and a title may be missing, and anything unreadable
 * gives an empty list rather than an error.
 */
internal fun parseTorboxSearchHits(raw: String): List<TorboxSearchHit> {
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
    val items: JsonArray = when (val data = root["data"]) {
        is JsonArray -> data
        is JsonObject -> data["torrents"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return items.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val hash = o.string("hash")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val title = (o.string("raw_title") ?: o.string("title"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TorboxSearchHit(
            hash = hash,
            title = title,
            magnet = o.string("magnet")?.takeIf { it.isNotBlank() },
            seeders = o.int("last_known_seeders") ?: o.int("seeders") ?: 0,
            sizeBytes = o.long("size") ?: 0L,
            cached = (o["cached"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

// region album -> search

/** Words that say which edition of an album this is, not which album. They never have to appear in a torrent's title. */
private val EDITION_WORDS = setOf(
    "deluxe", "edition", "remastered", "remaster", "expanded", "anniversary", "version", "bonus",
    "explicit", "ep", "single", "album", "the",
)

/** Words that mean the torrent holds many albums, not this one. */
private val BULK_WORDS = setOf(
    "discography", "collection", "anthology", "complete", "boxset", "box", "videography", "albums",
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

/** What to type into TorBox's search: the artist and the album's core words. */
internal fun torboxAlbumQuery(albumTitle: String, artist: String): String {
    val artistTokens = if (isVariousArtists(artist)) emptyList() else searchTokens(artist).filter { it != "the" }
    return (artistTokens + albumCoreTokens(albumTitle)).joinToString(" ")
}

/**
 * The cached hits that are really this album, best first. A hit has to carry the artist's and the
 * album's words, may not be a discography or collection, and must be cached. Lossless beats 320
 * beats V0 beats plain MP3; a matching year, a tidy title and more seeders break ties.
 */
internal fun rankTorboxAlbumHits(
    hits: List<TorboxSearchHit>,
    albumTitle: String,
    artist: String,
    year: String?,
): List<TorboxSearchHit> {
    val albumTokens = albumCoreTokens(albumTitle)
    if (albumTokens.isEmpty()) return emptyList()
    val artistTokens = if (isVariousArtists(artist)) emptyList() else searchTokens(artist).filter { it != "the" }
    val wantedYear = year?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    val albumSet = albumTokens.toSet()
    return hits
        .filter { it.cached }
        .mapNotNull { hit ->
            val set = searchTokens(hit.title).toSet()
            if (!set.containsAll(albumTokens) || !set.containsAll(artistTokens)) return@mapNotNull null
            if (BULK_WORDS.any { it in set && it !in albumSet }) return@mapNotNull null
            var score = when {
                "flac" in set || "lossless" in set || "alac" in set -> 40
                "320" in set -> 25
                "v0" in set -> 20
                "mp3" in set -> 10
                else -> 0
            }
            if (wantedYear != null && wantedYear in set) score += 15
            score -= (set.size - albumTokens.size - artistTokens.size).coerceIn(0, 20)
            score += hit.seeders.coerceIn(0, 50) / 5
            hit to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

/** A short label for the offer: "FLAC", "MP3 320" or "". */
internal fun torboxQualityLabel(title: String): String {
    val set = searchTokens(title).toSet()
    return when {
        "flac" in set -> "FLAC"
        "alac" in set -> "ALAC"
        "lossless" in set -> "Lossless"
        "320" in set -> "MP3 320"
        "v0" in set -> "MP3 V0"
        "mp3" in set -> "MP3"
        else -> ""
    }
}

// endregion

// region files -> tracks

/** An album track as the matcher needs it: its place in the album's list, its number, its title. */
internal data class TorboxTrackKey(val index: Int, val number: Int, val title: String)

/** One file of a TorBox torrent. */
internal data class TorboxFile(val id: Int, val path: String)

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "wv")

private val LEADING_NUMBER = Regex("^(?:\\d{1,2}[-.])?(\\d{1,3})(?=\\D|$)")

private fun fileName(path: String): String {
    val segment = path.replace('\\', '/').substringAfterLast('/')
    return segment.substringBeforeLast('.', segment)
}

private fun isAudio(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

private fun leadingNumber(path: String): Int =
    LEADING_NUMBER.find(fileName(path).trim())?.groupValues?.get(1)?.toIntOrNull() ?: -1

/** The words of a file's name after its track number: "03 - Something.flac" gives [something]. */
private fun fileTitleTokens(path: String): Set<String> {
    val name = fileName(path).trim()
    val rest = LEADING_NUMBER.find(name)?.let { name.substring(it.range.last + 1) } ?: name
    return searchTokens(rest).toSet()
}

private fun trackTitleTokens(title: String): List<String> =
    searchTokens(withoutBrackets(title)).ifEmpty { searchTokens(title) }

/**
 * Pairs each album track with a file of the torrent, by title first and track number second, and
 * never the same file twice. Returns track index to file, or null when too few tracks found a file
 * (under 60%) for the torrent to be trusted as this album.
 */
internal fun mapTorboxFilesToTracks(files: List<TorboxFile>, tracks: List<TorboxTrackKey>): Map<Int, TorboxFile>? {
    val audio = files.filter { isAudio(it.path) }
    if (audio.isEmpty() || tracks.isEmpty()) return null

    class Candidate(val file: TorboxFile, val tokens: Set<String>, val number: Int)

    val candidates = audio.map { Candidate(it, fileTitleTokens(it.path), leadingNumber(it.path)) }
    val numberCount = candidates.groupingBy { it.number }.eachCount()
    val used = HashSet<Int>()
    val result = LinkedHashMap<Int, TorboxFile>()
    for (track in tracks.sortedBy { it.index }) {
        val wanted = trackTitleTokens(track.title)
        var best: Candidate? = null
        var bestScore = 0
        for (c in candidates) {
            if (c.file.id in used) continue
            val coverage = if (wanted.isEmpty()) 0.0 else wanted.count { it in c.tokens }.toDouble() / wanted.size
            val numberMatch = track.number > 0 && c.number == track.number
            val score = when {
                coverage >= 0.75 -> 10 + (if (numberMatch) 5 else 0)
                numberMatch && numberCount[c.number] == 1 -> 6 // the only file with this number: the title just differs
                else -> 0
            }
            if (score > bestScore) { best = c; bestScore = score }
        }
        if (best != null) {
            used += best.file.id
            result[track.index] = best.file
        }
    }
    val needed = (tracks.size * 3 + 4) / 5
    return result.takeIf { it.size >= needed }
}

// endregion

// region failure reasons

/**
 * A short, plain reason a TorBox search failed, for the album page and the log. [status] is the HTTP
 * status when TorBox answered; [error] is the exception message when it could not be reached.
 */
internal fun torboxSearchFailureReason(status: Int?, error: String? = null): String = when {
    status == 401 -> "TorBox didn't accept your API key (HTTP 401)."
    status == 403 -> "TorBox refused the search (HTTP 403). Search needs a paid plan."
    status == 404 -> "TorBox's search address wasn't found (HTTP 404)."
    status == 429 -> "TorBox is limiting searches right now (HTTP 429). Wait a minute."
    status != null && status >= 500 -> "TorBox's search is down (HTTP $status)."
    status != null -> "TorBox's search answered HTTP $status."
    !error.isNullOrBlank() -> "Couldn't reach TorBox's search ($error)."
    else -> "Couldn't reach TorBox's search."
}

// endregion
