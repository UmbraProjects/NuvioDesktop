package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * Adds desktop-friendly mouse input (wheel + drag scrolling) to a horizontally
 * scrolling [LazyListState]. No-op on platforms where touch swiping already works.
 * [onFocusRequest] is invoked when the pointer interacts with (or hovers over) the
 * list, e.g. to request keyboard focus for the list.
 */
internal expect fun Modifier.horizontalListMouseInput(state: LazyListState, onFocusRequest: () -> Unit = {}): Modifier

/**
 * Makes a horizontally scrolling [LazyListState] usable on desktop: mouse wheel and
 * drag scrolling, plus left/right arrow-key navigation once the list has focus
 * (acquired automatically when the mouse hovers over it).
 *
 * [scrollStepPx] is the distance scrolled per arrow-key press. If null, defaults to
 * a fraction of the viewport width.
 */
@Composable
internal fun Modifier.desktopHorizontalListNavigation(
    state: LazyListState,
    scrollStepPx: Float? = null,
): Modifier {
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    return this
        .focusRequester(focusRequester)
        .horizontalListMouseInput(state, onFocusRequest = {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // The node may not be attached yet (or may have been disposed mid-navigation).
            }
        })
        .focusable()
        .onKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step = scrollStepPx ?: (state.layoutInfo.viewportSize.width * 0.85f)
            when (keyEvent.key) {
                Key.DirectionRight -> {
                    coroutineScope.launch { state.animateScrollBy(step) }
                    true
                }
                Key.DirectionLeft -> {
                    coroutineScope.launch { state.animateScrollBy(-step) }
                    true
                }
                else -> false
            }
        }
}
