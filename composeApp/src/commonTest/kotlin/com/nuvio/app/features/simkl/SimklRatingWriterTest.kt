package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingMediaKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklRatingWriterTest {
    @Test
    fun `movie rating uses movie collection and score`() {
        val body = SimklRatingWriter.buildRatingBody(
            item = SimklScrobbleItem.Movie(
                title = "Fight Club",
                ids = SimklScrobbleRepository.SimklIds(tmdb = 550),
            ),
            kind = TrackingMediaKind.MOVIE,
            rating = 9,
        )

        assertTrue(body.contains("\"movies\""))
        assertTrue(body.contains("\"rating\":9"))
        assertTrue(body.contains("\"tmdb\":550"))
        assertFalse(body.contains("\"shows\":[{"))
    }

    @Test
    fun `clearing a rating omits the score for the remove endpoint`() {
        val body = SimklRatingWriter.buildRatingBody(
            item = SimklScrobbleItem.Movie(
                title = "Fight Club",
                ids = SimklScrobbleRepository.SimklIds(simkl = 550),
            ),
            kind = TrackingMediaKind.SHOW,
            rating = null,
        )

        assertTrue(body.contains("\"shows\""))
        assertFalse(body.contains("\"rating\""))
    }
}
