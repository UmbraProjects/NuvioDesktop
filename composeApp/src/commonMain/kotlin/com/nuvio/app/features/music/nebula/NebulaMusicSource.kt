package com.nuvio.app.features.music.nebula

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicResolution
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.MusicLibraryStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** What the music addon has for an album. */
internal sealed interface NebulaAlbumLookup {
    /** [match] pairs our tracks with the addon's; [fromCache] is true when no search ran just now. */
    data class Found(val match: StoredLookup, val fromCache: Boolean) : NebulaAlbumLookup
    data object NotFound : NebulaAlbumLookup
    /** The addon could not be asked (offline, bad link, down). Not remembered, so a retry asks again. */
    data class Failed(val reason: String) : NebulaAlbumLookup
}

private val log = Logger.withTag("NebulaMusic")

private const val MAX_CANDIDATES = 3
private const val LINK_RETRY_DELAY_MS = 700L

/**
 * The most a stream request may take. The addon asks TorBox for the file, which can sit for a minute on an
 * album nobody has fetched lately; past this the track is reported as not answering rather than left spinning.
 */
private const val STREAM_TIMEOUT_MS = 25_000L
private const val STREAM_CONNECT_TIMEOUT_MS = 10_000L

/** What the player is waiting on from the addon, or what went wrong with the last track it asked for. */
internal sealed interface NebulaStreamStatus {
    val trackTitle: String
    data class Asking(override val trackTitle: String, val sinceMs: Long) : NebulaStreamStatus
    data class Failed(override val trackTitle: String, val reason: String) : NebulaStreamStatus
}

/** One stream request: a link, or why there is none ([staleMatch] when the addon no longer knows the track). */
private sealed interface StreamReply {
    data class Ok(val stream: NebulaStream) : StreamReply
    data class Refused(val reason: String, val staleMatch: Boolean, val worthRetry: Boolean) : StreamReply
}

/** Percent-encodes [text] for a query value. */
internal fun urlEncodeQuery(text: String): String {
    val sb = StringBuilder()
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-_.~") sb.append(ch)
        else sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
    return sb.toString()
}

/**
 * Plays an album from the person's music addon (Nebula, which serves TorBox links) through the
 * ordinary player. YouTube Music stays the catalogue; the addon only supplies audio. Tracks of the
 * prepared album carry the id `nebula:<addon track id>`, and the app's resolver hands those to
 * [resolve], which asks the addon for a direct link at play time (links expire, so never kept).
 *
 * What a search found is kept in memory and on disk ([NebulaLookupCache]).
 */
internal object NebulaMusicSource {
    const val ID_PREFIX = "nebula:"

    private val lock = Mutex()
    private val memory = HashMap<String, NebulaAlbumLookup>()
    private var book = NebulaLookupBook()
    private var bookProfile: Int? = null

    fun owns(track: MusicTrack): Boolean = track.videoId.startsWith(ID_PREFIX)

    /** The format and quality each track last streamed at, by track id. Snapshot state, so the player bar updates when it arrives. */
    private val qualities = mutableStateMapOf<String, String>()

    fun qualityOf(videoId: String): String? = qualities[videoId]

    /** Snapshot state for the album page: the stream request in flight, or the last one that failed; null once a link arrived. */
    var streamStatus by androidx.compose.runtime.mutableStateOf<NebulaStreamStatus?>(null)
        private set

    /** The addon's base URL, or null when no link is set. */
    fun baseUrl(): String? {
        MusicLibraryRepository.ensureLoaded()
        return nebulaBaseUrl(MusicLibraryRepository.state.value.settings.nebulaSecretLink)
    }

    private fun loadBook(): NebulaLookupBook {
        val profile = ProfileRepository.activeProfileId
        if (bookProfile != profile) {
            book = decodeNebulaLookups(MusicLibraryStorage.loadNebulaLookups(profile))
            bookProfile = profile
            memory.clear()
        }
        return book
    }

    private fun saveBook(next: NebulaLookupBook) {
        book = next
        bookProfile?.let { MusicLibraryStorage.saveNebulaLookups(it, encodeNebulaLookups(next)) }
    }

    suspend fun forget(album: MusicAlbum) = lock.withLock {
        loadBook()
        memory.remove(album.browseId)
        saveBook(book.without(album.browseId))
    }

    private class Reply(val status: Int, val body: String)

    private class AddonError(val reason: String) : Exception(reason)

    private suspend fun get(base: String, path: String, callTimeoutMs: Long = 0L, connectTimeoutMs: Long = 0L): Reply {
        val response = httpRequestRaw(
            method = "GET",
            url = base + path,
            headers = mapOf("Accept" to "application/json"),
            body = "",
            callTimeoutMs = callTimeoutMs,
            connectTimeoutMs = connectTimeoutMs,
        )
        return Reply(response.status, response.body)
    }

    private fun Reply.requireOk(): String {
        if (status !in 200..299) {
            log.w { "Music addon answered HTTP $status: ${body.take(200)}" }
            throw AddonError(nebulaFailureReason(status, parseNebulaDetail(body)))
        }
        return body
    }

    suspend fun find(album: MusicAlbum): NebulaAlbumLookup {
        val base = baseUrl() ?: return NebulaAlbumLookup.NotFound
        lock.withLock {
            loadBook()
            memory[album.browseId]?.let { return it }
            book.lookups[album.browseId]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) }?.let { stored ->
                val answer = if (stored.albumId == null) NebulaAlbumLookup.NotFound
                else NebulaAlbumLookup.Found(stored, fromCache = true)
                memory[album.browseId] = answer
                return answer
            }
        }
        val answer = try {
            search(base, album)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonError) {
            NebulaAlbumLookup.Failed(e.reason)
        } catch (e: Exception) {
            log.w(e) { "Music addon search failed" }
            NebulaAlbumLookup.Failed(nebulaFailureReason(status = null, error = e.message))
        }
        if (answer !is NebulaAlbumLookup.Failed) lock.withLock {
            loadBook()
            val entry = (answer as? NebulaAlbumLookup.Found)?.match ?: StoredLookup(savedAt = LibraryClock.nowEpochMs())
            memory[album.browseId] = answer
            saveBook(book.withLookup(album.browseId, entry))
        }
        return answer
    }

    private suspend fun search(base: String, album: MusicAlbum): NebulaAlbumLookup {
        val artist = album.artists.firstOrNull()?.name.orEmpty()
        val queries = nebulaSearchQueries(album.title, artist)
        if (queries.isEmpty()) return NebulaAlbumLookup.NotFound
        val wanted = album.tracks.mapIndexed { i, t -> NebulaTrackKey(i, t.trackNumber.takeIf { it > 0 } ?: (i + 1), t.title, t.durationSeconds) }
        val tried = HashSet<String>()
        for (query in queries) {
            val body = get(base, "/search?q=${urlEncodeQuery(query)}").requireOk()
            val ranked = rankNebulaAlbums(parseNebulaSearchAlbums(body), album.title, artist, album.year, album.tracks.size)
                .filter { tried.add(it.id) }
                .take(MAX_CANDIDATES)
            for (candidate in ranked) {
                val detail = parseNebulaAlbum(get(base, "/album/${urlEncodeQuery(candidate.id)}").requireOk()) ?: continue
                val mapped = mapNebulaTracks(detail.tracks, wanted) ?: continue
                return NebulaAlbumLookup.Found(
                    StoredLookup(LibraryClock.nowEpochMs(), detail.summary.id, detail.summary.title, mapped),
                    fromCache = false,
                )
            }
        }
        return NebulaAlbumLookup.NotFound
    }

    // region lazy "is it on the addon" check for album lists

    private val listChecks = HashMap<String, Boolean>()
    private val listGate = kotlinx.coroutines.sync.Semaphore(2)
    private var listFailures = 0
    private var listPausedUntil = 0L

    /**
     * True when the addon probably has this album, for a badge on a list row. A search and a ranking only
     * (no album fetch, so no track check): cheap, at most two requests, two at a time, remembered for the
     * session. A full lookup already kept for the album answers it for free. Null when it could not be
     * asked (no link, offline, or paused after repeated failures), so the row shows nothing.
     */
    suspend fun probablyHas(album: com.nuvio.app.features.music.MusicAlbumSummary): Boolean? {
        val base = baseUrl() ?: return null
        lock.withLock {
            loadBook()
            listChecks[album.browseId]?.let { return it }
            book.lookups[album.browseId]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) }?.let { return it.albumId != null }
        }
        if (LibraryClock.nowEpochMs() < listPausedUntil) return null
        return listGate.withPermit {
            try {
                val artist = album.artists.firstOrNull()?.name.orEmpty()
                var found = false
                for (query in nebulaSearchQueries(album.title, artist)) {
                    val body = get(base, "/search?q=${urlEncodeQuery(query)}").requireOk()
                    if (rankNebulaAlbums(parseNebulaSearchAlbums(body), album.title, artist, album.year, 0).isNotEmpty()) {
                        found = true
                        break
                    }
                }
                listFailures = 0
                lock.withLock { listChecks[album.browseId] = found }
                found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken addon would otherwise be asked about every row on screen: stop for a minute after three in a row.
                if (++listFailures >= 3) {
                    listPausedUntil = LibraryClock.nowEpochMs() + 60_000L
                    listFailures = 0
                }
                null
            }
        }
    }

    // endregion

    /** [album] with its tracks pointed at the addon; tracks with no counterpart are marked unavailable. */
    fun prepare(album: MusicAlbum, match: StoredLookup): MusicAlbum = album.copy(
        tracks = album.tracks.mapIndexed { i, track ->
            val id = match.trackIds.getOrNull(i)
            if (id == null) {
                track.copy(isAvailable = false)
            } else {
                // Kept so Video mode can still find the song's YouTube video (the addon has audio only).
                youTubeTracks[ID_PREFIX + id] = track to album
                track.copy(videoId = ID_PREFIX + id, isAvailable = true)
            }
        },
    )

    /** The YouTube Music track (and its album) each addon track stands in for, by the addon track's id. */
    private val youTubeTracks = HashMap<String, Pair<MusicTrack, MusicAlbum>>()

    suspend fun resolve(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution {
        // Video mode: the addon has the album's audio only, so the song's YouTube video plays (with its own sound: a
        // video is often another cut than the album track, so the lossless audio would not line up with it). Only
        // when YouTube has no video does the addon's audio play, with the lyric view as the picture.
        if (mode == MusicPlayMode.Video) {
            youTubeTracks[track.videoId]?.let { (original, originalAlbum) ->
                val video = try {
                    com.nuvio.app.features.music.resolveMusicTrack(original, originalAlbum, mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (video is MusicResolution.Playable && !video.lyricVideo) return video.copy(track = track)
            }
        }
        val base = baseUrl() ?: return MusicResolution.Failed(track, "The music addon link isn't set")
        val id = track.videoId.removePrefix(ID_PREFIX)
        streamStatus = NebulaStreamStatus.Asking(track.title, LibraryClock.nowEpochMs())
        return try {
            var reply = fetchStream(base, id)
            if (reply is StreamReply.Refused && reply.worthRetry) {
                delay(LINK_RETRY_DELAY_MS)
                reply = fetchStream(base, id)
            }
            val stream = when (reply) {
                is StreamReply.Ok -> reply.stream
                is StreamReply.Refused -> {
                    // Only a "no such track" means the kept match is stale (the addon's ids changed): search again next
                    // visit. A slow or broken addon says nothing about the match, so it is kept.
                    if (reply.staleMatch) forget(album)
                    streamStatus = NebulaStreamStatus.Failed(track.title, reply.reason)
                    return MusicResolution.Failed(track, reply.reason)
                }
            }
            streamStatus = null
            nebulaQualityLabel(stream.format, stream.quality)?.let { qualities[track.videoId] = it }
            MusicResolution.Playable(
                track = track,
                mode = mode,
                videoId = track.videoId,
                source = TrailerPlaybackSource(videoUrl = stream.url),
                viaCounterpart = false,
                // The addon gives audio only: in video mode (no YouTube video found) the lyric view is the picture.
                lyricVideo = mode == MusicPlayMode.Video,
            )
        } catch (e: CancellationException) {
            if ((streamStatus as? NebulaStreamStatus.Asking)?.trackTitle == track.title) streamStatus = null
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "The music addon failed"
            streamStatus = NebulaStreamStatus.Failed(track.title, reason)
            MusicResolution.Failed(track, reason)
        }
    }

    /**
     * fork:music-playlists - a playlist song (a YouTube Music track that knows its album) played from the addon's copy
     * of that album: the album is read from YouTube Music, looked up on the addon like an album page does (and kept the
     * same way), and the song's place in it gives the addon's track. Only in audio mode (the addon has no video), only
     * when the person prefers the addon, and null whenever any of it is missing, so the caller plays YouTube Music.
     */
    suspend fun resolveFromItsAlbum(track: MusicTrack, mode: MusicPlayMode): MusicResolution.Playable? {
        if (mode != MusicPlayMode.Audio || baseUrl() == null) return null
        if (!MusicLibraryRepository.state.value.settings.preferAddon) return null
        val albumId = track.albumBrowseId ?: return null
        return try {
            val album = com.nuvio.app.features.music.defaultMusicCatalog().album(albumId) ?: return null
            val index = album.tracks.indexOfFirst { it.videoId == track.videoId }.takeIf { it >= 0 }
                ?: album.tracks.indexOfFirst { it.title.equals(track.title, ignoreCase = true) }.takeIf { it >= 0 }
                ?: return null
            val match = (find(album) as? NebulaAlbumLookup.Found)?.match ?: return null
            val addonId = match.trackIds.getOrNull(index) ?: return null
            val playable = resolve(track.copy(videoId = ID_PREFIX + addonId), album, mode) as? MusicResolution.Playable ?: return null
            qualities[ID_PREFIX + addonId]?.let { qualities[track.videoId] = it }
            log.i { "\"${track.title}\" plays from the addon's copy of \"${album.title}\"" }
            // The playlist's own track stays the one playing (its cover, its place in the list); only the stream is the addon's.
            playable.copy(track = track, videoId = track.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Addon look-up for \"${track.title}\" failed; playing YouTube Music" }
            null
        }
    }

    private suspend fun fetchStream(base: String, id: String): StreamReply = try {
        val reply = get(base, "/stream/${urlEncodeQuery(id)}", callTimeoutMs = STREAM_TIMEOUT_MS, connectTimeoutMs = STREAM_CONNECT_TIMEOUT_MS)
        val stream = if (reply.status in 200..299) parseNebulaStream(reply.body) else null
        when {
            stream != null -> StreamReply.Ok(stream)
            reply.status in 200..299 -> {
                log.w { "Music addon stream answer had no link: ${reply.body.take(200)}" }
                StreamReply.Refused("The music addon answered without a link for this track.", staleMatch = true, worthRetry = true)
            }
            else -> {
                log.w { "Music addon stream answered HTTP ${reply.status}: ${reply.body.take(200)}" }
                StreamReply.Refused(
                    nebulaFailureReason(reply.status, parseNebulaDetail(reply.body)),
                    staleMatch = reply.status == 404,
                    worthRetry = reply.status >= 500,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Music addon stream request failed" }
        val timedOut = e.message.orEmpty().contains("timeout", ignoreCase = true) || e::class.simpleName.orEmpty().contains("Timeout")
        StreamReply.Refused(
            if (timedOut) "Your music addon didn't send a link within ${STREAM_TIMEOUT_MS / 1000} s. TorBox may still be fetching the album; try again in a minute."
            else nebulaFailureReason(status = null, error = e.message),
            staleMatch = false,
            // Another 25 s wait on a timeout would only double the silence.
            worthRetry = !timedOut,
        )
    }
}
