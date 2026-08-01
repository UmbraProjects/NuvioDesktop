package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdbListRatingWriterTest {
    @Test
    fun `movie rating uses movies collection and score`() {
        val body = MdbListRatingWriter.buildRatingBody(
            ids = MdbListScrobbleRepository.MdbListIds(imdb = "tt0137523", tmdb = 550),
            mediaType = "movie",
            rating = 9,
        )

        assertTrue(body.contains("\"movies\""))
        assertTrue(body.contains("\"rating\":9"))
        assertTrue(body.contains("\"imdb\":\"tt0137523\""))
        assertTrue(body.contains("\"tmdb\":550"))
        assertFalse(body.contains("\"shows\""))
    }

    @Test
    fun `clearing a show rating omits score for remove endpoint`() {
        val body = MdbListRatingWriter.buildRatingBody(
            ids = MdbListScrobbleRepository.MdbListIds(tvdb = 81189),
            mediaType = "show",
            rating = null,
        )

        assertTrue(body.contains("\"shows\""))
        assertTrue(body.contains("\"tvdb\":81189"))
        assertFalse(body.contains("\"rating\""))
        assertFalse(body.contains("\"movies\""))
    }

    @Test
    fun `anime movie is sent through movies collection`() {
        assertEquals(
            "movie",
            MdbListRatingWriter.ratingMediaType(
                kind = TrackingMediaKind.ANIME,
                preferredTmdbMediaType = "movie",
                contentType = "anime",
            ),
        )
    }

    @Test
    fun `anime series is sent through shows collection`() {
        assertEquals(
            "show",
            MdbListRatingWriter.ratingMediaType(
                kind = TrackingMediaKind.ANIME,
                preferredTmdbMediaType = "tv",
                contentType = "anime",
            ),
        )
    }
}
