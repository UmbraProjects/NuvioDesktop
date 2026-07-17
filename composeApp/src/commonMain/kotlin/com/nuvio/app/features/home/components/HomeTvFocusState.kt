package com.nuvio.app.features.home.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.home.MetaPreview

/**
 * A single focusable row on the home screen in TV Mode (continue watching,
 * a collection's folders, or a catalog's items), in emission order.
 *
 * [metaItems] is non-null only for catalog rows, where the focused item is
 * used to drive the adaptive hero.
 */
internal class HomeTvRow(
    val itemCount: Int,
    val metaItems: List<MetaPreview>?,
    val onEnter: (index: Int) -> Unit,
    // For horizontally infinite-scrolling catalog rows: request the next page (called as focus nears
    // the end). Null for non-paginating rows.
    val onLoadMore: (() -> Unit)? = null,
    // For non-paginating catalog rows: pressing Right on the last item opens the full grid. Null when
    // the row paginates (it just keeps loading more) or can't be opened.
    val onRightAtEnd: (() -> Unit)? = null,
)

/**
 * Maps a logical TV-focus section to the lazy-list position used for keyboard scrolling.
 *
 * Adaptive mode renders the hero as a fixed overlay and reserves its space with a leading lazy
 * item. The focused row therefore needs both the extra item-index shift and a negative scroll
 * offset that leaves it below the overlay. Keeping the measured/configured hero height in the
 * target prevents larger hero-height settings from covering the focused row.
 */
internal data class HomeTvLazyScrollTarget(
    val itemIndex: Int,
    val scrollOffset: Int,
)

internal fun homeTvLazyScrollTarget(
    sectionIndex: Int,
    heroFocusable: Boolean,
    adaptiveHeroHeightPx: Int,
): HomeTvLazyScrollTarget {
    if (heroFocusable && sectionIndex == 0) {
        return HomeTvLazyScrollTarget(itemIndex = 0, scrollOffset = 0)
    }

    val rowIndex = (sectionIndex - if (heroFocusable) 1 else 0).coerceAtLeast(0)
    return if (adaptiveHeroHeightPx > 0) {
        HomeTvLazyScrollTarget(
            itemIndex = rowIndex + 1,
            scrollOffset = -adaptiveHeroHeightPx,
        )
    } else {
        HomeTvLazyScrollTarget(itemIndex = rowIndex, scrollOffset = 0)
    }
}

/**
 * Tracks D-pad-style focus position for TV Mode on the home screen.
 *
 * [sectionIndex] is 0 for the hero and 1..N for the rows rendered below it
 * (continue watching, then catalog/collection shelves, in emission order).
 * [itemIndex] is the focused position within the focused row's shelf.
 */
internal class HomeTvFocusState(
    private val onPositionChanged: ((sectionIndex: Int, itemIndex: Int) -> Unit)? = null,
) {
    private var currentSectionIndex by mutableStateOf(0)
    var sectionIndex: Int
        get() = currentSectionIndex
        set(value) {
            currentSectionIndex = value
            onPositionChanged?.invoke(value, itemIndices[value] ?: 0)
        }
    private val itemIndices = mutableStateMapOf<Int, Int>()

    var itemIndex: Int
        get() = itemIndices[sectionIndex] ?: 0
        set(value) {
            itemIndices[sectionIndex] = value
            onPositionChanged?.invoke(sectionIndex, value)
        }

    /** Reads the stored focus position for an arbitrary section (0 if never focused). */
    fun itemIndexForSection(section: Int): Int = itemIndices[section] ?: 0

    /**
     * Pre-loads a section's focus position without moving [sectionIndex]. Used to restore the
     * within-row (horizontal) position on return from another screen while leaving the initial
     * section focus (e.g. the hero on a fresh launch) untouched.
     */
    fun restoreItemIndex(section: Int, index: Int) {
        itemIndices[section] = index
    }

    fun moveSection(delta: Int, sectionCount: Int) {
        if (sectionCount <= 0) return
        sectionIndex = (sectionIndex + delta).coerceIn(0, sectionCount - 1)
    }

    fun moveItem(delta: Int, itemCount: Int) {
        if (itemCount <= 0) {
            itemIndex = 0
            return
        }
        itemIndex = (itemIndex + delta).coerceIn(0, itemCount - 1)
    }
}
