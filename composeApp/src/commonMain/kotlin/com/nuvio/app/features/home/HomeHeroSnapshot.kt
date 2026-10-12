package com.nuvio.app.features.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hero items as Home settled on them, for the artwork prewarm.
 *
 * Settled means after the hero's metadata pass, which can replace a catalog backdrop with a TMDB,
 * TVDB or per-season anime one and fill a missing logo. The catalog row alone does not say which
 * backdrop the hero ends up showing, so warming from it would fetch one the hero then never asks
 * for. Null until Home's first pass has finished.
 */
object HomeHeroSnapshot {
    private val _items = MutableStateFlow<List<MetaPreview>?>(null)
    val items: StateFlow<List<MetaPreview>?> = _items.asStateFlow()

    fun publish(items: List<MetaPreview>) {
        _items.value = items
    }

    private val _focusRows = MutableStateFlow<List<List<MetaPreview>>>(emptyList())

    /**
     * Home's rows, top to bottom, when the hero follows the focused card (Adaptive, TV Mode), in
     * the form the hero reads them; empty when it does not. Each item still needs the hero's own
     * resolution — [withNormalizedHeroBackdrop], then [resolveFocusedHeroItem] when
     * [heroNeedsMetadataFetch] — to become what the hero shows.
     */
    val focusRows: StateFlow<List<List<MetaPreview>>> = _focusRows.asStateFlow()

    fun publishFocusRows(rows: List<List<MetaPreview>>) {
        _focusRows.value = rows
    }
}
