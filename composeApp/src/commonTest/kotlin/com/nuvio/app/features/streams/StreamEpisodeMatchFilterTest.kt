package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamEpisodeMatchFilterTest {
    @Test
    fun rejectsExplicitDifferentEpisodeFromFilename() {
        val stream = testStream(
            description = "Pokemon | S07 E01",
            filename = "Pokemon - 0353-07x41 Advanced 081 Challenge 41.mkv",
        )

        assertEquals(emptyList(), listOf(stream).filterForRequestedEpisode(7, 1))
    }

    @Test
    fun rejectsExplicitDifferentEpisodeFromDescription() {
        val stream = testStream(description = "Pokemon | S07 E41")

        assertEquals(emptyList(), listOf(stream).filterForRequestedEpisode(7, 1))
    }

    @Test
    fun keepsMatchingEpisode() {
        val stream = testStream(filename = "Pokemon.S07E01.1080p.mkv")

        assertEquals(listOf(stream), listOf(stream).filterForRequestedEpisode(7, 1))
    }

    @Test
    fun keepsRequestedEpisodeInsideMultiEpisodeFile() {
        val stream = testStream(filename = "Pokemon.S07E01-E02.1080p.mkv")

        assertEquals(listOf(stream), listOf(stream).filterForRequestedEpisode(7, 2))
    }

    @Test
    fun keepsSeasonPackAndUnlabelledAnimeRelease() {
        val seasonPack = testStream(filename = "Pokemon Season 07 Complete")
        val absoluteRelease = testStream(filename = "Pokemon Advanced Generation - 041.mkv")

        assertEquals(
            listOf(seasonPack, absoluteRelease),
            listOf(seasonPack, absoluteRelease).filterForRequestedEpisode(7, 1),
        )
    }

    @Test
    fun rejectsMislabeledAbsoluteReleaseUsingKnownEpisodeTitle() {
        val stream = testStream(
            filename = "Pokemon Advanced Challenge - 0317 - The Princess And The Togepi.mkv",
        )
        val titles = mapOf(
            (7 to 1) to "What You Seed Is What You Get",
            (7 to 4) to "The Princess and the Togepi",
        )

        assertEquals(
            emptyList(),
            listOf(stream).filterForRequestedEpisode(7, 1, titles),
        )
    }

    @Test
    fun keepsAbsoluteReleaseWithRequestedKnownEpisodeTitle() {
        val stream = testStream(
            filename = "Pokemon Advanced Challenge - 0317 - What You Seed Is What You Get.mkv",
        )
        val titles = mapOf(
            (7 to 1) to "What You Seed Is What You Get",
            (7 to 4) to "The Princess and the Togepi",
        )

        assertEquals(
            listOf(stream),
            listOf(stream).filterForRequestedEpisode(7, 1, titles),
        )
    }

    private fun testStream(
        description: String? = null,
        filename: String? = null,
    ): StreamItem = StreamItem(
        description = description,
        url = "https://example.test/video",
        addonName = "Test",
        addonId = "test",
        behaviorHints = StreamBehaviorHints(filename = filename),
    )
}
