package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriviaPrerollCandidatesTest {
    private fun same(film: String, other: String) = sharesFranchise(franchiseTokens(film), other)

    @Test
    fun sequelsOfTheFilmAreSameFranchise() {
        assertTrue(same("The Godfather", "The Godfather Part II"))
        assertTrue(same("The Godfather", "The Godfather Part III"))
        assertTrue(same("Rocky", "Rocky II"))
    }

    @Test
    fun sequelNumberingOnTheFilmItselfIsIgnored() {
        assertTrue(same("The Godfather Part II", "The Godfather"))
        assertTrue(same("Rocky II", "Rocky III"))
        assertEquals(listOf("the", "godfather"), franchiseTokens("The Godfather Part II"))
    }

    @Test
    fun subtitleIsNotPartOfTheFranchise() {
        assertTrue(same("Top Gun: Maverick", "Top Gun"))
        assertTrue(same("Spider-Man: Brand New Day", "Spider-Man: Homecoming"))
    }

    @Test
    fun unrelatedTitlesSurvive() {
        assertFalse(same("The Godfather", "Casino"))
        assertFalse(same("The Godfather", "A Bronx Tale"))
        assertFalse(same("The Godfather", "The Irishman"))
    }

    @Test
    fun wordBoundaryNotJustPrefix() {
        assertFalse(same("Her", "Hero"))
        assertFalse(same("Up", "Upgrade"))
    }
}
