package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class KeptRecordThumbnailTest {
    private val tvdbStill = "https://artworks.thetvdb.com/banners/episodes/161511/5749165.jpg"
    private val tmdbStill = "https://image.tmdb.org/t/p/w1280/abc.jpg"

    private fun video(thumbnail: String?, released: String? = "2017-11-19T00:00:00.000Z", title: String = "You Sold Me the Laundromat, Remember?") =
        MetaVideo(id = "tt1586680:7:8", title = title, released = released, season = 7, episode = 8, thumbnail = thumbnail)

    private fun tmdb(airDate: String? = "2017-11-19", title: String? = "Something Else") = TmdbEpisodeEnrichment(
        title = title,
        overview = null,
        thumbnail = tmdbStill,
        airDate = airDate,
        runtimeMinutes = null,
    )

    @Test
    fun tvdbStillGivesWayToTmdbForTheSameAirDate() {
        assertEquals(tmdbStill, keptRecordThumbnail(video(tvdbStill), tmdb()))
    }

    @Test
    fun tvdbStillGivesWayToTmdbForTheSameTitle() {
        assertEquals(
            tmdbStill,
            keptRecordThumbnail(video(tvdbStill, released = null), tmdb(airDate = null, title = "You Sold Me the Laundromat, Remember")),
        )
    }

    @Test
    fun tvdbStillStaysWhenTmdbMayMeanAnotherEpisode() {
        assertEquals(tvdbStill, keptRecordThumbnail(video(tvdbStill), tmdb(airDate = "2017-11-26")))
    }

    @Test
    fun anotherHostsStillIsNeverReplaced() {
        val own = "https://example.org/still.jpg"
        assertEquals(own, keptRecordThumbnail(video(own), tmdb()))
    }

    @Test
    fun blankStillIsFilledFromTmdb() {
        assertEquals(tmdbStill, keptRecordThumbnail(video(null), tmdb(airDate = "2001-01-01")))
    }
}
