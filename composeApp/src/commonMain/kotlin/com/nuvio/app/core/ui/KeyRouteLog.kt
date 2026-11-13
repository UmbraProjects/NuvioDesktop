package com.nuvio.app.core.ui

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
    private var lastKey = "none"
    private var lastKeyAtMs = 0L
    private var handledSinceProblem = 0

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
        val state = "compose focus=${if (composeHasFocus) "held" else "NONE"}, window focus=$windowFocus" +
            (if (typing) ", in a text field" else "")
        when {
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

/** On the window's content root, outside its own key handler: sees keys first, and last when nothing took them. */
fun Modifier.keyRouteOuter(): Modifier = this
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
