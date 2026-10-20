package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

/**
 * Whether the mouse is in use over a list: moved, scrolled or pressed within [STREAM_LIST_POINTER_HOLD_MS],
 * or a button is down. Plain fields, not state: they change on every mouse move and nothing should
 * recompose for that.
 */
internal class ListPointerActivity {
    @Volatile
    var lastActiveAtMs = Long.MIN_VALUE / 2

    @Volatile
    var pressed = false

    fun isActive(nowMs: Long, holdMs: Long): Boolean = pressed || nowMs - lastActiveAtMs < holdMs
}

/** Records pointer activity over this node without consuming anything. */
internal fun Modifier.trackListPointerActivity(
    activity: ListPointerActivity,
    nowMs: () -> Long = { System.currentTimeMillis() },
): Modifier = pointerInput(activity) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Exit) {
                activity.lastActiveAtMs = Long.MIN_VALUE / 2
                activity.pressed = false
            } else {
                activity.lastActiveAtMs = nowMs()
                activity.pressed = event.changes.any { it.pressed }
            }
        }
    }
}

// Longer than the pause before a click: at 800 ms a source could land while the user stopped to aim
// and move the target just before the press (2026-10-19, still occasional dead clicks).
internal const val STREAM_LIST_POINTER_HOLD_MS = 1_500L

/**
 * [live], except while the mouse is in use over the list: then the arrangement already on screen,
 * until the pointer has been still (or gone) for [holdMs].
 *
 * Addons answer one after another and every answer re-sorts the source list, so a better-scoring
 * stream lands above the one being aimed at and pushes it down mid-click: the press lands on one
 * card and the release on another (nothing happens), or the press itself lands on the wrong stream
 * (2026-10-19: "easily 5 dead clicks" in a short session). Holding the layout while the mouse is
 * moving or pressed means the card under the pointer is the card that gets the click.
 *
 * A change of [resetKey] (another addon chip, another sort) is the user's own doing and shows at
 * once; so does anything while [canHold] says the shown list has nothing that could be misclicked.
 */
@Composable
internal fun <T> rememberHeldWhilePointerActive(
    live: T,
    resetKey: Any?,
    activity: ListPointerActivity,
    canHold: (shown: T) -> Boolean,
    holdMs: Long = STREAM_LIST_POINTER_HOLD_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
): T {
    var shown by remember(resetKey) { mutableStateOf(live) }
    val holding = shown != live && canHold(shown) && activity.isActive(nowMs(), holdMs)
    LaunchedEffect(live, resetKey) {
        if (shown != live && canHold(shown)) {
            while (activity.isActive(nowMs(), holdMs)) {
                val idleFor = nowMs() - activity.lastActiveAtMs
                delay((holdMs - idleFor).coerceIn(50L, holdMs))
            }
        }
        shown = live
    }
    return if (holding) shown else live
}
