package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/*
 * fork:pins - facts the person has written or corrected by hand. A pin replaces one field of what the AI or Wikipedia
 * wrote (the album intro, one track note, one trivia card, one part of a song's story) and beats both: it never expires, is
 * never evicted, is never replaced by a refresh, and its key has no model name in it, so it survives a model change or a new
 * prompt version. Pins live in their own store, apart from the booklet cache, so that cache's size cap and whole-file
 * rewrites never touch them.
 *
 * Everything here is pure except [Pins] (which reads and writes [PinStorage]) so it can be tested. The overlay is applied
 * to what the repositories return and is never written back into their caches.
 */

internal expect object PinStorage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}

/** Who wrote a pin: the person at this computer. A later import (from another person's file) uses another value. */
internal const val PIN_ORIGIN_YOU = "you"

/** A pin is at most this long; longer text is cut at a word. */
internal const val MAX_PIN_CHARS = 2_000

/** The most trivia cards on a booklet, so the most slots a pin can name. */
internal const val MAX_FACT_SLOTS = 5

internal const val SONG_PART_MADE = "made"
internal const val SONG_PART_MEANING = "meaning"
internal const val SONG_PART_AFTER = "after"
internal val SONG_PARTS = listOf(SONG_PART_MADE, SONG_PART_MEANING, SONG_PART_AFTER)

/** One pinned field: the [text] the person wrote, and for a trivia card its [question] and [kind]. */
internal data class PinEntry(
    val atEpochMs: Long,
    val text: String,
    val question: String? = null,
    val kind: FactKind? = null,
    val origin: String = PIN_ORIGIN_YOU,
)

private val PIN_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** Lower case, letters and digits only: the same rule the story and track-note caches name songs and albums by. */
internal fun pinNorm(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

internal object PinKeys {
    private const val V = "pin1"
    fun intro(artist: String, album: String) = "$V|intro|${pinNorm(artist)}|${pinNorm(album)}"
    fun trackNote(artist: String, album: String, track: Int) = "$V|tnote|${pinNorm(artist)}|${pinNorm(album)}|$track"
    fun fact(artist: String, album: String, slot: Int) = "$V|fact|${pinNorm(artist)}|${pinNorm(album)}|$slot"
    fun songPart(artist: String, song: String, part: String) = "$V|song|${pinNorm(artist)}|${pinNorm(song)}|$part"
}

/** [text] with runs of blank space tidied, trimmed and cut to [MAX_PIN_CHARS] at a word; null when nothing is left. */
internal fun cleanPinText(text: String): String? {
    val tidy = text.replace(Regex("""[ \t]+"""), " ").replace(Regex("""\s*\n\s*(\n\s*)+"""), "\n\n").trim()
    if (tidy.isEmpty()) return null
    if (tidy.length <= MAX_PIN_CHARS) return tidy
    val cut = tidy.take(MAX_PIN_CHARS)
    val at = cut.lastIndexOf(' ')
    return (if (at > MAX_PIN_CHARS / 2) cut.take(at) else cut).trimEnd()
}

internal fun encodePin(entry: PinEntry): String = buildJsonObject {
    put("at", JsonPrimitive(entry.atEpochMs))
    put("t", JsonPrimitive(entry.text))
    entry.question?.let { put("q", JsonPrimitive(it)) }
    entry.kind?.let { put("k", JsonPrimitive(if (it == FactKind.Artist) "artist" else "album")) }
    put("o", JsonPrimitive(entry.origin))
}.toString()

/** The pin [encodePin] wrote, or null when [raw] is missing, damaged or has no text. */
internal fun decodePin(raw: String?): PinEntry? {
    if (raw == null) return null
    val root = runCatching { PIN_JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val text = root.str("t")?.takeIf { it.isNotBlank() } ?: return null
    val at = (root["at"] as? JsonPrimitive)?.longOrNull ?: 0L
    val kind = when (root.str("k")) {
        "artist" -> FactKind.Artist
        "album" -> FactKind.Album
        else -> null
    }
    return PinEntry(at, text, root.str("q")?.takeIf { it.isNotBlank() }, kind, root.str("o") ?: PIN_ORIGIN_YOU)
}

/** The pins on one album: its intro, track notes by place (1 for the first) and trivia cards by slot (1 for the first). */
internal data class AlbumPins(
    val intro: PinEntry? = null,
    val trackNotes: Map<Int, PinEntry> = emptyMap(),
    val facts: Map<Int, PinEntry> = emptyMap(),
) {
    val isEmpty: Boolean get() = intro == null && trackNotes.isEmpty() && facts.isEmpty()
}

/** The pins on one song's story, by part ([SONG_PARTS]). */
internal data class SongPins(val parts: Map<String, PinEntry> = emptyMap()) {
    val isEmpty: Boolean get() = parts.isEmpty()
}

/** The pins of an album with [trackCount] tracks, read through [get] (a key to its stored text). */
internal fun albumPinsFrom(artist: String, album: String, trackCount: Int, get: (String) -> String?): AlbumPins {
    fun entry(key: String) = decodePin(get(key))
    return AlbumPins(
        intro = entry(PinKeys.intro(artist, album)),
        trackNotes = (1..trackCount).mapNotNull { n -> entry(PinKeys.trackNote(artist, album, n))?.let { n to it } }.toMap(),
        facts = (1..MAX_FACT_SLOTS).mapNotNull { s -> entry(PinKeys.fact(artist, album, s))?.takeIf { it.question != null }?.let { s to it } }.toMap(),
    )
}

internal fun songPinsFrom(artist: String, song: String, get: (String) -> String?): SongPins =
    SongPins(SONG_PARTS.mapNotNull { part -> decodePin(get(PinKeys.songPart(artist, song, part)))?.let { part to it } }.toMap())

/**
 * The booklet with the pins laid over it. A pinned intro stands even when there is no booklet; other pins need one (or a
 * pinned intro) to sit on. [AlbumBooklet.pinned] names what was replaced. The result is for showing, never for caching.
 */
internal fun AlbumBooklet?.withPins(pins: AlbumPins): AlbumBooklet? {
    if (pins.isEmpty) return this
    val intro = pins.intro?.text ?: this?.intro ?: return this
    val base = this ?: AlbumBooklet(intro, emptyList(), emptyMap(), BookletSource.Wikipedia)
    val facts = base.facts.toMutableList()
    val pinnedFacts = mutableSetOf<String>()
    pins.facts.toSortedMap().forEach { (slot, pin) ->
        val question = pin.question ?: return@forEach
        val fact = BookletFact(pin.kind ?: facts.getOrNull(slot - 1)?.kind ?: FactKind.Album, question, pin.text)
        when {
            slot - 1 < facts.size -> facts[slot - 1] = fact
            slot - 1 == facts.size -> facts.add(fact)
            else -> return@forEach
        }
        pinnedFacts += "fact:$slot"
    }
    val pinned = buildSet {
        if (pins.intro != null) add("intro")
        pins.trackNotes.keys.forEach { add("tnote:$it") }
        addAll(pinnedFacts)
    }
    return base.copy(
        intro = intro,
        trackNotes = base.trackNotes + pins.trackNotes.mapValues { it.value.text },
        facts = facts,
        pinned = pinned,
    )
}

/**
 * The song's story with the pins laid over it; a pinned part stands even when there is no story. [SongStory.ai] is false when
 * every part that is shown was pinned, so nothing the person wrote is labelled as written by the AI.
 */
internal fun SongStory?.withPins(pins: SongPins): SongStory? {
    if (pins.isEmpty) return this
    fun part(name: String, kept: String?) = pins.parts[name]?.text ?: kept
    val made = part(SONG_PART_MADE, this?.made)
    val meaning = part(SONG_PART_MEANING, this?.meaning)
    val after = part(SONG_PART_AFTER, this?.after)
    val shown = listOf(SONG_PART_MADE to made, SONG_PART_MEANING to meaning, SONG_PART_AFTER to after).filter { it.second != null }.map { it.first }
    val allPinned = shown.isNotEmpty() && shown.all { it in pins.parts } && this?.quotes.isNullOrEmpty()
    return SongStory(made, meaning, after, this?.quotes.orEmpty(), ai = this?.ai == true && !allPinned, pinned = pins.parts.keys)
}

/** The track lines with the pinned notes laid over the AI's; [SongNotes.pinned] names the tracks the person wrote. */
internal fun SongNotes.withPins(pins: AlbumPins): SongNotes =
    if (pins.trackNotes.isEmpty()) this
    else copy(ai = ai + pins.trackNotes.mapValues { it.value.text }, pinned = pins.trackNotes.keys)

/** Reads and writes pins in [PinStorage]. */
internal object Pins {
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** Counts every change to the pins, so a screen showing the facts can read them again; see [changed]. */
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** Call after pins were written or removed outside [save] and [clear] (an import or its undo). */
    fun changed() { _version.value += 1 }

    fun read(key: String): PinEntry? = decodePin(PinStorage.get(key))

    /** Pins [text] under [key]; blank text clears the pin instead. Returns true when a pin is now kept. */
    fun save(key: String, text: String, question: String? = null, kind: FactKind? = null, origin: String = PIN_ORIGIN_YOU): Boolean {
        val clean = cleanPinText(text)
        if (clean == null) {
            clear(key)
            return false
        }
        PinStorage.put(key, encodePin(PinEntry(now(), clean, question?.let(::cleanPinText), kind, origin)))
        changed()
        return true
    }

    fun clear(key: String) {
        PinStorage.put(key, null)
        changed()
    }

    fun forAlbum(artist: String, album: String, trackCount: Int): AlbumPins = albumPinsFrom(artist, album, trackCount, PinStorage::get)

    fun forAlbum(request: BookletRequest): AlbumPins = forAlbum(request.artist, request.album, request.tracks.size)

    fun forSong(artist: String, song: String): SongPins = songPinsFrom(artist, song, PinStorage::get)
}
