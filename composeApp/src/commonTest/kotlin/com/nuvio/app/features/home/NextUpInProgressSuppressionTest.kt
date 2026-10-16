package com.nuvio.app.features.home

import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An in-progress episode hides Up Next only when it is at or after the completed seed. Report
 * 2026-10-15: South Park S17E3 half-watched in Continue Watching hid Up Next for S29E3.
 */
class NextUpInProgressSuppressionTest {

    private val seed = CompletedSeriesCandidate(
        content = WatchingContentRef(type = "series", id = "tt0121955"),
        seasonNumber = 29,
        episodeNumber = 2,
        markedAtEpochMs = 1_000L,
    )

    private fun inProgress(season: Int?, episode: Int?, updatedAt: Long = 2_000L) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "tt0121955",
        parentMetaType = "series",
        videoId = "tt0121955:$season:$episode",
        title = "South Park",
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = 300_000L,
        durationMs = 1_320_000L,
        lastUpdatedEpochMs = updatedAt,
    )

    @Test
    fun olderEpisodeInProgressDoesNotSuppress() {
        assertFalse(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(17, 3), seed))
        assertFalse(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(29, 1), seed))
    }

    @Test
    fun seedOrLaterEpisodeInProgressSuppresses() {
        assertTrue(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(29, 2), seed))
        assertTrue(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(29, 3), seed))
        assertTrue(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(30, 1), seed))
    }

    @Test
    fun progressOlderThanCompletionDoesNotSuppress() {
        assertFalse(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(29, 3, updatedAt = 500L), seed))
    }

    @Test
    fun missingCoordinatesKeepTimeOnlyRule() {
        assertTrue(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(null, null), seed))
        assertTrue(shouldTreatAsActiveInProgressForNextUpSuppression(inProgress(17, 3), null))
    }
}
