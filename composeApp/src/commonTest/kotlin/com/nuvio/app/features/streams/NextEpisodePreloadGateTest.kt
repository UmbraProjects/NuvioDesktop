package com.nuvio.app.features.streams

import com.nuvio.app.features.player.nextEpisodePreloadLeadMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextEpisodePreloadGateTest {

    private fun inputs(
        trigger: StreamPrefetchService.Trigger = StreamPrefetchService.Trigger.NextEpisode,
        scope: StreamPrefetchScope = StreamPrefetchScope.OFF,
        playbackActive: Boolean = true,
        withinCooldown: Boolean = false,
        interactiveFetchRunning: Boolean = false,
        hasInstantSourceAlready: Boolean = false,
    ) = PrefetchGateInputs(trigger, scope, playbackActive, withinCooldown, interactiveFetchRunning, hasInstantSourceAlready)

    @Test
    fun `next episode preload ignores the prefetch scope and runs during playback`() {
        assertEquals(PrefetchDecision.Proceed, evaluatePrefetchGate(inputs()))
    }

    @Test
    fun `next episode preload keeps the other fences`() {
        assertEquals(PrefetchDecision.CoolingDown, evaluatePrefetchGate(inputs(withinCooldown = true)))
        assertEquals(PrefetchDecision.InteractiveFetchRunning, evaluatePrefetchGate(inputs(interactiveFetchRunning = true)))
        assertEquals(PrefetchDecision.AlreadyInstant, evaluatePrefetchGate(inputs(hasInstantSourceAlready = true)))
    }

    @Test
    fun `other triggers still stop for playback and scope`() {
        assertEquals(
            PrefetchDecision.ScopeDisabled,
            evaluatePrefetchGate(inputs(trigger = StreamPrefetchService.Trigger.Details)),
        )
        assertEquals(
            PrefetchDecision.PlaybackActive,
            evaluatePrefetchGate(inputs(trigger = StreamPrefetchService.Trigger.Details, scope = StreamPrefetchScope.DETAILS)),
        )
    }

    @Test
    fun `next episode preload only skips for sources autoplay plays`() {
        val next = StreamPrefetchService.Trigger.NextEpisode
        // A show also kept on disk: autoplay streams the next episode, so it must still be searched.
        assertFalse(InstantSource.LocalLibrary.skipsPrefetchFor(next, localCopyIsBackup = false))
        assertFalse(InstantSource.LocalStream.skipsPrefetchFor(next, localCopyIsBackup = false))
        assertFalse(InstantSource.LastLink.skipsPrefetchFor(next, localCopyIsBackup = false))
        assertTrue(InstantSource.Embedded.skipsPrefetchFor(next, localCopyIsBackup = false))
        assertTrue(InstantSource.Download.skipsPrefetchFor(next, localCopyIsBackup = false))
        // A title set to play online keeps its download as a backup; autoplay searches instead.
        assertFalse(InstantSource.Download.skipsPrefetchFor(next, localCopyIsBackup = true))
    }

    @Test
    fun `home and details prefetch still skip for every instant source`() {
        for (trigger in listOf(StreamPrefetchService.Trigger.Details, StreamPrefetchService.Trigger.ContinueWatching)) {
            for (source in InstantSource.entries) {
                assertTrue(source.skipsPrefetchFor(trigger, localCopyIsBackup = true), "$trigger $source")
            }
        }
    }

    @Test
    fun `preload lead follows the autoplay timeout within 10 to 30 seconds`() {
        assertEquals(10_000L, nextEpisodePreloadLeadMs(0))
        assertEquals(10_000L, nextEpisodePreloadLeadMs(3))
        assertEquals(20_000L, nextEpisodePreloadLeadMs(20))
        assertEquals(30_000L, nextEpisodePreloadLeadMs(Int.MAX_VALUE))
    }
}
