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

/** One timed line of a synced lyric. A blank [text] is an instrumental gap. */
data class LyricLine(val timeMs: Long, val text: String)

sealed interface Lyrics {
    /** Line-by-line timing (LRC): the line for the current position can be highlighted. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

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
private val WORD_STAMP = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

/**
 * Parses LRC text (`[01:23.45] words`). A line may carry several stamps (a repeated chorus); each
 * becomes its own line. Metadata tags (`[ar:..]`, `[ti:..]`) and word-level `<mm:ss.xx>` stamps are
 * dropped. The result is sorted by time.
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
        val text = line.substring(cursor).replace(WORD_STAMP, "").trim()
        for (stamp in stamps) out += LyricLine(stamp, text)
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
        if (lines.any { it.text.isNotBlank() }) return Lyrics.Synced(lines)
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
    val artistKey = artist.lowercase().filter { it.isLetterOrDigit() }
    return records
        .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
        .filter { durationSeconds <= 0 || abs(it.durationSeconds - durationSeconds) <= DURATION_TOLERANCE_SECONDS }
        .filter { artistKey.isEmpty() || artistKey in it.artistName.lowercase().filter { c -> c.isLetterOrDigit() } }
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

/** Lyrics per track, for the session. Misses are remembered; network failures are not. */
object LyricsRepository {
    private val lock = Mutex()
    private val cache = HashMap<String, Lyrics?>()

    suspend fun forTrack(track: MusicTrack, album: MusicAlbum?): Lyrics? {
        val key = track.videoId
        lock.withLock { if (cache.containsKey(key)) return cache[key] }
        val artist = (track.artists.firstOrNull() ?: album?.artists?.firstOrNull())?.name.orEmpty()
        val result = LrcLibClient.find(track.title, artist, album?.title.orEmpty(), track.durationSeconds)
        return when (result) {
            is LyricsLookup.Found -> result.lyrics.also { lock.withLock { cache[key] = it } }
            LyricsLookup.Missing -> null.also { lock.withLock { cache[key] = null } }
            LyricsLookup.Unreachable -> null
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
