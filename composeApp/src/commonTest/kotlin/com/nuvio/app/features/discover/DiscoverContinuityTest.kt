package com.nuvio.app.features.discover

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000
private const val IDLE_30_DAYS = 30L * DAY

private fun progress(
    parentId: String,
    videoId: String = "$parentId:1:1",
    daysAgo: Long,
    positionMs: Long = 10 * 60 * 1000,
    durationMs: Long = 45 * 60 * 1000,
    title: String = "Show",
    type: String = "series",
    source: String = "local",
) = WatchProgressEntry(
    contentType = type,
    parentMetaId = parentId,
    parentMetaType = type,
    videoId = videoId,
    title = title,
    seasonNumber = 1,
    episodeNumber = 1,
    lastPositionMs = positionMs,
    durationMs = durationMs,
    lastUpdatedEpochMs = NOW - daysAgo * DAY,
    source = source,
)

class DiscoverContinuityTest {

    @Test
    fun `a title untouched past the threshold is offered back`() {
        val entries = listOf(progress("tt0903747", daysAgo = 45))

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertEquals(listOf("tt0903747"), picked.map { it.parentMetaId })
    }

    @Test
    fun `a title still in flight is left to Continue Watching`() {
        val entries = listOf(progress("tt0903747", daysAgo = 3))

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `titles Continue Watching is still showing are excluded`() {
        // The exclusion is what stops the two rows being the same row: with a short history
        // nothing falls off the end of Continue Watching, however stale it is.
        val entries = listOf(
            progress("tt0903747", daysAgo = 60),
            progress("tt0944947", daysAgo = 90, title = "Other"),
        )

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = setOf(finishGroupKey("series", "tt0903747")),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertEquals(listOf("tt0944947"), picked.map { it.parentMetaId })
    }

    @Test
    fun `a title abandoned years ago is forgotten, not unfinished`() {
        val entries = listOf(progress("tt0903747", daysAgo = 1200))

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a finished episode is a next-up case, not an unfinished one`() {
        val entries = listOf(
            progress("tt0903747", daysAgo = 60, positionMs = 45 * 60 * 1000, durationMs = 45 * 60 * 1000),
        )

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `several part-watched episodes of one show yield one card`() {
        val entries = listOf(
            progress("tt0903747", videoId = "tt0903747:1:2", daysAgo = 80),
            progress("tt0903747", videoId = "tt0903747:1:3", daysAgo = 40),
            progress("tt0903747", videoId = "tt0903747:1:4", daysAgo = 55),
        )

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertEquals(1, picked.size)
        // The newest of the group decides how stale the title is.
        assertEquals("tt0903747:1:3", picked.single().videoId)
    }

    @Test
    fun `Trakt history rows are not playback and never qualify`() {
        val entries = listOf(
            progress("tt0903747", daysAgo = 60, source = WatchProgressSourceTraktHistory),
        )

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
        )

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `newest first, capped`() {
        val entries = (1..30).map { index ->
            progress("tt$index", videoId = "tt$index:1:1", daysAgo = 31L + index, title = "Show $index")
        }

        val picked = selectFinishWhatYouStarted(
            entries = entries,
            continueWatchingParentKeys = emptySet(),
            now = NOW,
            minIdleMs = IDLE_30_DAYS,
            limit = 5,
        )

        assertEquals(listOf("tt1", "tt2", "tt3", "tt4", "tt5"), picked.map { it.parentMetaId })
    }

    @Test
    fun `reach reports how many titles the threshold matches`() {
        val entries = listOf(
            progress("tt1", daysAgo = 2),
            progress("tt2", daysAgo = 12),
            progress("tt3", daysAgo = 20),
        )
        assertEquals(3, finishWhatYouStartedReach(entries, emptySet(), NOW, minIdleDays = 1).matching)
        assertEquals(2, finishWhatYouStartedReach(entries, emptySet(), NOW, minIdleDays = 10).matching)
        assertEquals(1, finishWhatYouStartedReach(entries, emptySet(), NOW, minIdleDays = 14).matching)
    }

    @Test
    fun `reach names the oldest idle title so an empty row is diagnosable`() {
        // The case the user hits: every slider position past the end of the data produces an empty
        // row, and nothing on screen says why. The oldest age is what makes it explicable.
        val entries = listOf(progress("tt1", daysAgo = 2), progress("tt2", daysAgo = 20))
        val reach = finishWhatYouStartedReach(entries, emptySet(), NOW, minIdleDays = 30)
        assertEquals(0, reach.matching)
        assertEquals(2, reach.candidates)
        assertEquals(20, reach.oldestIdleDays)
    }

    @Test
    fun `reach reports no candidates at all separately from none matching`() {
        // "Nothing part-watched yet" and "nothing that old" need different wording, so they cannot
        // both collapse to matching == 0.
        val reach = finishWhatYouStartedReach(emptyList(), emptySet(), NOW, minIdleDays = 14)
        assertEquals(0, reach.matching)
        assertEquals(0, reach.candidates)
        assertEquals(null, reach.oldestIdleDays)
    }

    @Test
    fun `reach counts titles Continue Watching already shows as unavailable`() {
        val entries = listOf(progress("tt1", daysAgo = 20), progress("tt2", daysAgo = 20))
        val reach = finishWhatYouStartedReach(
            entries = entries,
            continueWatchingParentKeys = setOf(finishGroupKey("series", "tt1")),
            now = NOW,
            minIdleDays = 3,
        )
        assertEquals(1, reach.matching)
        assertEquals(1, reach.candidates)
    }
}
