package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiddleClickAutoscrollTest {

    @Test
    fun `nothing moves inside the dead zone`() {
        assertEquals(0f, autoscrollSpeedDpPerSecond(0f))
        assertEquals(0f, autoscrollSpeedDpPerSecond(AUTOSCROLL_DEAD_ZONE_DP))
        assertEquals(0f, autoscrollSpeedDpPerSecond(-AUTOSCROLL_DEAD_ZONE_DP))
    }

    @Test
    fun `above the marker scrolls up and below scrolls down`() {
        assertTrue(autoscrollSpeedDpPerSecond(60f) > 0f)
        assertTrue(autoscrollSpeedDpPerSecond(-60f) < 0f)
        assertEquals(autoscrollSpeedDpPerSecond(60f), -autoscrollSpeedDpPerSecond(-60f))
    }

    @Test
    fun `further away goes faster than in proportion, up to a cap`() {
        val near = autoscrollSpeedDpPerSecond(AUTOSCROLL_DEAD_ZONE_DP + 50f)
        val far = autoscrollSpeedDpPerSecond(AUTOSCROLL_DEAD_ZONE_DP + 100f)
        assertTrue(far > near * 2f)
        assertEquals(autoscrollSpeedDpPerSecond(2000f), autoscrollSpeedDpPerSecond(5000f))
    }
}
