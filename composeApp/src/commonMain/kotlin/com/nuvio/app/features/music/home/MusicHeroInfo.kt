package com.nuvio.app.features.music.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What the music search's hero says about a result (TV Mode and Adaptive), from TheAudioDB: an artist's photo, logo,
 * fanart, biography and genre, and an album's or a track's own description, genre and year. Its public test key is
 * rate limited, so each answer (a miss too) is kept for the session and only the result in focus is asked about.
 */

internal data class AudioDbArtist(
    val name: String,
    val thumb: String?,
    val logo: String?,
    val fanart: String?,
    val biography: String?,
    val genre: String?,
    val style: String?,
)

internal data class AudioDbRelease(
    val description: String?,
    val genre: String?,
    val year: String?,
)

internal object MusicHeroInfo {
    private val log = Logger.withTag("MusicHeroInfo")
    private const val BASE = "https://www.theaudiodb.com/api/v1/json/123"
    private val lock = Mutex()
    private val artists = HashMap<String, AudioDbArtist?>()
    private val releases = HashMap<String, AudioDbRelease?>()

    suspend fun artist(name: String): AudioDbArtist? {
        val key = name.trim().lowercase().ifEmpty { return null }
        lock.withLock { if (artists.containsKey(key)) return artists[key] }
        val found = get("$BASE/search.php?s=${name.trim().encodeURLParameter()}")?.let { parseAudioDbArtist(it, name) }
        lock.withLock { artists[key] = found }
        return found
    }

    suspend fun album(artist: String, title: String): AudioDbRelease? =
        release("album:$artist:$title", "$BASE/searchalbum.php?s=${artist.trim().encodeURLParameter()}&a=${title.trim().encodeURLParameter()}", "album")

    suspend fun track(artist: String, title: String): AudioDbRelease? =
        release("track:$artist:$title", "$BASE/searchtrack.php?s=${artist.trim().encodeURLParameter()}&t=${title.trim().encodeURLParameter()}", "track")

    private suspend fun release(cacheKey: String, url: String, listKey: String): AudioDbRelease? {
        val key = cacheKey.lowercase()
        lock.withLock { if (releases.containsKey(key)) return releases[key] }
        val found = get(url)?.let { parseAudioDbRelease(it, listKey) }
        lock.withLock { releases[key] = found }
        return found
    }

    private suspend fun get(url: String): String? = try {
        httpGetTextWithHeaders(url, mapOf("Accept" to "application/json"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        log.d { "TheAudioDB lookup failed: ${error.message}" }
        null
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/** The artist named [name] in a TheAudioDB `search.php` answer; null when it is not there. */
internal fun parseAudioDbArtist(body: String, name: String): AudioDbArtist? {
    val list = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("artists") as? JsonArray }.getOrNull() ?: return null
    val match = list.mapNotNull { it as? JsonObject }.firstOrNull { it.text("strArtist")?.equals(name.trim(), ignoreCase = true) == true }
        ?: return null
    return AudioDbArtist(
        name = match.text("strArtist") ?: name,
        thumb = match.text("strArtistThumb"),
        logo = match.text("strArtistLogo"),
        fanart = listOf("strArtistFanart", "strArtistFanart2", "strArtistWideThumb").firstNotNullOfOrNull { match.text(it) },
        biography = match.text("strBiographyEN"),
        genre = match.text("strGenre"),
        style = match.text("strStyle"),
    )
}

/** The first entry of a TheAudioDB `searchalbum.php` (`album`) or `searchtrack.php` (`track`) answer. */
internal fun parseAudioDbRelease(body: String, listKey: String): AudioDbRelease? {
    val list = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get(listKey) as? JsonArray }.getOrNull() ?: return null
    val first = list.firstOrNull() as? JsonObject ?: return null
    return AudioDbRelease(
        description = first.text("strDescriptionEN"),
        genre = first.text("strGenre"),
        year = first.text("intYearReleased")?.takeIf { it != "0" },
    )
}
