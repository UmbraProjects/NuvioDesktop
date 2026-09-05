package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledNightlyMarkerTest {

    @Test
    fun `keeps the marker when the updater swap produced a new build`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = true,
            runningBuildId = "20260825-120000",
            lastSeenBuildId = "20260822-090000",
        )

        assertTrue(decision.keepMarker)
        assertEquals("20260825-120000", decision.recordBuildId)
        assertTrue(decision.clearPending)
    }

    @Test
    fun `drops the marker when a build was installed outside the updater`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = false,
            runningBuildId = "20260825-120000",
            lastSeenBuildId = "20260822-090000",
        )

        assertFalse(decision.keepMarker)
        assertEquals("20260825-120000", decision.recordBuildId)
    }

    @Test
    fun `drops the marker when a pending swap never replaced the build`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = true,
            runningBuildId = "20260822-090000",
            lastSeenBuildId = "20260822-090000",
        )

        assertFalse(decision.keepMarker)
        assertTrue(decision.clearPending)
    }

    @Test
    fun `keeps the marker while the same nightly keeps running`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = false,
            runningBuildId = "20260822-090000",
            lastSeenBuildId = "20260822-090000",
        )

        assertTrue(decision.keepMarker)
    }

    @Test
    fun `keeps the marker when the first stamped build arrived through the updater`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = true,
            runningBuildId = "20260825-230710",
            lastSeenBuildId = null,
        )

        assertTrue(decision.keepMarker)
        assertEquals("20260825-230710", decision.recordBuildId)
        assertTrue(decision.clearPending)
    }

    @Test
    fun `drops the marker when the first stamped build was installed by hand`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = false,
            runningBuildId = "20260825-230710",
            lastSeenBuildId = null,
        )

        assertFalse(decision.keepMarker)
        assertEquals("20260825-230710", decision.recordBuildId)
    }

    @Test
    fun `changes nothing for an unpackaged run with no build stamp`() {
        val decision = decideNightlyMarker(
            hasMarker = true,
            swapPending = true,
            runningBuildId = null,
            lastSeenBuildId = "20260822-090000",
        )

        assertTrue(decision.keepMarker)
        assertNull(decision.recordBuildId)
        assertFalse(decision.clearPending)
    }
}
