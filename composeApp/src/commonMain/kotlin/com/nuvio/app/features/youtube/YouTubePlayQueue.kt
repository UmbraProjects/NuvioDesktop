package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:youtube-mode
 *
 * Plays a YouTube playlist through in order. The app's own playlist mode is built around searching
 * addon sources for a film or an episode; a YouTube video only needs its streams resolved, and the
 * app opens one player at a time, so the queue advances from the tab instead: a video played to its
 * end closes the player as any other does ([onFinished], from PlayerYouTubeWatchTime), and the tab,
 * back on screen, opens the next one ([takeNext]).
 */
internal object YouTubePlayQueue {
    private val log = Logger.withTag("YouTubeQueue")

    data class State(val name: String, val videos: List<YouTubeVideo>, val index: Int) {
        val current: YouTubeVideo? get() = videos.getOrNull(index)
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The playlist being played through, or null. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _next = MutableStateFlow<YouTubeVideo?>(null)

    /** The video the tab should open next, once the one before it finished. */
    val next: StateFlow<YouTubeVideo?> = _next.asStateFlow()

    /** Plays [videos] from [index]; the caller opens that first one itself. */
    fun start(name: String, videos: List<YouTubeVideo>, index: Int) {
        if (index !in videos.indices) return
        _next.value = null
        _state.value = State(name, videos, index)
        log.i { "queue started: ${videos.size} videos from ${index + 1}" }
    }

    fun stop() {
        if (_state.value != null) log.i { "queue stopped" }
        _state.value = null
        _next.value = null
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
