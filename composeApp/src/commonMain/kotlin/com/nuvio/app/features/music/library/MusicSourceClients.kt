package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("MusicSources")

private const val USER_AGENT = "NuvioDesktop/1.0 (https://github.com/UmbraProjects/NuvioDesktop)"

/** Whole-call ceiling for these artwork and metadata lookups; they are never worth a minute of waiting. */
private const val CALL_BUDGET_MS = 15_000L
private const val CONNECT_BUDGET_MS = 5_000L

/** A GET with a ceiling on the whole call, so a host that holds the connection open cannot hang a screen for 60 s. */
private suspend fun getWithBudget(url: String, headers: Map<String, String> = emptyMap()) =
    httpRequestRaw(
        method = "GET",
        url = url,
        headers = mapOf("User-Agent" to USER_AGENT) + headers,
        body = "",
        callTimeoutMs = CALL_BUDGET_MS,
        connectTimeoutMs = CONNECT_BUDGET_MS,
    )

/** One GET, or null on any failure (a 404 for an unknown id is normal, not an error). Cancellation passes through. */
private suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): String? =
    try {
        val response = getWithBudget(url, headers)
        if (response.status in 200..299 && response.body.isNotBlank()) {
            response.body
        } else {
            log.d { "GET ${url.substringBefore('?')} answered ${response.status}" }
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        log.d { "GET ${url.substringBefore('?')} failed: ${error.message}" }
        null
    }

/**
 * The MusicBrainz search query for an artist called [name]: the name as a phrase, in the artist's own name or in any
 * of its aliases. The `artist:` field alone does not look at aliases, so a band that changed its name is not found
 * by its new one. Not URL-encoded.
 */
internal fun mbArtistSearchQuery(name: String): String {
    val phrase = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return "artist:$phrase OR alias:$phrase"
}

/**
 * https://musicbrainz.org: free, no key. The rules ask for one request a second and an identifying
 * User-Agent; [paced] keeps every call at least 1.1 s from the one before it, and lets background work (ladders built
 * before anyone asked, see [BackgroundWork]) wait behind searches and Library refreshes.
 */
internal object MusicBrainzClient {
    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val GAP_MS = 1_100L
    private const val THROTTLE_RETRIES = 3
    private const val THROTTLE_BACKOFF_MS = 2_000L
    private const val MAX_PAGES = 6
    private const val TAG_SEARCH_LIMIT = 100
    private const val ARTIST_SEARCH_LIMIT = 5

    /** More room than [ARTIST_SEARCH_LIMIT]: searching aliases too brings in artists that only list the name as one. */
    private const val ARTIST_NAME_SEARCH_LIMIT = 10
    private val pacer = BackgroundAwarePacer(GAP_MS)

    /**
     * fork:music - the offline copy's answer, when one is installed: a disk read, so it runs on the IO pool. Null when
     * there is no copy, or it has no answer (an artist newer than the dump), and the caller then asks the web service.
     */
    private suspend fun <T : Any> offline(read: (MusicBrainzOfflineIndex) -> T?): T? {
        val index = musicBrainzOfflineCopy().index ?: return null
        return withContext(Dispatchers.IO) {
            try {
                read(index)
            } catch (error: Exception) {
                log.w { "MusicBrainz offline copy failed: ${error.message}" }
                null
            }
        }
    }

    /**
     * Every call goes through [pacer]; one made inside `withContext(BackgroundWork())` waits for ordinary calls first.
     * MusicBrainz answers 503 when it is shedding anonymous load, even to a paced client: that is a "try again shortly",
     * not a failure, so it is retried a few times with a growing pause (each retry still takes its turn at the pacer).
     * Without this one refused call among the dozen a "Where next?" ladder makes was enough to fail the whole ladder.
     */
    private suspend fun paced(url: String): String? {
        val background = currentCoroutineContext()[BackgroundWork] != null
        repeat(THROTTLE_RETRIES + 1) { attempt ->
            if (attempt > 0) delay(THROTTLE_BACKOFF_MS * attempt)
            when (val result = pacer.run(background) { fetchMb(url) }) {
                is MbResult.Ok -> return result.body
                MbResult.Failed -> return null
                MbResult.Throttled -> log.d { "MusicBrainz throttled ${url.substringBefore('?')}; retry ${attempt + 1} of $THROTTLE_RETRIES" }
            }
        }
        log.w { "MusicBrainz still throttling ${url.substringBefore('?')} after $THROTTLE_RETRIES retries" }
        return null
    }

    private sealed interface MbResult {
        class Ok(val body: String) : MbResult
        data object Throttled : MbResult
        data object Failed : MbResult
    }

    private suspend fun fetchMb(url: String): MbResult =
        try {
            val response = getWithBudget(url)
            when {
                response.status == 503 || response.status == 429 -> MbResult.Throttled
                response.status in 200..299 && response.body.isNotBlank() -> MbResult.Ok(response.body)
                else -> {
                    log.d { "GET ${url.substringBefore('?')} answered ${response.status}" }
                    MbResult.Failed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "GET ${url.substringBefore('?')} failed: ${error.message}" }
            MbResult.Failed
        }

    /** Finds [name] by its own name or by an alias, so a renamed band or a romanised name is still found. See [mbArtistSearchQuery]. */
    suspend fun searchArtist(name: String): List<MbArtistHit> {
        offline { it.searchArtist(name, limit = 6) }?.let { return parseMbArtistSearch(it) }
        val query = mbArtistSearchQuery(name).encodeURLParameter()
        return paced("$BASE/artist?query=$query&limit=$ARTIST_NAME_SEARCH_LIMIT&fmt=json")?.let(::parseMbArtistSearch).orEmpty()
    }

    /**
     * One search for the artists tagged [tag] (about [TAG_SEARCH_LIMIT]), each with its tag votes. A single paced
     * call, no per-artist look-ups. Null when MusicBrainz could not be reached; empty when nobody carries the tag.
     */
    /** True while an offline copy is installed and readable. */
    val hasOfflineCopy: Boolean get() = musicBrainzOfflineCopy().index != null

    /** [searchByTag] from the offline copy alone: null when there is none, or it has no answer. */
    suspend fun searchByTagOffline(tag: String): List<LadderCandidate>? = offlineTagSearch(tag)

    /**
     * The offline copy's artists for [tag] under every spelling of it ("hip-hop" and "hip hop": the copy matches a tag
     * exactly), merged. Null when there is no copy.
     */
    private suspend fun offlineTagSearch(tag: String): List<LadderCandidate>? {
        val spellings = tagSpellings(tag)
        val found = ArrayList<List<LadderCandidate>>()
        for (spelling in spellings) {
            val body = offline { it.searchByTag(spelling, TAG_SEARCH_LIMIT) } ?: return null
            found += parseMbTagSearch(body)
        }
        return if (found.isEmpty()) null else mergeTagSpellings(spellings, found, TAG_SEARCH_LIMIT)
    }

    /** [searchArtistWithTags] from the offline copy alone: null when there is none, or it has no answer. */
    suspend fun searchArtistWithTagsOffline(name: String): List<LadderCandidate>? =
        offline { it.searchArtist(name, ARTIST_SEARCH_LIMIT) }?.let(::parseMbTagSearch)

    suspend fun searchByTag(tag: String): List<LadderCandidate>? {
        offlineTagSearch(tag)?.let { return it }
        // The web service: the next spelling is tried only when the one before found nobody.
        var answer = emptyList<LadderCandidate>()
        for (spelling in tagSpellings(tag)) {
            val query = "tag:\"${spelling.replace("\"", "\\\"")}\"".encodeURLParameter()
            val found = paced("$BASE/artist?query=$query&limit=$TAG_SEARCH_LIMIT&fmt=json")?.let(::parseMbTagSearch)
                ?: return null
            if (found.isNotEmpty()) return found
            answer = found
        }
        return answer
    }

    /**
     * One search for an artist by name (a few hits, best first), each with its tag votes: the same answer shape as
     * [searchByTag], so [parseMbTagSearch] reads it. A single paced call. Null when MusicBrainz could not be reached.
     */
    suspend fun searchArtistWithTags(name: String): List<LadderCandidate>? {
        offline { it.searchArtist(name, ARTIST_SEARCH_LIMIT) }?.let { return parseMbTagSearch(it) }
        val query = "artist:\"${name.replace("\"", "\\\"")}\"".encodeURLParameter()
        return paced("$BASE/artist?query=$query&limit=$ARTIST_SEARCH_LIMIT&fmt=json")?.let(::parseMbTagSearch)
    }

    suspend fun artist(mbid: String): MbArtist? =
        offline { it.artist(mbid) }?.let(::parseMbArtist)
            ?: paced("$BASE/artist/$mbid?inc=url-rels+tags+genres+ratings&fmt=json")?.let(::parseMbArtist)

    /** Every release group of the artist, paged 100 at a time. */
    suspend fun releaseGroups(mbid: String): List<MbReleaseGroup> {
        offline { it.releaseGroups(mbid) }?.let { return parseMbReleaseGroups(it).first }
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

    private const val RELATED_LIMIT = 20

    // Calls are spaced from the start of the one before, not its end: the limit counts calls, so waiting out each
    // response as well held an artist's ~200 album lookups to about 5 a second instead of 8.
    private suspend fun pacedFetch(url: String): String? = gate.withLock {
        val wait = lastCallMs + GAP_MS - LibraryClock.nowEpochMs()
        if (wait > 0) delay(wait)
        lastCallMs = LibraryClock.nowEpochMs()
        fetch(url)
    }

    /**
     * The artists Deezer lists as related to the one called [name] (up to [RELATED_LIMIT], closest first). The artist
     * is the search hit with exactly that name (most fans wins among namesakes). Null when Deezer could not be
     * reached; empty when it has no such artist or no related ones.
     */
    suspend fun relatedByName(name: String): List<DeezerArtistHit>? {
        val search = pacedFetch("$BASE/search/artist?q=${name.encodeURLParameter()}&limit=8") ?: return null
        val artist = parseDeezerArtistSearch(search)
            .filter { sameArtistName(it.name, name) }
            .maxByOrNull { it.fans ?: 0L }
            ?: return emptyList()
        val related = pacedFetch("$BASE/artist/${artist.id}/related?limit=$RELATED_LIMIT") ?: return null
        return parseDeezerArtistSearch(related)
    }

    suspend fun albumDetail(albumId: Long): DeezerAlbumDetail? =
        pacedFetch("$BASE/album/$albumId")?.let(::parseDeezerAlbumDetail)

    /** The artist's most played tracks (up to 100), each with the album it is on. Null when Deezer could not be reached; empty when it has none. */
    suspend fun topTracks(artistId: Long): List<DeezerTopTrack>? =
        pacedFetch("$BASE/artist/$artistId/top?limit=100")?.let(::parseDeezerTopTracks)
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
