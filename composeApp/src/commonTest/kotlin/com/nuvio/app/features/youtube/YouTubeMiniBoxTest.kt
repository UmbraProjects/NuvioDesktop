package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeMiniBoxTest {
    private val start = YouTubeMiniBox(widthDp = 400f, rightDp = 24f, bottomDp = 24f)

    @Test
    fun moveShiftsTheBoxAndStaysInsideTheWindow() {
        val moved = miniBoxAfterDrag(start, mode = 1, dx = -100f, dy = -50f, frameWidth = 1600f, frameHeight = 900f)
        assertEquals(YouTubeMiniBox(400f, 124f, 74f), moved)
        val pinned = miniBoxAfterDrag(start, mode = 1, dx = 500f, dy = 500f, frameWidth = 1600f, frameHeight = 900f)
        assertEquals(0f, pinned.rightDp)
        assertEquals(0f, pinned.bottomDp)
    }

    @Test
    fun westEdgeGrowsLeftwardKeepingTheRightEdge() {
        val resized = miniBoxAfterDrag(start, mode = 8, dx = -80f, dy = 0f, frameWidth = 1600f, frameHeight = 900f)
        assertEquals(480f, resized.widthDp)
        assertEquals(24f, resized.rightDp)
        assertEquals(24f, resized.bottomDp)
    }

    @Test
    fun southEastCornerKeepsTheTopLeftCorner() {
        val resized = miniBoxAfterDrag(start, mode = 5, dx = -16f, dy = -9f, frameWidth = 1600f, frameHeight = 900f)
        // Shrunk by 16 dp, so the right and bottom margins grow by the same.
        assertEquals(384f, resized.widthDp)
        assertEquals(40f, resized.rightDp)
        assertEquals(33f, resized.bottomDp)
    }

    @Test
    fun widthIsClampedToTheAllowedRange() {
        val tiny = miniBoxAfterDrag(start, mode = 8, dx = 1000f, dy = 0f, frameWidth = 1600f, frameHeight = 900f)
        assertEquals(YOUTUBE_MINI_MIN_WIDTH_DP, tiny.widthDp)
        val huge = miniBoxAfterDrag(start, mode = 8, dx = -5000f, dy = 0f, frameWidth = 1600f, frameHeight = 900f)
        assertEquals(1600f * YOUTUBE_MINI_MAX_FRACTION, huge.widthDp)
    }
}
