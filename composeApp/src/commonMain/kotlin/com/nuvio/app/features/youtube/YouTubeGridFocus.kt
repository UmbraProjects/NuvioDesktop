package com.nuvio.app.features.youtube

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import com.nuvio.app.core.ui.arrowFocusDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Up and Down in the tab's grids. The arrow keys otherwise move focus by a geometric search, which only
 * sees the cards that are laid out: going down onto a row still below the screen found nothing in that
 * column and fell back to the row's first card. Here the move is by index (the same column, a row on),
 * scrolling the row in first when it is not laid out yet.
 */

internal class GridColumnFocus(private val state: LazyGridState, private val scope: CoroutineScope) {
    private val requesters = HashMap<Int, FocusRequester>()

    /** The grid item that has focus now; -1 before any has. */
    var focusedIndex by mutableIntStateOf(-1)

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    /** Cards per row, from the rows on screen. */
    private fun columns(): Int =
        state.layoutInfo.visibleItemsInfo.groupingBy { it.row }.eachCount().values.maxOrNull() ?: 0

    /**
     * Moves a row [down] (or up) from the focused item among [itemCount]. False leaves the key to the
     * screen's own navigation: up from the first row (to the header), or down from the last.
     */
    fun move(down: Boolean, itemCount: Int): Boolean {
        val from = focusedIndex
        val cols = columns()
        if (from !in 0 until itemCount || cols <= 0) return false
        val target = gridColumnTarget(from, down, cols, itemCount) ?: return false
        scope.launch {
            // A few rows' worth of tries: each scrolls one row towards the target and lets it lay out.
            repeat(MAX_SCROLL_STEPS) {
                val laidOut = state.layoutInfo.visibleItemsInfo.any { it.index == target }
                if (laidOut && runCatching { requester(target).requestFocus() }.getOrDefault(false)) return@launch
                val first = state.firstVisibleItemIndex
                state.scrollToItem((first + if (down) cols else -cols).coerceAtLeast(0), state.firstVisibleItemScrollOffset)
            }
        }
        return true
    }

    private companion object {
        const val MAX_SCROLL_STEPS = 4
    }
}

/**
 * The index a row move lands on: the same column in the next (or previous) row, or the last item when the
 * next row is the last and shorter. Null when there is no such row.
 */
internal fun gridColumnTarget(from: Int, down: Boolean, columns: Int, itemCount: Int): Int? {
    if (down) {
        val target = from + columns
        if (target < itemCount) return target
        return (itemCount - 1).takeIf { from / columns < (itemCount - 1) / columns }
    }
    return (from - columns).takeIf { it >= 0 }
}

@Composable
internal fun rememberGridColumnFocus(state: LazyGridState): GridColumnFocus {
    val scope = rememberCoroutineScope()
    return remember(state) { GridColumnFocus(state, scope) }
}

/** On the grid: Up and Down by index; see [GridColumnFocus]. */
internal fun Modifier.gridColumnKeys(focus: GridColumnFocus, itemCount: () -> Int): Modifier = onKeyEvent { event ->
    when (arrowFocusDirection(event)) {
        FocusDirection.Down -> focus.move(down = true, itemCount())
        FocusDirection.Up -> focus.move(down = false, itemCount())
        else -> false
    }
}

/** Around each item of the grid: where focus goes for [index], and the record that it is there. */
@Composable
internal fun GridColumnItem(focus: GridColumnFocus, index: Int, content: @Composable () -> Unit) {
    Box(
        Modifier
            .focusRequester(focus.requester(index))
            .onFocusChanged { if (it.hasFocus) focus.focusedIndex = index },
    ) { content() }
}
