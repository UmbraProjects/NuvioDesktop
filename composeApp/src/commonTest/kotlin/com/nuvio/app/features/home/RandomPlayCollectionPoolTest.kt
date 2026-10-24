package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandomPlayCollectionPoolTest {
    @Test
    fun retryableSourcesWaitForTraversalAndCooldown() {
        val progress = RandomPlayCollectionSourceProgress(
            sourceKeys = listOf("a", "b", "c", "d", "e", "f"),
            startIndex = 4,
            retryBaseDelayMs = 100,
            retryMaxDelayMs = 1_000,
        )

        assertEquals(listOf("e", "f"), progress.nextWindow(nowMs = 0, size = 2))
        progress.markLoaded("e")
        progress.markRetryable("f", nowMs = 0)

        assertEquals(listOf("a", "b"), progress.nextWindow(nowMs = 1, size = 2))
        progress.markLoaded("a")
        progress.markLoaded("b")
        assertEquals(listOf("c", "d"), progress.nextWindow(nowMs = 1, size = 2))
        progress.markLoaded("c")
        progress.markLoaded("d")

        // Failure did not complete the source, but rapid callers cannot hammer it.
        assertTrue(progress.nextWindow(nowMs = 99, size = 2).isEmpty())
        assertFalse(progress.hasEligibleSource(nowMs = 99))
        assertEquals(listOf("f"), progress.nextWindow(nowMs = 100, size = 2))
    }

    @Test
    fun repeatedFailuresBackOffAndSuccessCompletesTheSource() {
        val progress = RandomPlayCollectionSourceProgress(
            sourceKeys = listOf("only"),
            startIndex = 0,
            retryBaseDelayMs = 10,
            retryMaxDelayMs = 25,
        )

        assertEquals(listOf("only"), progress.nextWindow(nowMs = 0, size = 24))
        progress.markRetryable("only", nowMs = 0)
        assertTrue(progress.nextWindow(nowMs = 9, size = 24).isEmpty())
        assertEquals(listOf("only"), progress.nextWindow(nowMs = 10, size = 24))

        progress.markRetryable("only", nowMs = 10)
        assertTrue(progress.nextWindow(nowMs = 29, size = 24).isEmpty())
        assertEquals(listOf("only"), progress.nextWindow(nowMs = 30, size = 24))

        progress.markLoaded("only")
        assertEquals(1, progress.completedCount)
        assertFalse(progress.hasEligibleSource(nowMs = Long.MAX_VALUE))
        assertTrue(progress.nextWindow(nowMs = Long.MAX_VALUE, size = 24).isEmpty())
    }

    @Test
    fun windowsStayBoundedAndDoNotRefetchCompletedSources() {
        val progress = RandomPlayCollectionSourceProgress(
            sourceKeys = (1..60).map(Int::toString),
            startIndex = 55,
        )

        val first = progress.nextWindow(nowMs = 0, size = RANDOM_PLAY_COLLECTION_SOURCE_WINDOW)
        assertEquals(RANDOM_PLAY_COLLECTION_SOURCE_WINDOW, first.size)
        first.forEach(progress::markLoaded)

        val second = progress.nextWindow(nowMs = 0, size = RANDOM_PLAY_COLLECTION_SOURCE_WINDOW)
        assertEquals(RANDOM_PLAY_COLLECTION_SOURCE_WINDOW, second.size)
        assertTrue(first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun structuredAnimeTypesLeadWithoutDisturbingStableOrder() {
        data class Source(val name: String, val type: String?)

        val ordered = listOf(
            Source("generic movie", "movie"),
            Source("anime series", "anime.series"),
            Source("tmdb", null),
            Source("anime mixed", "Anime"),
            Source("generic series", "series"),
        ).stableAnimeSourcesFirst(Source::type)

        assertEquals(
            listOf("anime series", "anime mixed", "generic movie", "tmdb", "generic series"),
            ordered.map(Source::name),
        )
    }
}
