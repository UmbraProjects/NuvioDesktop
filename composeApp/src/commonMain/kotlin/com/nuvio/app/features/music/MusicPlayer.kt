package com.nuvio.app.features.music

import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Turns an album track into something playable. The desktop build supplies [MusicResolver]. */
typealias MusicTrackResolver = suspend (track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode) -> MusicResolution

/** Everything the surface and the mini-player draw from. [track] is null when nothing is loaded. */
/** The cover to show for what is playing: the track's own (a playlist mixes albums), else its album's. */
internal val MusicPlayerState.coverUrl: String? get() = track?.coverUrl ?: album?.thumbnailUrl

/** The album the playing track is from: the track's own (a playlist mixes albums), else the queue's album. */
internal val MusicPlayerState.albumTitle: String? get() = track?.albumTitle ?: album?.title

data class MusicPlayerState(
    val album: MusicAlbum? = null,
    val track: MusicTrack? = null,
    /** Index of [track] in [MusicAlbum.tracks]. */
    val trackIndex: Int = -1,
    val mode: MusicPlayMode = MusicPlayMode.Audio,
    val shuffled: Boolean = false,
    /** What the surface should open. The head of a fallback chain; see [MusicPlayer.onSurfaceError]. */
    val source: TrailerPlaybackSource? = null,
    /** Where the surface starts [source]: 0 for a new track, the song's position after a mode switch. */
    val startPositionMs: Long = 0L,
    /** Changes whenever the surface has to open something new, so it can key its attach on it. */
    val loadId: Long = 0L,
    /** True from the moment a track is chosen until the surface reports it is playing. */
    val isLoading: Boolean = false,
    /** What the person asked for (play or pause), not what mpv is doing this instant. */
    val playWhenReady: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 100,
    val hasNext: Boolean = false,
    /** Video mode with no official video for [track]: audio plays and the lyric view is the picture. */
    val lyricVideo: Boolean = false,
    /**
     * fork:music - switched from Video to Audio mid-song: the video's stream plays on with its picture hidden instead
     * of reloading the song, and the next song loads as audio. Switching back to Video on this song is then instant.
     */
    val videoCutPlaying: Boolean = false,
    /** A short line for the bar ("No video for this track"). Cleared by the next track change. */
    val notice: String? = null,
) {
    val isActive: Boolean get() = track != null
    val isPlaying: Boolean get() = isActive && playWhenReady && !isLoading
}

private const val RESTART_THRESHOLD_MS = 3_000L

/** How long the volume must stay put before it is saved. */
private const val VOLUME_SAVE_DELAY_MS = 600L

/** A stream that ended having played less than this share of the track was cut off, not finished. */
private const val CUT_SHORT_PERCENT = 60

/** How long a resolved stream is trusted. YouTube URLs live for hours; this stays well inside that. */
private const val PLAYABLE_TTL_MS = 20 * 60_000L
private const val NO_VIDEO_TTL_MS = 30 * 60_000L
private const val FAILED_TTL_MS = 60_000L

/** How many upcoming tracks are tried to find one that plays, when pre-resolving. */
private const val PREFETCH_TRIES = 3

/**
 * The album player, owned at the app root. It holds a [MusicQueue], starts tracks, moves on when one
 * ends, resolves the track after the current one while it plays so the gap is short, and switches
 * between audio and video on the same song at the same position.
 *
 * It never touches mpv. It publishes [state]; the surface reads [MusicPlayerState.source] and reports
 * back through [onSnapshot], [onSurfaceEnded] and [onSurfaceError].
 *
 * Everything it launches goes through [scope], so a caller that wants deterministic behaviour (tests)
 * passes an unconfined scope. Only the latest request (play, next, previous, switch) is honoured: a
 * newer one cancels the one still resolving.
 */
class MusicPlayer(
    private val scope: CoroutineScope,
    private val resolver: MusicTrackResolver,
    private val now: () -> Long = { LibraryClock.nowEpochMs() },
    /** The level the last session ended on, so a restart doesn't come back at full volume. */
    initialVolume: Int = 100,
    /** Called (debounced) when the person changes the volume, to keep it for the next session. */
    private val saveVolume: (Int) -> Unit = {},
) {
    private val _state = MutableStateFlow(MusicPlayerState(volume = initialVolume.coerceIn(0, 100)))
    private var volumeSave: Job? = null
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private var queue: MusicQueue? = null

    /** Albums [playAlbums] queued behind the one playing. Replaced, never edited, so a reader never sees it half changed. */
    @Volatile
    private var following: List<MusicAlbum> = emptyList()
    private var request: Job? = null
    private var prefetch: Job? = null
    private var loadCounter = 0L

    private class Cached(val result: Deferred<MusicResolution>, val createdAtMs: Long)

    private val cache = HashMap<String, Cached>()

    // region requests

    /**
     * Plays [album] from [trackIndex]. Tracks that cannot play are skipped; if none can, nothing starts.
     * [startPositionMs] opens the track part way in (the ladder's audition starts at the chorus); it applies only to
     * the track that was asked for, never to the one that plays instead when that one cannot.
     */
    fun play(
        album: MusicAlbum,
        trackIndex: Int = 0,
        mode: MusicPlayMode = _state.value.mode,
        shuffled: Boolean = false,
        seed: Long = now(),
        startPositionMs: Long = 0L,
    ) {
        if (album.tracks.isEmpty()) return
        following = emptyList()
        launchAlbum(album, trackIndex, mode, shuffled, seed, startPositionMs)
    }

    private fun launchAlbum(
        album: MusicAlbum,
        trackIndex: Int,
        mode: MusicPlayMode,
        shuffled: Boolean,
        seed: Long,
        startPositionMs: Long = 0L,
    ) {
        launchRequest {
            val requested = trackIndex.coerceIn(0, album.tracks.size - 1)
            val fresh = MusicQueue(album, mode, shuffled, seed, requested)
            _state.update { it.copy(isLoading = true, playWhenReady = true, notice = null) }
            val found = fresh.jumpTo(fresh.currentIndex ?: 0) { track -> resolve(track, album, fresh.mode) }
            if (found == null) {
                // Nothing in the album opened. Leave whatever was playing before alone if it still is.
                _state.update {
                    if (queue == null) MusicPlayerState(volume = it.volume, notice = "Couldn't play this album")
                    else it.copy(isLoading = false, notice = "Couldn't play this album")
                }
                return@launchRequest
            }
            queue = fresh
            val start = if (found.index == requested) startPositionMs.coerceAtLeast(0L) else 0L
            show(fresh, found, startPositionMs = start, skipped = found.skipped.size)
        }
    }

    /**
     * Plays [albums] one after the other, each from its first track (in its own shuffled order when
     * [shuffled]). Albums with no tracks are left out. Skipping past the last track of one album starts
     * the next; Previous stays inside the album that is playing.
     */
    fun playAlbums(albums: List<MusicAlbum>, shuffled: Boolean = false) {
        val playable = albums.filter { it.tracks.isNotEmpty() }
        val first = playable.firstOrNull() ?: return
        // Queued before the first album starts, so the bar's Next is already on for a one-track album.
        following = playable.drop(1)
        launchAlbum(first, trackIndex = 0, mode = _state.value.mode, shuffled = shuffled, seed = now())
    }

    fun next() {
        val q = queue ?: return
        launchRequest {
            _state.update { it.copy(isLoading = true) }
            advanceFrom(q)
        }
    }

    /** Restarts the song when it has been playing a few seconds, otherwise goes to the one before. */
    fun previous() {
        val q = queue ?: return
        if (_state.value.positionMs > RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        launchRequest {
            _state.update { it.copy(isLoading = true) }
            val album = q.album
            val found = q.retreat { track -> resolve(track, album, q.mode) }
            if (found == null) {
                _state.update { it.copy(isLoading = false) }
                seekTo(0L)
                return@launchRequest
            }
            show(q, found, startPositionMs = 0L, skipped = found.skipped.size)
        }
    }

    /** Jumps to the album track at [trackIndex] (a tap in the track list). */
    fun jumpTo(trackIndex: Int) {
        val q = queue ?: return
        launchRequest {
            _state.update { it.copy(isLoading = true, playWhenReady = true) }
            val album = q.album
            val found = q.jumpTo(trackIndex) { track -> resolve(track, album, q.mode) }
            if (found == null) {
                _state.update { it.copy(isLoading = false, notice = "Couldn't play that track") }
                return@launchRequest
            }
            show(q, found, startPositionMs = 0L, skipped = found.skipped.size)
        }
    }

    /**
     * Plays the same song in [newMode] from where it is now. When the other mode has nothing for this
     * track the current one carries on and [MusicPlayerState.notice] says why.
     */
    fun switchMode(newMode: MusicPlayMode) {
        val q = queue ?: return
        if (q.mode == newMode) return
        val now = _state.value
        // Video to Audio on a real video: keep its stream playing and only hide the picture (no reload, no gap); the
        // next song is resolved as audio. Back to Video while that stream still plays: show the picture again.
        if (newMode == MusicPlayMode.Audio && now.mode == MusicPlayMode.Video && !now.lyricVideo) {
            q.setMode(MusicPlayMode.Audio)
            _state.update { it.copy(mode = MusicPlayMode.Audio, videoCutPlaying = true, notice = null) }
            prefetchNext(q)
            return
        }
        if (newMode == MusicPlayMode.Video && now.videoCutPlaying) {
            q.setMode(MusicPlayMode.Video)
            _state.update { it.copy(mode = MusicPlayMode.Video, videoCutPlaying = false, notice = null) }
            prefetchNext(q)
            return
        }
        val positionAtRequest = now.positionMs
        launchRequest {
            val album = q.album
            when (val result = q.switchMode(newMode, positionAtRequest) { track, mode -> resolve(track, album, mode) }) {
                is MusicModeSwitch.Switched -> {
                    _state.update {
                        it.copy(
                            mode = newMode,
                            source = result.playable.source,
                            startPositionMs = result.startPositionMs,
                            loadId = ++loadCounter,
                            isLoading = true,
                            lyricVideo = result.playable.lyricVideo,
                            videoCutPlaying = false,
                            notice = if (result.playable.lyricVideo) "No official video, showing lyrics" else null,
                        )
                    }
                    prefetchNext(q)
                }
                MusicModeSwitch.Unavailable -> _state.update {
                    it.copy(
                        notice = if (newMode == MusicPlayMode.Video) "No video for this track" else "Couldn't switch to audio",
                    )
                }
            }
        }
    }

    fun setShuffled(on: Boolean) {
        val q = queue ?: return
        q.setShuffled(on)
        _state.update { it.copy(shuffled = on, hasNext = q.upNext.isNotEmpty() || following.isNotEmpty()) }
        prefetchNext(q)
    }

    fun pause() {
        _state.update { it.copy(playWhenReady = false) }
    }

    fun resume() {
        _state.update { if (it.isActive) it.copy(playWhenReady = true) else it }
    }

    fun togglePlayback() {
        if (_state.value.playWhenReady) pause() else resume()
    }

    /** Asks the surface to seek; the surface hears it through [seekRequests]. */
    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, _state.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        _state.update { it.copy(positionMs = target) }
        _seekRequests.value = SeekRequest(++seekCounter, target)
    }

    fun setVolume(volume: Int) {
        val level = volume.coerceIn(0, 100)
        _state.update { it.copy(volume = level) }
        // A slider drag is many steps; only where it settles is written.
        volumeSave?.cancel()
        volumeSave = scope.launch {
            delay(VOLUME_SAVE_DELAY_MS)
            saveVolume(level)
        }
    }

    /**
     * The whole queue in playing order (the album or playlist, then any albums queued after it) and the index of the
     * track playing in it, for the lyric view's list. Tracks after the album's own are [MusicQueueEntry.inAlbum] false:
     * they cannot be jumped to, Next reaches them.
     */
    fun queueView(): Pair<List<MusicQueueEntry>, Int> {
        val q = queue ?: return emptyList<MusicQueueEntry>() to -1
        val own = q.ordered.map { t -> MusicQueueEntry(t, q.album.tracks.indexOfFirst { it.videoId == t.videoId }) }
        val later = following.flatMap { it.tracks }.map { MusicQueueEntry(it, -1) }
        return (own + later) to q.orderedPosition
    }

    /** The next [limit] tracks in the queue that can be played, then the queued albums', for the Up next cards. */
    fun upNext(limit: Int): List<MusicTrack> =
        (queue?.upNext.orEmpty() + following.flatMap { it.tracks }).filter { it.isAvailable }.take(limit)

    /**
     * Puts [album] after everything queued, or plays it when nothing is playing. Returns true when it was queued
     * (false when it started, or has no tracks).
     */
    fun enqueue(album: MusicAlbum): Boolean {
        if (album.tracks.isEmpty()) return false
        val q = queue
        if (q == null) {
            play(album)
            return false
        }
        following = following + album
        _state.update { it.copy(hasNext = true) }
        return true
    }

    /** Stops playback and clears the queue. The mini-player goes away. */
    fun stop() {
        request?.cancel()
        prefetch?.cancel()
        queue = null
        following = emptyList()
        cache.clear()
        _state.update { MusicPlayerState(volume = it.volume) }
    }

    // endregion

    // region surface callbacks

    data class SeekRequest(val id: Long, val positionMs: Long)

    private var seekCounter = 0L

    private val _seekRequests = MutableStateFlow<SeekRequest?>(null)

    /** The surface applies each new request once, by [SeekRequest.id]. */
    val seekRequests: StateFlow<SeekRequest?> = _seekRequests.asStateFlow()

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot) {
        _state.update { current ->
            if (!current.isActive) return@update current
            current.copy(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs.takeIf { it > 0L } ?: current.durationMs,
                // Loading ends once mpv is playing, or once the stream is open while paused on request.
                isLoading = current.isLoading && !snapshot.isPlaying && (current.playWhenReady || snapshot.isLoading),
            )
        }
    }

    /** The track reached its end, or the stream stopped serving partway. */
    fun onSurfaceEnded() {
        val current = _state.value
        val q = queue ?: return
        val track = current.track ?: return
        val fallback = current.source?.fallback
        val expectedMs = track.durationSeconds * 1000L
        if (fallback != null && expectedMs > 0L && current.positionMs * 100 < expectedMs * CUT_SHORT_PERCENT) {
            // Ended well short of the song: carry on from the same spot on the other stream.
            _state.update {
                it.copy(source = fallback, startPositionMs = it.positionMs, loadId = ++loadCounter, isLoading = true)
            }
            return
        }
        launchRequest {
            _state.update { it.copy(isLoading = true) }
            advanceFrom(q)
        }
    }

    /** The surface could not open [MusicPlayerState.source]: try its fallback, else skip the track. */
    fun onSurfaceError() {
        val current = _state.value
        val q = queue ?: return
        // YouTube's chain is separate streams -> HLS manifest -> progressive. The HLS rung is passed over when there is a
        // rung after it: it opens every variant playlist before playing (about 8s on its own) and never got going in the
        // music player (every try on 2026-10-30 stalled the full 20s), so it only delayed the progressive stream.
        val fallback = current.source?.fallback?.let { next ->
            next.fallback?.takeIf { "/manifest/" in next.videoUrl } ?: next
        }
        if (fallback != null) {
            _state.update {
                it.copy(source = fallback, startPositionMs = it.positionMs, loadId = ++loadCounter, isLoading = true)
            }
            return
        }
        val failed = current.track ?: return
        // Don't hand the same dead stream back to the next attempt.
        cache.remove(cacheKey(failed, q.mode))
        launchRequest {
            _state.update { it.copy(isLoading = true) }
            advanceFrom(q, notice = "Skipped \"${failed.title}\"")
        }
    }

    // endregion

    // region internals

    private fun launchRequest(block: suspend () -> Unit) {
        request?.cancel()
        request = scope.launch { block() }
    }

    private suspend fun advanceFrom(q: MusicQueue, notice: String? = null) {
        val album = q.album
        val found = q.advance { track -> resolve(track, album, q.mode) }
        if (found == null) {
            startFollowingAlbum(q, notice)
            return
        }
        show(q, found, startPositionMs = 0L, skipped = found.skipped.size, notice = notice)
    }

    /** The album is over: carries on with the next one [playAlbums] queued that has a track that plays, else stops. */
    private suspend fun startFollowingAlbum(q: MusicQueue, notice: String?) {
        while (true) {
            val next = following.firstOrNull() ?: break
            following = following.drop(1)
            val fresh = MusicQueue(next, q.mode, q.shuffled, seed = now(), startIndex = 0)
            val found = fresh.jumpTo(fresh.currentIndex ?: 0) { track -> resolve(track, next, fresh.mode) } ?: continue
            queue = fresh
            show(fresh, found, startPositionMs = 0L, skipped = found.skipped.size, notice = notice)
            return
        }
        // End of the last album. Nothing else to play, so the bar goes away.
        stop()
    }

    private fun show(q: MusicQueue, found: NextPlayable, startPositionMs: Long, skipped: Int, notice: String? = null) {
        val track = found.playable.track
        _state.update {
            it.copy(
                album = q.album,
                track = track,
                trackIndex = found.index,
                mode = q.mode,
                shuffled = q.shuffled,
                source = found.playable.source,
                startPositionMs = startPositionMs,
                loadId = ++loadCounter,
                isLoading = true,
                lyricVideo = found.playable.lyricVideo,
                videoCutPlaying = false,
                positionMs = startPositionMs,
                durationMs = track.durationSeconds * 1000L,
                hasNext = q.upNext.isNotEmpty() || following.isNotEmpty(),
                notice = notice ?: if (skipped > 0) "Skipped $skipped track${if (skipped == 1) "" else "s"} that couldn't play" else null,
            )
        }
        prefetchNext(q)
    }

    private fun cacheKey(track: MusicTrack, mode: MusicPlayMode) = "${track.videoId}|$mode"

    /**
     * Resolves [track] once however many callers ask: the prefetch started it, and [MusicQueue] asks
     * for it again when the song ends. A caller that is cancelled leaves the lookup running.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun resolve(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution {
        val key = cacheKey(track, mode)
        val at = now()
        val hit = cache[key]?.takeIf { entry ->
            val done = entry.result.isCompleted
            val age = at - entry.createdAtMs
            if (!done) {
                true
            } else {
                when (entry.result.getCompleted()) {
                    is MusicResolution.Playable -> age < PLAYABLE_TTL_MS
                    is MusicResolution.NoVideo -> age < NO_VIDEO_TTL_MS
                    is MusicResolution.Failed -> age < FAILED_TTL_MS
                }
            }
        }
        val entry = hit ?: Cached(
            scope.async {
                try {
                    resolver(track, album, mode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    MusicResolution.Failed(track, error.message ?: "lookup failed")
                }
            },
            at,
        ).also { cache[key] = it }
        return entry.result.await()
    }

    /** Starts resolving the track that will follow the current one, and the one after it if that fails. */
    private fun prefetchNext(q: MusicQueue) {
        prefetch?.cancel()
        val upcoming = q.upNext.filter { it.isAvailable }.take(PREFETCH_TRIES)
        if (upcoming.isEmpty()) return
        val album = q.album
        val mode = q.mode
        prefetch = scope.launch {
            for (track in upcoming) {
                if (resolve(track, album, mode) is MusicResolution.Playable) break
            }
        }
    }

    // endregion
}

/** One row of [MusicPlayer.queueView]: the track and its index in the playing album, or -1 for a later album's track. */
data class MusicQueueEntry(val track: MusicTrack, val albumIndex: Int) {
    val inAlbum: Boolean get() = albumIndex >= 0
}
