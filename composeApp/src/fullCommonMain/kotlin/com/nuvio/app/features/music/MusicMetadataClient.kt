package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("MusicMetadata")

// Search filters YouTube Music understands (the same ones ytmusicapi sends).
private const val FILTER_ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
private const val FILTER_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"

private const val FOUND_TTL_MS = 30 * 60_000L
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

    suspend fun searchAlbums(query: String): List<MusicAlbumSummary> {
        val q = query.trim().ifEmpty { return emptyList() }
        return cached("albums:${q.lowercase()}") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q, "params" to FILTER_ALBUMS))
                ?.let(::parseAlbumSearch)?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    suspend fun searchArtists(query: String): List<MusicArtistSummary> {
        val q = query.trim().ifEmpty { return emptyList() }
        return cached("artists:${q.lowercase()}") {
            TrailerPlaybackResolver.musicRequest("search", mapOf("query" to q, "params" to FILTER_ARTISTS))
                ?.let(::parseArtistSearch)?.takeIf { it.isNotEmpty() }
        }.orEmpty()
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
