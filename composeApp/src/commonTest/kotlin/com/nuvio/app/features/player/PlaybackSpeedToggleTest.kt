package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackSpeedToggleTest {

    @Test
    fun `toggle flips between the two ends of the range`() {
        assertEquals(2f, nextToggledPlaybackSpeed(current = 1f, low = 1f, high = 2f))
        assertEquals(1f, nextToggledPlaybackSpeed(current = 2f, low = 1f, high = 2f))
    }

    @Test
    fun `toggle honours a custom range`() {
        assertEquals(1.6f, nextToggledPlaybackSpeed(current = 1.2f, low = 1.2f, high = 1.6f))
        assertEquals(1.2f, nextToggledPlaybackSpeed(current = 1.6f, low = 1.2f, high = 1.6f))
    }

    @Test
    fun `a speed stepped above the range resets to the low end`() {
        assertEquals(1.2f, nextToggledPlaybackSpeed(current = 3f, low = 1.2f, high = 1.6f))
    }

    @Test
    fun `a speed between the ends goes up`() {
        assertEquals(2f, nextToggledPlaybackSpeed(current = 1.5f, low = 1f, high = 2f))
    }

    @Test
    fun `configured speeds snap to the 0 05 grid within the supported range`() {
        assertEquals(1.2f, 1.21f.coercePlaybackSpeed())
        assertEquals(1.25f, 1.23f.coercePlaybackSpeed())
        assertEquals(0.5f, 0.1f.coercePlaybackSpeed())
        assertEquals(4f, 9f.coercePlaybackSpeed())
    }

    @Test
    fun `range normalisation orders the pair`() {
        assertEquals(1.2f to 1.6f, normalizePlaybackSpeedToggleRange(low = 1.6f, high = 1.2f))
        assertEquals(1.2f to 1.6f, normalizePlaybackSpeedToggleRange(low = 1.2f, high = 1.6f))
    }

    @Test
    fun `range normalisation keeps the ends apart so the shortcut always does something`() {
        val (low, high) = normalizePlaybackSpeedToggleRange(low = 1.5f, high = 1.5f)
        assertTrue(high > low, "collapsed range would make the toggle a no-op, got $low..$high")
        assertEquals(1.5f to 1.55f, low to high)
    }

    @Test
    fun `range normalisation separates downward at the ceiling`() {
        assertEquals(3.95f to 4f, normalizePlaybackSpeedToggleRange(low = 4f, high = 4f))
    }
}
