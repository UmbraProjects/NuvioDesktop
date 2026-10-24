package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val SCROLL_PIXELS_PER_NOTCH = 240f

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.horizontalListMouseInput(
    state: LazyListState,
    onFocusRequest: () -> Unit,
    treatPlainScrollAsHorizontal: Boolean,
): Modifier = composed {
    // A mouse click is rarely perfectly still, and at 8 dp a small hand movement during a click on a
    // card or chip turned it into a drag that ate the release (2026-10-19 (ChipClick) trace: chip
    // clicks with 6-18 px of travel did nothing). Dragging is deliberate, so it needs a real distance.
    val touchSlopPx = with(LocalDensity.current) { 24.dp.toPx() }

    this.pointerInput(state, touchSlopPx) {
        awaitPointerEventScope {
            var isDragging = false
            var accumulatedDrag = 0f

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> {
                        onFocusRequest()
                    }
                    PointerEventType.Press -> {
                        isDragging = false
                        accumulatedDrag = 0f
                        onFocusRequest()
                    }
                    PointerEventType.Move -> {
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val dragAmount = change.positionChange().x
                            accumulatedDrag += dragAmount
                            // A row whose items all fit has nothing to drag: never take a click
                            // from it for a scroll that cannot happen.
                            if (!isDragging && abs(accumulatedDrag) > touchSlopPx &&
                                (state.canScrollForward || state.canScrollBackward)
                            ) {
                                isDragging = true
                            }
                            if (isDragging) {
                                state.dispatchRawDelta(-dragAmount)
                                change.consume()
                            }
                        }
                    }
                    PointerEventType.Release -> {
                        if (isDragging) {
                            event.changes.forEach { it.consume() }
                        }
                        isDragging = false
                        accumulatedDrag = 0f
                    }
                    PointerEventType.Scroll -> {
                        val change = event.changes.firstOrNull() ?: continue
                        val scrollDelta = change.scrollDelta
                        // Only hijack the wheel for horizontal scrolling when the wheel itself
                        // produced a horizontal delta (trackpad swipe), the user holds Shift
                        // (the standard "scroll horizontally" gesture), or this row lives
                        // somewhere that never scrolls vertically anyway (treatPlainScrollAsHorizontal).
                        // Otherwise a plain vertical wheel scroll is left alone so an enclosing
                        // vertically-scrolling page can scroll past this row.
                        val amount = when {
                            abs(scrollDelta.x) > abs(scrollDelta.y) -> scrollDelta.x
                            event.keyboardModifiers.isShiftPressed ||
                                DesktopNavigationGestureBridge.horizontalScrollModifierActive.value ||
                                treatPlainScrollAsHorizontal -> scrollDelta.y
                            else -> 0f
                        }
                        if (amount != 0f) {
                            state.dispatchRawDelta(amount * SCROLL_PIXELS_PER_NOTCH)
                            change.consume()
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
