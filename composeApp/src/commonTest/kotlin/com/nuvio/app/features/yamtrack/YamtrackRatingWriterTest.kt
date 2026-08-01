package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.tracking.TrackingMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

class YamtrackRatingWriterTest {
    @Test
    fun `score body supports setting and clearing`() {
        assertEquals("{\"score\":8}", YamtrackRatingWriter.buildScoreBody(8))
        assertEquals("{\"score\":null}", YamtrackRatingWriter.buildScoreBody(null))
    }

    @Test
    fun `anime is addressed as tv, never as Floppy's MAL-backed anime type`() {
        assertEquals(
            "tv",
            YamtrackRatingWriter.floppyRatingMediaType(
                kind = TrackingMediaKind.ANIME,
                preferredTmdbMediaType = "series",
                contentType = "anime",
            ),
        )
    }

    @Test
    fun `an anime film is addressed as a movie`() {
        assertEquals(
            "movie",
            YamtrackRatingWriter.floppyRatingMediaType(
                kind = TrackingMediaKind.ANIME,
                preferredTmdbMediaType = "movie",
                contentType = "anime",
            ),
        )
    }

    @Test
    fun `ordinary movies and shows keep their types`() {
        assertEquals(
            "movie",
            YamtrackRatingWriter.floppyRatingMediaType(
                kind = TrackingMediaKind.MOVIE,
                preferredTmdbMediaType = null,
                contentType = "movie",
            ),
        )
        assertEquals(
            "tv",
            YamtrackRatingWriter.floppyRatingMediaType(
                kind = TrackingMediaKind.SHOW,
                preferredTmdbMediaType = null,
                contentType = "series",
            ),
        )
    }

    @Test
    fun `a movie content type wins when nothing else identifies the kind`() {
        assertEquals(
            "movie",
            YamtrackRatingWriter.floppyRatingMediaType(
                kind = TrackingMediaKind.ANIME,
                preferredTmdbMediaType = null,
                contentType = " Film ",
            ),
        )
    }
}
