package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.MusicBrainzClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/** What is known of each song on an album, by its place on the record (1 for the first): [ai] notes and MusicBrainz [credits]. */
internal data class SongNotes(
    val ai: Map<Int, String> = emptyMap(),
    val credits: Map<Int, String> = emptyMap(),
    /** The tracks whose note the person pinned (it is then in [ai]); set when pins are laid over, never cached. */
    val pinned: Set<Int> = emptySet(),
) {
    /** The best line for [track]: the AI's note, else who wrote and produced it. */
    fun lineFor(track: Int): String? = ai[track] ?: credits[track]
}

private val NOTES_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

internal const val TRACK_NOTES_SYSTEM_PROMPT =
    "You write one short note for every song on an album, for a music player's 'About this song' line. Ground each note in " +
        "the credits and album text you are given, and in what is well established about the song; if you are not sure of " +
        "something, leave it out and say something safe instead (who wrote it, who is featured, where it sits on the record). " +
        "Never invent facts, quotes, chart positions or dates. Plain text, no markdown. Reply with one JSON object and nothing else."

/** The user message: the album, every track with the credits known for it, and the album's intro as grounding. */
internal fun trackNotesPrompt(request: BookletRequest, credits: Map<Int, String>, intro: String?): String = buildString {
    append("Album: ").append(request.album).append('\n')
    append("Artist: ").append(request.artist).append('\n')
    request.year?.let { append("Year: ").append(it).append('\n') }
    intro?.takeIf { it.isNotBlank() }?.let { append("\nAbout the album:\n<<<\n").append(it.take(1_200)).append("\n>>>\n") }
    append("\nTracks:\n")
    request.tracks.forEachIndexed { i, track ->
        append(i + 1).append(". ").append(track.title)
        credits[i + 1]?.let { append(" [").append(it).append(']') }
        append('\n')
    }
    append("\nReturn JSON mapping every track number to its note, like {\"1\": \"...\", \"2\": \"...\"}. ")
    append("Each note is one sentence of at most 180 characters: what the song is about or sounds like, or what it is known for.")
}

private fun JsonElement?.noteText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.length >= 8 }

/** The notes in the model's [reply], keyed by track number; empty when it cannot be read. Tolerates a fence around the JSON. */
internal fun parseTrackNotes(reply: String, trackCount: Int): Map<Int, String> {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyMap()
    val root = runCatching { NOTES_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return emptyMap()
    val notes = LinkedHashMap<Int, String>()
    root.forEach { (key, value) ->
        val number = key.trim().toIntOrNull()?.takeIf { it in 1..trackCount } ?: return@forEach
        value.noteText()?.let { notes[number] = cutAtSentence(it, 200) }
    }
    return notes.entries.sortedBy { it.key }.associate { it.key to it.value }
}

/**
 * fork:essentials - the line about each song for the album page. The credits come from MusicBrainz (one release lookup, no
 * key); the notes from the person's own AI key (one request for the whole album). Both are kept with the booklets, so each
 * is asked for once; a failure is remembered for two hours.
 */
internal object TrackNotes {
    private val log = Logger.withTag("TrackNotes")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun creditsKey(r: BookletRequest) = "tcred1|${norm(r.artist)}|${norm(r.album)}"
    private fun notesKey(r: BookletRequest, model: String) = "tnotes1|${norm(r.artist)}|${norm(r.album)}|$model"

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))
    private fun keep(key: String, source: BookletSource, notes: Map<Int, String>) {
        val booklet = if (notes.isEmpty()) null else AlbumBooklet("notes", emptyList(), notes, source)
        BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), booklet)))
    }

    /** What is kept for [request], never making a request, with the person's pinned notes laid over the AI's. */
    fun peek(request: BookletRequest): SongNotes = peekKept(request).withPins(Pins.forAlbum(request))

    /** Looks up what [peek] does not have: the credits first (they ground the AI's notes), then the notes. [intro] is the album's. */
    suspend fun load(request: BookletRequest, intro: String?): SongNotes = loadKept(request, intro).withPins(Pins.forAlbum(request))

    /** Whether the AI's notes for the album's songs are kept, failed a moment ago or not asked for yet (see [AlbumBookletRepository.factState]). */
    fun factState(request: BookletRequest): FactState {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return FactState.Missed
        val entry = read(notesKey(request, settings.effectiveModel))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }
        return when {
            entry == null -> FactState.Missing
            entry.booklet != null -> FactState.Have
            else -> FactState.Missed
        }
    }

    /** What is kept for [request], without pins: the cache's own answer, which the lookup rules below go by. */
    private fun peekKept(request: BookletRequest): SongNotes {
        val at = now()
        val credits = read(creditsKey(request))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }?.booklet?.trackNotes.orEmpty()
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }
        val ai = settings?.let { read(notesKey(request, it.effectiveModel)) }
            ?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.booklet?.trackNotes.orEmpty()
        return SongNotes(ai, credits)
    }

    private suspend fun loadKept(request: BookletRequest, intro: String?): SongNotes = lock.withLock {
        val at = now()
        var have = peekKept(request)
        if (read(creditsKey(request))?.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) != true) {
            val credits = runCatching { MusicBrainzClient.trackCredits(request.artist, request.album) }.getOrDefault(emptyMap())
            keep(creditsKey(request), BookletSource.Wikipedia, credits)
            have = have.copy(credits = credits)
        }
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return@withLock have
        val key = notesKey(request, settings.effectiveModel)
        if (read(key)?.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) == true) return@withLock have
        val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(TRACK_NOTES_SYSTEM_PROMPT, trackNotesPrompt(request, have.credits, intro))).getOrElse {
            log.w { "song notes for ${request.album} failed: $it" }
            keep(key, BookletSource.Ai, emptyMap())
            return@withLock have
        }
        val notes = parseTrackNotes(reply, request.tracks.size)
        keep(key, BookletSource.Ai, notes)
        log.i { "song notes for ${request.album}: ${notes.size} of ${request.tracks.size}, ${have.credits.size} credits" }
        have.copy(ai = notes)
    }
}
