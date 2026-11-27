package com.nuvio.app.features.trakt

import com.nuvio.app.features.tracking.isNegligibleResumeProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktPlaybackProgressTest {
    @Test
    fun progressIsAlwaysReadAsAPercentage() {
        // An abandoned stop leaves 0.5% on Trakt; read as a fraction it was 50%, and 0.9% was 90%:
        // past the completion threshold, so the title counted as finished.
        assertEquals(0.5f, traktProgressPercent(0.5f))
        assertEquals(0.9f, traktProgressPercent(0.9f))
        assertEquals(1f, traktProgressPercent(1f))
        assertEquals(42.5f, traktProgressPercent(42.5f))
        assertEquals(100f, traktProgressPercent(130f))
        assertEquals(0f, traktProgressPercent(-3f))
        assertNull(traktProgressPercent(null))
        assertNull(traktProgressPercent(Float.NaN))
    }

    @Test
    fun aPlaybackUnderOnePercentIsNoResumePoint() {
        assertTrue(isNegligibleResumeProgress(0.5f))
        assertTrue(isNegligibleResumeProgress(0.99f))
        assertFalse(isNegligibleResumeProgress(1f))
        assertFalse(isNegligibleResumeProgress(12f))
    }
}
