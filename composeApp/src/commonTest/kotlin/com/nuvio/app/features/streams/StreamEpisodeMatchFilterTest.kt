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

    /**
     * "A Place Further Than the Universe" names its episode 12 after the series itself. Every
     * addon puts the series title in the stream description, so that one episode title used to
     * match the whole result set and reject it as episode 12 — emptying the picker for all other
     * episodes. Reported against PenguPlay, but the addon was returning correct S01E01 streams.
     */
    @Test
    fun keepsStreamsWhenAnEpisodeTitleRepeatsTheSeriesTitle() {
        val streams = listOf(
            testStream(description = "A Place Further Than the Universe (2018) • S01E01 | 1080p • HLS"),
            testStream(description = "A Place Further Than the Universe • S01E01 | 1080p • DASH"),
        )
        val titles = mapOf(
            (1 to 1) to "One Million Yen For Youth",
            (1 to 12) to "A Place Further Than the Universe",
            (1 to 13) to "We'll Go On Another Journey Someday",
        )

        assertEquals(
            streams,
            streams.filterForRequestedEpisode(
                season = 1,
                episode = 1,
                episodeTitlesByCoordinate = titles,
                seriesTitle = "A Place Further Than the Universe",
            ),
        )
    }

    /** The same collision without an explicit S/E tag to fall back on: the self-referential title
     * is dropped from the known-title set, so it cannot claim an unlabelled release either. */
    @Test
    fun ignoresSeriesTitleEchoWhenStreamHasNoExplicitCoordinates() {
        val stream = testStream(
            filename = "[SubsPlease] A Place Further Than the Universe - 03 (1080p).mkv",
        )
        val titles = mapOf(
            (1 to 12) to "A Place Further Than the Universe",
        )

        assertEquals(
            listOf(stream),
            listOf(stream).filterForRequestedEpisode(
                season = 1,
                episode = 3,
                episodeTitlesByCoordinate = titles,
                seriesTitle = "A Place Further Than the Universe",
            ),
        )
    }

    /** An explicit tag naming the requested episode outranks a title-inferred contradiction. */
    @Test
    fun explicitMatchingCoordinatesOutrankAnotherEpisodesTitle() {
        val stream = testStream(
            description = "Pokemon | S07 E01 | The Princess and the Togepi",
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

    /** A genuinely mislabelled title still loses when nothing explicit backs it up. */
    @Test
    fun stillRejectsOtherEpisodeTitleWhenNoExplicitCoordinatesAgree() {
        val stream = testStream(
            filename = "Pokemon Advanced Challenge - 0317 - The Princess And The Togepi.mkv",
        )
        val titles = mapOf(
            (7 to 1) to "What You Seed Is What You Get",
            (7 to 4) to "The Princess and the Togepi",
        )

        assertEquals(
            emptyList(),
            listOf(stream).filterForRequestedEpisode(
                season = 7,
                episode = 1,
                episodeTitlesByCoordinate = titles,
                seriesTitle = "Pokemon",
            ),
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
