package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("MusicMetadata")

// Search filters YouTube Music understands (the same ones ytmusicapi sends).
private const val FILTER_ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
private const val FILTER_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"

private const val FOUND_TTL_MS = 30 * 60_000L

/** Pages of one "more" grid read at most (100 releases each). */
private const val MAX_GRID_PAGES = 15
private const val MISSING_TTL_MS = 3 * 60_000L

/**
 * Album and artist data from YouTube Music, and the music-video counterpart of an album track.
 * Network and parsing only: no UI, no playback. Results are cached in memory so paging back and
 * forth through albums does not ask again; a miss is remembered only briefly.
 *
 * Every call returns an empty list or null on failure and logs why. Nothing throws.
 */
object MusicMetadataClient {
    private class Entry(val value: Any?, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, load: suspend () -> T?): T? {
        val now = LibraryClock.nowEpochMs()
        mutex.withLock { cache[key]?.takeIf { it.expiresAtMs > now } }?.let { return it.value as T? }
        val value = load()
        mutex.withLock {
            cache[key] = Entry(value, LibraryClock.nowEpochMs() + if (value == null) MISSING_TTL_MS else FOUND_TTL_MS)
        }
        return value
    }

    /**
     * Albums for [query], ranked: the albums filter matches on title only, so the unfiltered
     * search's top result (which weighs popularity) decides whose releases come first. When the
     * filtered list has nothing by that artist, one more album search names them.
     */
    suspend fun searchAlbums(query: String): List<MusicAlbumSummary> = coroutineScope {
        val q = query.trim().ifEmpty { return@coroutineScope emptyList() }
        val topJob = async { topResult(q) }
        val found = filteredAlbums(q)
        val top = topJob.await()
        // A song or video as the top result: the album it is on is what the person is after.
        val topAlbum = top?.takeIf { it.albumBrowseId == null }?.videoId?.let { trackAlbum(it) }
        val topArtists = top?.artists.orEmpty()
        val artist = topArtists.firstOrNull()
        val more = if (topAlbum == null && artist != null && found.none { it.isBy(topArtists) }) {
            filteredAlbums("$q ${artist.name}")
        } else {
            emptyList()
        }
        rankAlbumResults(found, top, topAlbum, more)
    }

    /** The album the track [videoId] is on, from its watch-next byline; null when it is on none. */
    private suspend fun trackAlbum(videoId: String): MusicAlbumSummary? =
        cached("trackAlbum:$videoId") {
            TrailerPlaybackResolver.musicRequest("next", mapOf("videoId" to videoId, "isAudioOnly" to true))
                ?.let { parseTrackAlbum(it, videoId) }
        }

    /** Artists for [query], with the top result's artist first (looked up by name when the filter missed them). */
    suspend fun searchArtists(query: String): List<MusicArtistSummary> = coroutineScope {
        val q = query.trim().ifEmpty { return@coroutineScope emptyList() }
        val topJob = async { topResult(q) }
        val found = filteredArtists(q)
        val top = topJob.await()
        val missing = top?.artists.orEmpty().filter { ref -> ref.browseId != null && found.none { it.browseId == ref.browseId } }
        val extra = missing.take(2).flatMap { filteredArtists(it.name) }
        rankArtistResults(found, top, extra)
    }

    private suspend fun filteredAlbums(q: String): List<MusicAlbumSummary> =
        cached("albums:${q.lowercase()}") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q, "params" to FILTER_ALBUMS))
                ?.let(::parseAlbumSearch)?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private suspend fun filteredArtists(q: String): List<MusicArtistSummary> =
        cached("artists:${q.lowercase()}") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q, "params" to FILTER_ARTISTS))
                ?.let(::parseArtistSearch)?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    /** The unfiltered search's "Top result" for [q], or null. Shared by the album and artist searches. */
    private suspend fun topResult(q: String): MusicTopResult? =
        cached("top:${q.lowercase()}") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q))?.let(::parseTopResult)
        }

    suspend fun album(browseId: String): MusicAlbum? {
        if (!browseId.startsWith("MPREb_")) return null
        return cached("album:$browseId") {
            val body = TrailerPlaybackResolver.musicRequest("browse", mapOf("browseId" to browseId))
            body?.let { parseAlbum(it, browseId) }
                .also { if (body != null && it == null) log.w { "album $browseId: response did not parse" } }
        }
    }

    suspend fun artist(browseId: String): MusicArtist? {
        if (!browseId.startsWith("UC")) return null
        return cached("artist:$browseId") {
            val body = TrailerPlaybackResolver.musicRequest("browse", mapOf("browseId" to browseId))
            body?.let { parseArtist(it, browseId) }
                .also { if (body != null && it == null) log.w { "artist $browseId: response did not parse" } }
        }
    }

    /**
     * Every album, single and EP of the artist. The artist page itself lists only the first few of
     * each; its "more" buttons name the full lists, which are fetched here. Empty on failure.
     */
    suspend fun artistReleases(browseId: String): List<MusicAlbumSummary> {
        if (!browseId.startsWith("UC")) return emptyList()
        return cached("releases:$browseId") {
            val body = TrailerPlaybackResolver.musicRequest("browse", mapOf("browseId" to browseId))
                ?: return@cached null
            val artist = parseArtist(body, browseId) ?: return@cached null
            val self = MusicArtistRef(artist.name, browseId)
            val found = ArrayList<MusicAlbumSummary>()
            for (shelf in parseArtistShelves(body, self)) {
                found += shelf.items
                val moreId = shelf.moreBrowseId ?: continue
                val params = buildMap<String, Any?> {
                    put("browseId", moreId)
                    shelf.moreParams?.let { put("params", it) }
                }
                // The grid comes 100 releases at a time; follow it to the end (bounded, in case a token loops).
                var page = TrailerPlaybackResolver.musicRequest("browse", params)
                var pages = 0
                while (page != null && pages < MAX_GRID_PAGES) {
                    found += parseAlbumGrid(page, self, shelf.title)
                    pages++
                    val next = parseGridContinuation(page) ?: break
                    page = TrailerPlaybackResolver.musicRequest("browse", mapOf("continuation" to next))
                }
                if (pages > 1) log.i { "${artist.name}: ${shelf.title} ran to $pages pages" }
            }
            // A page with no shelves still has whatever tiles parseArtist finds.
            if (found.isEmpty()) found += artist.albums
            found.distinctBy { it.browseId }.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    /**
     * The music video that goes with the audio track [videoId], or null when YouTube Music links
     * none. A miss here means "ask the ranker", not "there is no video".
     */
    suspend fun counterpartOf(videoId: String): MusicCounterpart? {
        if (videoId.length != 11) return null
        return cached("counterpart:$videoId") {
            val body = TrailerPlaybackResolver.musicRequest(
                "next",
                mapOf(
                    "videoId" to videoId,
                    "isAudioOnly" to true,
                    "enablePersistentPlaylistPanel" to true,
                    "tunerSettingValue" to "AUTOMIX_SETTING_NORMAL",
                ),
            )
            body?.let { parseCounterpart(it, videoId) }
        }
    }
}
