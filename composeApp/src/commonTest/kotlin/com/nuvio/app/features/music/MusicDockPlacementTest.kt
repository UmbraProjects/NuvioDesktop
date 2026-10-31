package com.nuvio.app.features.music

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicDockPlacementTest {
    private val height = 900.dp
    private val width = 1600.dp

    @Test
    fun `the dock opens upwards at the bottom and downwards in the top half`() {
        assertFalse(dockOpensDown(height, MusicPlayerPlacement.Default))
        assertFalse(dockOpensDown(height, MusicPlayerPlacement(dy = -300.dp)))
        assertTrue(dockOpensDown(height, MusicPlayerPlacement(dy = -600.dp)))
    }

    @Test
    fun `the notch can be dragged to the top edge but no further`() {
        val clamped = clampDockPlacement(width, height, MusicPlayerPlacement(dy = -5_000.dp))
        // The notch's top edge then sits one edge gap below the top of the window.
        assertEquals(DockEdgeGap, height + clamped.dy - DockEdgeGap - DockNotchHeight)
        assertTrue(dockOpensDown(height, clamped))
    }
}
