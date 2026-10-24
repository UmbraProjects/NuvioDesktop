package com.nuvio.app.features.themesong

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.trailer.YouTubeSearchHit
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.log10

/**
 * Finds a title's most popular *song* instead of searching YouTube for "<title> main theme".
 *
 * 1. Deezer (free, keyless) is asked for the title's soundtrack albums. Every Deezer track carries a
 *    global popularity `rank`, so the album's best-known track is simply the highest rank:
 *    "The Nightmare Before Christmas" -> "This Is Halloween".
 * 2. The track is then searched on YouTube by "artist + track name" and only an upload that is the
 *    same recording (same words, about the same length, not a trailer/cover/remix) is accepted.
 *    A trailer cannot pass that: its title is not the song's and its length is not the song's.
 */
private val JSON = Json { ignoreUnknownKeys = true }

internal data class SoundtrackStub(val id: Long, val title: String)

internal data class SoundtrackTrack(
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    /** Deezer's global popularity; only comparable between Deezer tracks. */
    val rank: Long,
)

internal data class SoundtrackAlbum(
    val title: String,
    val year: Int?,
    val trackCount: Int,
    val tracks: List<SoundtrackTrack>,
)

// ---------------------------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------------------------

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun parseAlbumSearch(body: String): List<SoundtrackStub> {
    val data = (runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("data") as? JsonArray)
        ?: return emptyList()
    return data.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SoundtrackStub(id, title)
    }
}

internal fun parseTracks(data: JsonElement?, fallbackArtist: String): List<SoundtrackTrack> =
    (data as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val title = (obj.str("title_short")?.takeIf { it.isNotBlank() } ?: obj.str("title")) ?: return@mapNotNull null
        SoundtrackTrack(
            title = title,
            artist = ((obj["artist"] as? JsonObject)?.str("name")).orEmpty().ifBlank { fallbackArtist },
            durationSeconds = (obj["duration"] as? JsonPrimitive)?.intOrNull ?: 0,
            rank = (obj["rank"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

internal fun parseAlbum(body: String): SoundtrackAlbum? {
    val obj = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val title = obj.str("title") ?: return null
    val artist = ((obj["artist"] as? JsonObject)?.str("name")).orEmpty()
    val tracks = parseTracks((obj["tracks"] as? JsonObject)?.get("data"), artist)
    return SoundtrackAlbum(
        title = title,
        year = obj.str("release_date")?.take(4)?.toIntOrNull(),
        trackCount = (obj["nb_tracks"] as? JsonPrimitive)?.intOrNull ?: tracks.size,
        tracks = tracks,
    )
}

// ---------------------------------------------------------------------------------------------
// Picking the albums
// ---------------------------------------------------------------------------------------------

/** Words that say an album is the title's soundtrack rather than something that shares its name. */
private val ALBUM_MUSIC_WORDS = setOf("soundtrack", "ost", "score", "motion", "television", "series")

private val ALBUM_UNWANTED = setOf(
    "tribute", "karaoke", "cover", "covers", "lullaby", "lullabies", "piano", "party", "workout",
    "remix", "remixed", "instrumentals", "quartet", "sleep", "famous", "inspired",
)

/** Words that may appear in a soundtrack album's name without making it a different title. */
private val ALBUM_GENERIC = setOf(
    "the", "a", "an", "of", "from", "and", "original", "motion", "picture", "soundtrack", "ost", "score",
    "music", "deluxe", "special", "expanded", "complete", "edition", "anniversary", "television", "series",
    "tv", "film", "movie", "official", "version",
)

private val YEAR_TOKEN = Regex("(19|20)\\d{2}")

/**
 * Soundtrack albums for [title], best first. An album has to carry every significant word of the
 * title and say it is a soundtrack, and not be a tribute, karaoke or cover record. Words the title
 * does not have count against it, so "Halloween Kills" loses to "Halloween" for the title
 * "Halloween"; only the albums with the fewest such words are kept.
 */
internal fun pickSoundtrackAlbums(
    stubs: List<SoundtrackStub>,
    title: String,
    year: String?,
    limit: Int = 3,
): List<SoundtrackStub> {
    val titleTokens = tokens(title).filterNot { it in ARTICLES }
    if (titleTokens.isEmpty()) return emptyList()
    val scored = stubs.distinctBy { it.id }.mapNotNull { stub ->
        val words = tokens(stub.title)
        val set = words.toSet()
        if (titleTokens.any { it !in set }) return@mapNotNull null
        if (set.none { it in ALBUM_MUSIC_WORDS }) return@mapNotNull null
        if (set.any { it in ALBUM_UNWANTED }) return@mapNotNull null
        var extras = 0
        for (word in words) {
            if (word in titleTokens || word in ALBUM_GENERIC) continue
            if (YEAR_TOKEN.matches(word)) { if (word != year) extras += 2; continue }
            extras += 1
        }
        extras to stub
    }
    val best = scored.minOfOrNull { it.first } ?: return emptyList()
    return scored.filter { it.first == best }.map { it.second }.take(limit)
}

// ---------------------------------------------------------------------------------------------
// Picking the song
// ---------------------------------------------------------------------------------------------

/** Tracks that are not music: spoken word, extras, ads. */
private val TRACK_UNWANTED = setOf(
    "dialogue", "dialog", "skit", "interview", "commentary", "trailer", "promo", "spot", "karaoke",
    "narration", "excerpt", "speech", "announcement",
)

/** Real music, but rarely the one anybody means. Ranked at a quarter of its popularity. */
private val TRACK_SOFT_UNWANTED = setOf(
    "reprise", "instrumental", "demo", "remix", "live", "acapella", "outtake", "alternate", "version",
)

private val BRACKETED = Regex("\\s*[(\\[].*?[)\\]]")

/** "This Is Halloween - From \"The Nightmare Before Christmas\"" and "This Is Halloween (Reprise)" -> "This Is Halloween". */
internal fun baseSongTitle(title: String): String =
    title.replace(BRACKETED, "").substringBefore(" - ").trim()

/**
 * The most popular songs across [albums], best first, one per distinct title. Popularity is
 * Deezer's rank; spoken tracks and clips that are too short or too long are dropped.
 */
internal fun pickPopularSongs(albums: List<SoundtrackAlbum>, limit: Int = 3): List<SoundtrackTrack> {
    val best = HashMap<String, Pair<Double, SoundtrackTrack>>()
    for (track in albums.flatMap { it.tracks }) {
        if (track.durationSeconds !in SONG_MIN_SECONDS..SONG_MAX_SECONDS) continue
        val words = tokens(track.title).toSet()
        if (words.any { it in TRACK_UNWANTED }) continue
        val base = tokens(baseSongTitle(track.title))
        if (base.isEmpty()) continue
        var weight = track.rank.toDouble()
        if (words.any { it in TRACK_SOFT_UNWANTED }) weight *= 0.25
        val key = base.joinToString(" ")
        val current = best[key]
        if (current == null || weight > current.first) best[key] = weight to track
    }
    return best.values.sortedByDescending { it.first }.take(limit).map { it.second }
}

internal const val SONG_MIN_SECONDS = 45
internal const val SONG_MAX_SECONDS = 10 * 60

// ---------------------------------------------------------------------------------------------
// Finding that exact recording on YouTube
// ---------------------------------------------------------------------------------------------

internal fun songSearchQuery(song: SoundtrackTrack): String =
    listOf(song.artist.takeUnless { it.equals("Various Artists", ignoreCase = true) }.orEmpty(), baseSongTitle(song.title), "audio")
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * Uploads worth trying for [song], best first. The upload must be the song: every word of its title
 * in the upload's title, a length within 15% (at least 15 seconds) of the album track, and no
 * trailer, cover, remix, reaction and so on. Then a "- Topic" channel (YouTube Music's own upload
 * of the album track), a verified channel and the artist's own channel win, with views as the tie-break.
 */
internal fun rankSongHits(hits: List<YouTubeSearchHit>, song: SoundtrackTrack, limit: Int = 3): List<YouTubeSearchHit> {
    val songTokens = tokens(baseSongTitle(song.title)).filterNot { it in ARTICLES }
    if (songTokens.isEmpty()) return emptyList()
    val ownWords = tokens(song.title).toSet()
    val artistToken = tokens(song.artist).firstOrNull { it !in ARTICLES && it.length > 2 }
    val tolerance = maxOf(15, song.durationSeconds * 15 / 100)
    return hits.withIndex()
        .mapNotNull { (index, hit) ->
            if (hit.durationSeconds <= 0) return@mapNotNull null
            val diff = if (song.durationSeconds > 0) abs(hit.durationSeconds - song.durationSeconds) else 0
            if (diff > tolerance) return@mapNotNull null
            val words = tokens(hit.title).toSet()
            val channelWords = tokens(hit.channel).toSet()
            if (songTokens.any { it !in words }) return@mapNotNull null
            if (words.any { (it in UNWANTED || it in TRACK_UNWANTED) && it !in ownWords }) return@mapNotNull null
            var score = 0
            if ("topic" in channelWords) score += 4
            if (hit.verified) score += 2
            if (artistToken != null && (artistToken in channelWords || artistToken in words)) score += 2
            if ("audio" in words || "official" in words) score += 1
            if (diff <= 3) score += 2
            if (hit.views > 0) score += (log10(hit.views.toDouble()) / 2.5).toInt().coerceIn(0, 3)
            Triple(score, index, hit)
        }
        .sortedWith(compareByDescending<Triple<Int, Int, YouTubeSearchHit>> { it.first }.thenBy { it.second })
        .take(limit)
        .map { it.third }
}

// ---------------------------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------------------------

private val log = Logger.withTag("ThemeSong")
private const val DEEZER = "https://api.deezer.com"
private const val REQUEST_TIMEOUT_MS = 6_000L
private val HEADERS = mapOf(
    "User-Agent" to "NuvioDesktop/1.0 (theme songs; Kotlin Multiplatform)",
    "Accept" to "application/json",
)

object ThemeSongCatalog {
    /**
     * The best-known songs from [title]'s soundtrack, most popular first (at most [limit]); empty
     * when Deezer is unreachable or has no soundtrack for it, so callers can fall back.
     */
    internal suspend fun popularSongs(title: String, year: String?, isSeries: Boolean, limit: Int = 3): List<SoundtrackTrack> {
        val name = title.trim()
        if (name.isEmpty()) return emptyList()
        return try {
            val queries = if (isSeries) {
                listOf("$name soundtrack", "$name original series soundtrack")
            } else {
                listOf("$name soundtrack", "$name original motion picture soundtrack")
            }
            val stubs = ArrayList<SoundtrackStub>()
            for (query in queries) {
                val body = get("$DEEZER/search/album?limit=25&q=${query.encodeURLParameter()}") ?: continue
                stubs += parseAlbumSearch(body)
            }
            var albums = pickSoundtrackAlbums(stubs, name, year).mapNotNull { loadAlbum(it.id) }
            // A remake or a later series has its own soundtrack. When the year is known and any album
            // is from about then, the rest are another production's.
            val filmYear = year?.toIntOrNull()
            if (filmYear != null) {
                val near = albums.filter { it.year != null && abs(it.year - filmYear) <= 1 }
                if (near.isNotEmpty()) albums = near
            }
            pickPopularSongs(albums, limit).also { songs ->
                log.i { "\"$name\": ${albums.size} soundtrack albums, songs ${songs.map { "${it.artist} - ${it.title} (${it.rank})" }}" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "soundtrack lookup failed for \"$name\": ${error.message}" }
            emptyList()
        }
    }

    private suspend fun loadAlbum(id: Long): SoundtrackAlbum? {
        val album = parseAlbum(get("$DEEZER/album/$id") ?: return null) ?: return null
        // The album object can list only part of a long album, or leave out the ranks; the track
        // endpoint has them all.
        if (album.tracks.size >= album.trackCount && album.tracks.any { it.rank > 0 }) return album
        val body = get("$DEEZER/album/$id/tracks?limit=100") ?: return album
        val full = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("data")
        val tracks = parseTracks(full, fallbackArtist = "")
        return if (tracks.isEmpty()) album else album.copy(tracks = tracks)
    }

    /** Deezer reports errors as 200 with an `error` object, so a body without data is a miss. */
    private suspend fun get(url: String): String? =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            try {
                httpGetTextWithHeaders(url, HEADERS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Deezer request failed: ${error.message}" }
                null
            }
        }?.takeUnless { it.contains("\"error\"") && !it.contains("\"data\"") && !it.contains("\"tracks\"") }
}
