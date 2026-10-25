package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopInverseToneMappingTest {
    @Test
    fun runsOnlyOnSdrVideoWithWindowsHdrOn() {
        assertTrue(shouldEnableDesktopInverseToneMapping(true, isHdr = false, displayHdr = true, rtxTrueHdrActive = false))
        assertFalse(shouldEnableDesktopInverseToneMapping(false, isHdr = false, displayHdr = true, rtxTrueHdrActive = false))
        // Native HDR, and the unknown states before detection, are left alone.
        assertFalse(shouldEnableDesktopInverseToneMapping(true, isHdr = true, displayHdr = true, rtxTrueHdrActive = false))
        assertFalse(shouldEnableDesktopInverseToneMapping(true, isHdr = null, displayHdr = true, rtxTrueHdrActive = false))
        assertFalse(shouldEnableDesktopInverseToneMapping(true, isHdr = false, displayHdr = false, rtxTrueHdrActive = false))
        assertFalse(shouldEnableDesktopInverseToneMapping(true, isHdr = false, displayHdr = null, rtxTrueHdrActive = false))
    }

    @Test
    fun rtxTrueHdrTakesOver() {
        assertFalse(shouldEnableDesktopInverseToneMapping(true, isHdr = false, displayHdr = true, rtxTrueHdrActive = true))
    }

    @Test
    fun activeTargetsPqWithThePeak() {
        val props = desktopInverseToneMappingProperties(active = true, peakNits = 600, customOptionNames = emptySet()).toMap()
        assertEquals("pq", props["target-trc"])
        assertEquals("bt.2020", props["target-prim"])
        assertEquals("600", props["target-peak"])
        assertEquals("yes", props["inverse-tone-mapping"])
    }

    @Test
    fun inactiveResetsUnlessTheCustomOptionsSetIt() {
        assertEquals(
            listOf("inverse-tone-mapping" to "no"),
            desktopInverseToneMappingProperties(active = false, peakNits = 400, customOptionNames = emptySet()),
        )
        assertEquals(
            emptyList(),
            desktopInverseToneMappingProperties(active = false, peakNits = 400, customOptionNames = setOf("inverse-tone-mapping")),
        )
    }
}
