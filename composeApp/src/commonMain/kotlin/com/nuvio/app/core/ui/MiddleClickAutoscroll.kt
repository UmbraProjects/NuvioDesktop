package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/*
 * Middle-click autoscroll, the way a browser does it (Nov 20 2026): a middle click drops a marker where it was
 * pressed, and the page scrolls towards the pointer, faster the further the pointer is from the marker. A quick
 * click leaves it running until the next click of any button (which does nothing else) or a turn of the wheel;
 * holding the middle button and moving scrolls only while it is held. Vertical only.
 *
 * The panel ring also listens for the middle button (hold it anywhere to open the ring). Over an area with this
 * modifier the button belongs to the scroll: [MiddleClickAutoscroll.claimed] tells the ring to stand down while
 * the pointer is inside one.
 */
object MiddleClickAutoscroll {
    @Volatile
    private var hoveredAreas = 0

    /** True while the pointer is over an area that scrolls on a middle click; the panel ring leaves it alone. */
    val claimed: Boolean get() = hoveredAreas > 0

    internal fun enter() {
        hoveredAreas++
    }

    internal fun exit() {
        hoveredAreas = (hoveredAreas - 1).coerceAtLeast(0)
    }
}

/** Distance (dp) from the marker inside which nothing moves, so a resting hand does not creep the page. */
internal const val AUTOSCROLL_DEAD_ZONE_DP = 12f

/** Pointer travel (dp) while the middle button is still held that turns a click into a hold-to-scroll. */
private const val AUTOSCROLL_DRAG_DP = 12f

/** The fastest it goes, in dp a second: a long page still takes a moment rather than vanishing. */
private const val AUTOSCROLL_MAX_DP_PER_SECOND = 6000f

/**
 * Scroll speed in dp per second for a pointer [offsetDp] from the marker (negative = above it, scrolling up).
 * Grows faster than the distance, as in a browser: a short way off creeps, a long way off races.
 */
internal fun autoscrollSpeedDpPerSecond(offsetDp: Float): Float {
    val beyond = abs(offsetDp) - AUTOSCROLL_DEAD_ZONE_DP
    if (beyond <= 0f) return 0f
    return sign(offsetDp) * (1.2f * beyond.pow(1.5f)).coerceAtMost(AUTOSCROLL_MAX_DP_PER_SECOND)
}

private enum class AutoscrollMode { Off, Pending, Sticky, Held }

/** Browser-style middle-click autoscroll for the vertical scroll container that owns [state]. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.middleClickAutoscroll(state: ScrollableState): Modifier = composed {
    val scope = rememberCoroutineScope()
    var anchor by remember { mutableStateOf<Offset?>(null) }
    val hovering = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (hovering[0]) MiddleClickAutoscroll.exit() }
    }
    this
        .pointerInput(state) {
            var mode = AutoscrollMode.Off
            var pointerY = 0f
            var job: Job? = null
            // After a click that stopped a sticky scroll, its release is swallowed too.
            var swallowRelease = false

            fun stop() {
                mode = AutoscrollMode.Off
                job?.cancel()
                job = null
                anchor = null
            }

            fun start(at: Offset) {
                anchor = at
                pointerY = at.y
                job?.cancel()
                job = scope.launch {
                    var last = withFrameNanos { it }
                    var carry = 0f
                    while (true) {
                        val now = withFrameNanos { it }
                        val seconds = (now - last) / 1_000_000_000f
                        last = now
                        val origin = anchor ?: break
                        val offsetDp = (pointerY - origin.y) / density
                        val px = autoscrollSpeedDpPerSecond(offsetDp) * density * seconds + carry
                        val whole = px.toInt().toFloat()
                        carry = px - whole
                        if (whole != 0f) state.scrollBy(whole)
                    }
                }
            }

            awaitPointerEventScope {
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Enter -> if (!hovering[0]) {
                                hovering[0] = true
                                MiddleClickAutoscroll.enter()
                            }
                            PointerEventType.Exit -> if (hovering[0]) {
                                hovering[0] = false
                                MiddleClickAutoscroll.exit()
                            }
                            PointerEventType.Press -> when {
                                mode == AutoscrollMode.Sticky || mode == AutoscrollMode.Held -> {
                                    // Any click ends it, and does nothing else: it must not open the card under it.
                                    stop()
                                    change.consume()
                                    swallowRelease = true
                                }
                                event.button == PointerButton.Tertiary -> {
                                    mode = AutoscrollMode.Pending
                                    start(change.position)
                                    change.consume()
                                }
                            }
                            PointerEventType.Move -> {
                                pointerY = change.position.y
                                val origin = anchor
                                if (mode == AutoscrollMode.Pending && origin != null &&
                                    (change.position - origin).getDistance() / density > AUTOSCROLL_DRAG_DP
                                ) {
                                    mode = AutoscrollMode.Held
                                }
                            }
                            PointerEventType.Release -> {
                                if (swallowRelease) {
                                    swallowRelease = false
                                    change.consume()
                                } else if (event.button == PointerButton.Tertiary) {
                                    when (mode) {
                                        // A click: keeps going until the next click.
                                        AutoscrollMode.Pending -> mode = AutoscrollMode.Sticky
                                        AutoscrollMode.Held -> stop()
                                        else -> Unit
                                    }
                                    change.consume()
                                }
                            }
                            // Turning the wheel takes over, as in a browser; the wheel's own scroll still happens.
                            PointerEventType.Scroll -> if (mode != AutoscrollMode.Off) stop()
                        }
                    }
                } finally {
                    stop()
                }
            }
        }
        .drawWithContent {
            drawContent()
            anchor?.let { drawAutoscrollMarker(it) }
        }
}

/** The marker: a small disc with an arrow up and an arrow down, like a browser's. */
private fun DrawScope.drawAutoscrollMarker(at: Offset) {
    val radius = 15f * density
    drawCircle(Color.Black.copy(alpha = 0.55f), radius, at)
    drawCircle(Color.White.copy(alpha = 0.85f), radius, at, style = Stroke(width = 1.5f * density))
    drawCircle(Color.White, 2f * density, at)
    val half = 4.5f * density
    val tip = 10f * density
    val base = 4.5f * density
    listOf(-1f, 1f).forEach { direction ->
        val path = Path().apply {
            moveTo(at.x, at.y + direction * tip)
            lineTo(at.x - half, at.y + direction * base)
            lineTo(at.x + half, at.y + direction * base)
            close()
        }
        drawPath(path, Color.White)
    }
}
