package com.nuvio.app.features.music.booklet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/*
 * fork:booklet - the sleeve notes shown beside the lyrics on the Now Playing stage, in the spirit of a CD's inlay booklet:
 * a few lines about the album, who made it, and a short note per track. Everything here is pure (text in, plain data out)
 * so it can be tested; the lookup is AlbumBookletRepository and the panel is NowPlayingBooklet.
 */

internal enum class BookletSource { Ai, Wikipedia }

internal data class BookletCredit(val role: String, val names: String)

/**
 * One album's booklet. [trackNotes] is keyed by the track's place in the album, 1 for the first. A Wikipedia booklet has
 * an [intro] only; the panel shows the track list in its place.
 */
internal data class AlbumBooklet(
    val intro: String,
    val credits: List<BookletCredit>,
    val trackNotes: Map<Int, String>,
    val source: BookletSource,
)

internal data class BookletTrack(val title: String, val durationSeconds: Int)

/** What is known about the album being played, enough to look it up and to ask about it. */
internal data class BookletRequest(
    val artist: String,
    val album: String,
    val year: String?,
    val tracks: List<BookletTrack>,
)

private const val MAX_INTRO_CHARS = 700
private const val MAX_NOTE_CHARS = 260
private const val MAX_CREDITS = 8

/** Lower case, letters and digits only, single spaces: "Travelling Without Moving (Deluxe)" and its plain title agree on the words. */
internal fun bookletNorm(text: String): String =
    buildString {
        var gap = false
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) {
                if (gap && isNotEmpty()) append(' ')
                gap = false
                append(c)
            } else {
                gap = true
            }
        }
    }

/** The key one album's booklet is cached under for [mode] ("ai" with the model, or "wiki"). */
internal fun bookletKey(request: BookletRequest, mode: String): String =
    "v1|$mode|${bookletNorm(request.artist)}|${bookletNorm(request.album)}"

/** [text] cut to at most [max] characters: at the last sentence end that fits, else at a word with an ellipsis. */
internal fun cutAtSentence(text: String, max: Int): String {
    val clean = text.replace(Regex("""\s+"""), " ").trim()
    if (clean.length <= max) return clean
    val head = clean.take(max)
    val stop = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
    if (stop >= max / 2) return head.take(stop + 1)
    val space = head.lastIndexOf(' ')
    val cut = if (space > 0) head.take(space) else head
    return cut.trimEnd(',', ';', ':', '-', ' ', '.') + "…"
}

/** The Wikipedia text as a booklet intro: its opening, ended at a sentence. Null when there is nothing to show. */
internal fun wikipediaBooklet(text: String?): AlbumBooklet? {
    val lead = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.length > 40 } ?: return null
    // Whatever follows a heading ("== Background ==") is not the opening.
    val intro = cutAtSentence(lead, MAX_INTRO_CHARS)
    return AlbumBooklet(intro, emptyList(), emptyMap(), BookletSource.Wikipedia)
}

// region AI

internal const val BOOKLET_SYSTEM_PROMPT =
    "You write short sleeve notes for an album's booklet. Use only the facts in the Wikipedia text and the track list " +
        "you are given. If something is not stated there, leave it out: never invent names, dates, places, chart " +
        "positions or quotes. Write plain text with no markdown. Reply with one JSON object and nothing else."

/** The user message: the album, its tracks, and the Wikipedia text it is to be grounded in. */
internal fun bookletPrompt(request: BookletRequest, wikipediaText: String): String = buildString {
    append("Album: ").append(request.album).append('\n')
    append("Artist: ").append(request.artist).append('\n')
    request.year?.let { append("Year: ").append(it).append('\n') }
    append("\nTrack list:\n")
    request.tracks.forEachIndexed { i, track ->
        append(i + 1).append(". ").append(track.title)
        if (track.durationSeconds > 0) append(" (").append(track.durationSeconds / 60).append(':').append((track.durationSeconds % 60).toString().padStart(2, '0')).append(')')
        append('\n')
    }
    append("\nWikipedia text:\n<<<\n").append(wikipediaText.take(3_500)).append("\n>>>\n\n")
    append("Return JSON of this shape:\n")
    append("{\"intro\": \"2 to 4 sentences about the album: when and where it was made, its sound, how it was received\",\n")
    append(" \"credits\": [{\"role\": \"Producer\", \"names\": \"...\"}],\n")
    append(" \"tracks\": {\"1\": \"one sentence about track 1, only if the text says something about it\"}}\n")
    append("Leave out any credit or track note the text does not support. Track keys are the numbers in the track list.")
}

private val BOOKLET_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The booklet in the model's [reply], or null when it cannot be read (not JSON, cut off, or no usable intro). Tolerates a
 * code fence or a sentence around the JSON, drops credits and notes that are empty, and keeps only notes for tracks that exist.
 */
internal fun parseAiBooklet(reply: String, trackCount: Int): AlbumBooklet? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { BOOKLET_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val intro = root["intro"].text()?.takeIf { it.length >= 20 }?.let { cutAtSentence(it, MAX_INTRO_CHARS) } ?: return null
    val credits = (root["credits"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val role = obj["role"].text() ?: return@mapNotNull null
        val names = obj["names"].text() ?: return@mapNotNull null
        BookletCredit(role.take(40), names.take(120))
    }.take(MAX_CREDITS)
    val notes = LinkedHashMap<Int, String>()
    (root["tracks"] as? JsonObject)?.forEach { (key, value) ->
        val number = key.trim().toIntOrNull() ?: return@forEach
        if (number !in 1..trackCount) return@forEach
        val note = value.text() ?: return@forEach
        notes[number] = cutAtSentence(note, MAX_NOTE_CHARS)
    }
    return AlbumBooklet(intro, credits, notes.entries.sortedBy { it.key }.associate { it.key to it.value }, BookletSource.Ai)
}

// endregion

// region cache

/** One cached answer: [booklet] null is a remembered "nothing found" (so the lookup is not repeated at once). */
internal data class BookletEntry(val atEpochMs: Long, val booklet: AlbumBooklet?)

/** `at` first, so the age can be read without parsing the rest. */
internal fun encodeBookletEntry(entry: BookletEntry): String = buildJsonObject {
    put("at", JsonPrimitive(entry.atEpochMs))
    entry.booklet?.let { booklet ->
        put("s", JsonPrimitive(if (booklet.source == BookletSource.Ai) "ai" else "wiki"))
        put("i", JsonPrimitive(booklet.intro))
        put("c", buildJsonArray {
            booklet.credits.forEach { credit -> add(buildJsonArray { add(JsonPrimitive(credit.role)); add(JsonPrimitive(credit.names)) }) }
        })
        put("t", buildJsonObject { booklet.trackNotes.forEach { (number, note) -> put(number.toString(), JsonPrimitive(note)) } })
    }
}.toString()

/** The entry [encodeBookletEntry] wrote, or null when [raw] is missing or damaged. */
internal fun decodeBookletEntry(raw: String?): BookletEntry? {
    if (raw == null) return null
    val root = runCatching { BOOKLET_JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val at = (root["at"] as? JsonPrimitive)?.longOrNull ?: return null
    val intro = root["i"].text() ?: return BookletEntry(at, null)
    val source = if (root["s"].text() == "ai") BookletSource.Ai else BookletSource.Wikipedia
    val credits = (root["c"] as? JsonArray).orEmpty().mapNotNull { element ->
        val pair = element as? JsonArray ?: return@mapNotNull null
        val role = pair.getOrNull(0).text() ?: return@mapNotNull null
        val names = pair.getOrNull(1).text() ?: return@mapNotNull null
        BookletCredit(role, names)
    }
    val notes = (root["t"] as? JsonObject)?.mapNotNull { (key, value) ->
        val number = key.toIntOrNull() ?: return@mapNotNull null
        val note = value.text() ?: return@mapNotNull null
        number to note
    }?.sortedBy { it.first }?.toMap().orEmpty()
    return BookletEntry(at, AlbumBooklet(intro, credits, notes, source))
}

private const val HOUR_MS = 60L * 60L * 1_000L
private const val DAY_MS = 24L * HOUR_MS

/** How long an AI booklet is trusted, a Wikipedia one, and a "nothing" from each (an AI failure is retried sooner). */
internal const val BOOKLET_AI_TTL_MS = 180 * DAY_MS
internal const val BOOKLET_WIKI_TTL_MS = 30 * DAY_MS
internal const val BOOKLET_AI_MISS_TTL_MS = 2 * HOUR_MS
internal const val BOOKLET_WIKI_MISS_TTL_MS = 3 * DAY_MS

/** Entries kept; past it the oldest go. A booklet is a few KB. */
internal const val BOOKLET_CACHE_MAX = 400
internal const val BOOKLET_CACHE_SLACK = 20

internal fun BookletEntry.isFresh(nowMs: Long, hitTtlMs: Long, missTtlMs: Long): Boolean =
    nowMs - atEpochMs < (if (booklet != null) hitTtlMs else missTtlMs)

/** The keys to drop so no more than [capacity] remain: the oldest first. [ages] maps each key to when it was cached. */
internal fun bookletKeysToEvict(ages: Map<String, Long>, capacity: Int): List<String> {
    if (ages.size <= capacity) return emptyList()
    return ages.entries.sortedBy { it.value }.take(ages.size - capacity).map { it.key }
}

// endregion

/**
 * Which note the panel is on: the place (1 for the first) of the track playing in the album's [videoIds], or null when it
 * is not among them (a track from another album in a mixed queue).
 */
internal fun bookletTrackNumber(videoIds: List<String>, playingVideoId: String?): Int? {
    if (playingVideoId == null) return null
    val at = videoIds.indexOf(playingVideoId)
    return if (at < 0) null else at + 1
}

/** An album a booklet can be written for: a real album, EP or single (`MPRE...` on YouTube Music), not a mix or playlist. */
internal fun isBookletAlbum(browseId: String?): Boolean = browseId?.startsWith("MPRE") == true
