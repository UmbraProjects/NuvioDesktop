package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

class SimklWatchedParsingTest {
    @Test
    fun extendedHistoryIncludesOnlyEpisodesWithWatchedTimestamps() {
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(
                    show = SimklShowMedia(title = "Show", ids = SimklMediaIds(imdb = "tt1")),
                    seasons = listOf(
                        SimklWatchedSeason(
                            number = 1,
                            episodes = listOf(
                                SimklWatchedEpisode(1, "2026-01-01T00:00:00Z"),
                                SimklWatchedEpisode(2, null),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val watched = response.toWatchedItems()

        assertEquals(1, watched.size)
        assertEquals(1, watched.single().episode)
    }
}
