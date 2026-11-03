package com.nuvio.app.features.youtube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:youtube-watch-page
 *
 * Whether a YouTube tab video shows as a watch page (the video in a box, related videos, the
 * description and comments around it) or fills the window as other videos do. Opened as Settings >
 * YouTube account > Open videos says; the player's fullscreen button then goes from the page to the
 * video filling the screen and back, as on the website, and Escape comes back too.
 *
 * Shared between the player screen (which lays it out) and the desktop player's fullscreen button and
 * Escape key (which change it).
 */
internal object YouTubeWatchLayout {
    private val _available = MutableStateFlow(false)

    /** A YouTube tab video is in the player, so there is a page to show. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _windowed = MutableStateFlow(false)

    /** The page is shown with the video in its box; false while the video fills the window. */
    val windowed: StateFlow<Boolean> = _windowed.asStateFlow()

    /** The video went to fill the screen from the page: its fullscreen button and Escape go back to it. */
    @kotlin.concurrent.Volatile
    var fullFromPage = false
        private set

    /** ...and doing so made the window fullscreen, so going back makes it windowed again. */
    @kotlin.concurrent.Volatile
    var madeWindowFullscreen = false
        private set

    @kotlin.concurrent.Volatile
    private var endedAtMs = 0L

    /** A YouTube video opened. The next one in a hand-over (a related pick, autoplay, a playlist) keeps the layout it left. */
    fun begin() {
        val handOver = com.nuvio.app.features.streams.epochMs() - endedAtMs < HAND_OVER_MS
        _available.value = true
        if (!handOver) {
            _browsing.value = false
            _windowed.value = YouTubeFeedRepository.openWindowed.value
            fullFromPage = false
            madeWindowFullscreen = false
        }
    }

    /**
     * Told when a player that made the window fullscreen ([goFull]) goes, so the window can be made windowed
     * again once it is clear no hand-over follows. Set by the desktop player.
     */
    @kotlin.concurrent.Volatile
    var onLeftInFullscreen: (() -> Unit)? = null

    /** The player went. */
    fun end() {
        _available.value = false
        endedAtMs = com.nuvio.app.features.streams.epochMs()
        if (madeWindowFullscreen) onLeftInFullscreen?.invoke()
    }

    /** From the page to the video filling the screen; [madeFullscreen]: the window was made fullscreen for it. */
    fun goFull(madeFullscreen: Boolean) {
        fullFromPage = true
        madeWindowFullscreen = madeFullscreen
        _windowed.value = false
    }

    /** Back to the page. Returns whether the window should leave the fullscreen [goFull] put it in. */
    fun backToPage(): Boolean {
        val undo = madeWindowFullscreen
        fullFromPage = false
        madeWindowFullscreen = false
        _windowed.value = true
        return undo
    }

    private val _mini = MutableStateFlow(false)

    /** The page has been scrolled from the top: the video sits in its corner box, controls hidden. */
    val mini: StateFlow<Boolean> = _mini.asStateFlow()

    fun setMini(mini: Boolean) {
        _mini.value = mini
    }

    private val _backToVideo = MutableStateFlow(0)

    /** Bumped when the corner player is clicked: the page scrolls back to the top, the video back to its place. */
    val backToVideo: StateFlow<Int> = _backToVideo.asStateFlow()

    fun requestBackToVideo() {
        if (_browsing.value) {
            _browsing.value = false
            return
        }
        _backToVideo.value += 1
    }

    private val _browsing = MutableStateFlow(false)

    /**
     * The mini player: the YouTube tab fills the window around the video, which keeps playing in the
     * corner box, so the person can search, browse, queue and pick playlists meanwhile. The player route
     * stays where it is (leaving it would end playback); the tab is drawn inside it ([YouTubeMiniPlayerHost]).
     * Kept through a hand-over, so the queue's next video carries on in the box.
     */
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    /** Back from a YouTube tab video: to the tab, the video carrying on in the corner. */
    fun startBrowsing() {
        if (_available.value) _browsing.value = true
    }

    /** From the tab back to the video, laid out as it was (the page, or filling the window). */
    fun stopBrowsing() {
        _browsing.value = false
    }

    private val _scrollRequests = kotlinx.coroutines.flow.MutableSharedFlow<Float>(extraBufferCapacity = 32)

    /** The wheel turned over the video (it takes the wheel): how far to scroll the page, in dp. */
    val scrollRequests: kotlinx.coroutines.flow.SharedFlow<Float> = _scrollRequests

    fun requestScroll(deltaDp: Float) {
        _scrollRequests.tryEmit(deltaDp)
    }

    /** The corner player's width in dp, as last resized; kept for the next video. */
    @kotlin.concurrent.Volatile
    var miniWidthDp = 420f

    /** The page is on screen around the video (not filling the window). */
    val pageShown: Boolean get() = _available.value && (_windowed.value || _browsing.value)

    /**
     * Shows the cursor the player hid. The player's controls hide it after a while without movement, but
     * on the page they only see movement over the video; moving over the page brings it back. Set by the
     * desktop player while it has a cursor to show.
     */
    @kotlin.concurrent.Volatile
    var revealCursor: (() -> Unit)? = null

    /** Opening the next video right after one ends counts as a hand-over. */
    private const val HAND_OVER_MS = 5_000L
}

/** The page's fullscreen button: the video fills the screen until its button or Escape brings the page back. */
internal expect fun requestYouTubeFullscreen()

/*
 * fork:youtube-mini-player
 *
 * The YouTube tab as the mini player draws it around the video ([YouTubeWatchLayout.browsing]). The app
 * sets it from the player route, which owns what the tab's actions do there (a video picked hands the
 * player over to it; settings close the player first) and keeps the tab's place between videos.
 */
internal object YouTubeMiniPlayerHost {
    var page: (@androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit)? by
        androidx.compose.runtime.mutableStateOf(null)
}
