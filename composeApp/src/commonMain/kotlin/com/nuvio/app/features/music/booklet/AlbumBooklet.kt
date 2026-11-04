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
    /** Question-and-answer trivia cards, about the album and about the artist. */
    val facts: List<BookletFact> = emptyList(),
    /** People who appear on the album, with the track they are on when the text says. */
    val guests: List<BookletGuest> = emptyList(),
    /** What the person pinned over the written text ("intro", "tnote:3", "fact:2"); set when pins are laid over, never cached. */
    val pinned: Set<String> = emptySet(),
)

internal enum class FactKind { Album, Artist }

/** One trivia card: the [question] is on its face, the [answer] is revealed. */
internal data class BookletFact(val kind: FactKind, val question: String, val answer: String)

internal data class BookletGuest(val name: String, val trackNumber: Int?)

internal data class BookletTrack(val title: String, val durationSeconds: Int)

/** One of the artist's albums, for the timeline. [browseId] opens it when the library has it. */
internal data class BookletRelease(val title: String, val year: Int, val browseId: String?)

/** What the app already knows about the artist (from the music library), used for cards and for the timeline. */
internal data class BookletArtist(
    /** "Group", "Person", "Orchestra" as MusicBrainz says; null when unknown. */
    val type: String? = null,
    val beginYear: String? = null,
    val country: String? = null,
    val genres: List<String> = emptyList(),
    val biography: String? = null,
    val albums: List<BookletRelease> = emptyList(),
)

/** What is known about the album being played, enough to look it up and to ask about it. */
internal data class BookletRequest(
    val artist: String,
    val album: String,
    val year: String?,
    val tracks: List<BookletTrack>,
    val artistInfo: BookletArtist? = null,
)

private const val MAX_INTRO_CHARS = 700
private const val MAX_NOTE_CHARS = 260
private const val MAX_CREDITS = 8
private const val MAX_FACTS = 5
private const val MAX_PLAIN_FACTS = 3
/** Trivia cards per page of the deck. */
internal const val FACTS_PER_PAGE = 3
private const val MAX_GUESTS = 8
private const val MAX_QUESTION_CHARS = 140
private const val MAX_ANSWER_CHARS = 320

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
    "v4|$mode|${bookletNorm(request.artist)}|${bookletNorm(request.album)}"

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

/**
 * The Wikipedia text as a booklet intro: its opening, ended at a sentence. Null when there is nothing to show. With a
 * [request] (and the artist's own Wikipedia text) it also carries trivia cards built straight from what is known.
 */
internal fun wikipediaBooklet(text: String?, request: BookletRequest? = null, artistText: String? = null): AlbumBooklet? {
    val lead = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.length > 40 } ?: return null
    // Whatever follows a heading ("== Background ==") is not the opening.
    val intro = cutAtSentence(lead, MAX_INTRO_CHARS)
    val facts = if (request != null) fallbackFacts(request, lead, artistText) else emptyList()
    return AlbumBooklet(intro, emptyList(), emptyMap(), BookletSource.Wikipedia, facts)
}

/** The sentences of [text], in order: a stop and a space before a capital or quote ends one. */
internal fun bookletSentences(text: String): List<String> {
    val clean = text.replace(Regex("""\s+"""), " ").trim()
    val parts = ArrayList<String>()
    var start = 0
    for (i in 0 until clean.length - 2) {
        val c = clean[i]
        if ((c == '.' || c == '!' || c == '?') && clean[i + 1] == ' ' && (clean[i + 2].isUpperCase() || clean[i + 2] == '"')) {
            // "St. Louis", "Vol. 2", "U.S. tour": a one or two letter word before the stop is not the end.
            val word = clean.substring(start, i).substringAfterLast(' ')
            if (word.length <= 2 && c == '.') continue
            parts += clean.substring(start, i + 1).trim()
            start = i + 2
        }
    }
    if (start < clean.length) parts += clean.substring(start).trim()
    return parts.filter { it.length >= 3 }
}

private fun beginVerb(type: String?): String = when (type?.lowercase()) {
    "person" -> "born"
    "group", "orchestra", "choir" -> "formed"
    else -> "started"
}

/**
 * Trivia cards made without the AI, from the text and the library data alone: fewer than the AI writes, but each one is a
 * stated fact. Cards that need data that is missing are left out.
 */
internal fun fallbackFacts(request: BookletRequest, albumLead: String?, artistText: String?): List<BookletFact> {
    val artist = request.artist
    val info = request.artistInfo
    val artistSentences = (artistText ?: info?.biography)?.let(::bookletSentences).orEmpty()
    val who = artistSentences.firstOrNull()?.takeIf { it.length >= 30 }?.let {
        BookletFact(FactKind.Artist, "Who is $artist?", cutAtSentence(it, MAX_ANSWER_CHARS))
    }
    val began = info?.beginYear?.takeIf { it.isNotBlank() }?.let { year ->
        val where = info.country?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        BookletFact(FactKind.Artist, "When were $artist ${beginVerb(info.type)}?", "$year$where")
    }
    val sound = info?.genres?.filter { it.isNotBlank() }?.take(3)?.takeIf { it.isNotEmpty() }?.let {
        BookletFact(FactKind.Artist, "What sound is $artist known for?", it.joinToString(", "))
    }
    val timeline = timelineFor(info, request.album, request.year)
    val before = timeline?.entries?.getOrNull(timeline.currentIndex - 1)?.let {
        BookletFact(FactKind.Album, "Which album came before ${request.album}?", "${it.title} (${it.year})")
    }
    val after = timeline?.entries?.getOrNull(timeline.currentIndex + 1)?.let {
        BookletFact(FactKind.Album, "And which came after it?", "${it.title} (${it.year})")
    }
    val length = if (request.tracks.size >= 2) {
        val seconds = request.tracks.sumOf { it.durationSeconds }
        val about = if (seconds >= 60) ", about ${(seconds + 30) / 60} minutes long" else ""
        BookletFact(FactKind.Album, "How many tracks are on ${request.album}?", "${request.tracks.size} tracks$about.")
    } else {
        null
    }
    val story = albumLead?.let(::bookletSentences)?.drop(2)?.take(2)?.joinToString(" ")?.takeIf { it.length >= 40 }?.let {
        BookletFact(FactKind.Album, "What is the story behind ${request.album}?", cutAtSentence(it, MAX_ANSWER_CHARS))
    }
    // Without the AI there are three cards, picked to mix the artist and the album.
    return listOfNotNull(who, before, story, began, sound, after, length).take(MAX_PLAIN_FACTS)
}

/** The artist's albums in order with the one playing marked, for the timeline page. */
internal data class BookletTimeline(val entries: List<BookletRelease>, val currentIndex: Int)

/**
 * The timeline of [info]'s albums around [albumTitle] ([albumYear] when the library lacks the album), at most nine of them.
 * Null when there is nothing to walk (fewer than two albums, or no year for the one playing).
 */
internal fun timelineFor(info: BookletArtist?, albumTitle: String, albumYear: String?): BookletTimeline? {
    info ?: return null
    val norm = bookletNorm(albumTitle)
    val all = info.albums.distinctBy { bookletNorm(it.title) }.toMutableList()
    var at = all.indexOfFirst { bookletNorm(it.title) == norm }
    if (at < 0) {
        val year = albumYear?.take(4)?.toIntOrNull() ?: return null
        all += BookletRelease(albumTitle, year, null)
    }
    val sorted = all.sortedBy { it.year }
    at = sorted.indexOfFirst { bookletNorm(it.title) == norm }
    if (sorted.size < 2 || at < 0) return null
    val window = 9
    val from = (at - window / 2).coerceIn(0, (sorted.size - window).coerceAtLeast(0))
    val shown = sorted.drop(from).take(window)
    return BookletTimeline(shown, at - from)
}

/** What the cover page's stat strip says: label and value pairs, only for what is known (at most four). */
internal fun coverStats(request: BookletRequest, released: String?): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    released?.takeIf { it.isNotBlank() }?.let { out += "Released" to it }
    if (request.tracks.isNotEmpty()) out += "Tracks" to request.tracks.size.toString()
    timelineFor(request.artistInfo, request.album, request.year)?.let { t ->
        t.entries.getOrNull(t.currentIndex - 1)?.let { out += "Before it" to "${it.title}, ${it.year}" }
    }
    request.artistInfo?.genres?.firstOrNull { it.isNotBlank() }?.let { out += "Sound" to it }
    return out.take(4)
}

/** One page of the deck. */
internal sealed interface LinerPage {
    data object Cover : LinerPage
    /** [facts] are cards [startIndex] onward of the booklet's list; this is page [number] of [total] of trivia. */
    data class Facts(val number: Int, val total: Int, val startIndex: Int, val facts: List<BookletFact>) : LinerPage
    data class Guests(val guests: List<BookletGuest>) : LinerPage
    data class Timeline(val timeline: BookletTimeline) : LinerPage
    /** [note] is the one line the booklet has; [story] (fork) the song's own story, which comes later than the booklet. */
    data class Track(val number: Int, val title: String, val note: String, val story: SongStory? = null) : LinerPage
}

/** The deck for [booklet], in order; a page with nothing to show is left out. [playing] is the track number playing, if any. */
internal fun linerPages(booklet: AlbumBooklet, request: BookletRequest, playing: Int?, story: SongStory? = null): List<LinerPage> {
    val pages = ArrayList<LinerPage>()
    pages += LinerPage.Cover
    val groups = booklet.facts.chunked(FACTS_PER_PAGE)
    groups.forEachIndexed { g, group -> pages += LinerPage.Facts(g + 1, groups.size, g * FACTS_PER_PAGE, group) }
    if (booklet.guests.isNotEmpty()) pages += LinerPage.Guests(booklet.guests)
    timelineFor(request.artistInfo, request.album, request.year)?.let { pages += LinerPage.Timeline(it) }
    val note = playing?.let { booklet.trackNotes[it] }
    if (playing != null && (note != null || story != null)) {
        pages += LinerPage.Track(playing, request.tracks.getOrNull(playing - 1)?.title.orEmpty(), note.orEmpty(), story)
    }
    return pages
}

/** The track a guest chip jumps to, matched by name in the guest list order; null when the text did not say. */
internal fun guestTrack(guest: BookletGuest, trackCount: Int): Int? = guest.trackNumber?.takeIf { it in 1..trackCount }

// region AI

internal const val BOOKLET_SYSTEM_PROMPT =
    "You write the sleeve notes and trivia cards for an album's booklet. Use only the facts in the texts and lists you " +
        "are given. If something is not stated there, leave it out: never invent names, dates, places, chart " +
        "positions, sales or quotes. Write plain text with no markdown. Reply with one JSON object and nothing else."

/** The user message: the album, its tracks, and the Wikipedia text (and what is known of the artist) it is to be grounded in. */
internal fun bookletPrompt(request: BookletRequest, wikipediaText: String, artistText: String? = null): String = buildString {
    append("Album: ").append(request.album).append('\n')
    append("Artist: ").append(request.artist).append('\n')
    request.year?.let { append("Year: ").append(it).append('\n') }
    request.artistInfo?.let { info ->
        info.type?.let { append("Artist type: ").append(it).append('\n') }
        info.beginYear?.let { append("Artist began: ").append(it).append('\n') }
        info.country?.let { append("Artist country: ").append(it).append('\n') }
        if (info.genres.isNotEmpty()) append("Artist genres: ").append(info.genres.take(4).joinToString(", ")).append('\n')
        if (info.albums.isNotEmpty()) {
            append("Artist albums: ").append(info.albums.sortedBy { it.year }.take(12).joinToString("; ") { "${it.title} (${it.year})" }).append('\n')
        }
    }
    append("\nTrack list:\n")
    request.tracks.forEachIndexed { i, track ->
        append(i + 1).append(". ").append(track.title)
        if (track.durationSeconds > 0) append(" (").append(track.durationSeconds / 60).append(':').append((track.durationSeconds % 60).toString().padStart(2, '0')).append(')')
        append('\n')
    }
    append("\nAlbum Wikipedia text:\n<<<\n").append(wikipediaText.take(3_500)).append("\n>>>\n")
    artistText?.takeIf { it.isNotBlank() }?.let { append("\nArtist text:\n<<<\n").append(it.take(2_500)).append("\n>>>\n") }
    append("\nReturn JSON of this shape:\n")
    append("{\"intro\": \"2 to 4 sentences about the album: when and where it was made, its sound, how it was received\",\n")
    append(" \"credits\": [{\"role\": \"Producer\", \"names\": \"...\"}],\n")
    append(" \"guests\": [{\"name\": \"a guest or featured artist\", \"track\": 3}],\n")
    append(" \"tracks\": {\"1\": \"one sentence about track 1, only if the text says something about it\"}}\n")
    append("Leave out any credit, guest or track note the texts do not support. Track keys and guest tracks are the numbers in the track list.")
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
    val seen = HashSet<String>()
    val facts = (root["facts"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val q = obj["q"].text() ?: obj["question"].text() ?: return@mapNotNull null
        val a = obj["a"].text() ?: obj["answer"].text() ?: return@mapNotNull null
        if (q.length < 8 || a.length < 3 || !seen.add(bookletNorm(q))) return@mapNotNull null
        val kind = if (obj["kind"].text()?.lowercase() == "artist") FactKind.Artist else FactKind.Album
        BookletFact(kind, cutAtSentence(q, MAX_QUESTION_CHARS), cutAtSentence(a, MAX_ANSWER_CHARS))
    }.take(MAX_FACTS)
    val guests = (root["guests"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj["name"].text() ?: return@mapNotNull null
        val track = (obj["track"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()?.takeIf { it in 1..trackCount }
        BookletGuest(name.take(60), track)
    }.distinctBy { bookletNorm(it.name) }.take(MAX_GUESTS)
    return AlbumBooklet(intro, credits, notes.entries.sortedBy { it.key }.associate { it.key to it.value }, BookletSource.Ai, facts, guests)
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
        put("f", buildJsonArray {
            booklet.facts.forEach { fact ->
                add(buildJsonArray { add(JsonPrimitive(if (fact.kind == FactKind.Artist) "artist" else "album")); add(JsonPrimitive(fact.question)); add(JsonPrimitive(fact.answer)) })
            }
        })
        put("g", buildJsonArray {
            booklet.guests.forEach { guest -> add(buildJsonArray { add(JsonPrimitive(guest.name)); add(JsonPrimitive(guest.trackNumber ?: 0)) }) }
        })
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
    val facts = (root["f"] as? JsonArray).orEmpty().mapNotNull { element ->
        val triple = element as? JsonArray ?: return@mapNotNull null
        val q = triple.getOrNull(1).text() ?: return@mapNotNull null
        val a = triple.getOrNull(2).text() ?: return@mapNotNull null
        BookletFact(if (triple.getOrNull(0).text() == "artist") FactKind.Artist else FactKind.Album, q, a)
    }
    val guests = (root["g"] as? JsonArray).orEmpty().mapNotNull { element ->
        val pair = element as? JsonArray ?: return@mapNotNull null
        val name = pair.getOrNull(0).text() ?: return@mapNotNull null
        BookletGuest(name, (pair.getOrNull(1) as? JsonPrimitive)?.longOrNull?.toInt()?.takeIf { it > 0 })
    }
    return BookletEntry(at, AlbumBooklet(intro, credits, notes, source, facts, guests))
}

private const val HOUR_MS = 60L * 60L * 1_000L
private const val DAY_MS = 24L * HOUR_MS

/** How long an AI booklet is trusted, a Wikipedia one, and a "nothing" from each (an AI failure is retried sooner). */
internal const val BOOKLET_AI_TTL_MS = 180 * DAY_MS
internal const val BOOKLET_WIKI_TTL_MS = 30 * DAY_MS
internal const val BOOKLET_AI_MISS_TTL_MS = 2 * HOUR_MS
internal const val BOOKLET_WIKI_MISS_TTL_MS = 3 * DAY_MS

/** Entries kept; past it the oldest go. A booklet is a few KB. */
internal const val BOOKLET_CACHE_MAX = 1_500
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

/** Where the deck is: the page and, on a page that walks a list (guests, the timeline), the entry under the cursor. */
internal data class DeckPosition(val page: Int, val cursor: Int)

/** Left or Right ([direction] -1 or 1): the next or previous page. Unchanged at the first or last page. */
internal fun deckTurn(position: DeckPosition, pageCount: Int, direction: Int): DeckPosition {
    val page = (position.page + direction).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    return if (page == position.page) position else DeckPosition(page, 0)
}

/** Up or Down on a page with [cursorCount] entries (guests, the timeline): the next entry. Unchanged at either end. */
internal fun cursorStep(position: DeckPosition, cursorCount: Int, direction: Int): DeckPosition {
    val cursor = position.cursor + direction
    return if (cursorCount > 1 && cursor in 0 until cursorCount) position.copy(cursor = cursor) else position
}

/** The first card on a page of [count] cards starting at [startIndex] that is not in [revealed], or null when all are. */
internal fun nextHiddenFact(startIndex: Int, count: Int, revealed: Set<Int>): Int? =
    (startIndex until startIndex + count).firstOrNull { it !in revealed }

private fun words(text: String): Set<String> = bookletNorm(text).split(' ').filter { it.length > 3 }.toSet()

/** [facts] without the cards whose answer mostly repeats what [shown] (the intro on the cover) already says. */
internal fun dropRepeats(facts: List<BookletFact>, shown: String): List<BookletFact> {
    val seen = words(shown)
    return facts.filter { fact ->
        val answer = words(fact.answer)
        answer.isEmpty() || answer.count { it in seen } * 10 < answer.size * 6
    }
}

// region AI trivia

internal const val FACTS_SYSTEM_PROMPT =
    "You write trivia cards for a music fan's liner notes. Only state facts you are sure are true about this exact album " +
        "and artist: never invent names, dates, places, chart positions, sales or quotes. If you do not know the album, " +
        "say so rather than guess. Plain text, no markdown. Reply with one JSON object and nothing else."

/** The user message for the trivia request: five facts, not repeating [shown] (the intro already on the cover). */
internal fun factsPrompt(request: BookletRequest, shown: String, wikipediaText: String?): String = buildString {
    append("Album: ").append(request.album).append('\n')
    append("Artist: ").append(request.artist).append('\n')
    request.year?.let { append("Year: ").append(it).append('\n') }
    if (request.tracks.isNotEmpty()) {
        append("Tracks: ").append(request.tracks.take(30).mapIndexed { i, t -> "${i + 1}. ${t.title}" }.joinToString("; ")).append('\n')
    }
    append("\nAlready shown to the reader, so do not repeat it:\n").append(shown.take(700)).append('\n')
    wikipediaText?.takeIf { it.isNotBlank() }?.let { append("\nBackground you may use if it helps:\n<<<\n").append(it.take(2_500)).append("\n>>>\n") }
    append("\nGive 5 facts: 3 about the album \"").append(request.album).append("\" and 2 about the artist \"").append(request.artist).append("\". ")
    append("Pick surprising, specific and nostalgic details a fan would love: how it was made, who nearly played on it, ")
    append("a story behind a song, where it was recorded, a record it broke, a famous guest. Not a general career summary.\n")
    append("Return JSON of this shape:\n")
    append("{\"known\": true, \"facts\": [{\"kind\": \"album\", \"q\": \"a short, friendly question\", ")
    append("\"a\": \"the answer in one or two sentences\", \"confidence\": \"high\"}]}\n")
    append("Each question must be answerable by its answer. Use confidence \"high\", \"medium\" or \"low\", and leave out anything you would mark low. ")
    append("If you do not recognise this exact album by this artist, return {\"known\": false, \"facts\": []}.")
}

/**
 * The cards in a trivia reply: empty when the model did not know the album or gave nothing usable, null when the reply
 * cannot be read at all. Cards it marked low, repeated questions and cards too short to be real are dropped.
 */
internal fun parseAiFacts(reply: String): List<BookletFact>? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { BOOKLET_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    if ((root["known"] as? JsonPrimitive)?.content == "false") return emptyList()
    val seen = HashSet<String>()
    return (root["facts"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val q = obj["q"].text() ?: obj["question"].text() ?: return@mapNotNull null
        val a = obj["a"].text() ?: obj["answer"].text() ?: return@mapNotNull null
        if (obj["confidence"].text()?.lowercase() == "low") return@mapNotNull null
        if (q.length < 8 || a.length < 3 || !seen.add(bookletNorm(q))) return@mapNotNull null
        val kind = if (obj["kind"].text()?.lowercase() == "artist") FactKind.Artist else FactKind.Album
        BookletFact(kind, cutAtSentence(q, MAX_QUESTION_CHARS), cutAtSentence(a, MAX_ANSWER_CHARS))
    }.take(MAX_FACTS)
}

// endregion
