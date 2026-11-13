package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry

/*
 * Logging only (Docs/plans/optimization-audit-2026-11.md, section 4, step 0): where each navigation key went.
 *
 * A key only reaches a screen's handler while something inside that screen holds Compose focus, and focus is handed
 * about by each screen on its own, so "the arrows do nothing until I revisit the tab" left no trace anywhere. This
 * follows every navigation key press (a pad's D-pad arrives as these) through three points and writes one line:
 *
 *   handled         a screen's own handler took it (Debug)
 *   window took it  no screen did; the window's shortcut handler did (Info)
 *   UNHANDLED       it went through Compose and nothing took it (Warn)
 *   NOT DELIVERED   it never reached Compose at all, which is what nothing-is-focused looks like (Warn)
 *
 * with the screen, whether anything in the window held Compose focus, and what the window's own focus was on. It
 * also logs focus being dropped to nothing, and every focus request that failed. All calls are on the UI thread.
 */
object KeyRouteLog {
    private val log = Logger.withTag("KeyRoute")

    private var pendingKey: String? = null
    private var reachedCompose = false
    private var passedScreens = false
    private var passedWindow = false

    private var composeHasFocus = false

    /** Whether anything in the window's content holds Compose focus (a popup's or dialog's own focus aside). */
    val focusHeld: Boolean get() = composeHasFocus
    private var lastKey = "none"
    private var lastKeyAtMs = 0L
    private var handledSinceProblem = 0

    /** Popups and dialogs that are open: they hold the keyboard in a layer of their own (see [KeyRouteLayer]). */
    private val layers = ArrayList<String>()

    /** The open ones among [layers] that are menus: the focus router's fallback stays out of those. */
    private val menus = ArrayList<String>()

    /** Reads where the focused control is in the window, set by [keyRouteOuter]; the only name a control has. */
    internal var focusPlace: (() -> String?)? = null

    /** A menu that said so ([KeyRouteLayer]) is open. Dialogs do not count: they are a [keyLayer] of their own. */
    val popupOpen: Boolean get() = menus.isNotEmpty()

    internal fun layerOpened(name: String, menu: Boolean) {
        layers += name
        if (menu) menus += name
    }

    internal fun layerClosed(name: String, menu: Boolean) {
        layers.remove(name)
        if (menu) menus.remove(name)
        // A menu hands focus to nobody when it closes (picked with the mouse): the screen under it takes it back.
        if (menu) KeyLayers.settle("a menu closed")
    }

    /** A navigation key went down, before anything has handled it. The caller follows with [settle], posted. */
    fun keyPressed(name: String) {
        if (pendingKey != null) settle("(next key arrived first)")
        pendingKey = name
        reachedCompose = false
        passedScreens = false
        passedWindow = false
    }

    /** The key from [keyPressed] has been through its handlers; [windowFocus] is what the window says holds focus. */
    fun settle(windowFocus: String) {
        val key = pendingKey ?: return
        pendingKey = null
        lastKey = key
        lastKeyAtMs = System.currentTimeMillis()
        val screen = PerfTelemetry.visibleScreen
        val typing = TextInputFocusTracker.active.value
        val place = runCatching { focusPlace?.invoke() }.getOrNull()
        val state = "compose focus=${if (composeHasFocus) "held" else "NONE"}" +
            (place?.let { ", on the control at $it" } ?: "") + ", window focus=$windowFocus" +
            (if (typing) ", in a text field" else "")
        val layer = layers.lastOrNull()
        when {
            // A popup or dialog is a layer of its own: its keys never pass the window's root, and that is expected.
            !reachedCompose && layer != null -> log.d { "$key went to the open $layer on $screen" }
            !reachedCompose -> {
                log.w { "$key NOT DELIVERED on $screen: $state (after $handledSinceProblem handled)" }
                handledSinceProblem = 0
            }
            !passedScreens -> {
                handledSinceProblem++
                log.d { "$key handled on $screen" }
            }
            !passedWindow -> log.i { "$key on $screen: no screen took it, the window did; $state" }
            typing -> log.d { "$key on $screen left to the text field" }
            else -> {
                log.w { "$key UNHANDLED on $screen: $state (after $handledSinceProblem handled)" }
                handledSinceProblem = 0
            }
        }
    }

    internal fun sawPreview() { if (pendingKey != null) reachedCompose = true }
    internal fun leftByScreens() { if (pendingKey != null) passedScreens = true }
    internal fun leftByWindow() { if (pendingKey != null) passedWindow = true }

    internal fun composeFocus(held: Boolean) {
        if (held == composeHasFocus) return
        composeHasFocus = held
        val sinceKey = if (lastKeyAtMs == 0L) "no key yet" else "${System.currentTimeMillis() - lastKeyAtMs}ms after $lastKey"
        if (held) log.d { "focus held again on ${PerfTelemetry.visibleScreen}" }
        else log.i { "focus dropped to nothing on ${PerfTelemetry.visibleScreen} ($sinceKey)" }
    }

    /** Something that takes focus away on purpose says so, to tell those drops from the unexplained ones. */
    fun note(what: String) = log.i { "$what on ${PerfTelemetry.visibleScreen}" }

    internal fun focusRequestFailed(error: Throwable?) {
        val caller = Throwable().stackTrace.firstOrNull {
            it.className.startsWith("com.nuvio.") && !it.className.contains("KeyRouteLog")
        }
        val where = caller?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        val why = error?.message?.take(80) ?: "refused"
        log.i { "focus request failed at $where on ${PerfTelemetry.visibleScreen}: $why" }
    }
}

/**
 * [FocusRequester.requestFocus] that never throws and says whether focus moved. A failure (the node is not attached
 * yet, or the request was refused) goes to the KeyRoute log with the caller, where a bare `runCatching` hid it.
 */
fun FocusRequester.requestFocusLogged(): Boolean {
    val result = runCatching { requestFocus() }
    val moved = result.getOrDefault(false)
    if (!moved) KeyRouteLog.focusRequestFailed(result.exceptionOrNull())
    return moved
}

/**
 * Marks a popup or dialog as open while it is composed, so its keys are not logged as lost. Call inside its content.
 * [menu]: the focus router's fallback leaves the keyboard alone while it is open; false for a dialog, which is a
 * [keyLayer] of its own and gets focus back when it loses it.
 */
@Composable
fun KeyRouteLayer(name: String, menu: Boolean = true) {
    DisposableEffect(name, menu) {
        KeyRouteLog.layerOpened(name, menu)
        onDispose { KeyRouteLog.layerClosed(name, menu) }
    }
}

/** On the window's content root, outside its own key handler: sees keys first, and last when nothing took them. */
@Composable
fun Modifier.keyRouteOuter(): Modifier {
    val focusManager = LocalFocusManager.current
    SideEffect {
        KeyRouteLog.focusPlace = {
            focusManager.focusedControl()?.takeIf { it.node.isAttached }?.requireLayoutCoordinates()
                ?.takeIf { it.isAttached }?.boundsInWindow()
                ?.let { "${it.left.toInt()},${it.top.toInt()} ${it.width.toInt()}x${it.height.toInt()}" }
        }
    }
    return keyRouteOuterKeys()
}

private fun Modifier.keyRouteOuterKeys(): Modifier = this
    .onFocusChanged { KeyRouteLog.composeFocus(it.hasFocus) }
    .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) KeyRouteLog.sawPreview()
        false
    }
    .onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) KeyRouteLog.leftByWindow()
        false
    }

/** On the window's content root, inside its own key handler: reached only when no screen took the key. */
fun Modifier.keyRouteInner(): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) KeyRouteLog.leftByScreens()
    false
}
