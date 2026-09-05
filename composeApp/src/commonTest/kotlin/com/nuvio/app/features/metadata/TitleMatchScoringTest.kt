package com.nuvio.app.features.metadata

import com.nuvio.app.features.locallibrary.normalizeLocalMatchTitle
import com.nuvio.app.features.tmdb.TmdbSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleMatchScoringTest {

    private fun score(release: String, tmdb: String, fuzzy: Boolean = false): Double =
        titleSimilarity(
            normalizeLocalMatchTitle(release),
            normalizeLocalMatchTitle(tmdb),
            allowCharacterSimilarity = fuzzy,
        )

    @Test
    fun punctuationOnlyDifferencesAreAnExactMatch() {
        // The apostrophe normalises to a space, which used to split "Pan's" into two tokens and
        // drag the score down to 0.25.
        listOf(
            "Pans Labyrinth" to "Pan's Labyrinth",
            "Widows Bay" to "Widow's Bay",
            "Spider Man" to "Spider-Man",
            "WALL E" to "WALL·E",
        ).forEach { (release, tmdb) ->
            assertTrue(score(release, tmdb) >= 1.0, "$release vs $tmdb scored ${score(release, tmdb)}")
        }
    }

    @Test
    fun fuzzyModeRescuesOneLeftoverToken() {
        assertTrue(score("friends 1994", "Friends") < TITLE_MATCH_ACCEPT_SCORE)
        assertTrue(score("friends 1994", "Friends", fuzzy = true) >= TITLE_MATCH_ACCEPT_SCORE)
    }

    @Test
    fun fuzzyModeStillRejectsADifferentTitle() {
        listOf(
            "Dune" to "Dune Part Two",
            "From" to "From Dusk Till Dawn",
            "The Bear" to "The Bear and the Nightingale",
        ).forEach { (release, tmdb) ->
            val fuzzyScore = score(release, tmdb, fuzzy = true)
            assertTrue(fuzzyScore < TITLE_MATCH_ACCEPT_SCORE, "$release vs $tmdb scored $fuzzyScore")
        }
    }

    @Test
    fun localLibraryScoringIsUnchangedByFuzzyMode() {
        // The default (local-library) path must stay strict: a wrong match there scrobbles a wrong
        // show, so it may not inherit the character-level floor.
        assertTrue(score("friends 1994", "Friends") < TITLE_MATCH_ACCEPT_SCORE)
    }

    @Test
    fun yearGapsUpToTwoStayReachable() {
        assertTrue(yearMismatchPenalty(2006, 2006) == 0.0)
        assertTrue(yearMismatchPenalty(2006, 2007) == 0.0)
        assertTrue(yearMismatchPenalty(2006, 2008) < 0.45)
        assertTrue(yearMismatchPenalty(1994, 2004) >= 0.45)
        assertTrue(yearMismatchPenalty(null, 2004) == 0.0)
    }

    @Test
    fun exactYearWinsWhenTitlesTie() {
        // A sequel whose only distinguishing mark is punctuation normalisation erases scores 1.0
        // against both entries, and the free ±1 year gap leaves them tied on score as well.
        val season1 = tvResult(id = 1, name = "Kaguya-sama: Love is War", firstAirDate = "2019-01-12")
        val season2 = tvResult(id = 2, name = "Kaguya-sama: Love is War?", firstAirDate = "2020-04-11")

        listOf(listOf(season1, season2), listOf(season2, season1)).forEach { results ->
            assertEquals(
                2,
                pickBestTmdbMatch("Kaguya-sama Love is War", year = 2020, results = results)?.id,
            )
            assertEquals(
                1,
                pickBestTmdbMatch("Kaguya-sama Love is War", year = 2019, results = results)?.id,
            )
        }
    }

    @Test
    fun exactYearNeverRescuesARejectedTitle() {
        // The tie-break may reorder candidates but must not lift one over the accept threshold.
        val wrongTitle = tvResult(id = 3, name = "Kaguya-sama: Love is War", firstAirDate = "2020-04-11")
        assertEquals(null, pickBestTmdbMatch("Something Else Entirely", year = 2020, results = listOf(wrongTitle)))
    }

    private fun tvResult(id: Int, name: String, firstAirDate: String) =
        TmdbSearchResult(id = id, name = name, firstAirDate = firstAirDate, mediaType = "tv")
}
