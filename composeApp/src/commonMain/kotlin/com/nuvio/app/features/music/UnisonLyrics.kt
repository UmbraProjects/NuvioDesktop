package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/*
 * Word- and syllable-timed lyrics from Unison, the community lyrics database behind the Better Lyrics browser
 * extension (https://github.com/better-lyrics/better-lyrics). Anyone can submit a lyric; most are TTML timed
 * to the syllable. Reading needs no key and no sign-in: a plain GET with the song's details.
 *
 * It is a volunteer-run service, so this client is deliberately light: one request per track, a hard time budget,
 * results (hits and misses) kept on disk by [LyricCache], and a pause when it errors or asks us to slow down.
 * Like Netease it is only ever an upgrade. Every failure means "no Unison lyrics" and the other sources are used.
 */

// region TTML

/** The lines of a TTML lyric. */
internal class TtmlLyrics(val lines: List<LyricLine>)

private val TTML_TAG = Regex("""<(/?)([A-Za-z_][\w.:-]*)([^>]*?)(/?)>""")
private val TTML_ATTRIBUTE = Regex("""([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val XML_ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);")
private val WHITESPACE_RUN = Regex("\\s+")

private fun codePointString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val v = codePoint - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** XML text with its character references (`&amp;`, `&apos;`, `&#39;`, `&#x27;`) turned into characters. */
internal fun decodeXmlEntities(text: String): String {
    if ('&' !in text) return text
    return XML_ENTITY.replace(text) { match ->
        when (val body = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (body.startsWith("#x")) body.drop(2).toIntOrNull(16) else body.drop(1).toIntOrNull()
                if (code != null && code in 1..0x10FFFF) codePointString(code) else match.value
            }
        }
    }
}

/**
 * A TTML time in milliseconds: clock time (`2:49.480`, `0:02:49.48`), plain seconds (`14.465`) or a value with a
 * unit (`14.465s`, `14465ms`). Frame-based times are not used by lyric files and give null.
 */
internal fun parseTtmlTime(value: String): Long? {
    val v = value.trim()
    if (v.isEmpty()) return null
    if (v.endsWith("ms")) return v.dropLast(2).toDoubleOrNull()?.roundToLong()
    if (v.endsWith("s")) return v.dropLast(1).toDoubleOrNull()?.let { (it * 1_000.0).roundToLong() }
    val parts = v.split(':')
    if (parts.size !in 1..3) return null
    var seconds = 0.0
    for (part in parts) seconds = seconds * 60.0 + (part.toDoubleOrNull() ?: return null)
    return (seconds * 1_000.0).roundToLong()
}

private fun ttmlAttributes(source: String): Map<String, String> =
    TTML_ATTRIBUTE.findAll(source).associate { match ->
        match.groupValues[1].lowercase() to decodeXmlEntities(match.groupValues[2].ifEmpty { match.groupValues[3] })
    }

/** A span being read: its times, what it says so far, and whether it is a translation or background vocal to leave out. */
private class TtmlSpan(val begin: Long?, val end: Long?, val skip: Boolean) {
    val text = StringBuilder()
}

/** A syllable with no closing time gets a length by its letters, as the LRC reader does for a last word. */
private fun openEnd(start: Long, text: String): Long = start + (text.trim().length * 95L).coerceAtLeast(300L)

/**
 * Reads a TTML lyric (the format Apple Music and Unison use): `<p begin end>` is a line and the `<span begin end>`
 * inside it are its syllables. Syllables of one word touch each other; a space in the source between two spans is
 * the break between words, and [mergeTimedSyllables] joins the touching ones back into words. A line with no spans
 * is kept as a line-timed one. Translation, romanisation and background-vocal spans are left out. Small and forgiving on purpose:
 * it reads tags and text in order rather than building a document, and a file it cannot read gives no lines.
 */
internal fun parseTtml(raw: String): TtmlLyrics {
    val lines = ArrayList<LyricLine>()
    val pieces = ArrayList<TimedWord>()
    val loose = StringBuilder()
    val spans = ArrayList<TtmlSpan>()
    var inLine = false
    var lineBegin = 0L
    var skipDepth = 0

    fun wordBreak() {
        val last = pieces.lastOrNull() ?: return
        if (!last.text.endsWith(" ")) pieces[pieces.lastIndex] = last.copy(text = last.text + " ")
    }

    fun onText(text: String) {
        if (!inLine || text.isEmpty() || skipDepth > 0) return
        val decoded = decodeXmlEntities(text)
        val span = spans.lastOrNull()
        when {
            span == null && decoded.isBlank() -> wordBreak()
            span == null -> loose.append(decoded)
            decoded.isBlank() && span.text.isBlank() -> wordBreak()
            else -> span.text.append(decoded)
        }
    }

    fun finishLine() {
        if (pieces.isNotEmpty()) {
            val text = pieces.joinToString("") { it.text }.trim()
            val words = mergeTimedSyllables(pieces)
            if (words.isNotEmpty()) lines += LyricLine(minOf(lineBegin, words.first().startMs), text, words)
        } else {
            val text = loose.toString().trim().replace(WHITESPACE_RUN, " ")
            if (text.isNotEmpty()) lines += LyricLine(lineBegin, text)
        }
        pieces.clear()
        loose.clear()
        spans.clear()
        skipDepth = 0
        inLine = false
    }

    var cursor = 0
    for (match in TTML_TAG.findAll(raw)) {
        onText(raw.substring(cursor, match.range.first))
        cursor = match.range.last + 1
        val closing = match.groupValues[1] == "/"
        val selfClosing = match.groupValues[4] == "/"
        val name = match.groupValues[2].substringAfter(':').lowercase()
        when (name) {
            "p" -> if (!closing) {
                if (inLine) finishLine()
                inLine = !selfClosing
                lineBegin = ttmlAttributes(match.groupValues[3])["begin"]?.let(::parseTtmlTime) ?: 0L
            } else if (inLine) {
                finishLine()
            }
            "br" -> if (inLine && !closing) wordBreak()
            "span" -> if (inLine) {
                if (!closing && !selfClosing) {
                    val attributes = ttmlAttributes(match.groupValues[3])
                    val role = attributes["ttm:role"] ?: attributes["role"]
                    // Translations, romanisation and background (ad-lib) vocals are not part of the sung line's words.
                    val skip = role == "x-translation" || role == "x-roman" || role == "x-bg"
                    spans += TtmlSpan(
                        begin = attributes["begin"]?.let(::parseTtmlTime),
                        end = attributes["end"]?.let(::parseTtmlTime),
                        skip = skip,
                    )
                    if (skip) skipDepth++
                } else if (closing && spans.isNotEmpty()) {
                    val span = spans.removeAt(spans.lastIndex)
                    if (span.skip) {
                        skipDepth--
                    } else {
                        val text = span.text.toString()
                        val begin = span.begin
                        if (begin != null && text.isNotBlank()) {
                            val end = span.end ?: openEnd(begin, text)
                            pieces += TimedWord(begin, end.coerceAtLeast(begin), text)
                        }
                    }
                }
            }
        }
    }
    if (inLine) finishLine()
    return TtmlLyrics(withRestLines(lines.sortedBy { it.timeMs }))
}

// endregion

// region response

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** How far past the end of the track the last sung word may fall before the lyric is taken for another cut. */
private const val UNISON_OVERRUN_TOLERANCE_MS = 4_000L

internal fun squashed(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/**
 * Whether a Unison answer is about this song. Unison finds a lyric by the video id, the title and artist and the
 * length, and may answer with a lyric attached to another upload of the song, so the answer is checked here: the
 * same title once "(feat. X)" and "- Remaster" tails are dropped, and the artist named by either side.
 */
internal fun unisonNamesTrack(song: String?, artist: String?, title: String, wantedArtist: String): Boolean {
    val gotTitle = squashed(coreTrackTitle(song.orEmpty()))
    if (gotTitle.isEmpty() || gotTitle != squashed(coreTrackTitle(title))) return false
    val want = squashed(wantedArtist)
    val got = squashed(artist.orEmpty())
    return want.isEmpty() || got.isEmpty() || want in got || got in want
}

/**
 * Whether lyric [lines] can belong to a track of [durationSeconds]: nothing is sung after the track has ended. A
 * Unison lyric does not say how long its recording was, so this is the one length check there is; a lyric for a
 * shorter cut still passes and the lyric view's own sync check then finds any offset.
 */
internal fun unisonFitsTrack(lines: List<LyricLine>, durationSeconds: Int): Boolean {
    if (durationSeconds <= 0) return true
    val lastMs = lines.maxOfOrNull { line -> line.words.lastOrNull()?.endMs ?: line.timeMs } ?: return false
    return lastMs <= durationSeconds * 1_000L + UNISON_OVERRUN_TOLERANCE_MS
}

/**
 * The timed lyric in a Unison `/lyrics` answer (`{"data": {"lyrics", "format", ...}}`) for the track [title] by
 * [artist], or null: no lyric, plain words only, hidden or down-voted, another song, or timed past the track's end.
 */
internal fun unisonLyricsOf(root: JsonElement?, title: String, artist: String, durationSeconds: Int, videoId: String = ""): Lyrics.Synced? {
    val data = root.asObject()?.get("data").asObject() ?: return null
    if ((data["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return null
    if ((data.number("effectiveScore") ?: 0.0) < 0.0) return null
    val body = data.string("lyrics")?.takeIf { it.isNotBlank() } ?: return null
    // A lyric attached to the very video that is playing is this recording, however the artist is spelt: a soundtrack
    // credits "Michael Jackson" for a song Unison files under "The Jackson 5". The title must still agree.
    val sameVideo = videoId.isNotBlank() && data.string("videoId") == videoId
    val artistToMatch = if (sameVideo) "" else artist
    if (!unisonNamesTrack(data.string("song"), data.string("artist"), title, artistToMatch)) return null
    val lines = when (data.string("format")) {
        "ttml" -> parseTtml(body).lines
        "lrc" -> parseLrc(body)
        else -> return null
    }
    if (lines.none { it.text.isNotBlank() } || !unisonFitsTrack(lines, durationSeconds)) return null
    return Lyrics.Synced(lines, durationSeconds.toDouble(), source = UNISON_SOURCE)
}

internal const val UNISON_SOURCE = "Unison"

/** How long a word-timing source rests after its first failure in a row (see [LookupBreaker]). The lyrics do not wait on it meanwhile. */
internal val FIRST_FAILURE_PAUSE = 20.seconds

// endregion

// region client

/**
 * Stops asking a service for a while after it has failed several times in a row or asked us to slow down.
 *
 * With [firstFailurePause] a failure short of the limit also rests the service briefly. Without it a dead host costs
 * the full time limit on each of the first [failuresBeforePause] tracks before it is given up on; with it only the first.
 * The failure count is kept through those brief rests and starts over after a full [pause] or a success.
 */
internal class LookupBreaker(
    private val failuresBeforePause: Int,
    private val pause: Duration,
    private val firstFailurePause: Duration = Duration.ZERO,
) {
    private var failures = 0
    private var pausedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var held: Duration = pause

    fun paused(): Boolean {
        val since = pausedAt ?: return false
        if (since.elapsedNow() < held) return true
        pausedAt = null
        if (held >= pause) failures = 0
        return false
    }

    fun succeeded() {
        failures = 0
    }

    fun failed() {
        failures++
        when {
            failures >= failuresBeforePause -> pauseNow()
            firstFailurePause > Duration.ZERO -> pauseFor(firstFailurePause)
        }
    }

    /** The full pause: for a service that told us to stop, or has failed enough times in a row. */
    fun pauseNow() = pauseFor(pause)

    /** A rest of [length], for a service that asked for a short wait (a rate limit that clears in seconds). */
    fun pauseFor(length: Duration) {
        held = length
        pausedAt = TimeSource.Monotonic.markNow()
    }
}

/**
 * Timed lyrics for a track from Unison. [LyricsLookup.Found] holds a [Lyrics.Synced]; [LyricsLookup.Missing] means
 * Unison answered that it has none (worth remembering); [LyricsLookup.Unreachable] is a failure, a timeout or a pause
 * (not worth remembering).
 */
object UnisonLyricsClient {
    private val log = Logger.withTag("UnisonLyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private const val URL = "https://unison.betterlyrics.org/lyrics"
    private const val BUDGET_MS = 6_000L
    private const val CONNECT_MS = 4_000L
    private val HEADERS = mapOf(
        "User-Agent" to "NuvioDesktop (https://github.com/UmbraProjects/NuvioDesktop)",
        "Accept" to "application/json",
    )
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes, firstFailurePause = FIRST_FAILURE_PAUSE)

    suspend fun find(videoId: String, title: String, artist: String, album: String, durationSeconds: Int): LyricsLookup {
        if (title.isBlank() || artist.isBlank()) return LyricsLookup.Missing
        if (breaker.paused()) return LyricsLookup.Unreachable
        val url = buildString {
            append(URL)
            append("?song=").append(title.encodeURLParameter())
            // The id is only a hint (Unison also finds a song by its title and artist), and only a YouTube id is one.
            if (YOUTUBE_ID.matches(videoId)) append("&v=").append(videoId)
            append("&artist=").append(artist.encodeURLParameter())
            if (durationSeconds > 0) append("&duration=").append(durationSeconds)
            if (album.isNotBlank()) append("&album=").append(album.encodeURLParameter())
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
                val lyrics = unisonLyricsOf(root, title, artist, durationSeconds, videoId.takeIf { YOUTUBE_ID.matches(it) }.orEmpty())
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
            log.d { "unison request failed: ${error.message}" }
            null
        }
}

// endregion
