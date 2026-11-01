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
import kotlin.time.Duration.Companion.minutes

/*
 * Word-timed lyrics from AMLL TTML DB (https://github.com/amll-dev/amll-ttml-db), an open, community-made collection
 * of Apple-Music-style TTML lyrics timed to the syllable. Reading needs no key: a search by title and artist, then the
 * file by id. It is the same TTML that Unison serves, so it goes through the same reader ([parseTtml]).
 *
 * Its search is loose (a search for one song also lists other songs by the artist), so every candidate is checked
 * against the track here. Like the other word-timed sources it is only ever an upgrade: any failure means "none".
 */

internal const val AMLL_SOURCE = "AMLL TTML DB"

/** One search hit: the file id and the names it is filed under. */
internal data class AmllEntry(val id: String, val titles: List<String>, val artists: List<String>)

private fun JsonElement?.names(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

/** The entries of an AMLL search answer (`{"data": {"items": [...]}}`). The id is a number too large to trust as a double, so it is kept as text. */
internal fun parseAmllSearch(root: JsonElement?): List<AmllEntry> {
    val items = ((root as? JsonObject)?.get("data") as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = (item["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AmllEntry(id, item["musicNames"].names(), item["artistNames"].names())
    }
}

/**
 * The entries that are this song: one of its titles is the track's title once "(feat. X)" and "- Remaster" tails are
 * dropped, and one of its artists is, or contains, the track's artist (or the other way round). Order is kept.
 */
internal fun pickAmllEntries(entries: List<AmllEntry>, title: String, artist: String): List<AmllEntry> {
    val wantedTitle = squashed(coreTrackTitle(title))
    val wantedArtist = squashed(artist)
    if (wantedTitle.isEmpty() || wantedArtist.isEmpty()) return emptyList()
    return entries.filter { entry ->
        entry.titles.any { squashed(coreTrackTitle(it)) == wantedTitle } &&
            entry.artists.any { squashed(it).let { a -> a.isNotEmpty() && (wantedArtist in a || a in wantedArtist) } }
    }
}

/** The TTML of an AMLL `lyrics/get` answer, whichever of the keys it is under. */
internal fun amllTtmlOf(root: JsonElement?): String? {
    val data = (root as? JsonObject)?.get("data") as? JsonObject ?: root as? JsonObject ?: return null
    return sequenceOf("lyrics", "ttml", "content")
        .mapNotNull { key -> (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .firstOrNull { it.contains("<tt", ignoreCase = true) }
}

object AmllLyricsClient {
    private val log = Logger.withTag("AmllLyrics")
    private val json = Json { ignoreUnknownKeys = true }
    private const val BASE = "https://api.amll.dev/v1/lyrics"
    private const val CALL_MS = 4_000L
    private const val CONNECT_MS = 3_000L

    /** More than this many same-named files for one song is not worth the requests; the first ones are the ones taken. */
    private const val MAX_FILES_TRIED = 2
    private val HEADERS = mapOf(
        "User-Agent" to "NuvioDesktop (https://github.com/UmbraProjects/NuvioDesktop)",
        "Accept" to "application/json",
    )
    private val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes, firstFailurePause = FIRST_FAILURE_PAUSE)

    /**
     * [LyricsLookup.Found] with a [Lyrics.Synced] timed to the syllable, [LyricsLookup.Missing] when AMLL has no file
     * for this song (or none that fits the track), [LyricsLookup.Unreachable] for a failure or a pause.
     */
    suspend fun find(title: String, artist: String, durationSeconds: Int): LyricsLookup {
        if (title.isBlank() || artist.isBlank()) return LyricsLookup.Missing
        if (breaker.paused()) return LyricsLookup.Unreachable
        val searchUrl = "$BASE/search?trackName=${coreTrackTitle(title).encodeURLParameter()}&artistName=${artist.encodeURLParameter()}"
        val search = request(searchUrl)
        when {
            search == null -> { breaker.failed(); return LyricsLookup.Unreachable }
            search.status == 404 -> { breaker.succeeded(); return LyricsLookup.Missing }
            search.status == 429 -> { breaker.pauseNow(); return LyricsLookup.Unreachable }
            search.status !in 200..299 -> { breaker.failed(); return LyricsLookup.Unreachable }
        }
        val entries = pickAmllEntries(parseAmllSearch(parse(search!!.body)), title, artist)
        if (entries.isEmpty()) { breaker.succeeded(); return LyricsLookup.Missing }
        for (entry in entries.take(MAX_FILES_TRIED)) {
            val file = request("$BASE/get?id=${entry.id.encodeURLParameter()}")
            when {
                file == null -> { breaker.failed(); return LyricsLookup.Unreachable }
                file.status == 429 -> { breaker.pauseNow(); return LyricsLookup.Unreachable }
                file.status == 404 -> continue
                file.status !in 200..299 -> { breaker.failed(); return LyricsLookup.Unreachable }
            }
            breaker.succeeded()
            val ttml = amllTtmlOf(parse(file!!.body)) ?: continue
            val lines = parseTtml(ttml).lines
            if (lines.none { it.text.isNotBlank() } || !unisonFitsTrack(lines, durationSeconds)) continue
            return LyricsLookup.Found(Lyrics.Synced(lines, durationSeconds.toDouble(), source = AMLL_SOURCE))
        }
        return LyricsLookup.Missing
    }

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private suspend fun request(url: String): RawHttpResponse? =
        try {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = HEADERS,
                body = "",
                callTimeoutMs = CALL_MS,
                connectTimeoutMs = CONNECT_MS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "amll request failed: ${error.message}" }
            null
        }
}
