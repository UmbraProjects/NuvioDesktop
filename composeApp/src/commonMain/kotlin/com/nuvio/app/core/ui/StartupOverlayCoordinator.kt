package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which full-window startup overlay is on screen when more than one wants to be.
 *
 * The overlays that fire around launch — the setup wizard, the missing-API-key prompt, the updater
 * — are independent of each other, and on a fresh install of a slightly stale download all three
 * can want the screen at once. Rather than teaching each one about the others (which is how the
 * "two popups at the same time" bug keeps coming back), every host declares that it *wants* to show
 * and renders only when it is the winner. Adding an overlay in a later release is one enum entry.
 */
object StartupOverlayCoordinator {
    /** Declared in priority order: the first entry that wants the screen gets it. */
    enum class Overlay {
        SetupWizard,
        ApiKeys,
        Updater,
    }

    private var wanted = emptySet<Overlay>()
    private val _visibleOverlay = MutableStateFlow<Overlay?>(null)

    val visibleOverlay: StateFlow<Overlay?> = _visibleOverlay.asStateFlow()

    fun setWantsToShow(overlay: Overlay, wants: Boolean) {
        val next = if (wants) wanted + overlay else wanted - overlay
        if (next == wanted) return
        wanted = next
        _visibleOverlay.value = Overlay.entries.firstOrNull { it in next }
    }
}
