package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/*
 * Word-by-word lyrics from Netease Cloud Music (YRC). LRCLIB only stamps where a line starts; YRC stamps every
 * word, which is what lets the lyric view sweep to the word like karaoke.
 *
 * This is an unofficial, undocumented interface (the one Netease's own desktop client talks to). It can change or
 * refuse a request without notice, and outside China it sometimes answers with a risk-control code. So it is only
 * ever an upgrade: every failure here means "no word timing", and the LRCLIB lines are shown as before.
 */

// region YRC

private val YRC_LINE = Regex("^\\[(\\d+),(\\d+)](.*)$")
private val YRC_WORD = Regex("\\((\\d+),(\\d+),-?\\d+\\)")

/** A rest this long between two sung lines becomes a blank line, so the view shows its "get ready" dots. */
private const val YRC_REST_MS = 5_000L

/**
 * Parses YRC text. A sung line is `[start,length](wordStart,wordLength,0)word (…)…` with absolute milliseconds;
 * credit lines are JSON (`{"t":0,"c":[...]}`) and are skipped. Every returned line has [LyricLine.words]; a line
 * with no word stamps is left out. Long rests between lines are filled with blank lines.
 */
internal fun parseYrc(raw: String): List<LyricLine> {
    val sung = ArrayList<LyricLine>()
    for (source in raw.lineSequence()) {
        val line = source.trim()
        if (line.isEmpty() || line.startsWith("{")) continue
        val head = YRC_LINE.matchEntire(line) ?: continue
        val start = head.groupValues[1].toLongOrNull() ?: continue
        val body = head.groupValues[3]
        val marks = YRC_WORD.findAll(body).toList()
        if (marks.isEmpty()) continue
        val pieces = ArrayList<TimedWord>()
        for ((i, mark) in marks.withIndex()) {
            val text = body.substring(mark.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: body.length)
            if (text.isBlank()) continue
            val wordStart = mark.groupValues[1].toLongOrNull() ?: continue
            val length = mark.groupValues[2].toLongOrNull() ?: continue
            pieces += TimedWord(wordStart, wordStart + length.coerceAtLeast(0L), text)
        }
        val words = mergeTimedSyllables(pieces)
        if (words.isEmpty()) continue
        sung += LyricLine(timeMs = minOf(start, words.first().startMs), text = words.joinToString(" ") { it.text }, words = words)
    }
    val ordered = sung.sortedBy { it.timeMs }
    val out = ArrayList<LyricLine>(ordered.size + 4)
    for ((i, line) in ordered.withIndex()) {
        out += line
        val ends = line.words.last().endMs
        val next = ordered.getOrNull(i + 1)?.timeMs
        if (next != null && next - ends >= YRC_REST_MS) out += LyricLine(ends + 800L, "")
    }
    return out
}

// endregion

// region matching

internal data class NeteaseSong(val id: Long, val name: String, val artists: List<String>, val durationMs: Long)

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()

/** The songs of a Netease search answer (`result.songs`), whichever of its two spellings of artists and length it uses. */
internal fun parseNeteaseSearch(root: JsonElement?): List<NeteaseSong> {
    val songs = root.obj()?.get("result").obj()?.get("songs") as? JsonArray ?: return emptyList()
    return songs.mapNotNull { element ->
        val song = element.obj() ?: return@mapNotNull null
        val id = song.long("id") ?: return@mapNotNull null
        val artists = ((song["ar"] ?: song["artists"]) as? JsonArray)
            ?.mapNotNull { it.obj()?.str("name") }
            .orEmpty()
        NeteaseSong(
            id = id,
            name = song.str("name").orEmpty(),
            artists = artists,
            durationMs = song.long("dt") ?: song.long("duration") ?: 0L,
        )
    }
}

private const val NETEASE_DURATION_TOLERANCE_MS = 4_000L

private fun squash(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/**
 * The Netease song that is this track: the same title once the "(feat. X)" / "- Remaster" tails are dropped, the
 * artist named by either side, and a length within a few seconds of [durationSeconds] when that is known (another cut
 * has other timing, and timing on the wrong cut is worse than none). The closest length wins.
 */
internal fun pickNeteaseSong(songs: List<NeteaseSong>, title: String, artist: String, durationSeconds: Int): NeteaseSong? {
    val wantedTitle = squash(coreTrackTitle(title))
    val wantedArtist = squash(artist)
    if (wantedTitle.isEmpty() || wantedArtist.isEmpty()) return null
    return songs
        .filter { squash(coreTrackTitle(it.name)) == wantedTitle }
        .filter { song ->
            val named = squash(song.artists.joinToString(""))
            wantedArtist in named || song.artists.any { squash(it).let { a -> a.isNotEmpty() && a in wantedArtist } }
        }
        .filter { durationSeconds <= 0 || (it.durationMs > 0 && abs(it.durationMs - durationSeconds * 1_000L) <= NETEASE_DURATION_TOLERANCE_MS) }
        .minByOrNull { if (durationSeconds > 0) abs(it.durationMs - durationSeconds * 1_000L) else 0L }
}

// endregion

// region request

private const val EAPI_KEY = "e82ckenh8dichen8"
private const val EAPI_SEPARATOR = "-36cd479b6b5-"

/**
 * The form body Netease's eapi expects for [path] (the `/api/...` path, not the `/eapi/...` one that is posted to) and
 * its JSON [payload]: the path, payload and an MD5 of both joined with a fixed separator, AES-128-ECB encrypted with a
 * fixed key and written as upper-case hex.
 */
internal fun neteaseEapiBody(path: String, payload: String): String {
    val digest = NeteaseCrypto.md5Hex("nobody${path}use${payload}md5forencrypt")
    val message = "$path$EAPI_SEPARATOR$payload$EAPI_SEPARATOR$digest"
    return "params=" + NeteaseCrypto.aesEcbHexUpper(message, EAPI_KEY)
}

internal expect object NeteaseCrypto {
    fun md5Hex(text: String): String
    fun aesEcbHexUpper(text: String, key: String): String
}

private fun clientHeader(): JsonObject = buildJsonObject {
    put("os", "pc")
    put("appver", "3.0.18.203152")
    put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
    put("deviceId", "0")
    put("channel", "netease")
    put("__csrf", "")
}

private val EAPI_HEADERS = mapOf(
    "Content-Type" to "application/x-www-form-urlencoded",
    "Accept" to "*/*",
    "Referer" to "https://music.163.com/",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152",
    "Cookie" to "os=pc; appver=3.0.18.203152; osver=Microsoft-Windows-10-Professional-build-19045-64bit; channel=netease",
)

/** Search and lyric payloads, as the JSON that goes into [neteaseEapiBody]. */
internal fun neteaseSearchPayload(keyword: String): String = buildJsonObject {
    put("s", keyword)
    put("type", "1")
    put("limit", "20")
    put("offset", "0")
    put("total", "true")
    put("header", clientHeader())
}.toString()

internal fun neteaseLyricPayload(songId: Long): String = buildJsonObject {
    put("id", songId.toString())
    put("cp", "false")
    put("lv", "0")
    put("kv", "0")
    put("tv", "0")
    put("rv", "0")
    put("yv", "0")
    put("ytv", "0")
    put("yrv", "0")
    put("csrf_token", "")
    put("header", clientHeader())
}.toString()

/** The YRC text of a `song/lyric/v1` answer, or null when the song has no word timing. */
internal fun yrcOf(root: JsonElement?): String? =
    root.obj()?.get("yrc").obj()?.str("lyric")?.takeIf { it.isNotBlank() }

// endregion

/**
 * Word-timed lyrics for a track from Netease, or null for any reason there are none (not found, no word timing, other
 * cut, request refused, offline). After three failed requests in a row it leaves Netease alone for ten minutes, so a
 * blocked network or a risk-control answer does not turn into a request per track.
 */
object NeteaseLyricsClient {
    private val log = Logger.withTag("NeteaseLyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private const val SEARCH_PATH = "/api/cloudsearch/pc"
    private const val SEARCH_URL = "https://interface.music.163.com/eapi/cloudsearch/pc"
    private const val LYRIC_PATH = "/api/song/lyric/v1"
    private const val LYRIC_URL = "https://interface3.music.163.com/eapi/song/lyric/v1"
    private const val FAILURES_BEFORE_PAUSE = 3
    private val pause = 10.minutes

    private var failuresInARow = 0
    private var pausedAt: TimeSource.Monotonic.ValueTimeMark? = null

    private fun paused(): Boolean {
        val since = pausedAt ?: return false
        if (since.elapsedNow() < pause) return true
        pausedAt = null
        failuresInARow = 0
        return false
    }

    private fun failed() {
        failuresInARow++
        if (failuresInARow >= FAILURES_BEFORE_PAUSE) pausedAt = TimeSource.Monotonic.markNow()
    }

    private suspend fun post(url: String, path: String, payload: String): JsonElement? =
        try {
            json.parseToJsonElement(httpPostJsonWithHeaders(url, neteaseEapiBody(path, payload), EAPI_HEADERS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "netease request failed: ${error.message}" }
            null
        }

    private const val BUDGET_MS = 6_000L
    private val NONE = Lyrics.Synced(emptyList())

    /**
     * Word-timed lyrics, or null. The whole lookup gets [BUDGET_MS]: the app's HTTP client waits far longer than that
     * for a host that does not answer, and the lyrics LRCLIB already found must not wait on this one.
     */
    suspend fun wordTimed(title: String, artist: String, durationSeconds: Int): Lyrics.Synced? {
        if (title.isBlank() || artist.isBlank() || paused()) return null
        val found = withTimeoutOrNull(BUDGET_MS) { lookup(title, artist, durationSeconds) ?: NONE }
        if (found == null) { failed(); return null }
        return found.takeIf { it !== NONE }
    }

    private suspend fun lookup(title: String, artist: String, durationSeconds: Int): Lyrics.Synced? {
        val search = post(SEARCH_URL, SEARCH_PATH, neteaseSearchPayload("${coreTrackTitle(title)} $artist"))
        if (search == null) { failed(); return null }
        val song = pickNeteaseSong(parseNeteaseSearch(search), title, artist, durationSeconds) ?: return null.also { failuresInARow = 0 }
        val answer = post(LYRIC_URL, LYRIC_PATH, neteaseLyricPayload(song.id))
        if (answer == null) { failed(); return null }
        failuresInARow = 0
        val lines = yrcOf(answer)?.let(::parseYrc).orEmpty()
        if (lines.none { it.words.isNotEmpty() }) return null
        return Lyrics.Synced(lines, durationSeconds = song.durationMs / 1_000.0)
    }
}
