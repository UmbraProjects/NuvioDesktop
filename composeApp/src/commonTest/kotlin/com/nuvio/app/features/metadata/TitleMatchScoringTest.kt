package com.nuvio.app.features.metadata

import com.nuvio.app.features.locallibrary.normalizeLocalMatchTitle
import kotlin.test.Test
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
}
