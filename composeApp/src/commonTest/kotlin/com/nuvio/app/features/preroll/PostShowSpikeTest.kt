package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostShowSpikeTest {
    @Test
    fun `window opens at the start and closes after the hold`() {
        assertFalse(postShowSpikeActive(positionMs = 44_999L, startMs = 45_000L, holdMs = 20_000L))
        assertTrue(postShowSpikeActive(positionMs = 45_000L, startMs = 45_000L, holdMs = 20_000L))
        assertTrue(postShowSpikeActive(positionMs = 64_999L, startMs = 45_000L, holdMs = 20_000L))
        assertFalse(postShowSpikeActive(positionMs = 65_000L, startMs = 45_000L, holdMs = 20_000L))
    }

    @Test
    fun `defaults use the published constants`() {
        assertTrue(postShowSpikeActive(POST_SHOW_SPIKE_START_MS))
        assertFalse(postShowSpikeActive(POST_SHOW_SPIKE_START_MS + POST_SHOW_SPIKE_HOLD_MS))
        assertFalse(postShowSpikeActive(0L))
    }

    @Test
    fun `seeking back out of the window closes it`() {
        assertTrue(postShowSpikeActive(50_000L))
        assertFalse(postShowSpikeActive(10_000L))
    }
}
