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
)

/**
 * Tracks D-pad-style focus position for TV Mode on the home screen.
 *
 * [sectionIndex] is 0 for the hero and 1..N for the rows rendered below it
 * (continue watching, then catalog/collection shelves, in emission order).
 * [itemIndex] is the focused position within the focused row's shelf.
 */
internal class HomeTvFocusState {
    var sectionIndex by mutableStateOf(0)
    private val itemIndices = mutableStateMapOf<Int, Int>()

    var itemIndex: Int
        get() = itemIndices[sectionIndex] ?: 0
        set(value) {
            itemIndices[sectionIndex] = value
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
