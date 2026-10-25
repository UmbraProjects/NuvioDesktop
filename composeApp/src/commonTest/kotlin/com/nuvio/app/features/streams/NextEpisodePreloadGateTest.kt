package com.nuvio.app.features.streams

import com.nuvio.app.features.player.nextEpisodePreloadLeadMs
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `preload lead follows the autoplay timeout within 10 to 30 seconds`() {
        assertEquals(10_000L, nextEpisodePreloadLeadMs(0))
        assertEquals(10_000L, nextEpisodePreloadLeadMs(3))
        assertEquals(20_000L, nextEpisodePreloadLeadMs(20))
        assertEquals(30_000L, nextEpisodePreloadLeadMs(Int.MAX_VALUE))
    }
}
