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
        if (closing) {
            // Closed from the corner: the next video opened is no hand-over and opens as a fresh one does.
            closing = false
            _browsing.value = false
            endedAtMs = 0L
        }
        if (madeWindowFullscreen) onLeftInFullscreen?.invoke()
    }

    @kotlin.concurrent.Volatile
    private var closing = false

    /**
     * fork:youtube-mini-player - the corner box is being closed (its X, or the tab's settings): the box
     * stays where it is until the player goes, rather than first going back to the full video as
     * [stopBrowsing] would have it, and [end] then leaves the mini player.
     */
    fun closeFromCorner() {
        if (_browsing.value) closing = true
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

    private val _channelRequest = MutableStateFlow<YouTubeChannelRequest?>(null)

    /** fork:youtube-mini-player - a channel for the tab to open, set by the watch page; taken by the tab. */
    val channelRequest: StateFlow<YouTubeChannelRequest?> = _channelRequest.asStateFlow()

    /** The watch page's channel name: the tab around the video in the corner, on that channel's page. */
    fun openChannel(channelId: String, name: String, avatarUrl: String?) {
        if (!_available.value) return
        _channelRequest.value = YouTubeChannelRequest(channelId, name, avatarUrl)
        _browsing.value = true
    }

    fun consumeChannelRequest(request: YouTubeChannelRequest) {
        _channelRequest.compareAndSet(request, null)
    }

    private val _scrollRequests = kotlinx.coroutines.flow.MutableSharedFlow<Float>(extraBufferCapacity = 32)

    /** The wheel turned over the video (it takes the wheel): how far to scroll the page, in dp. */
    val scrollRequests: kotlinx.coroutines.flow.SharedFlow<Float> = _scrollRequests

    fun requestScroll(deltaDp: Float) {
        _scrollRequests.tryEmit(deltaDp)
    }

    private val _miniBox = MutableStateFlow(YouTubeMiniBox())

    /** The corner player's size and place, as last moved or resized; kept for the next video. */
    val miniBox: StateFlow<YouTubeMiniBox> = _miniBox.asStateFlow()

    /** The window area the corner player lives in, in dp; set by the layout so a drag stays inside it. */
    @kotlin.concurrent.Volatile
    var miniFrameDp: Pair<Float, Float> = 0f to 0f

    private var interactionMode = 0
    private var interactionStart: YouTubeMiniBox = YouTubeMiniBox()
    private var interactionOrigin = 0f to 0f

    /**
     * fork:youtube-mini-player - a drag on the corner player's HUD began, the PiP window's gesture moving
     * the box instead of the window: [mode] 1 moves it, 2..9 resize from an edge or corner (PiP's numbering:
     * n, ne, e, se, s, sw, w, nw). [x], [y] are the pointer on screen in dp.
     */
    fun beginMiniInteraction(mode: Int, x: Float, y: Float) {
        if (mode !in 1..9) return
        interactionMode = mode
        interactionStart = _miniBox.value
        interactionOrigin = x to y
    }

    fun updateMiniInteraction(x: Float, y: Float) {
        val mode = interactionMode
        if (mode == 0) return
        _miniBox.value = miniBoxAfterDrag(
            start = interactionStart,
            mode = mode,
            dx = x - interactionOrigin.first,
            dy = y - interactionOrigin.second,
            frameWidth = miniFrameDp.first,
            frameHeight = miniFrameDp.second,
        )
    }

    fun endMiniInteraction() {
        interactionMode = 0
    }

    private val _closeRequests = MutableStateFlow(0)

    /** Bumped by the corner player's X (on the HUD): the layout closes the player, as the tab's X did. */
    val closeRequests: StateFlow<Int> = _closeRequests.asStateFlow()

    fun requestCloseFromCorner() {
        if (_browsing.value) _closeRequests.value += 1
    }

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

internal data class YouTubeChannelRequest(val channelId: String, val name: String, val avatarUrl: String?)

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

/** The corner player: its width and its distance from the window's right and bottom edges, in dp (16:9). */
internal data class YouTubeMiniBox(val widthDp: Float = 420f, val rightDp: Float = 24f, val bottomDp: Float = 24f) {
    val heightDp: Float get() = widthDp * 9f / 16f
}

internal const val YOUTUBE_MINI_MIN_WIDTH_DP = 260f
internal const val YOUTUBE_MINI_MAX_FRACTION = 0.6f

/** [box] kept inside a [frameWidth] x [frameHeight] window (an unknown frame, 0, leaves it as it is). */
internal fun clampMiniBox(box: YouTubeMiniBox, frameWidth: Float, frameHeight: Float): YouTubeMiniBox {
    if (frameWidth <= 0f || frameHeight <= 0f) return box
    val maxWidth = minOf(frameWidth * YOUTUBE_MINI_MAX_FRACTION, frameHeight * 16f / 9f).coerceAtLeast(YOUTUBE_MINI_MIN_WIDTH_DP)
    val width = box.widthDp.coerceIn(YOUTUBE_MINI_MIN_WIDTH_DP, maxWidth)
    val height = width * 9f / 16f
    return YouTubeMiniBox(
        widthDp = width,
        rightDp = box.rightDp.coerceIn(0f, (frameWidth - width).coerceAtLeast(0f)),
        bottomDp = box.bottomDp.coerceIn(0f, (frameHeight - height).coerceAtLeast(0f)),
    )
}

/**
 * The corner player after a drag of [dx], [dy] dp from [start]: [mode] 1 moves it; 2..9 resize it from
 * the edge or corner PiP numbers them by (n, ne, e, se, s, sw, w, nw), keeping 16:9 and the far side still.
 */
internal fun miniBoxAfterDrag(
    start: YouTubeMiniBox,
    mode: Int,
    dx: Float,
    dy: Float,
    frameWidth: Float,
    frameHeight: Float,
): YouTubeMiniBox {
    if (mode == 1) {
        return clampMiniBox(start.copy(rightDp = start.rightDp - dx, bottomDp = start.bottomDp - dy), frameWidth, frameHeight)
    }
    val edge = mode - 1
    val north = edge == 1 || edge == 2 || edge == 8
    val south = edge == 4 || edge == 5 || edge == 6
    val east = edge == 2 || edge == 3 || edge == 4
    val west = edge == 6 || edge == 7 || edge == 8
    // The width each axis asks for; a corner takes the two halfway.
    val fromX = when {
        east -> dx
        west -> -dx
        else -> null
    }
    val fromY = when {
        south -> dy * 16f / 9f
        north -> -dy * 16f / 9f
        else -> null
    }
    val grow = when {
        fromX != null && fromY != null -> (fromX + fromY) / 2f
        else -> fromX ?: fromY ?: 0f
    }
    val sized = clampMiniBox(start.copy(widthDp = start.widthDp + grow), frameWidth, frameHeight)
    val grown = sized.widthDp - start.widthDp
    // East and south grow away from the right and bottom edges, so the left and top stay put.
    val moved = start.copy(
        widthDp = sized.widthDp,
        rightDp = if (east) start.rightDp - grown else start.rightDp,
        bottomDp = if (south) start.bottomDp - grown * 9f / 16f else start.bottomDp,
    )
    return clampMiniBox(moved, frameWidth, frameHeight)
}
