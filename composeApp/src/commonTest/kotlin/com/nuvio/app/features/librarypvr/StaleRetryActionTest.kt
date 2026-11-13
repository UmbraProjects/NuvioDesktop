package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals

class StaleRetryActionTest {
    private fun grab(id: String, item: String = "m1", episode: Int = 1, created: Long = 1L) = GrabRecord(
        id = id,
        monitoredItemId = item,
        title = "Show",
        season = 1,
        episode = episode,
        status = GrabStatus.FAILED,
        retryCount = 2,
        nextAttemptAtEpochMs = 10L,
        createdAtEpochMs = created,
        updatedAtEpochMs = created,
    )

    private val monitored = mapOf("m1" to MonitoredItem(
        id = "m1",
        contentType = "series",
        contentId = "tt1",
        title = "Show",
        targetFolderId = "f",
        mode = MonitorMode.SELECTED_PLUS_FUTURE,
        addedAtEpochMs = 0L,
    ))

    @Test
    fun aRetryForATitleNoLongerMonitoredExpires() {
        val g = grab("a", item = "gone")
        assertEquals(StaleRetryAction.EXPIRE, staleRetryAction(g, monitored, listOf(g)))
    }

    @Test
    fun aRetryReplacedByANewerGrabOfTheSameEpisodeExpires() {
        val old = grab("a", created = 1L)
        val newer = grab("b", created = 5L).copy(status = GrabStatus.COMPLETED, nextAttemptAtEpochMs = null)
        assertEquals(StaleRetryAction.EXPIRE, staleRetryAction(old, monitored, listOf(old, newer)))
    }

    @Test
    fun aRetryTheCheckCouldNotReachIsPushedBack() {
        val g = grab("a")
        val otherEpisode = grab("b", episode = 2, created = 9L)
        assertEquals(StaleRetryAction.PUSH_BACK, staleRetryAction(g, monitored, listOf(g, otherEpisode)))
    }
}
