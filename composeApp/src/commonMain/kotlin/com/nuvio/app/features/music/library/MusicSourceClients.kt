package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.library.LibraryClock
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("MusicSources")

private const val USER_AGENT = "NuvioDesktop/1.0 (https://github.com/UmbraProjects/NuvioDesktop)"

/** One GET, or null on any failure (a 404 for an unknown id is normal, not an error). Cancellation passes through. */
private suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): String? =
    try {
        httpGetTextWithHeaders(url, mapOf("User-Agent" to USER_AGENT) + headers)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        log.d { "GET ${url.substringBefore('?')} failed: ${error.message}" }
        null
    }

/**
 * https://musicbrainz.org: free, no key. The rules ask for one request a second and an identifying
 * User-Agent; [paced] keeps every call at least 1.1 s from the one before it.
 */
internal object MusicBrainzClient {
    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val GAP_MS = 1_100L
    private const val MAX_PAGES = 6
    private const val TAG_SEARCH_LIMIT = 100
    private const val ARTIST_SEARCH_LIMIT = 5
    private val gate = Mutex()
    private var lastCallMs = 0L

    private suspend fun paced(url: String): String? = gate.withLock {
        val wait = lastCallMs + GAP_MS - LibraryClock.nowEpochMs()
        if (wait > 0) delay(wait)
        try {
            fetch(url)
        } finally {
            lastCallMs = LibraryClock.nowEpochMs()
        }
    }

    suspend fun searchArtist(name: String): List<MbArtistHit> {
        val query = "artist:\"${name.replace("\"", "\\\"")}\"".encodeURLParameter()
        return paced("$BASE/artist?query=$query&limit=6&fmt=json")?.let(::parseMbArtistSearch).orEmpty()
    }

    /**
     * One search for the artists tagged [tag] (about [TAG_SEARCH_LIMIT]), each with its tag votes. A single paced
     * call, no per-artist look-ups. Null when MusicBrainz could not be reached; empty when nobody carries the tag.
     */
    suspend fun searchByTag(tag: String): List<LadderCandidate>? {
        val query = "tag:\"${tag.replace("\"", "\\\"")}\"".encodeURLParameter()
        return paced("$BASE/artist?query=$query&limit=$TAG_SEARCH_LIMIT&fmt=json")?.let(::parseMbTagSearch)
    }

    /**
     * One search for an artist by name (a few hits, best first), each with its tag votes: the same answer shape as
     * [searchByTag], so [parseMbTagSearch] reads it. A single paced call. Null when MusicBrainz could not be reached.
     */
    suspend fun searchArtistWithTags(name: String): List<LadderCandidate>? {
        val query = "artist:\"${name.replace("\"", "\\\"")}\"".encodeURLParameter()
        return paced("$BASE/artist?query=$query&limit=$ARTIST_SEARCH_LIMIT&fmt=json")?.let(::parseMbTagSearch)
    }

    suspend fun artist(mbid: String): MbArtist? =
        paced("$BASE/artist/$mbid?inc=url-rels+tags+genres+ratings&fmt=json")?.let(::parseMbArtist)

    /** Every release group of the artist, paged 100 at a time. */
    suspend fun releaseGroups(mbid: String): List<MbReleaseGroup> {
        val all = ArrayList<MbReleaseGroup>()
        var offset = 0
        repeat(MAX_PAGES) {
            val body = paced("$BASE/release-group?artist=$mbid&limit=100&offset=$offset&fmt=json") ?: return all
            val (page, total) = parseMbReleaseGroups(body)
            all += page
            offset += 100
            if (page.isEmpty() || offset >= total) return all
        }
        return all
    }
}

/** https://developers.deezer.com/api: free, no key. Gives release types, covers, fan counts, labels. */
internal object DeezerClient {
    private const val BASE = "https://api.deezer.com"
    private const val MAX_PAGES = 5

    suspend fun searchArtist(name: String): List<DeezerArtistHit> =
        fetch("$BASE/search/artist?q=${name.encodeURLParameter()}&limit=8")?.let(::parseDeezerArtistSearch).orEmpty()

    suspend fun albums(artistId: Long): List<DeezerAlbum> {
        val all = ArrayList<DeezerAlbum>()
        var url: String? = "$BASE/artist/$artistId/albums?limit=100"
        repeat(MAX_PAGES) {
            val current = url ?: return all
            val body = fetch(current) ?: return all
            val (page, next) = parseDeezerAlbums(body)
            all += page
            url = next
        }
        return all
    }

    // Deezer allows about 50 calls per 5 seconds; stay under 10 a second.
    private const val GAP_MS = 120L
    private val gate = Mutex()
    private var lastCallMs = 0L

    suspend fun albumDetail(albumId: Long): DeezerAlbumDetail? = gate.withLock {
        val wait = lastCallMs + GAP_MS - LibraryClock.nowEpochMs()
        if (wait > 0) delay(wait)
        try {
            fetch("$BASE/album/$albumId")?.let(::parseDeezerAlbumDetail)
        } finally {
            lastCallMs = LibraryClock.nowEpochMs()
        }
    }

    /** The artist's most played tracks (up to 100), each with the album it is on. Null when Deezer could not be reached; empty when it has none. */
    suspend fun topTracks(artistId: Long): List<DeezerTopTrack>? = gate.withLock {
        val wait = lastCallMs + GAP_MS - LibraryClock.nowEpochMs()
        if (wait > 0) delay(wait)
        try {
            fetch("$BASE/artist/$artistId/top?limit=100")?.let(::parseDeezerTopTracks)
        } finally {
            lastCallMs = LibraryClock.nowEpochMs()
        }
    }
}

/**
 * https://www.theaudiodb.com: biography, style, mood, logo, clear art, cut-out, banner, fanart and
 * per-album CD art and descriptions. "123" is its public test key; it works but is rate limited.
 */
internal class AudioDbClient(private val key: String) {
    private val base get() = "https://www.theaudiodb.com/api/v1/json/${key.ifBlank { DEFAULT_KEY }}"

    suspend fun artistByMusicBrainzId(mbid: String): AudioDbArtist? =
        fetch("$base/artist-mb.php?i=$mbid")?.let(::parseAudioDbArtists)?.firstOrNull()

    suspend fun artistByName(name: String): AudioDbArtist? =
        fetch("$base/search.php?s=${name.encodeURLParameter()}")?.let(::parseAudioDbArtists)
            ?.firstOrNull { sameArtistName(it.name, name) }

    suspend fun albums(artistId: String): List<AudioDbAlbum> =
        fetch("$base/album.php?i=$artistId")?.let(::parseAudioDbAlbums).orEmpty()

    companion object {
        const val DEFAULT_KEY = "123"
    }
}

/** https://fanart.tv music API. Needs a (free) personal API key; with none, [artist] answers null. */
internal class FanartTvClient(private val key: String) {
    val isConfigured: Boolean get() = key.isNotBlank()

    suspend fun artist(mbid: String): FanartArtist? {
        if (!isConfigured) return null
        return fetch("https://webservice.fanart.tv/v3/music/$mbid?api_key=${key.encodeURLParameter()}")
            ?.let(::parseFanartArtist)
    }
}

/** English Wikipedia's page summary: the fallback biography. */
internal object WikipediaClient {
    /** [title] is the already-encoded last part of the article URL, as MusicBrainz gives it. */
    suspend fun summary(title: String): String? =
        fetch("https://en.wikipedia.org/api/rest_v1/page/summary/$title")?.let(::parseWikipediaSummary)
}
