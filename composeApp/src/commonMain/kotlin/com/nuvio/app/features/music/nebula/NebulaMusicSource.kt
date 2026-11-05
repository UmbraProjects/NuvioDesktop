package com.nuvio.app.features.music.nebula

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicResolution
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.addonLinks
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

/**
 * What several addons' answers for one album come to, asked in order: the first that has the album wins; "not found" only
 * when every addon said so; and when none has it but one could not be asked, that failure (so it is retried, not remembered).
 */
internal fun firstAddonAnswer(answers: List<NebulaAlbumLookup>): NebulaAlbumLookup =
    answers.firstOrNull { it is NebulaAlbumLookup.Found }
        ?: answers.firstOrNull { it is NebulaAlbumLookup.Failed }
        ?: NebulaAlbumLookup.NotFound

private val log = Logger.withTag("NebulaMusic")

/** How much of a raw addon answer goes in the log. */
private const val RAW_LOG_CHARS = 1200

private const val MAX_CANDIDATES = 3

/** The least time between two availability searches (0.8 s still drew 429s with 42 to 874 s bans; the addon's limit is not published). */
private const val SEARCH_SPACING_MS = 2_000L

/** The longest a check waits in line for a pause the addon asked for; a longer ban fails the check at once. */
private const val MAX_SEARCH_QUEUE_WAIT_MS = 60_000L
private const val LINK_RETRY_DELAY_MS = 700L

/** How long a track the addon is still preparing is waited for in all, counted from the first ask, and how often it is asked again. */
private const val STREAM_PATIENCE_MS = 90_000L
private const val STREAM_POLL_MS = 3_000L

/** fork:nebula - fetch on Play waits much longer than a track tapped by hand (nobody is waiting on it): how long, and how often it asks. */
private const val FETCH_AHEAD_PATIENCE_MS = 10 * 60_000L
private const val FETCH_AHEAD_POLL_MS = 5_000L

/**
 * The most a stream request may take. The addon asks TorBox for the file, which can sit for a minute on an
 * album nobody has fetched lately; past this the track is reported as not answering rather than left spinning.
 */
private const val STREAM_TIMEOUT_MS = 25_000L
private const val STREAM_CONNECT_TIMEOUT_MS = 10_000L

/** What the player is waiting on from the addon, or what went wrong with the last track it asked for. */
internal sealed interface NebulaStreamStatus {
    val trackTitle: String
    data class Asking(override val trackTitle: String, val sinceMs: Long, val preparing: Boolean = false) : NebulaStreamStatus
    data class Failed(override val trackTitle: String, val reason: String) : NebulaStreamStatus
}

/** One stream request: a link, or why there is none ([staleMatch] when the addon no longer knows the track). */
private sealed interface StreamReply {
    data class Ok(val stream: NebulaStream) : StreamReply
    data class Refused(val reason: String, val staleMatch: Boolean, val worthRetry: Boolean, val waitable: Boolean = false) : StreamReply
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
    private var memoryAddons = ""
    private var book = NebulaLookupBook()
    private var bookProfile: Int? = null

    fun owns(track: MusicTrack): Boolean = track.videoId.startsWith(ID_PREFIX)

    /** The format and quality each track last streamed at, by track id. Snapshot state, so the player bar updates when it arrives. */
    private val qualities = mutableStateMapOf<String, String>()

    fun qualityOf(videoId: String): String? = qualities[videoId]

    /** Snapshot state for the album page: the stream request in flight, or the last one that failed; null once a link arrived. */
    var streamStatus by androidx.compose.runtime.mutableStateOf<NebulaStreamStatus?>(null)
        private set

    /** The addons that have a link, in the order they are asked. */
    fun addons(): List<NebulaAddon> {
        MusicLibraryRepository.ensureLoaded()
        return nebulaAddons(MusicLibraryRepository.state.value.settings.addonLinks())
    }

    /** The first addon's base URL, or null when no link is set. */
    fun baseUrl(): String? = addons().firstOrNull()?.base

    /** The addon a track or album belongs to: the one with [key], or the first for an id made before there were several. */
    private fun addonFor(key: String?): NebulaAddon? {
        val list = addons()
        return if (key == null) list.firstOrNull() else list.firstOrNull { it.key == key }
    }

    /** The links that are set, as one string; it changes when a link is added, removed or moved. */
    private fun addonSet(addons: List<NebulaAddon>): String = addons.joinToString(",") { it.key }

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
        val addons = addons()
        if (addons.isEmpty()) return NebulaAlbumLookup.NotFound
        val set = addonSet(addons)
        lock.withLock {
            loadBook()
            // What was found for another set of links is not believed for this one.
            if (memoryAddons != set) {
                memory.clear()
                memoryAddons = set
            }
            memory[album.browseId]?.let { return it }
            book.lookups[album.browseId]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) && it.fitsAddons(addons) }?.let { stored ->
                val answer = if (stored.albumId == null) NebulaAlbumLookup.NotFound
                else NebulaAlbumLookup.Found(stored, fromCache = true)
                memory[album.browseId] = answer
                return answer
            }
        }
        // Each addon in turn; the first that has the album is the one that plays it.
        val answers = ArrayList<NebulaAlbumLookup>()
        for (addon in addons) {
            val one = try {
                search(addon, album)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AddonError) {
                NebulaAlbumLookup.Failed(e.reason)
            } catch (e: Exception) {
                log.w(e) { "Music addon search failed" }
                NebulaAlbumLookup.Failed(nebulaFailureReason(status = null, error = e.message))
            }
            answers += one
            if (one is NebulaAlbumLookup.Found) break
        }
        val answer = firstAddonAnswer(answers)
        if (answer !is NebulaAlbumLookup.Failed) lock.withLock {
            loadBook()
            val entry = (answer as? NebulaAlbumLookup.Found)?.match ?: StoredLookup(savedAt = LibraryClock.nowEpochMs(), asked = set)
            memory[album.browseId] = answer
            saveBook(book.withLookup(album.browseId, entry))
        }
        return answer
    }

    private suspend fun search(addon: NebulaAddon, album: MusicAlbum): NebulaAlbumLookup {
        val artist = album.artists.firstOrNull()?.name.orEmpty()
        val queries = nebulaSearchQueries(album.title, artist)
        if (queries.isEmpty()) return NebulaAlbumLookup.NotFound
        val wanted = album.tracks.mapIndexed { i, t -> NebulaTrackKey(i, t.trackNumber.takeIf { it > 0 } ?: (i + 1), t.title, t.durationSeconds) }
        val tried = HashSet<String>()
        for (query in queries) {
            val body = get(addon.base, "/search?q=${urlEncodeQuery(query)}").requireOk()
            val ranked = rankNebulaAlbums(parseNebulaSearchAlbums(body), album.title, artist, album.year, album.tracks.size)
                .filter { tried.add(it.id) }
                .take(MAX_CANDIDATES)
            for (candidate in ranked) {
                val detail = parseNebulaAlbum(get(addon.base, "/album/${urlEncodeQuery(candidate.id)}").requireOk()) ?: continue
                val mapped = mapNebulaTracks(detail.tracks, wanted) ?: continue
                return NebulaAlbumLookup.Found(
                    StoredLookup(LibraryClock.nowEpochMs(), detail.summary.id, detail.summary.title, mapped, addon = addon.key),
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
        val addons = addons()
        if (addons.isEmpty()) return null
        val memoKey = addonSet(addons) + "|" + album.browseId
        lock.withLock {
            loadBook()
            listChecks[memoKey]?.let { return it }
            book.lookups[album.browseId]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) && it.fitsAddons(addons) }?.let { return it.albumId != null }
        }
        if (LibraryClock.nowEpochMs() < listPausedUntil) return null
        return listGate.withPermit {
            try {
                val artist = album.artists.firstOrNull()?.name.orEmpty()
                var found = false
                var failed = false
                for (addon in addons) {
                    try {
                        for (query in nebulaSearchQueries(album.title, artist)) {
                            val body = get(addon.base, "/search?q=${urlEncodeQuery(query)}").requireOk()
                            if (rankNebulaAlbums(parseNebulaSearchAlbums(body), album.title, artist, album.year, 0).isNotEmpty()) {
                                found = true
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                    }
                    if (found) break
                }
                // Not found, and an addon that could not be asked: not an answer, so nothing is remembered.
                if (!found && failed) throw AddonError("no addon could be asked")
                listFailures = 0
                lock.withLock { listChecks[memoKey] = found }
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

    // region releases YouTube Music does not have

    private var avail = AddonAvailabilityBook()
    private var availProfile: Int? = null
    private val availGate = kotlinx.coroutines.sync.Semaphore(2)
    private var availFailures = 0
    private var availPausedUntil = 0L

    private fun loadAvail(): AddonAvailabilityBook {
        val profile = ProfileRepository.activeProfileId
        if (availProfile != profile) {
            avail = decodeAddonAvailability(MusicLibraryStorage.loadNebulaAvailability(profile))
            availProfile = profile
        }
        return avail
    }

    private fun saveAvail(next: AddonAvailabilityBook) {
        avail = next
        availProfile?.let { MusicLibraryStorage.saveNebulaAvailability(it, encodeAddonAvailability(next)) }
    }

    /** What was kept for [cacheKey] while it is still fresh, else null. Memory and disk only: no request is made. */
    suspend fun cachedAvailability(cacheKey: String): AddonAvailability? {
        val addons = addons()
        return lock.withLock {
            loadAvail()
            avail.entries[cacheKey]?.takeIf { it.isFresh(LibraryClock.nowEpochMs()) && it.fitsAddons(addons) }
        }
    }

    /** Drops what was kept for [cacheKey], so the next check asks the addon again (a stream that said "no such track"). */
    suspend fun forgetRelease(cacheKey: String) = lock.withLock {
        loadAvail()
        saveAvail(avail.without(cacheKey))
    }

    /**
     * Whether the addon has [query]'s release, for one that YouTube Music does not list. A kept answer comes back
     * with no request. Otherwise the addon's search is asked (at most two requests, two checks at a time) and the
     * answer kept: a match for two weeks, a miss for longer each time it repeats. A check that queued behind another
     * for the same release finds that one's answer instead of asking again. Only a strict match counts
     * ([strictNebulaMatch]), since the row is un-dimmed and opened on it.
     *
     * Null when it could not be asked (no link, offline, or paused for a minute after three failures in a row); that
     * is never kept, so the next call asks again. A match found earlier that cannot be re-checked now (expired, addon
     * down) is returned as it was rather than dropped.
     */
    suspend fun availabilityOf(query: AddonReleaseQuery): AddonAvailability? {
        val addons = addons()
        if (addons.isEmpty()) return null
        cachedAvailability(query.cacheKey)?.let { return it }
        if (LibraryClock.nowEpochMs() < availPausedUntil) return null
        return availGate.withPermit {
            cachedAvailability(query.cacheKey)?.let { return@withPermit it }
            val previous = lock.withLock { avail.entries[query.cacheKey] }
            try {
                val match = searchRelease(addons, query)
                availFailures = 0
                val now = LibraryClock.nowEpochMs()
                val entry = if (match != null) addonFound(now, match.second, match.first.key) else addonMissed(now, previous, addonSet(addons))
                lock.withLock {
                    loadAvail()
                    saveAvail(avail.withEntry(query.cacheKey, entry))
                }
                entry
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refusal by the addon is a line, not a stack trace (the log had hundreds of them).
                if (e is AddonError) log.w { "Music addon availability check failed: ${e.reason}" } else log.w(e) { "Music addon availability check failed" }
                // A broken addon would otherwise be asked about every row on screen: stop for a minute after three in a row.
                if (++availFailures >= 3) {
                    availPausedUntil = LibraryClock.nowEpochMs() + 60_000L
                    availFailures = 0
                }
                previous?.takeIf { it.albumId != null }
            }
        }
    }

    private val paceLock = Mutex()
    private var nextSearchAt = 0L

    /** Waits for this request's turn: availability searches start at least [SEARCH_SPACING_MS] apart, whichever release or addon they are for. */
    private suspend fun waitForSearchTurn() {
        val wait = paceLock.withLock {
            val now = LibraryClock.nowEpochMs()
            val at = maxOf(now, nextSearchAt)
            // A long ban (the addon asked for minutes): do not sit in line for it, fail now so the check is asked again next visit.
            if (at - now > MAX_SEARCH_QUEUE_WAIT_MS) throw AddonError("The music addon is limiting searches (about ${(at - now) / 1000} s left). Try again later.")
            nextSearchAt = at + SEARCH_SPACING_MS
            at - now
        }
        if (wait > 0) delay(wait)
    }

    /**
     * fork:nebula - one /search for the availability check, paced, and asked once more after the wait when the addon says
     * "too many search requests" (its reply names the seconds to wait). The log showed 85 of a 4-minute visit's checks
     * refused that way when 30 albums were asked about in a burst. Everyone else waits too while it waits.
     */
    private suspend fun pacedSearch(base: String, text: String): String {
        waitForSearchTurn()
        var reply = get(base, "/search?q=${urlEncodeQuery(text)}")
        if (reply.status == 429) {
            // The whole wait the addon names, not a shorter one: the log showed it answering 42, 59 and then 874 seconds to
            // searches that came back after only 16, so asking early made the ban longer.
            val seconds = Regex("""in (\d+) second""").find(reply.body)?.groupValues?.get(1)?.toLongOrNull() ?: 10L
            val pause = (seconds.coerceIn(1L, 3_600L) + 2L) * 1000L
            paceLock.withLock { nextSearchAt = maxOf(nextSearchAt, LibraryClock.nowEpochMs() + pause) }
            log.i { "Music addon asked for a $seconds s pause between searches" }
            // A short pause is waited out and the search asked once more; a long one fails this check (asked again next visit)
            // and, through the turn, every check queued behind it.
            if (pause > MAX_SEARCH_QUEUE_WAIT_MS) return reply.requireOk()
            waitForSearchTurn()
            reply = get(base, "/search?q=${urlEncodeQuery(text)}")
        }
        return reply.requireOk()
    }

    /**
     * The addon album for [query] from /search alone (artist and title first, then the title alone), asking each addon in
     * turn: the first that has it, with that addon. When none has it but one could not be asked, that failure is thrown, so
     * the miss is not remembered.
     */
    private suspend fun searchRelease(addons: List<NebulaAddon>, query: AddonReleaseQuery): Pair<NebulaAddon, NebulaAlbumSummary>? {
        var failure: Exception? = null
        for (addon in addons) {
            try {
                for (text in nebulaSearchQueries(query.title, query.artist)) {
                    val body = pacedSearch(addon.base, text)
                    val match = strictNebulaMatch(parseNebulaSearchAlbums(body), query.title, query.artist, query.year, query.trackCount ?: 0)
                    // fork:nebula - raw answer kept in the log, to learn how the addon marks an album ready on TorBox vs fetchable.
                    log.i { "Lossless check \"${query.title}\" by ${query.artist} [$text]: matched=${match?.title} raw=${body.take(RAW_LOG_CHARS)}" }
                    if (match != null) return addon to match
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
        return null
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
                val videoId = nebulaTrackVideoId(match.addon, id)
                youTubeTracks[videoId] = track to album
                track.copy(videoId = videoId, isAvailable = true)
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
        val ref = nebulaTrackRef(track.videoId) ?: return MusicResolution.Failed(track, "That isn't a music addon track")
        val addon = addonFor(ref.addonKey) ?: return MusicResolution.Failed(
            track,
            if (addons().isEmpty()) "The music addon link isn't set" else "That music addon's link was removed from Music settings.",
        )
        val base = addon.base
        val id = ref.id
        val askedAt = LibraryClock.nowEpochMs()
        streamStatus = NebulaStreamStatus.Asking(track.title, askedAt)
        return try {
            var reply = fetchStream(base, id)
            if (reply is StreamReply.Refused && reply.worthRetry && !reply.waitable) {
                delay(LINK_RETRY_DELAY_MS)
                reply = fetchStream(base, id)
            }
            var found: NebulaStream? = (reply as? StreamReply.Ok)?.stream
            if (found == null) {
                // fork:addons - another addon that has the same album may have this track ready when this one does not.
                found = streamFromOtherAddons(track.videoId, addon)
            }
            if (found == null && (reply as? StreamReply.Refused)?.waitable == true) {
                // The addon is still fetching the file: ask again until it is ready, for up to STREAM_PATIENCE_MS in all.
                streamStatus = NebulaStreamStatus.Asking(track.title, askedAt, preparing = true)
                while ((reply as? StreamReply.Refused)?.waitable == true && LibraryClock.nowEpochMs() - askedAt < STREAM_PATIENCE_MS) {
                    delay(STREAM_POLL_MS)
                    reply = fetchStream(base, id)
                }
                found = (reply as? StreamReply.Ok)?.stream
                if (found == null && (reply as? StreamReply.Refused)?.waitable == true) {
                    reply = StreamReply.Refused(
                        "Your music addon is still preparing this track after ${STREAM_PATIENCE_MS / 1000} s. Try again in a minute.",
                        staleMatch = false,
                        worthRetry = false,
                    )
                }
            }
            val stream = found ?: run {
                val refused = reply as StreamReply.Refused
                // Only a "no such track" means the kept match is stale (the addon's ids changed): search again next
                // visit. A slow or broken addon says nothing about the match, so it is kept.
                if (refused.staleMatch) {
                    forget(album)
                    // fork:nebula - an addon-only album is remembered per release, not per YouTube Music album.
                    parseAddonAlbumId(album.browseId)?.let { forgetRelease(addonCacheKey(it.artistId, it.releaseKey)) }
                }
                streamStatus = NebulaStreamStatus.Failed(track.title, refused.reason)
                return MusicResolution.Failed(track, refused.reason)
            }
            streamStatus = null
            nebulaQualityLabel(stream.format, stream.quality)?.let { qualities[track.videoId] = it }
            MusicResolution.Playable(
                track = track,
                mode = mode,
                videoId = track.videoId,
                source = TrailerPlaybackSource(videoUrl = stream.url, loudnessKey = "addon:${track.videoId}"),
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
            val addonVideoId = nebulaTrackVideoId(match.addon, addonId)
            val playable = resolve(track.copy(videoId = addonVideoId), album, mode) as? MusicResolution.Playable ?: return null
            qualities[addonVideoId]?.let { qualities[track.videoId] = it }
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

    /**
     * fork:nebula - fetch on Play. [album] is playing from YouTube Music; when the addon has it, one track of it is asked
     * for (the first matched one after [afterIndex]), which makes the addon fetch the album when it is not ready. Asked
     * again every [FETCH_AHEAD_POLL_MS] while the addon says it is still preparing, for up to [FETCH_AHEAD_PATIENCE_MS].
     * Returns the album with its tracks pointed at the addon once that track answers (so the rest can swap to it), else
     * null (not on the addon, nothing matched, the addon refused or never finished). It never touches the stream status
     * the album page shows, and plays nothing itself. Every stream answer lands in the log (links masked).
     */
    suspend fun fetchAhead(album: MusicAlbum, afterIndex: Int): MusicAlbum? {
        if (addons().isEmpty()) return null
        if (parseAddonAlbumId(album.browseId) != null || album.kind == "Playlist" || album.kind == "Mix") return null
        if (album.tracks.any { owns(it) }) return null
        val match = (find(album) as? NebulaAlbumLookup.Found)?.match ?: return null
        val prepared = prepare(album, match)
        val pick = prepared.tracks.withIndex()
            .filter { (i, t) -> i > afterIndex && owns(t) && t.isAvailable }
            .firstOrNull()?.value
            ?: prepared.tracks.firstOrNull { owns(it) && it.isAvailable }
            ?: return null
        val ref = nebulaTrackRef(pick.videoId) ?: return null
        val addon = addonFor(ref.addonKey) ?: return null
        val startedAt = LibraryClock.nowEpochMs()
        log.i { "Fetch on Play: asking the addon for \"${pick.title}\" of \"${album.title}\" (${match.trackIds.count { it != null }} tracks matched)" }
        while (true) {
            val reply = fetchStream(addon.base, ref.id)
            if (reply is StreamReply.Ok) {
                log.i { "Fetch on Play: \"${pick.title}\" answered after ${(LibraryClock.nowEpochMs() - startedAt) / 1000} s, \"${album.title}\" is ready" }
                return prepared
            }
            val waitable = (reply as? StreamReply.Refused)?.waitable == true
            if (!waitable || LibraryClock.nowEpochMs() - startedAt >= FETCH_AHEAD_PATIENCE_MS) {
                log.w { "Fetch on Play gave up on \"${album.title}\": ${(reply as? StreamReply.Refused)?.reason}" }
                return null
            }
            delay(FETCH_AHEAD_POLL_MS)
        }
    }

    /**
     * The album page's album for a release the addon has and YouTube Music does not ([ref]): the addon's own track list
     * (so nothing to match against), with the title, artist, year and cover the library knows for the release. Its tracks
     * are `nebula:<id>`, so the player asks the addon for each link when it plays. Null when the addon cannot be asked or
     * has no such album; a "no such album" also forgets the kept answer, so the artist page asks again next time.
     */
    suspend fun openAddonAlbum(ref: AddonAlbumRef): MusicAlbum? {
        val base = addonFor(ref.addon)?.base ?: return null
        val reply = try {
            get(base, "/album/${urlEncodeQuery(ref.albumId)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Music addon album request failed" }
            return null
        }
        if (reply.status == 404) {
            forgetRelease(addonCacheKey(ref.artistId, ref.releaseKey))
            return null
        }
        if (reply.status !in 200..299) {
            log.w { "Music addon album answered HTTP ${reply.status}: ${reply.body.take(200)}" }
            return null
        }
        val detail = parseNebulaAlbum(reply.body) ?: return null
        val artist = MusicLibraryRepository.state.value.artistOrPreview(ref.artistId)
        return buildAddonAlbum(ref, detail, artist, artist?.releases?.firstOrNull { it.key == ref.releaseKey })
    }

    /** What the other addons have, per addon and album: kept in memory only, for the tracks that need a second source. */
    private val alternates = HashMap<String, StoredLookup?>()

    private suspend fun alternateMatch(addon: NebulaAddon, album: MusicAlbum): StoredLookup? {
        val key = "${addon.key}|${album.browseId}"
        lock.withLock { if (alternates.containsKey(key)) return alternates[key] }
        val match = try {
            (search(addon, album) as? NebulaAlbumLookup.Found)?.match
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // could not be asked: not remembered, so a later track asks again
        }
        lock.withLock { alternates[key] = match }
        return match
    }

    /**
     * fork:addons - the stream of the same song from another addon, for when [tried] gave none. The album is looked up on
     * each of the others (kept for the session) and the song's place in it gives that addon's track. Null when the track
     * has no YouTube Music original to match by (an addon-only album, a playlist song) or no other addon has it ready.
     */
    private suspend fun streamFromOtherAddons(videoId: String, tried: NebulaAddon): NebulaStream? {
        val (original, originalAlbum) = youTubeTracks[videoId] ?: return null
        val index = originalAlbum.tracks.indexOfFirst { it.videoId == original.videoId }.takeIf { it >= 0 } ?: return null
        for (other in addons()) {
            if (other.key == tried.key) continue
            val otherId = alternateMatch(other, originalAlbum)?.trackIds?.getOrNull(index) ?: continue
            val reply = try {
                fetchStream(other.base, otherId)
            } catch (e: CancellationException) {
                throw e
            }
            if (reply is StreamReply.Ok) {
                log.i { "\"${original.title}\" came from another music addon (the first had none ready)" }
                return reply.stream
            }
        }
        return null
    }

    private suspend fun fetchStream(base: String, id: String): StreamReply = try {
        val reply = get(base, "/stream/${urlEncodeQuery(id)}", callTimeoutMs = STREAM_TIMEOUT_MS, connectTimeoutMs = STREAM_CONNECT_TIMEOUT_MS)
        val stream = if (reply.status in 200..299) parseNebulaStream(reply.body) else null
        // fork:nebula - the answer's shape (links masked), to learn what a ready vs a fetching track looks like.
        log.i { "Music addon stream HTTP ${reply.status} for $id: ${reply.body.take(RAW_LOG_CHARS).replace(Regex("""https?://[^"\s\\]+"""), "<url>")}" }
        when {
            stream != null -> StreamReply.Ok(stream)
            reply.status in 200..299 -> {
                log.w { "Music addon stream answer had no link: ${reply.body.take(200)}" }
                StreamReply.Refused("The music addon answered without a link for this track.", staleMatch = true, worthRetry = true)
            }
            else -> {
                log.w { "Music addon stream answered HTTP ${reply.status}: ${reply.body.take(200)}" }
                val waitable = nebulaStreamWaitable(reply.status, parseNebulaDetail(reply.body), timedOut = false)
                StreamReply.Refused(
                    nebulaFailureReason(reply.status, parseNebulaDetail(reply.body)),
                    staleMatch = reply.status == 404,
                    worthRetry = reply.status >= 500 && !waitable,
                    waitable = waitable,
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
            worthRetry = false,
            // ... but the addon is probably still fetching the file, so it is asked again later (see resolve).
            waitable = timedOut,
        )
    }
}
