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
internal actual fun Modifier.horizontalListMouseInput(state: LazyListState, onFocusRequest: () -> Unit): Modifier = composed {
    val touchSlopPx = with(LocalDensity.current) { 8.dp.toPx() }

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
                            if (!isDragging && abs(accumulatedDrag) > touchSlopPx) {
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
                        // produced a horizontal delta (trackpad swipe) or the user holds Shift
                        // (the standard "scroll horizontally" gesture). A plain vertical wheel
                        // scroll is left alone so the page can scroll past this row.
                        val amount = when {
                            abs(scrollDelta.x) > abs(scrollDelta.y) -> scrollDelta.x
                            event.keyboardModifiers.isShiftPressed -> scrollDelta.y
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
