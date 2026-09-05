package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The display mode is stored as three booleans that exclude each other, so the mapping in both
 * directions is the thing most likely to break silently — a reordered write produces
 * "AdaptiveAmbient" with ambient quietly off, and nothing else in the app would notice.
 */
class HomeDisplayModeTest {

    @Test
    fun `every mode round-trips through its flags`() {
        HomeDisplayMode.entries.forEach { mode ->
            assertEquals(mode, homeDisplayModeOf(mode.toFlags()), "round-trip failed for $mode")
        }
    }

    @Test
    fun `every mode reaches every other mode`() {
        // Guards the "from each of the other three" half: the flags a mode writes must not depend
        // on what was set before it.
        HomeDisplayMode.entries.forEach { from ->
            HomeDisplayMode.entries.forEach { to ->
                val flags = to.toFlags()
                assertEquals(to, homeDisplayModeOf(flags), "$from -> $to produced the wrong mode")
            }
        }
    }

    @Test
    fun `tv mode excludes the adaptive hero and its ambient background`() {
        val flags = HomeDisplayMode.TvMode.toFlags()
        assertTrue(flags.tvModeEnabled)
        assertFalse(flags.adaptiveHeroEnabled)
        assertFalse(flags.heroAmbientBackgroundEnabled)
    }

    @Test
    fun `ambient implies the adaptive hero`() {
        val flags = HomeDisplayMode.AdaptiveAmbient.toFlags()
        assertTrue(flags.heroAmbientBackgroundEnabled)
        assertTrue(flags.adaptiveHeroEnabled, "ambient without the adaptive hero reads back as Basic")
        assertFalse(flags.tvModeEnabled)
    }

    @Test
    fun `basic clears all three flags`() {
        val flags = HomeDisplayMode.Basic.toFlags()
        assertFalse(flags.adaptiveHeroEnabled)
        assertFalse(flags.heroAmbientBackgroundEnabled)
        assertFalse(flags.tvModeEnabled)
    }

    @Test
    fun `tv mode wins over stale adaptive flags on read`() {
        // Payloads written before the modes were consolidated can carry both; normalizeHeroModes
        // resolves it the same way.
        val mode = homeDisplayModeOf(
            adaptiveHeroEnabled = true,
            heroAmbientBackgroundEnabled = true,
            tvModeEnabled = true,
        )
        assertEquals(HomeDisplayMode.TvMode, mode)
    }
}
