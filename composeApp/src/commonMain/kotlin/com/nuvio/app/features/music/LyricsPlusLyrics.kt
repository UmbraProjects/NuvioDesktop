package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Word-timed lyrics from LyricsPlus (https://lyricsplus.binimum.org, the service behind the YouLyPlus extension): a
 * small public API that fetches lyrics from several catalogues and answers with one JSON. Asked for its `qapple` source
 * it gives Apple Music's syllable-timed lyrics, which on a check of eight popular songs and UK garage tracks had word
 * timing for most of them in well under a second. Its default mix of sources is not used: the others it names time out
 * for seconds at a stretch.
 *
 * It is third-party and relays another catalogue's lyrics without stating terms, and it allows two requests per ten
 * seconds per address; that is why this source is OFF until the person turns it on in Music settings, and why a lookup
 * is made once per track and then remembered on disk. The service refuses requests that carry no User-Agent.
 *
 * Other hosts serve the same API (the extension lists five); on the day this was written two of them had their
 * deployment disabled and one answered with an error page, so only the one that answered is used.
 */

internal const val LYRICSPLUS_SOURCE = "LyricsPlus"

/** How far the answered recording's length may be from the track's before it is taken for another cut. */
private const val LYRICSPLUS_DURATION_TOLERANCE_MS = 4_000L

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/**
 * The lyric in a LyricsPlus `/v2/lyrics/get` answer for [title] by [artist] ([durationSeconds] long), or null when it
 * names another song, is another length, or has no usable line.
 *
 * Each line carries `time` (ms), a `text` and a `syllabus` of `{time, duration, text}` pieces. A piece's text keeps the
 * space that follows its word ("club ", "wan" "na "), which is what lets [mergeTimedSyllables] put a word back
 * together. The answer's own `type` is not trusted (a fully syllable-timed song came back as "Line"); a line is
 * word-timed when it has pieces. Background vocals (`isBackground`, listed after the line's own pieces and sung over
 * them) are left out, as the TTML reader leaves out `x-bg`, and the line's text is rebuilt without them.
 */
internal fun lyricsPlusLyricsOf(root: JsonElement?, title: String, artist: String, durationSeconds: Int): Lyrics.Synced? {
    val obj = root as? JsonObject ?: return null
    val meta = obj["metadata"] as? JsonObject
    // An answer that names its song must name this one; one that does not name it is judged by its timing alone.
    val gotTitle = meta?.text("title")
    if (gotTitle != null && !unisonNamesTrack(gotTitle, meta?.text("artist"), title, artist)) return null
    val gotMs = meta?.text("totalDuration")?.let(::parseTtmlTime)
    if (durationSeconds > 0 && gotMs != null && gotMs > 0L &&
        abs(gotMs - durationSeconds * 1_000L) > LYRICSPLUS_DURATION_TOLERANCE_MS
    ) return null

    val lines = ArrayList<LyricLine>()
    for (raw in (obj["lyrics"] as? JsonArray).orEmpty()) {
        val line = raw as? JsonObject ?: continue
        val lineStart = line.num("time")?.toLong()
        val pieces = (line["syllabus"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filterNot { it.flag("isBackground") }
            .mapNotNull { piece ->
                val text = piece.text("text") ?: return@mapNotNull null
                val start = piece.num("time")?.toLong() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                TimedWord(start, start + (piece.num("duration")?.toLong() ?: 0L).coerceAtLeast(0L), text)
            }
            // Background pieces were already out; the rest are in order unless the source listed them out of turn.
            .sortedBy { it.startMs }
        val words = mergeTimedSyllables(pieces)
        if (words.isEmpty()) {
            // No pieces: the line is timed as a whole. Its text may carry a background vocal in brackets; keep it as given.
            val plain = line.text("text")?.trim().orEmpty()
            if (plain.isNotEmpty() && lineStart != null) lines += LyricLine(lineStart, plain)
            continue
        }
        val text = pieces.joinToString("") { it.text }.trim()
        lines += LyricLine(minOf(lineStart ?: words.first().startMs, words.first().startMs), text, words)
    }
    if (lines.isEmpty()) return null
    val ordered = withRestLines(lines.sortedBy { it.timeMs })
    if (!unisonFitsTrack(ordered, durationSeconds)) return null
    return Lyrics.Synced(
        ordered,
        (gotMs?.let { it / 1_000.0 } ?: durationSeconds.toDouble()),
        source = LYRICSPLUS_SOURCE,
    )
}

/**
 * Timed lyrics from LyricsPlus. [LyricsLookup.Found] holds a [Lyrics.Synced]; [LyricsLookup.Missing] means it
 * answered that it has none; [LyricsLookup.Unreachable] is a failure, a timeout, a rate limit or a pause.
 */
object LyricsPlusLyricsClient {
    private val log = Logger.withTag("LyricsPlusLyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private const val URL = "https://lyricsplus.binimum.org/v2/lyrics/get"
    private const val SOURCE_PARAM = "qapple"
    private const val BUDGET_MS = 4_500L
    private const val CONNECT_MS = 3_000L
    private val RATE_LIMIT_REST = 15.seconds
    private val HEADERS = mapOf(
        "User-Agent" to "NuvioDesktop (https://github.com/UmbraProjects/NuvioDesktop)",
        "Accept" to "application/json",
    )
    private val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes, firstFailurePause = FIRST_FAILURE_PAUSE)

    suspend fun find(title: String, artist: String, durationSeconds: Int): LyricsLookup {
        if (title.isBlank() || artist.isBlank()) return LyricsLookup.Missing
        if (breaker.paused()) return LyricsLookup.Unreachable
        val url = buildString {
            append(URL)
            append("?title=").append(title.encodeURLParameter())
            append("&artist=").append(artist.encodeURLParameter())
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
            append("&source=").append(SOURCE_PARAM)
        }
        val response = request(url)
        return when {
            response == null -> { breaker.failed(); LyricsLookup.Unreachable }
            response.status == 404 -> { breaker.succeeded(); LyricsLookup.Missing }
            // Two requests per ten seconds: the limit clears in seconds, so rest for a moment, not for ten minutes.
            response.status == 429 -> { breaker.pauseFor(RATE_LIMIT_REST); LyricsLookup.Unreachable }
            response.status !in 200..299 -> { breaker.failed(); LyricsLookup.Unreachable }
            else -> {
                breaker.succeeded()
                val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull()
                val lyrics = lyricsPlusLyricsOf(root, title, artist, durationSeconds)
                if (lyrics != null) LyricsLookup.Found(lyrics) else LyricsLookup.Missing
            }
        }
    }

    private suspend fun request(url: String): RawHttpResponse? =
        try {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = HEADERS,
                body = "",
                callTimeoutMs = BUDGET_MS,
                connectTimeoutMs = CONNECT_MS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "lyricsplus request failed: ${error.message}" }
            null
        }
}
