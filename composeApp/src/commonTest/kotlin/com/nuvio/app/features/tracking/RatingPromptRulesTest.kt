package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingPromptRulesTest {
    private val twoSeasons = mapOf(
        1 to setOf(1, 2, 3),
        2 to setOf(1, 2),
    )

    @Test
    fun `a movie always prompts`() {
        assertEquals(
            RatingPromptReason.MOVIE,
            ratingPromptReasonFor(contentType = "movie", seasonNumber = null, episodeNumber = null),
        )
    }

    @Test
    fun `the last episode of a middle season is a season finale`() {
        assertEquals(
            RatingPromptReason.SEASON_FINALE,
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 1,
                episodeNumber = 3,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = "Ended",
            ),
        )
    }

    @Test
    fun `the last episode of the last season of an ended show is a series finale`() {
        assertEquals(
            RatingPromptReason.SERIES_FINALE,
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 2,
                episodeNumber = 2,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = "Ended",
            ),
        )
    }

    @Test
    fun `a running show never reports a series finale`() {
        assertEquals(
            RatingPromptReason.SEASON_FINALE,
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 2,
                episodeNumber = 2,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = "Returning Series",
            ),
        )
    }

    @Test
    fun `an unknown status is treated as still running`() {
        assertEquals(
            RatingPromptReason.SEASON_FINALE,
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 2,
                episodeNumber = 2,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = null,
            ),
        )
    }

    @Test
    fun `cancelled spellings both count as ended`() {
        listOf("Canceled", "cancelled").forEach { status ->
            assertEquals(
                RatingPromptReason.SERIES_FINALE,
                ratingPromptReasonFor(
                    contentType = "series",
                    seasonNumber = 2,
                    episodeNumber = 2,
                    seasonEpisodeNumbers = twoSeasons,
                    seriesStatus = status,
                ),
                "status=$status",
            )
        }
    }

    @Test
    fun `a mid-season episode does not prompt`() {
        assertNull(
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 1,
                episodeNumber = 2,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = "Ended",
            ),
        )
    }

    @Test
    fun `the newest episode of an airing season is not a finale`() {
        // The season is fully listed with unaired episodes; finishing episode 2 of 8 must stay
        // silent, or an airing show would ask for a rating every week.
        assertNull(
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 1,
                episodeNumber = 2,
                seasonEpisodeNumbers = mapOf(1 to setOf(1, 2, 3, 4, 5, 6, 7, 8)),
                seriesStatus = "Returning Series",
            ),
        )
    }

    @Test
    fun `specials never prompt`() {
        assertNull(
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 0,
                episodeNumber = 1,
                seasonEpisodeNumbers = mapOf(0 to setOf(1), 1 to setOf(1)),
                seriesStatus = "Ended",
            ),
        )
    }

    @Test
    fun `an episode from a season the metadata does not know is ignored`() {
        assertNull(
            ratingPromptReasonFor(
                contentType = "series",
                seasonNumber = 9,
                episodeNumber = 1,
                seasonEpisodeNumbers = twoSeasons,
                seriesStatus = "Ended",
            ),
        )
    }
}
