package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.concurrent.Volatile

/**
 * Clicks lost to a list that is still scrolling, for every list in the app.
 *
 * A wheel notch animates its list for a few hundred ms (Compose's own wheel easing, and Home's
 * [smoothVerticalWheelScroll]). While that runs, the list takes the next press on the Initial pass to
 * "catch" the scroll, before the card under the pointer sees it; the card's clickable then ignores
 * the consumed press, and the click does nothing (2026-10: first fixed for stream cards only, in
 * StreamCard.kt). [detectClicksSwallowedByScrolling] sits at the window root, where it sees each
 * event last, and recognises exactly that: a primary mouse press soon after a wheel event that
 * something consumed, released in place with nothing taking the release (a clickable that fires
 * consumes its release). The desktop event queue then replays the click at the same spot once the
 * list has stopped ([takePendingReplay]).
 */
object SwallowedClickRescue {
    /** A press this long after the last wheel event can still land on an easing list. */
    internal const val WheelWindowMs = 1_000L
    private const val MaxClickMs = 700L
    private const val SlopPx = 12f

    @Volatile
    internal var lastWheelAtMs = 0L

    @Volatile
    private var pendingReplay = false

    /** Desktop event queue, after dispatching a primary release: replay this click? */
    fun takePendingReplay(): Boolean {
        val pending = pendingReplay
        pendingReplay = false
        return pending
    }

    internal fun Modifier.detectClicksSwallowedByScrolling(nowMs: () -> Long = { System.currentTimeMillis() }): Modifier =
        pointerInput(Unit) {
            awaitPointerEventScope {
                var trackedId: PointerId? = null
                var downPosition = Offset.Zero
                var downAtMs = 0L
                while (true) {
                    // Main pass at the root: every child has had the event, so consumption is final.
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Scroll -> lastWheelAtMs = nowMs()
                        PointerEventType.Press -> {
                            trackedId = null
                            val now = nowMs()
                            if (change.type == PointerType.Mouse && event.buttons.isPrimaryPressed &&
                                event.changes.size == 1 && change.isConsumed &&
                                now - lastWheelAtMs <= WheelWindowMs
                            ) {
                                trackedId = change.id
                                downPosition = change.position
                                downAtMs = now
                            }
                        }
                        PointerEventType.Move -> {
                            if (trackedId == change.id && (change.position - downPosition).getDistance() > SlopPx) {
                                trackedId = null
                            }
                        }
                        PointerEventType.Release -> {
                            if (trackedId == change.id && !change.isConsumed &&
                                (change.position - downPosition).getDistance() <= SlopPx &&
                                nowMs() - downAtMs <= MaxClickMs &&
                                // A press into a text field is its own; replaying it would make a
                                // double-click (word selection).
                                !TextInputFocusTracker.active.value
                            ) {
                                pendingReplay = true
                            }
                            trackedId = null
                        }
                        else -> Unit
                    }
                }
            }
        }
}

/**
 * How many right-click menus are open. A right-press outside an open menu only closes it: the
 * focusable popup takes the press, so the card under the pointer never saw it and the user had to
 * right-click again. The desktop event queue reads this to re-send that right-click once the old
 * menu has gone, so it opens the new target's menu straight away.
 */
object ContextMenuReopen {
    @Volatile
    var openMenus = 0
        private set

    fun opened() { openMenus++ }

    fun closed() { openMenus = (openMenus - 1).coerceAtLeast(0) }
}
