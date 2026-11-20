package com.nuvio.app.features.streams

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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

    /** The pointer along the list's scrolling axis, in the list's own pixels; NaN when it is not over the list. */
    @Volatile
    var mainAxisPx = Float.NaN

    fun isActive(nowMs: Long, holdMs: Long): Boolean = pressed || nowMs - lastActiveAtMs < holdMs
}

/** Records pointer activity over this node without consuming anything. */
internal fun Modifier.trackListPointerActivity(
    activity: ListPointerActivity,
    vertical: Boolean = true,
    nowMs: () -> Long = { System.currentTimeMillis() },
): Modifier = pointerInput(activity, vertical) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Exit) {
                activity.lastActiveAtMs = Long.MIN_VALUE / 2
                activity.pressed = false
                activity.mainAxisPx = Float.NaN
            } else {
                activity.lastActiveAtMs = nowMs()
                activity.pressed = event.changes.any { it.pressed }
                event.changes.firstOrNull()?.position?.let { position ->
                    activity.mainAxisPx = if (vertical) position.y else position.x
                }
            }
        }
    }
}

// Longer than the pause before a click: at 800 ms a source could land while the user stopped to aim
// and move the target just before the press (2026-10-19, still occasional dead clicks).
internal const val STREAM_LIST_POINTER_HOLD_MS = 1_500L

/**
 * [live], except while the mouse is in use over the list and the change would move what it is
 * aiming at: then the arrangement already on screen, until the pointer has been still (or gone)
 * for [holdMs], or has moved somewhere the change no longer reaches.
 *
 * Addons answer one after another and every answer re-sorts the source list, so a better-scoring
 * stream lands above the one being aimed at and pushes it down mid-click: the press lands on one
 * card and the release on another (nothing happens), or the press itself lands on the wrong stream
 * (2026-10-19: "easily 5 dead clicks" in a short session). Holding the layout while the mouse is
 * moving or pressed means the card under the pointer is the card that gets the click.
 *
 * [movesUnderPointer] narrows that to changes that matter (2026-11-19: on All, every late addon
 * waited out the full hold even when its rows landed below the pointer, so finished results showed
 * up 1-2 s late). It is called outside snapshot observation, so it may read scroll state freely.
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
    movesUnderPointer: (shown: T, live: T) -> Boolean = { _, _ -> true },
    holdMs: Long = STREAM_LIST_POINTER_HOLD_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
): T {
    var shown by remember(resetKey) { mutableStateOf(live) }
    fun mustWait(current: T): Boolean =
        shown != current && canHold(shown) && activity.isActive(nowMs(), holdMs) &&
            Snapshot.withoutReadObservation { movesUnderPointer(shown, current) }
    val holding = mustWait(live)
    LaunchedEffect(live, resetKey) {
        while (mustWait(live)) {
            // Short steps rather than one sleep to the end of the hold: the pointer may move off the
            // rows the change would shift, and then the change can show straight away.
            val idleFor = nowMs() - activity.lastActiveAtMs
            delay((holdMs - idleFor).coerceIn(50L, 150L))
        }
        shown = live
    }
    return if (holding) shown else live
}

/**
 * Whether replacing [shownKeys] with [liveKeys] moves the item under the pointer or any item shown
 * above it (left of it, in a row). [anchorKey] is the first visible item's key when the list is
 * scrolled, since a keyed lazy list then keeps that item where it is; null when the list sits at its
 * top, where everything is laid out from the first item. [pointerKey] is the item under the pointer,
 * or the one before the gap it is in; null when that cannot be told, which counts as moving.
 */
internal fun arrangementMovesUnderPointer(
    shownKeys: List<Any>,
    liveKeys: List<Any>,
    anchorKey: Any?,
    pointerKey: Any?,
): Boolean {
    if (pointerKey == null) return true
    val pointerAt = shownKeys.indexOf(pointerKey)
    if (pointerAt < 0) return true
    val shownStart = if (anchorKey == null) 0 else shownKeys.indexOf(anchorKey)
    if (shownStart < 0 || shownStart > pointerAt) return true
    val liveStart = if (anchorKey == null) 0 else liveKeys.indexOf(anchorKey)
    if (liveStart < 0) return true
    val span = pointerAt - shownStart + 1
    if (liveStart + span > liveKeys.size) return true
    for (offset in 0 until span) {
        if (shownKeys[shownStart + offset] != liveKeys[liveStart + offset]) return true
    }
    return false
}

/**
 * [arrangementMovesUnderPointer] for a keyed lazy list drawing [shownKeys], with the pointer read
 * from [activity]. Between the leading edge and the first item nothing can move in, so that is a
 * no; past the last item a new one may slide in under the pointer, so that is a yes.
 */
internal fun LazyListState.arrangementMovesUnderPointer(
    activity: ListPointerActivity,
    shownKeys: List<Any>,
    liveKeys: List<Any>,
): Boolean {
    val pointerPx = activity.mainAxisPx
    if (pointerPx.isNaN()) return true
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return true
    // Item offsets start after the leading content padding; the pointer is in the list's own pixels.
    val position = pointerPx + info.viewportStartOffset
    val atTop = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
    if (atTop && position < visible.first().offset) return false
    val last = visible.last()
    if (position >= last.offset + last.size && last.index == info.totalItemsCount - 1) return true
    val pointerItem = visible.lastOrNull { it.offset <= position } ?: return true
    return arrangementMovesUnderPointer(
        shownKeys = shownKeys,
        liveKeys = liveKeys,
        anchorKey = if (atTop) null else visible.first().key,
        pointerKey = pointerItem.key,
    )
}
