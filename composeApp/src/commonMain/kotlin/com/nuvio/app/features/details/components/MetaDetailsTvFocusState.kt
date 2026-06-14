package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** TV-mode focus position for [com.nuvio.app.features.details.MetaDetailsScreen]. */
internal class MetaDetailsTvFocusState {
    var sectionIndex by mutableStateOf(0)
        internal set
    var itemIndex by mutableStateOf(0)
        internal set

    fun moveSection(delta: Int, sectionCount: Int) {
        if (sectionCount <= 0) {
            sectionIndex = 0
            itemIndex = 0
            return
        }
        sectionIndex = (sectionIndex + delta).coerceIn(0, sectionCount - 1)
    }

    fun moveItem(delta: Int, itemCount: Int) {
        itemIndex = if (itemCount <= 0) {
            0
        } else {
            (itemIndex + delta).coerceIn(0, itemCount - 1)
        }
    }

    fun coerceItemIndex(itemCount: Int) {
        itemIndex = if (itemCount <= 0) 0 else itemIndex.coerceIn(0, itemCount - 1)
    }
}

@Composable
internal fun rememberMetaDetailsTvFocusState(): MetaDetailsTvFocusState = remember { MetaDetailsTvFocusState() }
