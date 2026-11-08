package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.youtube.YouTubeMiniBox
import com.nuvio.app.features.youtube.miniBoxAfterDrag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop

/*
 * fork:pip-browse
 *
 * Picture in picture that keeps the app usable: from the classic PiP (the whole window shrunk to the
 * video over Windows, Main.kt), its Browse button gives the window back its size with the video in a
 * box in its corner and the app around it, so the person can browse, search and read while it plays
 * on, the way a browser's video does. The box is the native player, an OS window, so it always sits over the app; it is
 * moved and resized from its HUD, its exit button brings the video back to fill the window and its
 * X closes it.
 *
 * Opt-in (Settings > Player > Browse while in picture in picture), and only for the players App.kt
 * hosts outside the navigation graph (see PlayerHostLayer there): a player inside its route would
 * be disposed by the first screen pushed over it. The setting is read when a video opens, so a
 * change takes effect from the next one. A YouTube tab video in the box hands over to the next
 * (its queue, autoplay, a related pick) inside the box; its watch page is not drawn there. The
 * app's playlists (each entry is its own route) keep the classic PiP.
 *
 * While the box is up, hero trailers and theme songs stay off (they would play over the film), the
 * music player stays paused, and the player's keyboard shortcuts stand down so the arrows and Enter
 * drive the app.
 */
internal object PlayerBrowsePip {
    private const val KEY_ENABLED = "browse_enabled"

    private val _enabled = MutableStateFlow(PlayerBrowsePipStorage.load(KEY_ENABLED) == "true")

    /** The setting: PiP leaves the app usable around the video (desktop, built-in player). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        PlayerBrowsePipStorage.save(KEY_ENABLED, if (enabled) "true" else null)
    }

    private val _hostedPlayerAvailable = MutableStateFlow(false)

    /**
     * Set by App.kt while the player on screen is one it hosts outside the navigation graph, so
     * classic PiP shows its Browse button (leave PiP for the corner box over the app).
     */
    val hostedPlayerAvailable: StateFlow<Boolean> = _hostedPlayerAvailable.asStateFlow()

    internal fun setHostedPlayerAvailable(available: Boolean) {
        _hostedPlayerAvailable.value = available
    }

    private val _active = MutableStateFlow(false)

    /** The video is in its corner box and the app is in use around it. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    internal fun setActive(active: Boolean) {
        // The box stays up while one video in it hands over to the next (the YouTube queue, a related
        // pick): the outgoing player's end would otherwise let trailers and music in for a moment.
        if (!active && handoffPending) return
        _active.value = active
        if (!active) interactionMode = 0
    }

    /** A video in the box is handing over to the next, which opens in the box too (App.kt). */
    @kotlin.concurrent.Volatile
    internal var handoffPending = false
        private set

    internal fun beginHandoff() {
        handoffPending = true
    }

    /** The next video is in the box, or the hand-over came to nothing; [stillActive]: the box stays. */
    internal fun endHandoff(stillActive: Boolean) {
        handoffPending = false
        setActive(stillActive)
    }

    private val _enterRequests = MutableStateFlow(0)

    /** Bumped by classic PiP's Browse button: App.kt takes the player's route away, the player staying. */
    val enterRequests: StateFlow<Int> = _enterRequests.asStateFlow()

    /** Classic PiP's Browse button: true when the corner box was asked for (a hosted player is on screen). */
    fun requestEnter(): Boolean {
        if (!_hostedPlayerAvailable.value || _active.value) return false
        popOutPending = false
        _enterRequests.value += 1
        return true
    }

    /** The box's Pop out button wants classic PiP (over Windows) once the video fills the window again. */
    @kotlin.concurrent.Volatile
    private var popOutPending = false

    /** The box's Pop out button: back to filling the window ([requestExpand]), then on into classic PiP. */
    fun requestPopOut() {
        if (!_active.value) return
        popOutPending = true
        requestExpand()
    }

    /** Taken by the player as the box goes: whether it should go on into classic PiP. */
    internal fun consumePopOut(): Boolean {
        val pending = popOutPending
        popOutPending = false
        return pending
    }

    private val _expandRequests = MutableStateFlow(0)

    /** Bumped by the box's exit button (or a double-click on it): the video fills the window again. */
    val expandRequests: StateFlow<Int> = _expandRequests.asStateFlow()

    fun requestExpand() {
        if (_active.value) _expandRequests.value += 1
    }

    private val _closeRequests = MutableStateFlow(0)

    /** Bumped by the box's X: the player closes as its Back would, and the app stays where it is. */
    val closeRequests: StateFlow<Int> = _closeRequests.asStateFlow()

    fun requestClose() {
        if (_active.value) _closeRequests.value += 1
    }

    private val _box = MutableStateFlow(YouTubeMiniBox())

    /** The corner box's size and place, as last moved or resized; kept for the session. */
    val box: StateFlow<YouTubeMiniBox> = _box.asStateFlow()

    /** The window area the box lives in, in dp; set by the layout so a drag stays inside it. */
    @kotlin.concurrent.Volatile
    var frameDp: Pair<Float, Float> = 0f to 0f

    private var interactionMode = 0
    private var interactionStart = YouTubeMiniBox()
    private var interactionOrigin = 0f to 0f

    /** A drag on the box's HUD began: [mode] 1 moves it, 2..9 resize it (PiP's edge numbering). [x], [y]: pointer on screen, dp. */
    fun beginInteraction(mode: Int, x: Float, y: Float) {
        if (mode !in 1..9) return
        interactionMode = mode
        interactionStart = _box.value
        interactionOrigin = x to y
    }

    fun updateInteraction(x: Float, y: Float) {
        val mode = interactionMode
        if (mode == 0) return
        _box.value = miniBoxAfterDrag(
            start = interactionStart,
            mode = mode,
            dx = x - interactionOrigin.first,
            dy = y - interactionOrigin.second,
            frameWidth = frameDp.first,
            frameHeight = frameDp.second,
        )
    }

    fun endInteraction() {
        interactionMode = 0
    }
}

internal expect object PlayerBrowsePipStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}

/**
 * The player's side of the corner box: its X closes the player the way Back does (progress saved
 * first), which App.kt then turns into ending the hosted player rather than popping a route.
 */
@Composable
internal fun PlayerScreenRuntime.BindPlayerBrowsePip() {
    LaunchedEffect(Unit) {
        PlayerBrowsePip.closeRequests.drop(1).collect {
            flushWatchProgress()
            args.onBack()
        }
    }
    // The box's Pop out button: once the video fills the window again, on into classic PiP. A moment's
    // wait so the layer has the video full size before Main.kt shrinks the window to it.
    LaunchedEffect(Unit) {
        var wasActive = PlayerBrowsePip.active.value
        PlayerBrowsePip.active.collect { active ->
            if (wasActive && !active && PlayerBrowsePip.consumePopOut()) {
                kotlinx.coroutines.delay(150)
                pictureInPictureActive = true
                controlsVisible = false
            }
            wasActive = active
        }
    }
}
