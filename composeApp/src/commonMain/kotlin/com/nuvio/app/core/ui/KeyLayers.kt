package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry

/*
 * The keyboard focus router, step 1 (Docs/plans/optimization-audit-2026-11.md, section 4): a fallback.
 *
 * A key only reaches a screen while something inside it holds Compose focus, and each screen holds on to focus by
 * hand. Every way of losing it that nobody wrote code for (a popup closed with the mouse, a row that reloaded, a click
 * on something that cannot take focus) left the arrows and OK doing nothing until the tab was revisited.
 *
 * Screens now register their root here ([keyLayer]). When an arrow or OK comes back from the window unhandled by
 * anyone (Compose marks a key it handled as consumed, popups included) and the screen on top does not hold focus, the
 * window asks [reclaim] to put focus back on that screen and sends the key again, so the press that found the problem
 * also does what it was meant to. A screen keeps all of its own handling; this only runs when nothing else did.
 */
object KeyLayers {
    private val log = Logger.withTag("KeyRoute")

    class Layer internal constructor(val name: String) {
        internal var active = false
        internal var hasFocus = false
        internal var reseat: () -> Boolean = { false }
    }

    /** In composition order: a screen drawn over another registers after it. UI thread only. */
    private val stack = ArrayList<Layer>()

    /** True while the window sends a key again after [reclaim]; that second pass must not reclaim again. */
    var replaying = false

    /**
     * Bumped when the keyboard may have been left with nowhere to go: a screen came back into view (a panel switch,
     * Back from a page), a panel over a screen closed, a menu closed. The screen on top then takes focus if nothing
     * holds it (see [keyLayer]). Hotkeys only reach the app while something holds focus, so waiting for the next arrow
     * left them dead after every panel switch (2026-11-13).
     */
    internal val settleRequests = androidx.compose.runtime.mutableIntStateOf(0)

    fun settle(why: String) {
        settleReason = why
        settleRequests.intValue++
    }

    internal var settleReason = ""

    internal fun isTop(layer: Layer): Boolean = top() === layer

    /**
     * Set by the window: called when focus has just been settled on a screen. Keys still held down at that moment were
     * pressed for something else (L opening Library from Live TV), so their release must not reach the screen: Home
     * acts on a shortcut's release, and L's toggles Library back to Home (2026-11-13).
     */
    var onFocusSettled: (() -> Unit)? = null

    internal fun add(layer: Layer) { stack += layer }
    internal fun remove(layer: Layer) { stack.remove(layer) }

    private fun top(): Layer? = stack.lastOrNull { it.active }

    /**
     * Called by the window when navigation key [key] went to no one. Moves focus onto the screen on top when focus is
     * not already inside it, and returns that screen's name; null when it did nothing (nothing registered, focus is
     * already there, or the user is typing).
     */
    fun reclaim(key: String): String? {
        val layer = top() ?: return null
        if (layer.hasFocus || TextInputFocusTracker.active.value) return null
        val moved = runCatching { layer.reseat() }.getOrDefault(false)
        log.i {
            if (moved) "$key went to no one on ${PerfTelemetry.visibleScreen}: focus put back on ${layer.name}, key sent again"
            else "$key went to no one on ${PerfTelemetry.visibleScreen}: ${layer.name} would not take focus"
        }
        PerfTelemetry.count(if (moved) "KeyLayers.reclaimed" else "KeyLayers.refused")
        return layer.name.takeIf { moved }
    }
}

/**
 * Registers the screen whose root this modifier sits on, just outside its focus target. [reseat] puts focus back on
 * the screen (its own request, with its own rules); [active] is false while the screen should not take the keyboard
 * (a panel of its own is open over it). A screen covered by another ([LocalScreenCovered]) is never the one on top.
 */
@Composable
fun Modifier.keyLayer(name: String, active: Boolean = true, reseat: () -> Boolean): Modifier {
    val covered = LocalScreenCovered.current
    val layer = remember(name) { KeyLayers.Layer(name) }
    val currentReseat by rememberUpdatedState(reseat)
    val isActive = active && !covered
    SideEffect {
        layer.active = isActive
        layer.reseat = { currentReseat() }
    }
    DisposableEffect(layer) {
        KeyLayers.add(layer)
        onDispose { KeyLayers.remove(layer) }
    }
    // Shown (or shown again), or asked to settle: when this is the screen on top and nothing holds focus, take it, so
    // hotkeys and the first arrow have somewhere to go. Over a few frames: a route's exit animation can still hold
    // focus, and the root may not be attached on the first frame.
    val settleRequest = KeyLayers.settleRequests.intValue
    LaunchedEffect(isActive, settleRequest) {
        if (!isActive) return@LaunchedEffect
        repeat(SETTLE_TRIES) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 32L else 48L)
            if (KeyRouteLog.focusHeld || TextInputFocusTracker.active.value) return@LaunchedEffect
            if (!KeyLayers.isTop(layer)) return@LaunchedEffect
            if (runCatching { layer.reseat() }.getOrDefault(false)) {
                KeyLayers.onFocusSettled?.invoke()
                KeyRouteLog.note("focus settled on ${layer.name} (${if (settleRequest == 0) "shown" else KeyLayers.settleReason})")
                return@LaunchedEffect
            }
        }
    }
    return onFocusChanged { layer.hasFocus = it.hasFocus }
}

private const val SETTLE_TRIES = 10

/**
 * [keyLayer] for a screen whose controls take focus themselves (a grid of posters, a page of buttons) rather than a
 * root that steers a selection: putting focus back returns it to the control that last had it inside the screen
 * ([focusRestorer]), or the first one. Sits on the screen's root and makes it a focus group.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.keyLayer(name: String, active: Boolean = true): Modifier {
    val requester = remember(name) { androidx.compose.ui.focus.FocusRequester() }
    return keyLayer(name, active) { requester.requestFocusLogged() }
        .then(
            Modifier
                .focusRequester(requester)
                .focusRestorer()
                .focusGroup(),
        )
}
