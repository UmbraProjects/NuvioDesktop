package com.nuvio.app.features.simkl

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimklSuppressedSeedTest {
    private fun seed(videoId: String, watchedAtEpochMs: Long) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = videoId.substringBefore(':'),
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 1,
        lastPositionMs = 0L,
        durationMs = 0L,
        isCompleted = true,
        progressPercent = 100f,
        lastUpdatedEpochMs = watchedAtEpochMs,
        source = WatchProgressSourceSimkl,
    )

    @Test
    fun `a cleared seed stays out of the projection`() {
        val suppressed = mutableMapOf("tt27497393:1:1" to 1_000L)

        val kept = withoutSuppressedSimklSeeds(
            entries = listOf(seed("tt27497393:1:1", 1_000L), seed("tt27497393:1:2", 1_000L)),
            suppressedByVideoId = suppressed,
        )

        assertEquals(listOf("tt27497393:1:2"), kept.map(WatchProgressEntry::videoId))
        assertTrue(suppressed.containsKey("tt27497393:1:1"))
    }

    @Test
    fun `a newer watch clears the suppression`() {
        val suppressed = mutableMapOf("tt27497393:1:1" to 1_000L)

        val kept = withoutSuppressedSimklSeeds(
            entries = listOf(seed("tt27497393:1:1", 2_000L)),
            suppressedByVideoId = suppressed,
        )

        assertEquals(listOf("tt27497393:1:1"), kept.map(WatchProgressEntry::videoId))
        assertTrue(suppressed.isEmpty())
    }

    @Test
    fun `nothing suppressed leaves the list untouched`() {
        val entries = listOf(seed("tt1:1:1", 1_000L))

        assertEquals(entries, withoutSuppressedSimklSeeds(entries, mutableMapOf()))
    }
}
