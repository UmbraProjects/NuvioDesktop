package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals

class GrabWalkDecisionTest {
    private val now = 1_700_000_000_000L

    private fun grab(
        status: GrabStatus,
        retryCount: Int = 0,
        nextAttemptAtEpochMs: Long? = null,
    ) = GrabRecord(
        id = "grab-1",
        monitoredItemId = "item-1",
        title = "Show",
        season = 2,
        episode = 1,
        status = status,
        retryCount = retryCount,
        nextAttemptAtEpochMs = nextAttemptAtEpochMs,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    @Test
    fun grabsAnEpisodeThatHasNeverBeenAttempted() {
        assertEquals(GrabWalkDecision.GRAB, grabWalkDecision(emptyList(), now))
    }

    @Test
    fun blocksWhileAnEarlierEpisodeIsStillTransferring() {
        assertEquals(GrabWalkDecision.BLOCK, grabWalkDecision(listOf(grab(GrabStatus.DOWNLOADING)), now))
        assertEquals(GrabWalkDecision.BLOCK, grabWalkDecision(listOf(grab(GrabStatus.QUEUED)), now))
    }

    @Test
    fun blocksWhileAFailedEpisodeIsWaitingOutItsBackoff() {
        val pending = grab(GrabStatus.FAILED, retryCount = 1, nextAttemptAtEpochMs = now + 60_000L)
        assertEquals(GrabWalkDecision.BLOCK, grabWalkDecision(listOf(pending), now))
    }

    @Test
    fun retriesTheFailedEpisodeOnceItsBackoffElapses() {
        val due = grab(GrabStatus.FAILED, retryCount = 1, nextAttemptAtEpochMs = now - 1L)
        assertEquals(GrabWalkDecision.GRAB, grabWalkDecision(listOf(due), now))
    }

    @Test
    fun skipsOnlyOnceTheRetryBudgetIsSpent() {
        val exhausted = grab(GrabStatus.FAILED, retryCount = 5, nextAttemptAtEpochMs = null)
        assertEquals(GrabWalkDecision.SKIP, grabWalkDecision(listOf(exhausted), now))

        val noSources = grab(GrabStatus.SKIPPED, retryCount = 5, nextAttemptAtEpochMs = null)
        assertEquals(GrabWalkDecision.SKIP, grabWalkDecision(listOf(noSources), now))
    }

    @Test
    fun aCancelledEpisodeHoldsTheLineRatherThanLettingTheNextOneOvertakeIt() {
        assertEquals(GrabWalkDecision.BLOCK, grabWalkDecision(listOf(grab(GrabStatus.CANCELLED)), now))
    }

    @Test
    fun anInFlightRetryOutranksAnOlderExhaustedRecordForTheSameEpisode() {
        val records = listOf(
            grab(GrabStatus.FAILED, retryCount = 5, nextAttemptAtEpochMs = null),
            grab(GrabStatus.DOWNLOADING),
        )
        assertEquals(GrabWalkDecision.BLOCK, grabWalkDecision(records, now))
    }
}
