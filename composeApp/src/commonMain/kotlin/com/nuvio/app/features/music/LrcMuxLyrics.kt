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
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/*
 * Word-timed lyrics from LRCMux (https://api.lrcmux.dev), a lookup service that asks several lyric catalogues (it
 * reports Deezer, KuGou, LRCLIB and others as the origin) and answers with one JSON. It has by far the widest reach of
 * the word-timed sources, and it is third-party: it relays other catalogues' lyrics and says nothing about the terms
 * it does so under. That is why this source is OFF until the person turns it on in Music settings.
 *
 * The answer names the song and its length, so a different recording (a remix, a live take) is turned away here.
 */

internal const val LRCMUX_SOURCE = "LRCMux"

/** How far the answered recording's length may be from the track's before it is taken for another cut. */
private const val LRCMUX_DURATION_TOLERANCE_MS = 4_000L

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The word-timed lyric in an LRCMux `/get?format=json&level=word` answer for the track [title] ([durationSeconds]
 * long), or null when it has no word timing, names another song, or is another length. Times are milliseconds; if the
 * whole answer is small numbers they are seconds, decided once for the answer (judged value by value, a line in the
 * first seconds would read as the wrong unit).
 */
internal fun lrcMuxLyricsOf(root: JsonElement?, title: String, durationSeconds: Int): Lyrics.Synced? {
    val obj = root as? JsonObject ?: return null
    val track = obj["track"] as? JsonObject
    val gotTitle = track?.text("title")
    if (gotTitle != null && squashed(coreTrackTitle(gotTitle)) != squashed(coreTrackTitle(title))) return null
    val gotSeconds = track?.num("duration")
    if (durationSeconds > 0 && gotSeconds != null && gotSeconds > 0.0 &&
        abs(gotSeconds * 1_000.0 - durationSeconds * 1_000.0) > LRCMUX_DURATION_TOLERANCE_MS
    ) return null

    val rawLines = (obj["lines"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val allTimes = rawLines.flatMap { line ->
        listOfNotNull(line.num("start"), line.num("end")) +
            ((line["words"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()).flatMap { listOfNotNull(it.num("start"), it.num("end")) }
    }
    val scale = if ((allTimes.maxOrNull() ?: 0.0) > 1_000.0) 1.0 else 1_000.0

    val lines = ArrayList<LyricLine>()
    for (line in rawLines) {
        val pieces = (line["words"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { word ->
            val text = word.text("text") ?: return@mapNotNull null
            val start = word.num("start") ?: return@mapNotNull null
            val end = word.num("end") ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val startMs = (start * scale).toLong()
            TimedWord(startMs, (end * scale).toLong().coerceAtLeast(startMs), text)
        }
        // These are whole words. Most answers keep the space after each; where one does not, say so, or the syllable
        // joiner would glue "Hello" and "world" into one word.
        val spaced = pieces.mapIndexed { i, piece ->
            val next = pieces.getOrNull(i + 1)
            val glued = next != null && !piece.text.endsWith(" ") && !next.text.startsWith(" ") &&
                isLatinWordChar(piece.text.last()) && isLatinWordChar(next.text.first())
            if (glued) piece.copy(text = piece.text + " ") else piece
        }
        val words = mergeTimedSyllables(spaced)
        val lineStartMs = line.num("start")?.let { (it * scale).toLong() }
        if (words.isEmpty()) {
            // A catalogue that only timed whole lines (its `meta.level` is "line"): keep the line as it is.
            val plain = line.text("text")?.trim().orEmpty()
            if (plain.isNotEmpty() && lineStartMs != null) lines += LyricLine(lineStartMs, plain)
            continue
        }
        val text = spaced.joinToString("") { it.text }.trim()
        val startMs = lineStartMs ?: words.first().startMs
        lines += LyricLine(minOf(startMs, words.first().startMs), text, words)
    }
    // A line-timed answer is kept too: LyricsRepository prefers any word-timed source and uses a line-timed one only
    // when LRCLIB has nothing, which is exactly when (an outage, a song LRCLIB lacks) this is the only lyric there is.
    if (lines.isEmpty()) return null
    val ordered = withRestLines(lines.sortedBy { it.timeMs })
    if (!unisonFitsTrack(ordered, durationSeconds)) return null
    val origin = ((obj["meta"] as? JsonObject)?.get("source") as? JsonObject)?.text("name")?.takeIf { it.isNotBlank() }
    return Lyrics.Synced(ordered, durationSeconds.toDouble(), source = if (origin != null) "$LRCMUX_SOURCE ($origin)" else LRCMUX_SOURCE)
}

object LrcMuxLyricsClient {
    private val log = Logger.withTag("LrcMuxLyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private const val URL = "https://api.lrcmux.dev/get"
    private const val BUDGET_MS = 7_000L
    private const val CONNECT_MS = 4_000L
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
            append("?artist=").append(artist.encodeURLParameter())
            append("&title=").append(title.encodeURLParameter())
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
            append("&format=json&level=word")
        }
        val response = request(url)
        return when {
            response == null -> { breaker.failed(); LyricsLookup.Unreachable }
            response.status == 404 -> { breaker.succeeded(); LyricsLookup.Missing }
            response.status == 429 -> { breaker.pauseNow(); LyricsLookup.Unreachable }
            response.status !in 200..299 -> { breaker.failed(); LyricsLookup.Unreachable }
            else -> {
                breaker.succeeded()
                val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull()
                val lyrics = lrcMuxLyricsOf(root, title, durationSeconds)
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
            log.d { "lrcmux request failed: ${error.message}" }
            null
        }
}
