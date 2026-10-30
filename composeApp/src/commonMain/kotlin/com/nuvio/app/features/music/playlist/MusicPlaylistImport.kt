package com.nuvio.app.features.music.playlist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.sameArtistName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/*
 * fork:music-playlists
 *
 * Bringing a playlist in from elsewhere. A YouTube Music or YouTube link is read as it is (its songs are already
 * YouTube Music's). Spotify and Apple Music links are read from the public page each serves for a playlist or album
 * (no account or key), and every song is then found on YouTube Music by its title, artist and length. Anything else
 * pasted is taken as a list, one "Artist - Title" per line. Spotify's page lists at most 100 songs, Apple's as many as
 * it shows without scrolling.
 */

private val log = Logger.withTag("MusicPlaylistImport")
private val JSON = Json { ignoreUnknownKeys = true }
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
private const val MATCH_PARALLEL = 4

/** What the person pasted, recognised. */
internal sealed interface ImportSource {
    data class YouTube(val playlistId: String) : ImportSource
    data class Spotify(val kind: String, val id: String) : ImportSource
    data class AppleMusic(val url: String) : ImportSource
    data class TextList(val lines: List<WantedSong>) : ImportSource
}

/** A song named by another service (or a pasted line), to be found on YouTube Music. */
internal data class WantedSong(val title: String, val artist: String, val durationSeconds: Int = 0, val album: String? = null) {
    val label: String get() = if (artist.isBlank()) title else "$artist - $title"
}

/** A list read from a source: its name (when it has one) and its songs in order. */
internal data class ImportedList(val name: String?, val songs: List<WantedSong>)

internal sealed interface ImportOutcome {
    data class Ready(val name: String, val source: String, val tracks: List<PlaylistTrack>, val missed: List<String>) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

private val YT_LIST = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
private val SPOTIFY = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?(playlist|album)/([A-Za-z0-9]+)""")
private val SPOTIFY_URI = Regex("""spotify:(playlist|album):([A-Za-z0-9]+)""")
private val APPLE = Regex("""https?://music\.apple\.com/[^\s]+/(?:playlist|album)/[^\s]+""")
private val LINE_SPLIT = Regex("""\s+[-–—]\s+""")

/** Recognises [text]; null when it is empty or holds nothing usable. */
internal fun recogniseImport(text: String): ImportSource? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if ("youtube.com" in t || "youtu.be" in t) {
        YT_LIST.find(t)?.let { return ImportSource.YouTube(it.groupValues[1]) }
    }
    SPOTIFY.find(t)?.let { return ImportSource.Spotify(it.groupValues[1], it.groupValues[2]) }
    SPOTIFY_URI.find(t)?.let { return ImportSource.Spotify(it.groupValues[1], it.groupValues[2]) }
    APPLE.find(t)?.let { return ImportSource.AppleMusic(it.value) }
    val lines = parseSongLines(t)
    return if (lines.isEmpty()) null else ImportSource.TextList(lines)
}

/**
 * One song per line: "Artist - Title" (a hyphen or dash with spaces round it), or a title alone. Numbering ("1.", "02)")
 * and a trailing length ("3:45") are dropped, so a list copied out of most apps reads as it is.
 */
internal fun parseSongLines(text: String): List<WantedSong> =
    text.lines().mapNotNull { raw ->
        var line = raw.trim().replace(Regex("""^\d{1,4}[.)]\s*"""), "")
        var seconds = 0
        Regex("""\s+\(?(\d{1,2}):(\d{2})\)?$""").find(line)?.let { m ->
            seconds = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            line = line.removeRange(m.range).trim()
        }
        if (line.isEmpty() || line.startsWith("http")) return@mapNotNull null
        val parts = line.split(LINE_SPLIT, limit = 2)
        if (parts.size == 2) WantedSong(title = parts[1].trim(), artist = parts[0].trim(), durationSeconds = seconds)
        else WantedSong(title = line, artist = "", durationSeconds = seconds)
    }

// region reading the services' pages

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.num(): Long? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

private fun scriptJson(html: String, id: String): JsonElement? {
    val start = html.indexOf("id=\"$id\"").takeIf { it >= 0 } ?: return null
    val open = html.indexOf('>', start) + 1
    val close = html.indexOf("</script>", open)
    if (open <= 0 || close < 0) return null
    return runCatching { JSON.parseToJsonElement(html.substring(open, close)) }.getOrNull()
}

/** Spotify's embed page for a playlist or album: its `__NEXT_DATA__` holds the name and the track list. */
internal fun parseSpotifyEmbed(html: String): ImportedList? {
    val entity = scriptJson(html, "__NEXT_DATA__")
        .obj()?.get("props").obj()?.get("pageProps").obj()?.get("state").obj()?.get("data").obj()?.get("entity").obj()
        ?: return null
    val songs = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item.obj() ?: return@mapNotNull null
        val title = o["title"].str()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        // The artists come as one line, comma separated, sometimes with no-break spaces.
        val artist = o["subtitle"].str().orEmpty().replace(' ', ' ').split(',').firstOrNull()?.trim().orEmpty()
        WantedSong(title, artist, ((o["duration"].num() ?: 0L) / 1000).toInt())
    }
    if (songs.isEmpty()) return null
    return ImportedList(entity["name"].str() ?: entity["title"].str(), songs)
}

/** Apple Music's playlist or album page: its `serialized-server-data` lists each song with its artist and album. */
internal fun parseAppleMusicPage(html: String): ImportedList? {
    val root = scriptJson(html, "serialized-server-data") ?: return null
    val songs = ArrayList<WantedSong>()
    fun walk(node: JsonElement?) {
        when (node) {
            is JsonObject -> {
                val title = node["title"].str()
                val artist = node["artistName"].str()
                if (title != null && artist != null && node["duration"] != null) {
                    val album = (node["tertiaryLinks"] as? JsonArray)?.firstOrNull().obj()?.get("title").str()
                    songs += WantedSong(title.trim(), artist.trim(), ((node["duration"].num() ?: 0L) / 1000).toInt(), album)
                } else {
                    node.values.forEach(::walk)
                }
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    if (songs.isEmpty()) return null
    val name = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""").find(html)?.groupValues?.get(1)
        ?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")
        ?.substringBefore(" by ")?.trim()
    return ImportedList(name, songs.distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() })
}

// endregion

// region matching on YouTube Music

private fun key(text: String): String =
    text.lowercase().replace(Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|remaster|version|edit|mix)[^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit).*$"""), "")
        .filter { it.isLetterOrDigit() }

/**
 * The search hit that is [wanted]: the same title (ignoring "feat." notes, remaster tags and punctuation) by the same
 * artist, nearest in length; failing that, the same title by anyone when the line named no artist. Null when nothing
 * fits well enough, rather than a wrong song.
 */
internal fun pickSongMatch(wanted: WantedSong, hits: List<MusicTrack>): MusicTrack? {
    val title = key(wanted.title)
    if (title.isEmpty()) return null
    fun titleFits(t: MusicTrack): Boolean {
        val k = key(t.title)
        return k == title || (k.length >= 4 && title.length >= 4 && (k.startsWith(title) || title.startsWith(k)))
    }
    fun artistFits(t: MusicTrack): Boolean =
        wanted.artist.isBlank() || t.artists.any { a ->
            sameArtistName(a.name, wanted.artist) || key(wanted.artist).let { w -> w.isNotEmpty() && (key(a.name).contains(w) || w.contains(key(a.name))) }
        }
    fun lengthGap(t: MusicTrack): Int =
        if (wanted.durationSeconds <= 0 || t.durationSeconds <= 0) 0 else abs(wanted.durationSeconds - t.durationSeconds)
    // An exact title beats one that only starts the same ("... (Live)"), then the nearest length.
    return hits.filter { titleFits(it) && artistFits(it) && lengthGap(it) <= 20 }
        .minWithOrNull(compareBy<MusicTrack> { if (key(it.title) == title) 0 else 1 }.thenBy { lengthGap(it) })
        ?: hits.firstOrNull()?.takeIf { wanted.artist.isNotBlank() && artistFits(it) && titleFits(it) }
}

private suspend fun matchAll(
    catalog: MusicCatalog,
    songs: List<WantedSong>,
    onProgress: (done: Int, total: Int) -> Unit,
): Pair<List<PlaylistTrack>, List<String>> = coroutineScope {
    val gate = Semaphore(MATCH_PARALLEL)
    var done = 0
    val found = songs.map { wanted ->
        async {
            gate.withPermit {
                val hit = try {
                    pickSongMatch(wanted, catalog.searchSongs(listOf(wanted.artist, wanted.title).filter { it.isNotBlank() }.joinToString(" ")))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                done++
                onProgress(done, songs.size)
                hit
            }
        }
    }.awaitAll()
    val tracks = found.filterNotNull().map { it.toPlaylistTrack() }.distinctBy { it.videoId }
    val missed = songs.zip(found).filter { it.second == null }.map { it.first.label }
    tracks to missed
}

// endregion

/** Reads [source] and finds its songs on YouTube Music. [onProgress] gets a line to show while it runs. */
internal suspend fun runImport(
    source: ImportSource,
    catalog: MusicCatalog,
    onProgress: (String) -> Unit,
): ImportOutcome {
    try {
        val (list, from) = when (source) {
            is ImportSource.YouTube -> {
                onProgress("Reading the YouTube playlist…")
                val page = catalog.playlist(source.playlistId)
                    ?: return ImportOutcome.Failed("Couldn't read that playlist. Check it's public (or unlisted) and try again.")
                if (page.tracks.isEmpty()) return ImportOutcome.Failed("That playlist has no songs YouTube Music can play.")
                return ImportOutcome.Ready(
                    name = page.title ?: "YouTube playlist",
                    source = "YouTube Music",
                    tracks = page.tracks.map { it.toPlaylistTrack() },
                    missed = emptyList(),
                )
            }
            is ImportSource.Spotify -> {
                onProgress("Reading the Spotify ${source.kind}…")
                val html = httpGetTextWithHeaders("https://open.spotify.com/embed/${source.kind}/${source.id}", mapOf("User-Agent" to BROWSER_UA))
                (parseSpotifyEmbed(html) ?: return ImportOutcome.Failed("Couldn't read that Spotify link. Is the playlist public?")) to "Spotify"
            }
            is ImportSource.AppleMusic -> {
                onProgress("Reading the Apple Music page…")
                val html = httpGetTextWithHeaders(source.url, mapOf("User-Agent" to BROWSER_UA))
                (parseAppleMusicPage(html) ?: return ImportOutcome.Failed("Couldn't read that Apple Music link. Is the playlist public?")) to "Apple Music"
            }
            is ImportSource.TextList -> ImportedList(null, source.lines) to "a pasted list"
        }
        val (tracks, missed) = matchAll(catalog, list.songs) { done, total -> onProgress("Finding songs on YouTube Music… $done of $total") }
        if (tracks.isEmpty()) return ImportOutcome.Failed("None of the ${list.songs.size} songs could be found on YouTube Music.")
        return ImportOutcome.Ready(list.name ?: "Imported playlist", from, tracks, missed)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Import failed" }
        return ImportOutcome.Failed("Couldn't import: ${e.message ?: "the page could not be read"}.")
    }
}
