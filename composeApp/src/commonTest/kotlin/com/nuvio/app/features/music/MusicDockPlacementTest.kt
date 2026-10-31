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

    @Test
    fun `an open dock dragged across the middle keeps the panel where it was`() {
        val extra = 300.dp
        val from = MusicPlayerPlacement(dy = -420.dp)
        val moved = MusicPlayerPlacement(dy = -440.dp)
        assertFalse(dockOpensDown(height, from))
        assertTrue(dockOpensDown(height, moved))
        val step = dockDragStep(height, from, moved, extra)
        // Opening down, the panel spans from the notch's top edge; it must land where the upward panel's top was.
        val upwardTop = height + moved.dy - DockEdgeGap - DockNotchHeight - extra
        val downwardTop = height + step.dy - DockEdgeGap - DockNotchHeight
        assertEquals(upwardTop, downwardTop)
        // The notch is now well past the middle: a small drag back does not swap again.
        val back = MusicPlayerPlacement(dy = step.dy + 20.dp)
        assertEquals(back, dockDragStep(height, step, back, extra))
    }

    @Test
    fun `a folded dock or a drag that stays on one side moves as dragged`() {
        val moved = MusicPlayerPlacement(dy = -440.dp)
        assertEquals(moved, dockDragStep(height, MusicPlayerPlacement(dy = -420.dp), moved, 0.dp))
        val near = MusicPlayerPlacement(dy = -100.dp)
        assertEquals(near, dockDragStep(height, MusicPlayerPlacement(dy = -80.dp), near, 300.dp))
    }

    @Test
    fun `the notch can be dragged into a side corner, one edge gap from the side`() {
        val notch = 200.dp
        val clamped = clampDockPlacement(width, height, MusicPlayerPlacement(dx = -5_000.dp, dy = -5_000.dp), notch)
        // Its left edge, from the window's left: centred plus the offset.
        assertEquals(DockEdgeGap, (width - notch) / 2 + clamped.dx)
        val right = clampDockPlacement(width, height, MusicPlayerPlacement(dx = 5_000.dp), notch)
        assertEquals(width - DockEdgeGap, (width - notch) / 2 + right.dx + notch)
    }
}
