package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun film(
    id: String,
    genres: List<String> = emptyList(),
    minutes: Int? = 110,
    rating: Double? = 7.0,
    year: Int? = 2020,
    origins: Set<TonightOrigin> = setOf(TonightOrigin.Library),
    watched: Boolean = false,
    started: Boolean = false,
) = TonightTitle(
    type = "movie", id = id, name = id, kind = TonightKind.Film, genres = genres, runtimeMinutes = minutes,
    rating = rating, year = year, origins = origins, watched = watched, started = started,
)

internal fun show(
    id: String,
    genres: List<String> = emptyList(),
    episodeMinutes: Int? = 30,
    origins: Set<TonightOrigin> = setOf(TonightOrigin.Library),
    started: Boolean = false,
    year: Int? = 2022,
) = TonightTitle(
    type = "series", id = id, name = id, kind = TonightKind.Show, genres = genres, runtimeMinutes = episodeMinutes,
    origins = origins, started = started, year = year, rating = 7.5,
)

class TonightRulesTest {
    @Test
    fun comedyIsLightAndWarDramaHeavy() {
        assertTrue(tonightLightness(film("a", listOf("Comedy", "Family"), minutes = 95)) > 0.5f)
        assertTrue(tonightLightness(film("b", listOf("War", "Drama"), minutes = 165)) < -0.5f)
    }

    @Test
    fun noGenresAndAverageLengthIsNeutral() {
        assertEquals(0f, tonightLightness(film("c", minutes = 110, rating = null)))
    }

    @Test
    fun noveltyRunsFromStartedToTrending() {
        assertEquals(-1f, tonightNovelty(film("a", started = true)))
        assertTrue(tonightNovelty(film("b", watched = true)) < 0f)
        assertTrue(tonightNovelty(film("c")) > 0f)
        assertTrue(tonightNovelty(film("d", origins = setOf(TonightOrigin.Trending))) > tonightNovelty(film("c")))
    }

    @Test
    fun bedtimeAfterMidnightIsLaterTonight() {
        assertEquals(140, tonightMinutesUntilBed(nowMinuteOfDay = 21 * 60 + 10, bedMinuteOfDay = 23 * 60 + 30))
        assertEquals(200, tonightMinutesUntilBed(nowMinuteOfDay = 21 * 60 + 10, bedMinuteOfDay = 30))
        assertEquals(-20, tonightMinutesUntilBed(nowMinuteOfDay = 23 * 60 + 50, bedMinuteOfDay = 23 * 60 + 30))
    }

    @Test
    fun filmMustEndByBedtimeButAShowNeedsOneEpisode() {
        assertTrue(film("a", minutes = 140).fitsBedtime(140))
        assertTrue(film("b", minutes = 148).fitsBedtime(140)) // ten minute grace
        assertFalse(film("c", minutes = 170).fitsBedtime(140))
        assertTrue(film("d", minutes = null).fitsBedtime(10))
        assertTrue(show("e", episodeMinutes = 45).fitsBedtime(60))
        assertEquals(4, tonightEpisodesBeforeBed(30, 140))
        assertEquals(1, tonightEpisodesBeforeBed(60, 20))
    }

    @Test
    fun aHalfWatchedFilmCountsWhatIsLeft() {
        val half = film("a", minutes = 160).copy(resumeMinutesLeft = 50)
        assertTrue(half.fitsBedtime(60))
        assertEquals("22:00", half.endsAtLabel(21 * 60 + 10))
    }

    @Test
    fun clockAndLengthLabels() {
        assertEquals("00:30", clockLabel(24 * 60 + 30))
        assertEquals("23:06", clockLabel(23 * 60 + 6))
        assertEquals("1h 56m", lengthLabel(116))
        assertEquals("2h", lengthLabel(120))
        assertEquals("45m", lengthLabel(45))
    }

    @Test
    fun earlyHoursBelongToTheNightBefore() {
        assertEquals("2026-11-10", tonightNightOf("2026-11-11", 30, "2026-11-10"))
        assertEquals("2026-11-11", tonightNightOf("2026-11-11", 21 * 60, "2026-11-10"))
    }

    @Test
    fun tasteLeansOnWhatYouAreWatching() {
        val taste = TonightTaste.from(
            listOf(
                show("s", listOf("Comedy"), started = true),
                film("w", listOf("Comedy"), watched = true),
                film("l", listOf("Horror")),
                film("t", listOf("Western"), origins = setOf(TonightOrigin.Trending)),
            ),
        )
        assertEquals(1f, taste.score(film("x", listOf("Comedy"))))
        assertTrue(taste.score(film("y", listOf("Horror"))) < 0.5f)
        assertEquals(0f, taste.score(film("z", listOf("Western"))))
        assertEquals("Comedy", taste.topGenre(film("q", listOf("Horror", "Comedy"))))
    }
}
