package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToLong

/** A word of a line with the stretch of the song it is sung over (absolute milliseconds). */
data class TimedWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One timed line of a synced lyric. A blank [text] is an instrumental gap. [words] is filled only when the source
 * timed each word (enhanced LRC, Netease YRC); it is empty for plain line timing, and the view then estimates.
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<TimedWord> = emptyList())

sealed interface Lyrics {
    /**
     * Line-by-line timing (LRC): the line for the current position can be highlighted. [durationSeconds] is the
     * length of the recording the timing was made on (LRCLIB's `duration`), 0 when not known. It is what the
     * played stream is compared with to tell whether these lines already fit it.
     */
    data class Synced(val lines: List<LyricLine>, val durationSeconds: Double = 0.0) : Lyrics {
        val durationMs: Long get() = (durationSeconds * 1_000.0).roundToLong()
    }

    /** Words only, no timing. */
    data class Plain(val lines: List<String>) : Lyrics

    data object Instrumental : Lyrics
}

/** What a lookup came back with. [Unreachable] is not cached: it may be a network blip. */
sealed interface LyricsLookup {
    data class Found(val lyrics: Lyrics) : LyricsLookup
    data object Missing : LyricsLookup
    data object Unreachable : LyricsLookup
}

// region LRC

private val TIME_STAMP = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
private val WORD_STAMP = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")

private fun wordStampMs(mark: MatchResult): Long {
    val fraction = mark.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return mark.groupValues[1].toLong() * 60_000L + mark.groupValues[2].toLong() * 1_000L + fraction
}

/** Words of one enhanced-LRC line body (`<00:12.00>Hello <00:12.50>world`), or empty when it has no word stamps. */
internal fun enhancedLrcWords(body: String): List<TimedWord> {
    val marks = WORD_STAMP.findAll(body).toList()
    if (marks.isEmpty()) return emptyList()
    val pieces = ArrayList<TimedWord>()
    for ((i, mark) in marks.withIndex()) {
        val next = marks.getOrNull(i + 1)
        val text = body.substring(mark.range.last + 1, next?.range?.first ?: body.length)
        if (text.isBlank()) continue
        val start = wordStampMs(mark)
        // The last word has no closing stamp unless the file adds one; give it a length by its letters.
        val end = next?.let(::wordStampMs) ?: (start + (text.trim().length * 95L).coerceAtLeast(300L))
        pieces += TimedWord(start, end.coerceAtLeast(start), text)
    }
    return mergeTimedSyllables(pieces)
}

private fun isLatinWordChar(c: Char): Boolean = (c.isLetterOrDigit() && c.code < 0x2E80) || c == '\''

/**
 * Joins the pieces a source splits a word into ("beau" "ti" "ful") back into one word, so the highlight moves by
 * word. A piece belongs to the one before it only when both touch with letters; anything with a space between,
 * and every piece of a script written without spaces, stays its own word. Texts come back trimmed.
 */
internal fun mergeTimedSyllables(pieces: List<TimedWord>): List<TimedWord> {
    val out = ArrayList<TimedWord>()
    var current: TimedWord? = null
    for (piece in pieces) {
        if (piece.text.isEmpty()) continue
        val joins = current != null && isLatinWordChar(current.text.last()) && isLatinWordChar(piece.text.first())
        if (joins) {
            current = current!!.copy(endMs = maxOf(current.endMs, piece.endMs), text = current.text + piece.text)
        } else {
            current?.let { out += it }
            current = piece
        }
    }
    current?.let { out += it }
    return out
        .map { it.copy(text = it.text.trim()) }
        .filter { it.text.isNotEmpty() }
}

/**
 * Parses LRC text (`[01:23.45] words`). A line may carry several stamps (a repeated chorus); each
 * becomes its own line. Metadata tags (`[ar:..]`, `[ti:..]`) are dropped. Word-level `<mm:ss.xx>` stamps
 * (enhanced LRC) are kept as the line's [LyricLine.words]. The result is sorted by time.
 */
internal fun parseLrc(raw: String): List<LyricLine> {
    val out = ArrayList<LyricLine>()
    for (source in raw.lineSequence()) {
        val line = source.trim()
        if (line.isEmpty()) continue
        val stamps = ArrayList<Long>()
        var cursor = 0
        while (true) {
            val match = TIME_STAMP.matchAt(line, cursor) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            stamps += minutes * 60_000L + seconds * 1_000L + fraction
            cursor = match.range.last + 1
        }
        if (stamps.isEmpty()) continue
        val body = line.substring(cursor)
        val text = body.replace(WORD_STAMP, "").trim()
        val words = enhancedLrcWords(body)
        for (stamp in stamps) {
            // A repeated line sings the same words later: move them by the distance between its stamps.
            val shift = stamp - stamps[0]
            out += LyricLine(stamp, text, if (shift == 0L) words else words.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) })
        }
    }
    return out.sortedBy { it.timeMs }
}

/** Index of the line that is current at [positionMs]: the last one that has started. -1 before the first. */
internal fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

// endregion

// region LRCLIB

internal data class LrcLibRecord(
    val trackName: String,
    val artistName: String,
    val durationSeconds: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun lrcLibRecord(element: JsonElement?): LrcLibRecord? {
    val obj = element as? JsonObject ?: return null
    return LrcLibRecord(
        trackName = obj.text("trackName").orEmpty(),
        artistName = obj.text("artistName").orEmpty(),
        durationSeconds = obj.text("duration")?.toDoubleOrNull() ?: 0.0,
        instrumental = obj.text("instrumental") == "true",
        plainLyrics = obj.text("plainLyrics")?.takeIf { it.isNotBlank() },
        syncedLyrics = obj.text("syncedLyrics")?.takeIf { it.isNotBlank() },
    )
}

/** Synced lyrics when the record has them and they parse, else plain words, else instrumental. */
internal fun lyricsOf(record: LrcLibRecord): Lyrics? {
    record.syncedLyrics?.let { raw ->
        val lines = parseLrc(raw)
        if (lines.any { it.text.isNotBlank() }) return Lyrics.Synced(lines, record.durationSeconds)
    }
    record.plainLyrics?.let { raw ->
        val lines = raw.lines().map { it.trim() }
        if (lines.any { it.isNotEmpty() }) return Lyrics.Plain(lines)
    }
    return if (record.instrumental) Lyrics.Instrumental else null
}

private const val DURATION_TOLERANCE_SECONDS = 4.0

/**
 * The record that is this track: it has lyrics (or is marked instrumental), its length is within a
 * few seconds of [durationSeconds] when that is known (a different cut of the song has different
 * timing), and it names [artist]. Synced beats plain; the closest length breaks ties.
 */
internal fun pickLrcLibRecord(records: List<LrcLibRecord>, artist: String, durationSeconds: Int): LrcLibRecord? {
    return records
        .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
        .filter { durationSeconds <= 0 || abs(it.durationSeconds - durationSeconds) <= DURATION_TOLERANCE_SECONDS }
        .filter { namesArtist(it, artist) }
        .sortedWith(
            compareByDescending<LrcLibRecord> { it.syncedLyrics != null }
                .thenBy { if (durationSeconds > 0) abs(it.durationSeconds - durationSeconds) else 0.0 },
        )
        .firstOrNull()
}

/** https://lrclib.net: free, no key. It asks for an identifying User-Agent. */
object LrcLibClient {
    private val log = Logger.withTag("LrcLib")
    private const val BASE = "https://lrclib.net/api"
    private val HEADERS = mapOf(
        "User-Agent" to "NuvioDesktop (https://github.com/UmbraProjects/NuvioDesktop)",
        "Accept" to "application/json",
    )

    suspend fun find(title: String, artist: String, album: String, durationSeconds: Int): LyricsLookup {
        if (title.isBlank() || artist.isBlank()) return LyricsLookup.Missing
        var answered = false
        for (name in listOf(title, coreTrackTitle(title)).filter { it.isNotBlank() }.distinct()) {
            // Exact match first: LRCLIB matches artist, title, album and length to a couple of seconds.
            if (durationSeconds > 0 && album.isNotBlank()) {
                val url = "$BASE/get?track_name=${name.encodeURLParameter()}&artist_name=${artist.encodeURLParameter()}" +
                    "&album_name=${album.encodeURLParameter()}&duration=$durationSeconds"
                val record = lrcLibRecord(fetchJson(url))
                val lyrics = record?.let(::lyricsOf)
                if (lyrics != null) return LyricsLookup.Found(lyrics)
            }
            val searchUrl = "$BASE/search?track_name=${name.encodeURLParameter()}&artist_name=${artist.encodeURLParameter()}"
            val results = fetchJson(searchUrl) as? JsonArray
            if (results != null) {
                answered = true
                val best = pickLrcLibRecord(results.mapNotNull { lrcLibRecord(it) }, artist, durationSeconds)
                val lyrics = best?.let(::lyricsOf)
                if (lyrics != null) return LyricsLookup.Found(lyrics)
            }
        }
        return if (answered) LyricsLookup.Missing else LyricsLookup.Unreachable
    }

    /**
     * The other timed versions of the song (see [syncedAlternatives]), or null when LRCLIB could not be
     * reached (so the caller asks again later rather than remembering "none").
     */
    suspend fun alternatives(title: String, artist: String): List<Lyrics.Synced>? {
        if (title.isBlank() || artist.isBlank()) return emptyList()
        var answered = false
        for (name in listOf(title, coreTrackTitle(title)).filter { it.isNotBlank() }.distinct()) {
            val searchUrl = "$BASE/search?track_name=${name.encodeURLParameter()}&artist_name=${artist.encodeURLParameter()}"
            val results = fetchJson(searchUrl) as? JsonArray ?: continue
            answered = true
            val found = syncedAlternatives(results.mapNotNull { lrcLibRecord(it) }, artist)
            if (found.isNotEmpty()) return found
        }
        return if (answered) emptyList() else null
    }

    private suspend fun fetchJson(url: String): JsonElement? =
        try {
            Json.parseToJsonElement(httpGetTextWithHeaders(url, HEADERS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A 404 from /get lands here too; it only means "no exact match".
            log.d { "lrclib request failed: ${error.message}" }
            null
        }
}

private fun namesArtist(record: LrcLibRecord, artist: String): Boolean {
    val artistKey = artist.lowercase().filter { it.isLetterOrDigit() }
    return artistKey.isEmpty() || artistKey in record.artistName.lowercase().filter { c -> c.isLetterOrDigit() }
}

/**
 * Every timed version of the song LRCLIB holds for [artist], one per distinct length (an album cut, a single, a
 * video, a live take each have their own timing and their own length). Records with no length are left out: with
 * no length there is no telling which recording they belong to.
 */
internal fun syncedAlternatives(records: List<LrcLibRecord>, artist: String): List<Lyrics.Synced> =
    records
        .filter { it.syncedLyrics != null && it.durationSeconds > 0.0 && namesArtist(it, artist) }
        .mapNotNull { lyricsOf(it) as? Lyrics.Synced }
        .distinctBy { it.durationMs / 1_000L }

/**
 * The version of [primary] to show on a stream that plays for [playedDurationMs]: another LRCLIB version
 * whose length matches the stream (within [LYRIC_SAME_CUT_MS]) when [primary] does not, the closest such one;
 * null when [primary] already fits, has no known length, or nothing else fits (the audio analysis then finds
 * the offset). Only a version the stream is the same cut of is ever swapped in, so a live take or a remix
 * that merely has a nearby length is not.
 */
internal fun pickLyricsForStream(
    primary: Lyrics.Synced,
    alternatives: List<Lyrics.Synced>,
    playedDurationMs: Long,
): Lyrics.Synced? {
    if (playedDurationMs <= 0L || primary.durationMs <= 0L) return null
    if (abs(primary.durationMs - playedDurationMs) <= LYRIC_SAME_CUT_MS) return null
    return alternatives
        .filter { abs(it.durationMs - playedDurationMs) <= LYRIC_SAME_CUT_MS }
        .minByOrNull { abs(it.durationMs - playedDurationMs) }
}

/** Lyrics per track, for the session. Misses are remembered; network failures are not. */
object LyricsRepository {
    private val lock = Mutex()
    private val cache = HashMap<String, Lyrics?>()

    private val alternativesCache = HashMap<String, List<Lyrics.Synced>>()

    private fun artistOf(track: MusicTrack, album: MusicAlbum?): String =
        (track.artists.firstOrNull() ?: album?.artists?.firstOrNull())?.name.orEmpty()

    /**
     * The timed lyrics to show on a stream that plays for [playedDurationMs]: [primary] (what [forTrack] found,
     * timed to the album cut) unless it is another length than the stream and LRCLIB holds a version that is
     * the stream's own length (see [pickLyricsForStream]). LRCLIB is asked for the other versions only then, once
     * per track. Null means keep [primary].
     */
    suspend fun forStream(
        track: MusicTrack,
        album: MusicAlbum?,
        primary: Lyrics.Synced,
        playedDurationMs: Long,
    ): Lyrics.Synced? {
        if (playedDurationMs <= 0L || primary.durationMs <= 0L) return null
        if (abs(primary.durationMs - playedDurationMs) <= LYRIC_SAME_CUT_MS) return null
        val key = track.videoId
        val known = lock.withLock { alternativesCache[key] }
        val alternatives = known ?: LrcLibClient.alternatives(track.title, artistOf(track, album))
            ?.also { found -> lock.withLock { alternativesCache[key] = found } }
            ?: return null
        return pickLyricsForStream(primary, alternatives, playedDurationMs)
    }

    suspend fun forTrack(track: MusicTrack, album: MusicAlbum?): Lyrics? {
        val key = track.videoId
        lock.withLock { if (cache.containsKey(key)) return cache[key] }
        val albumName = (track.albumTitle ?: album?.title).orEmpty()
        // fork:music-playlists - a YouTube upload in a playlist (a cover, a duet) names several people in one artist
        // string and the original song in its title; each reading is asked in turn until one has lyrics.
        var result: LyricsLookup = LyricsLookup.Missing
        var reached = false
        for ((title, artist) in lyricLookupCandidates(track.title, artistOf(track, album))) {
            val found = LrcLibClient.find(title, artist, albumName, track.durationSeconds)
            if (found is LyricsLookup.Found) { result = found; break }
            if (found == LyricsLookup.Missing) reached = true
            result = if (reached) LyricsLookup.Missing else found
        }
        // Word-by-word timing from Netease replaces line timing when it has this very cut of the song; any failure
        // there leaves LRCLIB's lines exactly as before.
        val lrclib = (result as? LyricsLookup.Found)?.lyrics
        val wordTimed = if (lrclib === Lyrics.Instrumental) null
        else NeteaseLyricsClient.wordTimed(track.title, artistOf(track, album), track.durationSeconds)
        val best: Lyrics? = wordTimed ?: lrclib
        return when {
            best != null -> best.also { lock.withLock { cache[key] = it } }
            result == LyricsLookup.Missing -> null.also { lock.withLock { cache[key] = null } }
            else -> null
        }
    }
}

// endregion

// region fanart

/**
 * Artist fanart from TheAudioDB. The key below is its public test key; it is rate limited, so a
 * personal key (free tier) goes here if lookups start failing. An artist it does not know simply
 * has no fanart and the view falls back to the album cover.
 */
object ArtistFanartRepository {
    private val log = Logger.withTag("ArtistFanart")
    private const val AUDIODB_KEY = "123"
    private val lock = Mutex()
    private val cache = HashMap<String, List<String>>()

    suspend fun forArtist(artist: String): List<String> {
        val key = artist.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        lock.withLock { cache[key] }?.let { return it }
        val url = "https://www.theaudiodb.com/api/v1/json/$AUDIODB_KEY/search.php?s=${artist.trim().encodeURLParameter()}"
        val body = try {
            httpGetTextWithHeaders(url, mapOf("Accept" to "application/json"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "audiodb lookup for $artist failed: ${error.message}" }
            return emptyList()
        }
        val images = fanartFromAudioDb(body, artist)
        lock.withLock { cache[key] = images }
        return images
    }
}

/** The fanart URLs of the artist named [artist] in a TheAudioDB `search.php` answer, best first. */
internal fun fanartFromAudioDb(body: String, artist: String): List<String> {
    val artists = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("artists") as? JsonArray }
        .getOrNull() ?: return emptyList()
    val match = artists.mapNotNull { it as? JsonObject }
        .firstOrNull { it.text("strArtist")?.equals(artist.trim(), ignoreCase = true) == true }
        ?: return emptyList()
    return listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4", "strArtistWideThumb")
        .mapNotNull { match.text(it)?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
}

// endregion

private val ARTIST_JOINER = Regex("""\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?|\band\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
private val COVER_NOTE = Regex("""\s*[(\[][^)\]]*\bcover\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

/**
 * fork:music-playlists - the (title, artist) pairs to ask LRCLIB for, best first. An album track is just its own title
 * and artist. A YouTube upload often is not: "Alex Goot, Kurt Hugo Schneider & First to Eleven" names three people,
 * and "Hopelessly Devoted To You - Olivia Newton-John (Cover by Emily Linge)" holds the original song and singer.
 * So the first named artist is tried, and a title split at " - " is tried both ways round with cover notes removed.
 */
internal fun lyricLookupCandidates(title: String, artist: String): List<Pair<String, String>> {
    val out = LinkedHashSet<Pair<String, String>>()
    val cleanTitle = title.replace(COVER_NOTE, "").trim()
    val first = artist.split(ARTIST_JOINER).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    out += title to artist
    if (first.isNotEmpty() && first != artist) out += cleanTitle to first
    if (" - " in cleanTitle) {
        val left = cleanTitle.substringBefore(" - ").trim()
        val right = cleanTitle.substringAfter(" - ").trim()
        if (left.isNotEmpty() && right.isNotEmpty()) {
            out += left to right
            out += right to left
        }
    }
    return out.filter { (t, a) -> t.isNotBlank() && a.isNotBlank() }
}
