package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkChurnTrimTest {
    private val mb = 1024L * 1024

    @Test
    fun trimsAfterABurstOnceTheScreenIsQuiet() {
        assertTrue(ArtworkChurnTrim.shouldTrim(600 * mb, sinceLastDecodeMillis = 3_000, sinceLastTrimMillis = 120_000))
    }

    @Test
    fun noTrimWhileStillDecoding() {
        // Mid-scroll: a collection now would only race the next decodes.
        assertFalse(ArtworkChurnTrim.shouldTrim(2_000 * mb, sinceLastDecodeMillis = 400, sinceLastTrimMillis = 120_000))
    }

    @Test
    fun noTrimForOrdinaryBrowsing() {
        assertFalse(ArtworkChurnTrim.shouldTrim(200 * mb, sinceLastDecodeMillis = 10_000, sinceLastTrimMillis = 120_000))
    }

    @Test
    fun noTrimSoonerThanAMinuteApart() {
        assertFalse(ArtworkChurnTrim.shouldTrim(900 * mb, sinceLastDecodeMillis = 5_000, sinceLastTrimMillis = 30_000))
    }
}
