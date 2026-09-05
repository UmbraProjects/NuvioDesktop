package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hover preview is stored per display mode, so the interesting failures are all in the gate
 * rather than in the preview itself: a mode reading the other mode's switch, or TV Mode picking up
 * a stored value at all.
 */
class HoverPreviewModeGateTest {

    @Test
    fun `defaults are on for basic and off for adaptive`() {
        val defaults = HomeCatalogSettingsUiState()
        assertTrue(defaults.hoverPreviewEnabledFor(HomeDisplayMode.Basic))
        assertFalse(defaults.hoverPreviewEnabledFor(HomeDisplayMode.Adaptive))
        assertFalse(defaults.hoverPreviewEnabledFor(HomeDisplayMode.AdaptiveAmbient))
    }

    @Test
    fun `tv mode is off whatever is stored`() {
        val state = HomeCatalogSettingsUiState(
            hoverPreviewBasicEnabled = true,
            hoverPreviewAdaptiveEnabled = true,
        )
        assertFalse(state.hoverPreviewEnabledFor(HomeDisplayMode.TvMode))
    }

    @Test
    fun `each mode reads its own switch`() {
        val basicOnly = HomeCatalogSettingsUiState(
            hoverPreviewBasicEnabled = true,
            hoverPreviewAdaptiveEnabled = false,
        )
        val adaptiveOnly = HomeCatalogSettingsUiState(
            hoverPreviewBasicEnabled = false,
            hoverPreviewAdaptiveEnabled = true,
        )
        assertTrue(basicOnly.hoverPreviewEnabledFor(HomeDisplayMode.Basic))
        assertFalse(basicOnly.hoverPreviewEnabledFor(HomeDisplayMode.Adaptive))
        assertFalse(adaptiveOnly.hoverPreviewEnabledFor(HomeDisplayMode.Basic))
        assertTrue(adaptiveOnly.hoverPreviewEnabledFor(HomeDisplayMode.Adaptive))
    }

    @Test
    fun `ambient shares the adaptive switch`() {
        val state = HomeCatalogSettingsUiState(hoverPreviewAdaptiveEnabled = true)
        assertEquals(
            state.hoverPreviewEnabledFor(HomeDisplayMode.Adaptive),
            state.hoverPreviewEnabledFor(HomeDisplayMode.AdaptiveAmbient),
        )
    }

    @Test
    fun `the default mode argument follows the state's own flags`() {
        // The composable calls this with no argument, so the flags-to-mode step is part of the gate.
        val adaptiveState = HomeCatalogSettingsUiState(
            adaptiveHeroEnabled = true,
            hoverPreviewBasicEnabled = true,
            hoverPreviewAdaptiveEnabled = false,
        )
        assertFalse(adaptiveState.hoverPreviewEnabledFor())

        val tvState = HomeCatalogSettingsUiState(
            tvModeEnabled = true,
            hoverPreviewBasicEnabled = true,
            hoverPreviewAdaptiveEnabled = true,
        )
        assertFalse(tvState.hoverPreviewEnabledFor())

        val basicState = HomeCatalogSettingsUiState(hoverPreviewBasicEnabled = true)
        assertTrue(basicState.hoverPreviewEnabledFor())
    }
}
