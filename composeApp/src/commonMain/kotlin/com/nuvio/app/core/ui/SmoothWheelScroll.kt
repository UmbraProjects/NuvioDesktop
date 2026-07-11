package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// One wheel notch reports ~1.0 on desktop; this is how far (in px) a single notch travels.
private const val SMOOTH_WHEEL_PIXELS_PER_NOTCH = 210f
private const val SMOOTH_WHEEL_DURATION_MS = 360

/**
 * Replaces the default (immediate, steppy) desktop mouse-wheel behaviour of a vertically
 * scrolling [LazyListState] with an eased, animated scroll. Each notch accumulates into a
 * moving target that the list glides toward, so fast repeated ticks blend into one smooth
 * motion instead of a series of jumps.
 *
 * Only plain vertical wheel input is taken over. Horizontal wheel deltas (trackpads) and
 * Shift+wheel are left untouched so the horizontal poster rows nested inside the list keep
 * handling them. When [enabled] is false this is a no-op and the platform default applies.
 *
 * Intended for the desktop-only fork; attach to the scroll container that owns [state].
 */
internal fun Modifier.smoothVerticalWheelScroll(
    state: LazyListState,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else composed {
    val scope = rememberCoroutineScope()
    val controller = remember(state) { SmoothWheelScrollController(state) }
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass: intercept before the LazyColumn's own scrollable consumes the
                // wheel, but after the nested horizontal rows have had their crack at horizontal
                // deltas (they only consume horizontal/Shift, which we deliberately ignore here).
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (event.keyboardModifiers.isShiftPressed) continue
                val delta = change.scrollDelta
                if (abs(delta.y) < abs(delta.x) || delta.y == 0f) continue
                controller.onWheel(delta.y, scope)
                change.consume()
            }
        }
    }
}

/**
 * Drives the eased scroll. [target] and [animatable] are running totals in pixels; only their
 * differences are ever applied via [ScrollScope.scrollBy], so their absolute magnitude is
 * irrelevant to correctness. Each wheel tick bumps [target] and (re)launches an animation from
 * wherever the previous one left off, giving seamless continuity when ticks arrive rapidly.
 */
private class SmoothWheelScrollController(private val state: LazyListState) {
    private val animatable = Animatable(0f)
    private var target = 0f
    private var job: Job? = null

    fun onWheel(rawDeltaY: Float, scope: CoroutineScope) {
        val deltaPx = rawDeltaY * SMOOTH_WHEEL_PIXELS_PER_NOTCH
        // At a content edge, drop any accumulated overshoot so an immediate reverse scroll isn't
        // spent unwinding invisible debt.
        if (deltaPx > 0f && !state.canScrollForward) {
            target = animatable.value
            return
        }
        if (deltaPx < 0f && !state.canScrollBackward) {
            target = animatable.value
            return
        }
        target += deltaPx
        val capturedTarget = target
        job?.cancel()
        job = scope.launch {
            state.scroll {
                var previous = animatable.value
                animatable.animateTo(
                    targetValue = capturedTarget,
                    animationSpec = tween(
                        durationMillis = SMOOTH_WHEEL_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ) {
                    val step = value - previous
                    previous = value
                    scrollBy(step)
                }
            }
        }
    }
}
