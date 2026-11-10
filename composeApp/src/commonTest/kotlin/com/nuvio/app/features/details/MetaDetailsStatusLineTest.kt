package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaDetailsStatusLineTest {
    private val names = mapOf("JP" to "Japan", "GB" to "United Kingdom", "FR" to "France", "US" to "United States")
    private fun line(meta: MetaDetails) = buildDetailStatusLine(meta) { names[it] }

    private fun episodes(seasons: Int, perSeason: Int) = (1..seasons).flatMap { s ->
        (1..perSeason).map { e -> MetaVideo(id = "x:$s:$e", title = "", season = s, episode = e) }
    }

    private fun series(
        status: String? = null,
        seriesType: String? = null,
        country: String? = "JP",
        videos: List<MetaVideo> = episodes(2, 10),
    ) = MetaDetails(id = "tt1", type = "series", name = "S", status = status, seriesType = seriesType, country = country, videos = videos)

    @Test
    fun returningSeriesReadsAiringWithCountryName() {
        assertEquals(
            DetailStatusLine(DetailStatus.Airing, DetailForm.Series, "Japan"),
            line(series(status = "Returning Series")),
        )
    }

    @Test
    fun cancelledCountsSeasons() {
        assertEquals(
            DetailStatusLine(DetailStatus.Cancelled, DetailForm.Series, "Japan", cancelledAfterSeasons = 2),
            line(series(status = "Canceled")),
        )
    }

    @Test
    fun tmdbMiniseriesDropsRedundantEnded() {
        assertEquals(
            DetailStatusLine(null, DetailForm.LimitedSeries, "United Kingdom"),
            line(series(status = "Ended", seriesType = "Miniseries", country = "GB, US")),
        )
    }

    @Test
    fun oneShortFinishedSeasonWithoutTmdbTypeIsLimited() {
        assertEquals(DetailForm.LimitedSeries, line(series(status = "Ended", videos = episodes(1, 6)))?.form)
    }

    @Test
    fun scriptedTypeOverridesShortSeasonHeuristic() {
        assertEquals(DetailForm.Series, line(series(status = "Ended", seriesType = "Scripted", videos = episodes(1, 6)))?.form)
    }

    @Test
    fun seriesWithoutCountryShowsStatusOnly() {
        assertEquals(DetailStatusLine(DetailStatus.Ended, null, null), line(series(status = "Ended", country = null)))
    }

    @Test
    fun lowercaseAlpha3CodeIsLookedUpUppercase() {
        assertEquals("Australia", buildDetailStatusLine(series(status = "Ended", country = "aus")) { if (it == "AUS") "Australia" else null }?.country)
    }

    @Test
    fun unknownCodeIsUppercased() {
        assertEquals("USA", line(series(status = "Ended", country = "usa"))?.country)
    }

    @Test
    fun addonFreeTextCountryIsKept() {
        assertEquals("USA", line(series(status = "Ended", country = "USA, Canada"))?.country)
    }

    @Test
    fun releasedEnglishFilmHasNoLine() {
        assertNull(line(MetaDetails(id = "tt2", type = "movie", name = "M", status = "Released", country = "US", language = "en")))
    }

    @Test
    fun foreignFilmShowsCountry() {
        assertEquals(
            DetailStatusLine(null, DetailForm.Film, "France"),
            line(MetaDetails(id = "tt3", type = "movie", name = "M", status = "Released", country = "FR", language = "fr")),
        )
    }

    @Test
    fun englishFilmShowsDirectors() {
        assertEquals(
            DetailStatusLine(null, null, null, directors = "Joel Coen & Ethan Coen"),
            line(MetaDetails(id = "tt5", type = "movie", name = "M", status = "Released", country = "US", language = "en", director = listOf("Joel Coen", "Ethan Coen", "Joel Coen"))),
        )
    }

    @Test
    fun unreleasedFilmIsUpcoming() {
        assertEquals(
            DetailStatusLine(DetailStatus.Upcoming, null, null),
            line(MetaDetails(id = "tt4", type = "movie", name = "M", status = "Post Production", country = "US", language = "en")),
        )
    }
}
