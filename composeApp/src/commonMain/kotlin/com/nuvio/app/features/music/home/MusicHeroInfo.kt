package com.nuvio.app.features.music.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.music.library.FanartTvClient
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.WikipediaClient
import com.nuvio.app.features.music.library.looksLikeMusicianText
import com.nuvio.app.features.music.library.sameArtistName
import com.nuvio.app.features.music.library.parseDeezerAlbumDetail
import com.nuvio.app.features.music.library.songKey
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What the music search's hero says about a result (TV Mode and Adaptive). In order of preference:
 *  - the library's own artist, when it is saved (its biography, HD logo and photo are already there);
 *  - TheAudioDB (photo, logo, fanart, biography, genre; an album's or track's description), with the person's own key
 *    from Settings > Music when they set one, else the rate-limited public one;
 *  - fanart.tv for an HD logo, when a key is set and TheAudioDB gave the artist's MusicBrainz id;
 *  - English Wikipedia for a biography or an album's description that TheAudioDB does not have.
 * Each answer (a real "not found" too) is kept for the session; a lookup that failed (offline, rate limited) is not, so
 * the next look asks again. Only the result in focus is asked about.
 */

internal data class AudioDbArtist(
    val name: String,
    val thumb: String?,
    val logo: String?,
    val fanart: String?,
    val biography: String?,
    val genre: String?,
    val style: String?,
    val musicBrainzId: String? = null,
    /** Every fanart photo TheAudioDB has for the artist (up to four), so results can each show a different one. */
    val fanarts: List<String> = listOfNotNull(fanart),
)

internal data class AudioDbRelease(
    val description: String?,
    val genre: String?,
    val year: String?,
    /** The record's own cover at full size (an album), or the song's own picture (a track). */
    val cover: String? = null,
    /** The disc's face, for the disc peeking out of the cover. */
    val cdArt: String? = null,
    /** TheAudioDB's score out of 10 ("8.4"), when people have rated it. */
    val score: String? = null,
    val label: String? = null,
    /** MusicBrainz's release-group id, which fanart.tv keys its disc art by. */
    val musicBrainzId: String? = null,
)

/** What the hero shows for an artist, from wherever it was found. */
internal data class HeroArtistInfo(
    val thumb: String?,
    /** The artist's name set as a logo (their "fancy name"), shown in the hero's logo slot. */
    val logo: String?,
    val fanart: String?,
    val biography: String?,
    val genre: String?,
    val style: String?,
    /** The artist's fanart photos (TheAudioDB's, then fanart.tv's backgrounds), for backdrops that differ per result. */
    val fanarts: List<String> = listOfNotNull(fanart),
)

/** What the hero's facts line says about a song (Deezer). */
internal data class HeroSongFacts(val year: String?, val bpm: Int?, val rank: Int?, val explicit: Boolean)

/** What the hero's facts line says about a record (Deezer, and TheAudioDB's score). */
internal data class HeroAlbumFacts(val date: String?, val label: String?, val tracks: Int?, val minutes: Int?)

internal object MusicHeroInfo {
    private val log = Logger.withTag("MusicHeroInfo")
    private const val PUBLIC_KEY = "123"
    private val lock = Mutex()
    private val artists = HashMap<String, AudioDbArtist?>()
    private val releases = HashMap<String, AudioDbRelease?>()
    private val wiki = HashMap<String, String?>()
    private val fanartLogos = HashMap<String, String?>()
    private val fanartArtists = HashMap<String, com.nuvio.app.features.music.library.FanartArtist?>()
    private val songFacts = HashMap<String, HeroSongFacts?>()
    private val albumFacts = HashMap<String, HeroAlbumFacts?>()
    private val topSongs = HashMap<Long, List<String>>()
    // Read from composition (the search rows' order) while lookups write it: a snapshot map is safe for both.
    private val fans = androidx.compose.runtime.mutableStateMapOf<String, Long?>()

    private val base: String
        get() {
            val own = runCatching { MusicLibraryRepository.state.value.settings.audioDbKey }.getOrNull().orEmpty().trim()
            return "https://www.theaudiodb.com/api/v1/json/${own.ifEmpty { PUBLIC_KEY }}"
        }

    /** TheAudioDB's artist named [name]; null when it has none, or the lookup failed. */
    suspend fun artist(name: String, essential: Boolean = true): AudioDbArtist? {
        val key = name.trim().lowercase().ifEmpty { return null }
        lock.withLock { if (artists.containsKey(key)) return artists[key] }
        val body = get("$base/search.php?s=${name.trim().encodeURLParameter()}", essential) ?: return null
        val found = parseAudioDbArtist(body, name)
        lock.withLock { artists[key] = found }
        return found
    }

    /**
     * Everything the hero shows for the artist [name]: the library's saved artist first, then TheAudioDB, a fanart.tv HD
     * logo, and Wikipedia's opening for a biography when neither has one.
     */
    suspend fun heroArtist(name: String, browseId: String? = null): HeroArtistInfo? {
        val saved = runCatching {
            MusicLibraryRepository.state.value.artists.firstOrNull { (browseId != null && it.id == browseId) || sameArtistName(it.name, name) }
        }.getOrNull()
        val audio = artist(name)
        val fanartLogo = if (saved?.art?.bestLogo == null) audio?.musicBrainzId?.let { fanartLogo(it) } else null
        val biography = saved?.biography?.takeIf { it.isNotBlank() } ?: audio?.biography ?: wikipediaArtist(name)
        val backgrounds = audio?.musicBrainzId?.let { fanartBackgrounds(it) }.orEmpty()
        val info = HeroArtistInfo(
            thumb = saved?.art?.thumb ?: audio?.thumb,
            logo = saved?.art?.bestLogo ?: fanartLogo ?: audio?.logo,
            fanart = audio?.fanart,
            biography = biography,
            genre = audio?.genre ?: saved?.genres?.firstOrNull(),
            style = audio?.style ?: saved?.style,
            fanarts = (audio?.fanarts.orEmpty() + backgrounds).distinct(),
        )
        return info.takeIf { it != HeroArtistInfo(null, null, null, null, null, null) }
    }

    suspend fun album(artist: String, title: String): AudioDbRelease? =
        release("album:$artist:$title", "$base/searchalbum.php?s=${artist.trim().encodeURLParameter()}&a=${title.trim().encodeURLParameter()}", "album")

    suspend fun track(artist: String, title: String): AudioDbRelease? =
        release("track:$artist:$title", "$base/searchtrack.php?s=${artist.trim().encodeURLParameter()}&t=${title.trim().encodeURLParameter()}", "track")

    /** The album's description: TheAudioDB's, else the opening of its English Wikipedia page. */
    suspend fun albumDescription(artist: String, title: String): String? =
        album(artist, title)?.description ?: wikipediaAlbum(artist, title)

    /** The song's own description: TheAudioDB's, else the opening of the song's English Wikipedia article. */
    suspend fun songDescription(artist: String, title: String): String? =
        track(artist, title)?.description ?: cachedWiki("song:${artist.lowercase()}:${title.lowercase()}") {
            WikipediaClient.songText(title, artist)
        }

    private suspend fun release(cacheKey: String, url: String, listKey: String): AudioDbRelease? {
        val key = cacheKey.lowercase()
        lock.withLock { if (releases.containsKey(key)) return releases[key] }
        val body = get(url) ?: return null
        val found = parseAudioDbRelease(body, listKey)
        lock.withLock { releases[key] = found }
        return found
    }

    /** fanart.tv's background photos of the artist, when a key is set (kept per session). */
    private suspend fun fanartBackgrounds(mbid: String): List<String> = fanartArtist(mbid)?.backgrounds?.take(6).orEmpty()

    /** fanart.tv's answer for the artist [mbid] (photos, logos, the records' discs), when a key is set; kept per session. */
    private suspend fun fanartArtist(mbid: String): com.nuvio.app.features.music.library.FanartArtist? {
        lock.withLock { if (fanartArtists.containsKey(mbid)) return fanartArtists[mbid] }
        val key = runCatching { MusicLibraryRepository.state.value.settings.fanartApiKey }.getOrNull().orEmpty()
        val client = FanartTvClient(key)
        if (!client.isConfigured) return null
        val art = runCatching { client.artist(mbid) }.getOrNull() ?: return null
        lock.withLock { fanartArtists[mbid] = art }
        return art
    }

    /**
     * The disc of the record [albumTitle] by [artist], for the disc peeking out of the cover: TheAudioDB's disc art (the
     * title as given, then without its brackets: "(Deluxe)", "(10 Year Anniversary Edition)"), else fanart.tv's for the
     * same release group. Null when neither has one.
     */
    suspend fun discArt(artist: String, albumTitle: String): String? {
        val plain = albumTitle.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim()
        val release = album(artist, albumTitle)?.takeIf { it.cdArt != null || plain == albumTitle.trim() }
            ?: plain.takeIf { it.isNotEmpty() && it != albumTitle.trim() }?.let { album(artist, it) }
            ?: album(artist, albumTitle)
        release?.cdArt?.let { return it }
        val group = release?.musicBrainzId ?: return null
        val artistMbid = artist(artist)?.musicBrainzId ?: return null
        return fanartArtist(artistMbid)?.releases?.get(group)?.cdArt
    }

    /** A song's year, tempo, place among the artist's most played and explicit flag, from Deezer (no key needed). */
    suspend fun songFacts(artist: String, title: String): HeroSongFacts? {
        val key = "${artist.lowercase()}|${title.lowercase()}"
        lock.withLock { if (songFacts.containsKey(key)) return songFacts[key] }
        val q = "artist:\"${artist.trim()}\" track:\"${title.trim()}\"".encodeURLParameter()
        val search = get("$DEEZER/search/track?q=$q&limit=5") ?: return null
        val hit = parseDeezerTrackHit(search, title) ?: run {
            lock.withLock { songFacts[key] = null }
            return null
        }
        val detail = get("$DEEZER/track/${hit.id}")?.let(::parseDeezerTrackDetail)
        val rank = hit.artistId?.let { id -> deezerTopSongs(id) }?.let { top ->
            val wanted = songKey(title)
            top.indexOfFirst { songKey(it) == wanted }.takeIf { it >= 0 }?.plus(1)
        }
        val facts = HeroSongFacts(
            year = detail?.releaseDate?.take(4)?.takeIf { it.length == 4 && it != "0000" },
            bpm = detail?.bpm?.takeIf { it in 40..260 },
            rank = rank?.takeIf { it <= TOP_SONG_RANKS },
            explicit = hit.explicit,
        )
        lock.withLock { songFacts[key] = facts }
        return facts
    }

    /** A record's release date, label, track count and length, from Deezer. */
    suspend fun albumFacts(artist: String, title: String): HeroAlbumFacts? {
        val key = "${artist.lowercase()}|${title.lowercase()}"
        lock.withLock { if (albumFacts.containsKey(key)) return albumFacts[key] }
        val q = "artist:\"${artist.trim()}\" album:\"${title.trim()}\"".encodeURLParameter()
        val search = get("$DEEZER/search/album?q=$q&limit=5") ?: return null
        val id = parseDeezerAlbumHitId(search, title) ?: run {
            lock.withLock { albumFacts[key] = null }
            return null
        }
        val detail = get("$DEEZER/album/$id")?.let(::parseDeezerAlbumDetail) ?: return null
        val facts = HeroAlbumFacts(
            date = detail.releaseDate,
            label = detail.label?.takeIf { it.isNotBlank() },
            tracks = detail.trackCount?.takeIf { it > 0 },
            minutes = detail.durationSeconds?.takeIf { it > 0 }?.let { (it + 30) / 60 },
        )
        lock.withLock { albumFacts[key] = facts }
        return facts
    }

    private suspend fun deezerTopSongs(artistId: Long): List<String>? {
        lock.withLock { topSongs[artistId]?.let { return it } }
        val body = get("$DEEZER/artist/$artistId/top?limit=$TOP_SONG_RANKS") ?: return null
        val titles = parseDeezerTitles(body)
        lock.withLock { topSongs[artistId] = titles }
        return titles
    }

    /** Deezer's fan count for the artist named [name] (the same name, loosely), or null; kept per session, misses too. */
    suspend fun artistFans(name: String): Long? {
        val key = name.trim().lowercase().ifEmpty { return null }
        lock.withLock { if (fans.containsKey(key)) return fans[key] }
        val body = get("$DEEZER/search/artist?q=${name.trim().encodeURLParameter()}&limit=5") ?: return null
        val found = parseDeezerArtistFans(body, name)
        lock.withLock { fans[key] = found }
        return found
    }

    /** The fan count already known for [name], without asking (null when not known yet or unknown). */
    fun peekFans(name: String): Long? = fans[name.trim().lowercase()]

    private const val DEEZER = "https://api.deezer.com"
    private const val TOP_SONG_RANKS = 50

    private suspend fun fanartLogo(mbid: String): String? {
        lock.withLock { if (fanartLogos.containsKey(mbid)) return fanartLogos[mbid] }
        val key = runCatching { MusicLibraryRepository.state.value.settings.fanartApiKey }.getOrNull().orEmpty()
        val client = FanartTvClient(key)
        if (!client.isConfigured) return null
        val art = runCatching { client.artist(mbid) }.getOrNull() ?: return null
        val logo = art.hdLogos.firstOrNull() ?: art.logos.firstOrNull()
        lock.withLock { fanartLogos[mbid] = logo }
        return logo
    }

    private suspend fun wikipediaArtist(name: String): String? = cachedWiki("artist:${name.lowercase()}") {
        WikipediaClient.artistText(name)?.takeIf(::looksLikeMusicianText)
    }

    private suspend fun wikipediaAlbum(artist: String, title: String): String? = cachedWiki("album:${artist.lowercase()}:${title.lowercase()}") {
        WikipediaClient.albumText(title, artist)
    }

    /** A Wikipedia answer kept for the session; a failed lookup is kept as a miss too (Wikipedia is not rate limited). */
    private suspend fun cachedWiki(key: String, load: suspend () -> String?): String? {
        lock.withLock { if (wiki.containsKey(key)) return wiki[key] }
        val text = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return null
        }
        lock.withLock { wiki[key] = text }
        return text
    }

    /** The answer's body, or null when the lookup failed (so nothing is kept and it is asked again). */
    /**
     * The answer's body, or null when the lookup failed or was held back (so nothing is kept and it is asked again).
     * One request per URL at a time: two parts of the hero asking for the same thing at once share it (every lookup was
     * being made twice). TheAudioDB's public key allows about 30 requests a minute, so its calls are paced ([AudioDbGate]);
     * a call that is not [essential] (a portrait in the people panel) gives way while the allowance is low.
     */
    private suspend fun get(url: String, essential: Boolean = true): String? {
        val shared = CompletableDeferred<String?>()
        val running = lock.withLock { inFlight[url] ?: run { inFlight[url] = shared; null } }
        if (running != null) return running.await()
        try {
            val body = fetch(url, essential)
            shared.complete(body)
            return body
        } catch (error: Throwable) {
            shared.complete(null)
            throw error
        } finally {
            lock.withLock { inFlight.remove(url) }
        }
    }

    private suspend fun fetch(url: String, essential: Boolean): String? {
        val audioDb = "theaudiodb.com" in url
        if (audioDb && !AudioDbGate.take(essential)) {
            log.i { "TheAudioDB allowance low: left a portrait for later (${url.substringAfter("/json/").substringAfter('/').substringBefore('?')})" }
            return null
        }
        return try {
            val body = httpGetTextWithHeaders(url, mapOf("Accept" to "application/json")).trim()
            // Anything but JSON (an HTML error page, a "limit reached" line) is a failed lookup, not an answer.
            body.takeIf { it.startsWith("{") || it.startsWith("[") } ?: run {
                log.i { "Lookup answered without data: ${url.substringBefore('?')} (${body.take(80)})" }
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.i { "Lookup failed: ${url.substringBefore('?')}: ${error.message}" }
            null
        }
    }

    private val inFlight = HashMap<String, CompletableDeferred<String?>>()
}

/**
 * fork:music-search - TheAudioDB's public key allows about 30 requests a minute. A bucket of 30 that refills one every two
 * seconds: the main lookups wait for a turn; the rest (portraits) are only made while at least [RESERVE] are left, so a
 * burst of browsing never starves the result in focus.
 */
internal object AudioDbGate {
    private const val CAPACITY = 30.0
    private const val PER_SECOND = 0.5
    private const val RESERVE = 10.0
    private val lock = Mutex()
    private var tokens = CAPACITY
    private var last = kotlin.time.TimeSource.Monotonic.markNow()

    suspend fun take(essential: Boolean): Boolean {
        while (true) {
            val wait = lock.withLock {
                val now = kotlin.time.TimeSource.Monotonic.markNow()
                tokens = (tokens + (now - last).inWholeMilliseconds / 1000.0 * PER_SECOND).coerceAtMost(CAPACITY)
                last = now
                when {
                    tokens >= (if (essential) 1.0 else RESERVE + 1.0) -> { tokens -= 1.0; return true }
                    !essential -> return false
                    else -> ((1.0 - tokens) / PER_SECOND * 1000).toLong().coerceAtLeast(50)
                }
            }
            kotlinx.coroutines.delay(wait)
        }
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/** The artist named [name] in a TheAudioDB `search.php` answer (names compared loosely); null when it is not there. */
internal fun parseAudioDbArtist(body: String, name: String): AudioDbArtist? {
    val list = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("artists") as? JsonArray }.getOrNull() ?: return null
    val entries = list.mapNotNull { it as? JsonObject }
    val match = entries.firstOrNull { it.text("strArtist")?.equals(name.trim(), ignoreCase = true) == true }
        ?: entries.firstOrNull { entry -> entry.text("strArtist")?.let { sameArtistName(it, name) } == true }
        ?: return null
    return AudioDbArtist(
        name = match.text("strArtist") ?: name,
        thumb = match.text("strArtistThumb"),
        logo = match.text("strArtistLogo"),
        fanart = listOf("strArtistFanart", "strArtistFanart2", "strArtistWideThumb").firstNotNullOfOrNull { match.text(it) },
        biography = match.text("strBiographyEN"),
        genre = match.text("strGenre"),
        style = match.text("strStyle"),
        musicBrainzId = match.text("strMusicBrainzID"),
        fanarts = listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4").mapNotNull { match.text(it) },
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
        cover = first.text("strAlbumThumbHQ") ?: first.text("strAlbumThumb") ?: first.text("strTrackThumb"),
        cdArt = first.text("strAlbumCDart"),
        score = first.text("intScore")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { s -> ((s * 10).toInt() / 10.0).toString() },
        label = first.text("strLabel"),
        musicBrainzId = first.text("strMusicBrainzID"),
    )
}

/** A Deezer `search/track` hit: the first whose title matches [title] (brackets left out), else the first. */
internal class DeezerTrackHit(val id: Long, val artistId: Long?, val explicit: Boolean)

internal fun parseDeezerTrackHit(body: String, title: String): DeezerTrackHit? {
    val data = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull() ?: return null
    val items = data.mapNotNull { it as? JsonObject }
    val pick = pickByTitle(items, title) ?: return null
    val id = pick.text("id")?.toLongOrNull() ?: return null
    val artistId = (pick["artist"] as? JsonObject)?.text("id")?.toLongOrNull()
    return DeezerTrackHit(id, artistId, pick.text("explicit_lyrics") == "true")
}

internal class DeezerTrackDetail(val bpm: Int?, val releaseDate: String?)

internal fun parseDeezerTrackDetail(body: String): DeezerTrackDetail? {
    val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (o.containsKey("error")) return null
    return DeezerTrackDetail(
        bpm = o.text("bpm")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { kotlin.math.round(it).toInt() },
        releaseDate = o.text("release_date"),
    )
}

/** The id of the Deezer `search/album` hit whose title matches [title] (brackets left out), else the first. */
internal fun parseDeezerAlbumHitId(body: String, title: String): Long? {
    val data = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull() ?: return null
    val items = data.mapNotNull { it as? JsonObject }
    return pickByTitle(items, title)?.text("id")?.toLongOrNull()
}

/** Words in brackets that make a different version of a song or record (a live take, a remix, a demo). */
private val VERSION_WORDS = listOf("live", "remix", "mix", "acoustic", "demo", "instrumental", "edit", "version", "karaoke", "cover")

private fun versionWords(title: String): Set<String> =
    VERSION_WORDS.filter { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) }.toSet()

/**
 * The Deezer entry that is the song or record titled [title]: the same title exactly, else one with the same title once
 * brackets are left out and the same version words (a "Live" or "Remix" only when [title] asks for one), else any with
 * the same bracket-free title, else the first.
 */
private fun pickByTitle(items: List<JsonObject>, title: String): JsonObject? {
    val wanted = songKey(title)
    val asked = versionWords(title)
    val same = items.filter { it.text("title")?.let(::songKey) == wanted }
    return items.firstOrNull { it.text("title")?.equals(title.trim(), ignoreCase = true) == true }
        ?: same.firstOrNull { versionWords(it.text("title").orEmpty()) == asked }
        ?: same.firstOrNull()
        ?: items.firstOrNull()
}

/** The fan count of the artist named [name] in a Deezer `search/artist` answer; null when no entry has that name. */
internal fun parseDeezerArtistFans(body: String, name: String): Long? {
    val data = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull() ?: return null
    return data.mapNotNull { it as? JsonObject }
        .firstOrNull { entry -> entry.text("name")?.let { sameArtistName(it, name) } == true }
        ?.text("nb_fan")?.toLongOrNull()
}

/** The song titles of a Deezer `artist/{id}/top` answer, most played first. */
internal fun parseDeezerTitles(body: String): List<String> =
    runCatching { ((Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("title") } }
        .getOrNull().orEmpty()

/** "2013-09-20" as "20 Sep 2013"; a bare year stays as it is. */
internal fun heroReleaseDate(date: String?): String? {
    val d = date?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("0000") } ?: return null
    val parts = d.split('-')
    if (parts.size != 3) return d.take(4)
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return parts[0]
    val day = parts[2].toIntOrNull() ?: return parts[0]
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "$day ${names[month - 1]} ${parts[0]}"
}

/** The facts line for a song: year, tempo, its place among the artist's most played, Explicit. */
internal fun songFactsLine(facts: HeroSongFacts?, artist: String?): String? =
    facts?.let { f ->
        listOfNotNull(
            f.year,
            f.bpm?.let { "$it BPM" },
            f.rank?.let { r -> if (artist != null) "No. $r of $artist's most played" else "No. $r most played" },
            "Explicit".takeIf { f.explicit },
        ).joinToString(" · ").ifEmpty { null }
    }

/** The facts line for a record: release date, label, tracks, length, TheAudioDB's score. */
internal fun albumFactsLine(facts: HeroAlbumFacts?, score: String?): String? =
    listOfNotNull(
        heroReleaseDate(facts?.date),
        facts?.label,
        facts?.tracks?.let { "$it track${if (it == 1) "" else "s"}" },
        facts?.minutes?.let { "$it min" },
        score?.let { "★ $it" },
    ).joinToString(" · ").ifEmpty { null }

/**
 * fork:music-search - the logo URLs that are an artist's name set as a logo, not a result's artwork: the hero draws these
 * like a film's logo, alone in the slot, rather than as the artwork-and-name lockup.
 */
internal object MusicHeroLogos {
    private val urls = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val blurred = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val discs = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val facts = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun mark(url: String) { urls[url] = true }
    fun isNameLogo(url: String?): Boolean = url != null && urls[url] == true

    /** A backdrop that is a record's cover: drawn as a gradient of its colours (the hero's CoverColourBackdrop), not a blow-up. */
    fun markBlurred(url: String) { blurred[url] = true }
    fun isBlurred(url: String?): Boolean = url != null && blurred[url] == true

    /** The disc that peeks out of the cover [coverUrl] in the hero's logo slot. */
    fun setDisc(coverUrl: String, discUrl: String) { discs[coverUrl] = discUrl }
    fun discFor(coverUrl: String?): String? = coverUrl?.let { discs[it] }

    /** The facts line (year, tempo, label, ...) for the hero item [itemId], drawn where a film's ratings go. */
    fun setFacts(itemId: String, line: String) { facts[itemId] = line }
    fun factsFor(itemId: String): String? = facts[itemId]
}
