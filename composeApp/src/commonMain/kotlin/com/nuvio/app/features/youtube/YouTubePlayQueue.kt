package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:youtube-mode
 *
 * Plays a YouTube playlist through in order. The app's own playlist mode is built around searching
 * addon sources for a film or an episode; a YouTube video only needs its streams resolved.
 *
 * The next video is resolved while the current one nears its end ([prepareNext], from
 * PlayerYouTubeWatchTime), and when it ends the app hands the player straight over to it
 * ([handOffNext], from App's playback-completed path), behind the same shield the app's playlists
 * use, without going back to the tab. Should that not happen (the next video would not open), the
 * player closes as before and the tab, back on screen, tries it ([takeNext]).
 */
internal object YouTubePlayQueue {
    private val log = Logger.withTag("YouTubeQueue")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The next video's launch, resolved ahead, keyed by that video's id. */
    private var prepared: Pair<String, Deferred<PlayerLaunch?>>? = null

    /** Whether [videoId] is the video the queue is playing now. */
    fun isPlaying(videoId: String): Boolean = _state.value?.current?.videoId == videoId

    /** The video autoplay was last looked up for, so it is asked once per video. */
    @Volatile
    private var autoplayAskedFor: String? = null

    /**
     * Autoplay ([YouTubeFeedRepository.autoplay]): with no playlist playing, YouTube's next pick after
     * [videoId] is set up as a one-off hand-over, exactly like a related video picked in the player, and
     * resolved ahead; the app then hands over to it at the end as it does at a playlist's.
     */
    private fun prepareAutoplay(videoId: String) {
        if (!YouTubeFeedRepository.autoplay.value || autoplayAskedFor == videoId) return
        autoplayAskedFor = videoId
        scope.launch {
            val pick = runCatching { YouTubeFeedRepository.autoplayPick(videoId) }.getOrNull() ?: return@launch
            // A playlist or a related video picked in the player took over meanwhile: that comes first.
            if (_state.value != null || !YouTubeFeedRepository.autoplay.value) return@launch
            handOverFromPlayer(videoId, pick)
            prepareNext(videoId)
        }
    }

    /** Resolves the video after [videoId] ahead of time, once; its stream URLs outlast any video's end. */
    fun prepareNext(videoId: String) {
        val state = _state.value ?: return prepareAutoplay(videoId)
        if (state.current?.videoId != videoId) return
        val upcoming = state.videos.getOrNull(state.index + 1) ?: return
        if (prepared?.first == upcoming.videoId) return
        log.i { "preparing ${state.index + 2} of ${state.videos.size}" }
        prepared = upcoming.videoId to scope.async { youTubePlayerLaunch(upcoming) }
    }

    /**
     * [videoId] played to its end: moves the queue on and returns the next video's launch for the
     * player to be handed over to, or null when the queue is done (it ends) or the next video would
     * not open (the tab is left to try it, [next]).
     */
    suspend fun handOffNext(videoId: String): PlayerLaunch? {
        val state = _state.value ?: return null
        if (state.current?.videoId != videoId) return null
        val nextIndex = state.index + 1
        val upcoming = state.videos.getOrNull(nextIndex)
        if (upcoming == null) {
            log.i { "queue finished" }
            stop()
            return null
        }
        // Moved on before the old player goes, so its own end report (onFinished) finds nothing to do.
        _state.value = state.copy(index = nextIndex)
        val ready = prepared?.takeIf { it.first == upcoming.videoId }?.second
        prepared = null
        val launch = ready?.await() ?: youTubePlayerLaunch(upcoming)
        if (launch == null) {
            log.i { "next video would not open; leaving it to the tab" }
            _next.value = upcoming
        } else {
            log.i { "handing over to ${nextIndex + 1} of ${state.videos.size}" }
        }
        // A one-off hand-over is done once it is handed over: nothing to play after it.
        if (state.oneOff) _state.value = null
        return launch
    }

    /**
     * [oneOff]: a single hand-over from the player (a related video), not a playlist; no banner, ends after it.
     * [watchQueue]: a play-through of [YouTubeWatchQueue], which grows as videos are added to it.
     */
    data class State(
        val name: String,
        val videos: List<YouTubeVideo>,
        val index: Int,
        val oneOff: Boolean = false,
        val watchQueue: Boolean = false,
    ) {
        val current: YouTubeVideo? get() = videos.getOrNull(index)
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The playlist being played through, or null. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _next = MutableStateFlow<YouTubeVideo?>(null)

    /** The video the tab should open next, once the one before it finished. */
    val next: StateFlow<YouTubeVideo?> = _next.asStateFlow()

    /** Plays [videos] from [index]; the caller opens that first one itself. [watchQueue]: see [State]. */
    fun start(name: String, videos: List<YouTubeVideo>, index: Int, watchQueue: Boolean = false) {
        if (index !in videos.indices) return
        _next.value = null
        _state.value = State(name, videos, index, watchQueue = watchQueue)
        log.i { "queue started: ${videos.size} videos from ${index + 1}" }
    }

    /** A video added to [YouTubeWatchQueue] while it is being played through joins the play-through's end. */
    fun onWatchQueueAdded(video: YouTubeVideo) {
        val state = _state.value?.takeIf { it.watchQueue } ?: return
        if (state.videos.any { it.videoId == video.videoId }) return
        _state.value = state.copy(videos = state.videos + video)
    }

    fun stop() {
        if (_state.value != null) log.i { "queue stopped" }
        _state.value = null
        _next.value = null
        prepared?.second?.cancel()
        prepared = null
    }

    /**
     * The player is to go straight on to [next] (a related video picked in it) from [currentVideoId]:
     * set up as a two-video queue so the app hands the player over exactly as at a playlist's end
     * ([handOffNext]); any playlist being played through ends here.
     */
    fun handOverFromPlayer(currentVideoId: String, next: YouTubeVideo) {
        prepared?.second?.cancel()
        prepared = null
        _next.value = null
        val current = YouTubeVideo(videoId = currentVideoId, title = "", channel = null, details = null, duration = null, thumbnailUrl = null)
        _state.value = State(name = "", videos = listOf(current, next), index = 0, oneOff = true)
        log.i { "handing over to ${next.videoId} next" }
    }

    /** A video opened outside the queue ends it, unless it is the queue's own current one. */
    fun onOpened(videoId: String) {
        if (_state.value?.current?.videoId != videoId) stop()
    }

    /** [videoId] was played to its end. Moves on to the next video, or ends the queue after the last. */
    fun onFinished(videoId: String) {
        val state = _state.value ?: return
        if (state.current?.videoId != videoId) return
        val nextIndex = state.index + 1
        val upcoming = state.videos.getOrNull(nextIndex)
        if (upcoming == null) {
            log.i { "queue finished" }
            stop()
            return
        }
        _state.value = state.copy(index = nextIndex)
        _next.value = upcoming
    }

    /** The video to open now, handed out once. */
    fun takeNext(): YouTubeVideo? = _next.value.also { _next.value = null }
}
