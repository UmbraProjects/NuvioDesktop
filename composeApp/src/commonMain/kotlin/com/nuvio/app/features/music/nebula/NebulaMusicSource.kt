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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private suspend fun get(base: String, path: String): Reply {
        val response = httpRequestRaw(
            method = "GET",
            url = base + path,
            headers = mapOf("Accept" to "application/json"),
            body = "",
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

    /** [album] with its tracks pointed at the addon; tracks with no counterpart are marked unavailable. */
    fun prepare(album: MusicAlbum, match: StoredLookup): MusicAlbum = album.copy(
        tracks = album.tracks.mapIndexed { i, track ->
            val id = match.trackIds.getOrNull(i)
            if (id == null) track.copy(isAvailable = false) else track.copy(videoId = ID_PREFIX + id, isAvailable = true)
        },
    )

    suspend fun resolve(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution {
        val base = baseUrl() ?: return MusicResolution.Failed(track, "The music addon link isn't set")
        val id = track.videoId.removePrefix(ID_PREFIX)
        return try {
            var stream = fetchStream(base, id)
            if (stream == null) {
                delay(LINK_RETRY_DELAY_MS)
                stream = fetchStream(base, id)
            }
            if (stream == null) {
                // The kept match may be stale (the addon's ids changed): search again next visit.
                forget(album)
                return MusicResolution.Failed(track, "The music addon has no link for this track")
            }
            MusicResolution.Playable(
                track = track,
                mode = mode,
                videoId = track.videoId,
                source = TrailerPlaybackSource(videoUrl = stream.url),
                viaCounterpart = false,
                // The addon gives audio only, so video mode shows the lyric view as its picture.
                lyricVideo = mode == MusicPlayMode.Video,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MusicResolution.Failed(track, e.message ?: "The music addon failed")
        }
    }

    private suspend fun fetchStream(base: String, id: String): NebulaStream? = try {
        val reply = get(base, "/stream/${urlEncodeQuery(id)}")
        if (reply.status in 200..299) parseNebulaStream(reply.body) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Music addon stream request failed" }
        null
    }
}
