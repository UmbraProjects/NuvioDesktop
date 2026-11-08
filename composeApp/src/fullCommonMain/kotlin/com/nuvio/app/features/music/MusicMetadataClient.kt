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
private const val FILTER_SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

/** Playlist pages read at most (100 tracks each). */
private const val MAX_PLAYLIST_PAGES = 30

/** fork:music-radio - pages of a radio read at most (each is about 25 tracks). */
private const val MAX_RADIO_PAGES = 8

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
    private suspend fun <T> cached(key: String, keepMiss: () -> Boolean = { true }, load: suspend () -> T?): T? {
        val now = LibraryClock.nowEpochMs()
        mutex.withLock { cache[key]?.takeIf { it.expiresAtMs > now } }?.let { return it.value as T? }
        val value = load()
        // A miss is remembered so a page that is not there is not asked for again and again; but a request that never got an answer
        // is not a miss, and is not kept (it would make "Try again" repeat the failure for three minutes).
        if (value == null && !keepMiss()) return null
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

    /** The album filter's results as YouTube Music orders them: one request, no ranking. */
    suspend fun searchReleases(query: String): List<MusicAlbumSummary> = filteredAlbums(query.trim().ifEmpty { return emptyList() })

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

    /**
     * The unfiltered search's "Top result" for [q], or null. Shared by the album and artist searches, and read from the
     * same cached answer as [searchEverything], so the first screen and the album search never both send it.
     */
    private suspend fun topResult(q: String): MusicTopResult? = unfiltered(q)?.top

    /**
     * fork:music-home - the unfiltered search answer for [q], read whole. Null when the request got no answer (not kept,
     * so the next keystroke asks again); an answer with nothing in it is kept like any other.
     */
    private suspend fun unfiltered(q: String): MusicSearchEverything? =
        cached("all:${q.lowercase()}", keepMiss = { false }) {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q))?.let(::parseSearchEverything)
        }

    /**
     * Whether the last unfiltered answer came without songs or albums. YouTube Music sends some sessions a flat answer
     * of artists and videos only; once seen, the filtered searches start alongside the unfiltered one instead of after
     * it, so the first screen does not wait for two round trips in a row.
     */
    @kotlin.concurrent.Volatile
    private var unfilteredLacksMusic = false

    /** fork:music-home - see [MusicCatalog.searchEverything]. */
    suspend fun searchEverything(query: String): MusicSearchEverything = coroutineScope {
        val q = query.trim().ifEmpty { return@coroutineScope MusicSearchEverything.EMPTY }
        val early = unfilteredLacksMusic
        val earlySongs = if (early) async { searchSongs(q) } else null
        val earlyAlbums = if (early) async { filteredAlbums(q) } else null
        val base = unfiltered(q)
        val answer = base ?: MusicSearchEverything.EMPTY
        val needSongs = answer.songs.isEmpty()
        val needAlbums = answer.albums.isEmpty()
        if (base != null) unfilteredLacksMusic = needSongs || needAlbums
        val songs = when {
            !needSongs -> { earlySongs?.cancel(); answer.songs }
            earlySongs != null -> earlySongs.await()
            else -> searchSongs(q)
        }
        val albums = when {
            !needAlbums -> { earlyAlbums?.cancel(); answer.albums }
            else -> rankAlbumResults(earlyAlbums?.await() ?: filteredAlbums(q), answer.top)
        }
        val merged = answer.copy(songs = songs, albums = albums)
        if (base == null && merged.isEmpty) merged.copy(failed = true) else merged
    }

    /** fork:music-home - see [MusicCatalog.searchSuggestions]. A trailing space is kept: "yelawolf " asks for next words. */
    suspend fun searchSuggestions(typed: String): List<String> {
        val input = typed.trimStart().ifBlank { return emptyList() }
        return cached("suggest:${input.lowercase()}", keepMiss = { false }) {
            TrailerPlaybackResolver.musicRequest("music/get_search_suggestions", mapOf("input" to input))
                ?.let(::parseSearchSuggestions)
        }.orEmpty()
    }

    /** fork:music-playlists - see [MusicCatalog.searchSongs]. */
    suspend fun searchSongs(query: String): List<MusicTrack> {
        val q = query.trim().ifEmpty { return emptyList() }
        return cached("songs:$q") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q, "params" to FILTER_SONGS))
                ?.let(::parseSongSearch)?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    /**
     * fork:music-account - a request as the signed-in account when someone is signed in (a private playlist, Liked Music, a
     * mix made for them), else, or when that fails, as a visitor.
     */
    private suspend fun personal(endpoint: String, params: Map<String, Any?>): String? =
        TrailerPlaybackResolver.musicRequestSigned(endpoint, params) ?: TrailerPlaybackResolver.musicRequest(endpoint, params)

    /** fork:music-playlists - see [MusicCatalog.playlist]. Not cached: an import should read the list as it is now. */
    suspend fun playlist(playlistId: String): MusicPlaylistPage? {
        val id = playlistId.trim().removePrefix("VL").ifEmpty { return null }
        val first = personal("browse", mapOf("browseId" to "VL$id"))
            ?.let { parsePlaylistPage(it) }
            ?: return null.also { log.w { "playlist $id: no readable page" } }
        val tracks = first.tracks.toMutableList()
        var token = first.continuation
        var pages = 1
        while (token != null && pages < MAX_PLAYLIST_PAGES) {
            val page = personal("browse", mapOf("continuation" to token))
                ?.let { parsePlaylistPage(it, firstNumber = tracks.size + 1) } ?: break
            if (page.tracks.isEmpty()) break
            tracks += page.tracks
            token = page.continuation?.takeIf { it != token }
            pages++
        }
        return MusicPlaylistPage(first.title, tracks.distinctBy { it.videoId }, null)
    }

    /** fork:music-radio - see [MusicCatalog.radio]. Not cached: a radio is made fresh each time. */
    suspend fun radio(playlistId: String, videoId: String? = null, maxTracks: Int = 100): MusicPlaylistPage? {
        val id = playlistId.trim().removePrefix("VL").ifEmpty { return null }
        val base = buildMap<String, Any?> {
            put("playlistId", id)
            if (videoId != null) put("videoId", videoId)
            // As music.youtube.com starts a radio: "wAEB" (no shuffle), audio first.
            put("params", "wAEB")
            put("isAudioOnly", true)
        }
        val first = personal("next", base)?.let { parseRadioPage(it) }
            ?: return null.also { log.w { "radio $id: no readable queue" } }
        val tracks = first.tracks.toMutableList()
        var token = first.continuation
        var pages = 1
        while (token != null && tracks.size < maxTracks && pages < MAX_RADIO_PAGES) {
            val page = personal("next", base + ("continuation" to token))
                ?.let { parseRadioPage(it, firstNumber = tracks.size + 1) } ?: break
            val fresh = page.tracks.filter { t -> tracks.none { it.videoId == t.videoId } }
            if (fresh.isEmpty()) break
            tracks += fresh
            token = page.continuation?.takeIf { it != token }
            pages++
        }
        log.i { "radio $id: ${tracks.size} tracks in $pages page(s)" }
        return MusicPlaylistPage(first.title, tracks.take(maxTracks), token)
    }

    /** fork:music-account - see [MusicCatalog.accountHome]. The first page and up to two more (the mixes are near the top). */
    suspend fun accountHome(): List<MusicHomeShelf>? {
        val first = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("browseId" to "FEmusic_home"))
            ?.let(::parseHomePage) ?: return null
        val shelves = first.shelves.toMutableList()
        var token = first.continuation
        var pages = 1
        while (token != null && pages < 3) {
            val page = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("continuation" to token))?.let(::parseHomePage) ?: break
            shelves += page.shelves
            token = page.continuation?.takeIf { it != token }
            pages++
        }
        log.i { "account home: ${shelves.size} shelves with playlists (${shelves.joinToString { it.title }})" }
        return shelves
    }

    /** fork:music-account - see [MusicCatalog.accountPlaylists]. */
    suspend fun accountPlaylists(): List<MusicPlaylistSummary>? {
        val first = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("browseId" to "FEmusic_liked_playlists"))
            ?.let(::parseLibraryPlaylists) ?: return null
        val lists = first.playlists.toMutableList()
        var token = first.continuation
        var pages = 1
        while (token != null && pages < 6) {
            val page = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("continuation" to token))?.let(::parseLibraryPlaylists) ?: break
            lists += page.playlists.filter { p -> lists.none { it.playlistId == p.playlistId } }
            token = page.continuation?.takeIf { it != token }
            pages++
        }
        log.i { "account playlists: ${lists.size}" }
        return lists
    }

    /** fork:music-account - see [MusicCatalog.accountHistory]. */
    suspend fun accountHistory(): List<MusicHistoryItem>? {
        val first = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("browseId" to "FEmusic_history"))
            ?.let(::parseHistory) ?: return null
        val items = first.items.toMutableList()
        var token = first.continuation
        var pages = 1
        while (token != null && pages < 3) {
            val page = TrailerPlaybackResolver.musicRequestSigned("browse", mapOf("continuation" to token))?.let(::parseHistory) ?: break
            items += page.items
            token = page.continuation?.takeIf { it != token }
            pages++
        }
        log.i { "account history: ${items.size} songs" }
        return items
    }

    /** fork:music-account - see [MusicCatalog.setSongLiked]. */
    suspend fun setSongLiked(videoId: String, liked: Boolean): Boolean {
        val answer = TrailerPlaybackResolver.musicRequestSigned(
            if (liked) "like/like" else "like/removelike",
            mapOf("target" to mapOf("videoId" to videoId)),
        )
        log.i { "song $videoId ${if (liked) "liked" else "unliked"}: ${if (answer != null) "done" else "failed"}" }
        return answer != null
    }

    suspend fun album(browseId: String): MusicAlbum? {
        if (!browseId.startsWith("MPREb_")) return null.also { log.w { "album $browseId: not a YouTube Music album id" } }
        var unanswered = false
        return cached("album:$browseId", keepMiss = { !unanswered }) {
            val body = TrailerPlaybackResolver.musicRequest("browse", mapOf("browseId" to browseId))
            if (body == null) { unanswered = true; log.w { "album $browseId: YouTube Music gave no answer" } }
            body?.let { parseAlbum(it, browseId) }
                .also { if (body != null && it == null) log.w { "album $browseId: response did not parse" } }
        }
    }

    suspend fun artist(browseId: String): MusicArtist? {
        if (!browseId.startsWith("UC")) return null
        var unanswered = false
        return cached("artist:$browseId", keepMiss = { !unanswered }) {
            val body = TrailerPlaybackResolver.musicRequest("browse", mapOf("browseId" to browseId))
            if (body == null) { unanswered = true; log.w { "artist $browseId: YouTube Music gave no answer" } }
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
    suspend fun counterpartOf(videoId: String): MusicCounterpart? =
        watchNext(videoId)?.let { counterpartIn(it, videoId) }

    /**
     * fork:music-home - the length in seconds of [videoId] as YouTube Music's player lists it, or null when unknown. For
     * search rows whose answer carried a play or view count instead of a length. Shares [counterpartOf]'s request, so
     * a row looked up here and then played costs one request, not two. Nothing is asked while YouTube is refusing us.
     */
    suspend fun lengthOf(videoId: String): Int? {
        if (videoId.length != 11 || MusicRateLimit.isBlocked()) return null
        return watchNext(videoId)?.firstOrNull { it.videoId == videoId }?.durationSeconds?.takeIf { it > 0 }
    }

    /** The watch-next queue that starts at [videoId] (the track itself first, with its counterpart), cached. */
    private suspend fun watchNext(videoId: String): List<MusicWatchTrack>? {
        if (videoId.length != 11) return null
        return cached("next:$videoId") {
            val body = TrailerPlaybackResolver.musicRequest(
                "next",
                mapOf(
                    "videoId" to videoId,
                    "isAudioOnly" to true,
                    "enablePersistentPlaylistPanel" to true,
                    "tunerSettingValue" to "AUTOMIX_SETTING_NORMAL",
                ),
            )
            body?.let(::parseWatchNext)
        }
    }
}
