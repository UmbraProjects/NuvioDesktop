package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The display mode is stored as booleans that exclude each other, so the mapping in both
 * directions is the thing most likely to break silently. Adaptive's ambient wash is a toggle kept
 * across modes, so its gate ([adaptiveAmbientActive]) is covered here too.
 */
class HomeDisplayModeTest {

    @Test
    fun `every mode round-trips through its flags`() {
        HomeDisplayMode.entries.forEach { mode ->
            assertEquals(mode, homeDisplayModeOf(mode.toFlags()), "round-trip failed for $mode")
        }
    }

    @Test
    fun `tv mode excludes the adaptive hero`() {
        val flags = HomeDisplayMode.TvMode.toFlags()
        assertTrue(flags.tvModeEnabled)
        assertFalse(flags.adaptiveHeroEnabled)
    }

    @Test
    fun `basic clears both flags`() {
        val flags = HomeDisplayMode.Basic.toFlags()
        assertFalse(flags.adaptiveHeroEnabled)
        assertFalse(flags.tvModeEnabled)
    }

    @Test
    fun `tv mode wins over stale adaptive flags on read`() {
        // Payloads written before the modes were consolidated can carry both; normalizeHeroModes
        // resolves it the same way.
        assertEquals(HomeDisplayMode.TvMode, homeDisplayModeOf(adaptiveHeroEnabled = true, tvModeEnabled = true))
    }

    @Test
    fun `adaptive ambient is drawn only in adaptive`() {
        val ambientOn = HomeCatalogSettingsUiState(heroAmbientBackgroundEnabled = true)
        assertTrue(ambientOn.copy(adaptiveHeroEnabled = true).adaptiveAmbientActive)
        assertFalse(ambientOn.adaptiveAmbientActive, "Basic must not draw Adaptive's ambient")
        assertFalse(ambientOn.copy(tvModeEnabled = true).adaptiveAmbientActive, "TV Mode has its own toggle")
        assertFalse(HomeCatalogSettingsUiState(adaptiveHeroEnabled = true).adaptiveAmbientActive)
    }
}
