package com.nuvio.app.features.music.torbox

import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.TorboxApiClient
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicResolution
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What TorBox has for an album, as far as a search can tell. */
internal sealed interface TorboxAlbumLookup {
    /** The best cached torrent for the album. */
    data class Found(val hit: TorboxSearchHit) : TorboxAlbumLookup

    /** The search ran and nothing cached is this album. */
    data object NotFound : TorboxAlbumLookup

    /** The search could not run (offline, no paid search plan, TorBox down). Not remembered, so a retry asks again. */
    data object Failed : TorboxAlbumLookup
}

internal sealed interface TorboxPrepared {
    /** The album with its tracks pointed at TorBox files; tracks with no file are marked unavailable. */
    data class Ready(val album: MusicAlbum, val matched: Int) : TorboxPrepared
    data object NoMatch : TorboxPrepared
    data object Failed : TorboxPrepared
}

/**
 * Plays an album from TorBox through the ordinary music player. Nothing here touches the player:
 * the tracks of the prepared album carry an id of the form `torbox:<torrentId>:<fileId>`, and the
 * app's resolver hands those to [resolve], which asks TorBox for a direct link. YouTube Music stays
 * the catalogue (covers, track list, lyrics); TorBox only supplies the audio.
 *
 * Uses the TorBox key already set under Debrid settings. It never adds an uncached torrent: that
 * would start a transfer that can take hours.
 */
internal object TorboxMusicSource {
    const val ID_PREFIX = "torbox:"

    private val lock = Mutex()
    private val lookups = HashMap<String, TorboxAlbumLookup>()

    fun apiKey(): String = DebridSettingsRepository.snapshot().torboxApiKey.trim()

    fun owns(track: MusicTrack): Boolean = track.videoId.startsWith(ID_PREFIX)

    /** Searches TorBox for [album]. Answers are kept for the session; a failure is not. */
    suspend fun find(album: MusicAlbum): TorboxAlbumLookup {
        val key = apiKey()
        if (key.isEmpty()) return TorboxAlbumLookup.NotFound
        lock.withLock { lookups[album.browseId] }?.let { return it }
        val artist = album.artists.firstOrNull()?.name.orEmpty()
        val query = torboxAlbumQuery(album.title, artist)
        if (query.isBlank()) return TorboxAlbumLookup.NotFound
        val answer = try {
            val response = TorboxApiClient.searchTorrents(apiKey = key, query = query)
            if (!response.isSuccessful) {
                TorboxAlbumLookup.Failed
            } else {
                val best = rankTorboxAlbumHits(parseTorboxSearchHits(response.rawBody), album.title, artist, album.year).firstOrNull()
                if (best != null) TorboxAlbumLookup.Found(best) else TorboxAlbumLookup.NotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TorboxAlbumLookup.Failed
        }
        if (answer !is TorboxAlbumLookup.Failed) lock.withLock { lookups[album.browseId] = answer }
        return answer
    }

    /**
     * Opens [hit] on the account (only if it is cached, so it is instant), lists its files and pairs
     * them with [album]'s tracks. The album comes back with the same ids and order, so the album page
     * and the player's queue line up with the page the person is on.
     */
    suspend fun prepare(album: MusicAlbum, hit: TorboxSearchHit): TorboxPrepared {
        val key = apiKey()
        if (key.isEmpty()) return TorboxPrepared.Failed
        return try {
            val magnet = hit.magnet ?: "magnet:?xt=urn:btih:${hit.hash}"
            val create = TorboxApiClient.createTorrent(apiKey = key, magnet = magnet, onlyIfCached = true)
            val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
                ?: return TorboxPrepared.Failed
            val torrent = TorboxApiClient.getTorrent(apiKey = key, id = torrentId)
            if (!torrent.isSuccessful) return TorboxPrepared.Failed
            val files = torrent.body?.data?.files.orEmpty().mapNotNull { f ->
                val id = f.id ?: return@mapNotNull null
                TorboxFile(id, f.displayName().ifBlank { return@mapNotNull null })
            }
            val keys = album.tracks.mapIndexed { i, t -> TorboxTrackKey(i, t.trackNumber, t.title) }
            val mapped = mapTorboxFilesToTracks(files, keys) ?: return TorboxPrepared.NoMatch
            val tracks = album.tracks.mapIndexed { i, track ->
                val file = mapped[i]
                if (file == null) track.copy(isAvailable = false)
                else track.copy(videoId = "$ID_PREFIX$torrentId:${file.id}", isAvailable = true)
            }
            TorboxPrepared.Ready(album.copy(tracks = tracks), matched = mapped.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TorboxPrepared.Failed
        }
    }

    /** The player's resolver for a TorBox track: a fresh direct link for its file. */
    suspend fun resolve(track: MusicTrack, mode: MusicPlayMode): MusicResolution {
        val key = apiKey()
        val parts = track.videoId.removePrefix(ID_PREFIX).split(':')
        val torrentId = parts.getOrNull(0)?.toIntOrNull()
        val fileId = parts.getOrNull(1)?.toIntOrNull()
        if (key.isEmpty() || torrentId == null || fileId == null) {
            return MusicResolution.Failed(track, "TorBox is not set up")
        }
        return try {
            val link = TorboxApiClient.requestDownloadLink(apiKey = key, torrentId = torrentId, fileId = fileId)
            val url = link.body?.takeIf { link.isSuccessful && it.success != false }?.data?.takeIf { it.isNotBlank() }
                ?: return MusicResolution.Failed(track, "TorBox would not give a link for this file")
            MusicResolution.Playable(
                track = track,
                mode = mode,
                videoId = track.videoId,
                source = TrailerPlaybackSource(videoUrl = url),
                viaCounterpart = false,
                // A TorBox file is only audio, so video mode shows the lyric view as its picture.
                lyricVideo = mode == MusicPlayMode.Video,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MusicResolution.Failed(track, e.message ?: "TorBox lookup failed")
        }
    }
}
