package com.nuvio.app.features.music.torbox

import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.TorboxApiClient
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicResolution
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.MusicLibraryStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What TorBox has for an album, as far as a search can tell. */
internal sealed interface TorboxAlbumLookup {
    /**
     * The best cached torrents for the album, best first (up to [MAX_CANDIDATES]). [fromCache] is true when
     * the answer came from memory or disk rather than a search just now, which is what makes it worth
     * re-checking if playing it fails.
     */
    data class Found(val hits: List<TorboxSearchHit>, val fromCache: Boolean) : TorboxAlbumLookup

    /** The search ran and nothing cached is this album. */
    data object NotFound : TorboxAlbumLookup

    /** The search could not run (offline, no paid search plan, TorBox down). Not remembered, so a retry asks again. */
    data class Failed(val reason: String) : TorboxAlbumLookup
}

internal sealed interface TorboxPrepared {
    /** The album with its tracks pointed at TorBox files; tracks with no file are marked unavailable. */
    data class Ready(val album: MusicAlbum, val matched: Int) : TorboxPrepared
    data object NoMatch : TorboxPrepared
    data object Failed : TorboxPrepared
}

private const val MAX_CANDIDATES = 3

private val log = Logger.withTag("TorboxMusic")

/** A short pause before the one plain retry of a link TorBox would not give: it is usually a blip. */
private const val LINK_RETRY_DELAY_MS = 700L

/** A torrent opened on the account: which hit it was, and the path of each of its files, so a lost one can be reopened and its files found again. */
private class OpenTorrent(val hit: TorboxSearchHit, val paths: Map<Int, String>)

/**
 * Plays an album from TorBox through the ordinary music player. Nothing here touches the player:
 * the tracks of the prepared album carry an id of the form `torbox:<torrentId>:<fileId>`, and the
 * app's resolver hands those to [resolve], which asks TorBox for a direct link. YouTube Music stays
 * the catalogue (covers, track list, lyrics); TorBox only supplies the audio.
 *
 * Uses the TorBox key already set under Debrid settings. It never adds an uncached torrent: that
 * would start a transfer that can take hours.
 *
 * What a search found is kept in memory and on disk ([TorboxLookupCache]), so an album costs one
 * search per week at most. Because a kept answer can go stale (TorBox drops cached torrents), a copy
 * that will not open is searched for again, the next candidate is tried, and a link that stops
 * working mid-album is retried and, if the torrent is gone, the torrent is reopened.
 */
internal object TorboxMusicSource {
    const val ID_PREFIX = "torbox:"

    private val lock = Mutex()
    private val repairLock = Mutex()
    private val memory = HashMap<String, TorboxAlbumLookup>()
    private var book = TorboxLookupBook()
    private var bookProfile: Int? = null

    private val opened = HashMap<Int, OpenTorrent>()
    /** Torrent ids that were reopened under a new id, so the rest of the album follows without repairing again. */
    private val moved = HashMap<Int, Int>()

    fun apiKey(): String = DebridSettingsRepository.snapshot().torboxApiKey.trim()

    fun owns(track: MusicTrack): Boolean = track.videoId.startsWith(ID_PREFIX)

    // region lookups

    /** The disk book for the active profile; switching profile drops what was held for the last one. */
    private fun loadBook(): TorboxLookupBook {
        val profile = ProfileRepository.activeProfileId
        if (bookProfile != profile) {
            book = decodeTorboxLookups(MusicLibraryStorage.loadTorboxLookups(profile))
            bookProfile = profile
            memory.clear()
        }
        return book
    }

    private fun saveBook(next: TorboxLookupBook) {
        book = next
        bookProfile?.let { MusicLibraryStorage.saveTorboxLookups(it, encodeTorboxLookups(next)) }
    }

    /** Forgets what is known about [album], in memory and on disk, so the next [find] searches again. */
    suspend fun forget(album: MusicAlbum) = lock.withLock {
        loadBook()
        memory.remove(album.browseId)
        saveBook(book.without(album.browseId))
    }

    /** What TorBox has for [album]: from memory, then disk while fresh, then a search. A failure is not kept. */
    suspend fun find(album: MusicAlbum): TorboxAlbumLookup {
        val key = apiKey()
        if (key.isEmpty()) return TorboxAlbumLookup.NotFound
        lock.withLock {
            loadBook()
            memory[album.browseId]?.let { return it }
            book.lookups[album.browseId]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) }?.let { stored ->
                val answer = if (stored.hits.isEmpty()) TorboxAlbumLookup.NotFound
                else TorboxAlbumLookup.Found(stored.hits.map { it.toHit() }, fromCache = true)
                memory[album.browseId] = answer
                return answer
            }
        }
        val artist = album.artists.firstOrNull()?.name.orEmpty()
        val query = torboxAlbumQuery(album.title, artist)
        if (query.isBlank()) return TorboxAlbumLookup.NotFound
        val answer = try {
            val response = TorboxApiClient.searchTorrents(apiKey = key, query = query)
            if (!response.isSuccessful) {
                log.w { "TorBox search for \"$query\" failed: HTTP ${response.status} ${response.rawBody.take(300)}" }
                TorboxAlbumLookup.Failed(torboxSearchFailureReason(response.status))
            } else {
                val ranked = rankTorboxAlbumHits(parseTorboxSearchHits(response.rawBody), album.title, artist, album.year)
                    .take(MAX_CANDIDATES)
                if (ranked.isNotEmpty()) TorboxAlbumLookup.Found(ranked, fromCache = false) else TorboxAlbumLookup.NotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "TorBox search for \"$query\" failed" }
            TorboxAlbumLookup.Failed(torboxSearchFailureReason(status = null, error = e.message))
        }
        if (answer !is TorboxAlbumLookup.Failed) {
            lock.withLock {
                loadBook()
                memory[album.browseId] = answer
                val stored = StoredLookup(
                    savedAt = LibraryClock.nowEpochMs(),
                    hits = (answer as? TorboxAlbumLookup.Found)?.hits.orEmpty().map { it.toStored() },
                )
                saveBook(book.withLookup(album.browseId, stored))
            }
        }
        return answer
    }

    // endregion

    // region opening

    /**
     * Opens [album] on TorBox: the best candidate that opens and lines up with the tracks. If every
     * candidate came from a kept answer and none opens, the answer was probably stale, so it is
     * forgotten, searched for again, and the new candidates tried.
     */
    suspend fun open(album: MusicAlbum): TorboxPrepared {
        val first = find(album) as? TorboxAlbumLookup.Found ?: return TorboxPrepared.Failed
        var result = prepareFirst(album, first.hits)
        if (result is TorboxPrepared.Ready || !first.fromCache) return result
        forget(album)
        val fresh = find(album) as? TorboxAlbumLookup.Found ?: return result
        val tried = first.hits.map { it.hash }.toSet()
        val next = fresh.hits.filter { it.hash !in tried }
        if (next.isEmpty()) return result
        result = prepareFirst(album, next)
        return result
    }

    private suspend fun prepareFirst(album: MusicAlbum, hits: List<TorboxSearchHit>): TorboxPrepared {
        var last: TorboxPrepared = TorboxPrepared.Failed
        for (hit in hits) {
            val result = prepare(album, hit)
            if (result is TorboxPrepared.Ready) return result
            last = result
        }
        return last
    }

    /** The files of [torrentId] as the matcher wants them, or null when TorBox will not list them. */
    private suspend fun listFiles(key: String, torrentId: Int): List<TorboxFile>? {
        val torrent = TorboxApiClient.getTorrent(apiKey = key, id = torrentId)
        if (!torrent.isSuccessful) return null
        return torrent.body?.data?.files.orEmpty().mapNotNull { f ->
            val id = f.id ?: return@mapNotNull null
            TorboxFile(id, f.displayName().ifBlank { return@mapNotNull null })
        }
    }

    /** Opens [hit] on the account (cached only, so instant) and pairs its files with [album]'s tracks. */
    private suspend fun prepare(album: MusicAlbum, hit: TorboxSearchHit): TorboxPrepared {
        val key = apiKey()
        if (key.isEmpty()) return TorboxPrepared.Failed
        return try {
            val create = TorboxApiClient.createTorrent(apiKey = key, magnet = magnetFor(hit), onlyIfCached = true)
            val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
                ?: return TorboxPrepared.Failed
            val files = listFiles(key, torrentId) ?: return TorboxPrepared.Failed
            val keys = album.tracks.mapIndexed { i, t -> TorboxTrackKey(i, t.trackNumber, t.title) }
            val mapped = mapTorboxFilesToTracks(files, keys) ?: return TorboxPrepared.NoMatch
            opened[torrentId] = OpenTorrent(hit, files.associate { it.id to it.path })
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

    private fun magnetFor(hit: TorboxSearchHit) = hit.magnet ?: "magnet:?xt=urn:btih:${hit.hash}"

    // endregion

    // region playing

    private suspend fun linkFor(key: String, torrentId: Int, fileId: Int): String? = try {
        val link = TorboxApiClient.requestDownloadLink(apiKey = key, torrentId = torrentId, fileId = fileId)
        link.body?.takeIf { link.isSuccessful && it.success != false }?.data?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Where a track's file lives now, following a torrent that was reopened under a new id. */
    private suspend fun current(torrentId: Int, fileId: Int): Pair<Int, Int> = repairLock.withLock {
        val newId = moved[torrentId] ?: return torrentId to fileId
        val path = opened[torrentId]?.paths?.get(fileId)
        val newFile = opened[newId]?.paths?.entries?.firstOrNull { it.value == path }?.key
        newId to (newFile ?: fileId)
    }

    /**
     * The torrent behind [torrentId] is no longer on the account. Opens it again (still cached only),
     * finds [fileId]'s file by its path in the new listing, and remembers the move so the other
     * tracks of the album do not each repeat this. Null when it cannot be brought back.
     */
    private suspend fun repair(key: String, torrentId: Int, fileId: Int): Pair<Int, Int>? = repairLock.withLock {
        moved[torrentId]?.let { newId ->
            val path = opened[torrentId]?.paths?.get(fileId)
            val newFile = opened[newId]?.paths?.entries?.firstOrNull { it.value == path }?.key ?: return null
            return newId to newFile
        }
        val old = opened[torrentId] ?: return null
        val path = old.paths[fileId] ?: return null
        val create = TorboxApiClient.createTorrent(apiKey = key, magnet = magnetFor(old.hit), onlyIfCached = true)
        val newId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId() ?: return null
        val files = listFiles(key, newId) ?: return null
        val newFile = files.firstOrNull { it.path == path } ?: return null
        opened[newId] = OpenTorrent(old.hit, files.associate { it.id to it.path })
        moved[torrentId] = newId
        newId to newFile.id
    }

    /**
     * The player's resolver for a TorBox track: a fresh direct link for its file. A link TorBox will
     * not give is asked for once more, then the torrent is reopened if it has gone; if it cannot be
     * brought back the kept answer for the album is forgotten so the next visit searches again.
     */
    suspend fun resolve(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution {
        val key = apiKey()
        val parts = track.videoId.removePrefix(ID_PREFIX).split(':')
        val torrentId = parts.getOrNull(0)?.toIntOrNull()
        val fileId = parts.getOrNull(1)?.toIntOrNull()
        if (key.isEmpty() || torrentId == null || fileId == null) {
            return MusicResolution.Failed(track, "TorBox is not set up")
        }
        return try {
            val (tid, fid) = current(torrentId, fileId)
            var url = linkFor(key, tid, fid)
            if (url == null) {
                delay(LINK_RETRY_DELAY_MS)
                url = linkFor(key, tid, fid)
            }
            if (url == null) {
                val repaired = repair(key, tid, fid)
                url = repaired?.let { (newTid, newFid) -> linkFor(key, newTid, newFid) }
                if (repaired == null) forget(album)
            }
            if (url == null) {
                log.w { "TorBox gave no link for torrent $torrentId file $fileId, even after a retry and a reopen" }
                return MusicResolution.Failed(track, "TorBox no longer has this file")
            }
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

    // endregion
}
