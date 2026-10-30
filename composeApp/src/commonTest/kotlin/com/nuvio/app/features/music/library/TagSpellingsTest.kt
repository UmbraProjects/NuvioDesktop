package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagSpellingsTest {
    private fun artist(id: String, vararg votes: Pair<String, Int>) =
        LadderCandidate(id = id, name = id, votes = votes.toMap())

    @Test
    fun aHyphenatedGenreIsAlsoSearchedWithSpaces() {
        assertEquals(listOf("hip-hop", "hip hop"), tagSpellings("hip-hop"))
        assertEquals(listOf("trip-hop", "trip hop"), tagSpellings("trip-hop"))
    }

    @Test
    fun aSpacedGenreIsAlsoSearchedWithHyphens() {
        assertEquals(listOf("hip hop", "hip-hop"), tagSpellings("hip hop"))
    }

    @Test
    fun aSingleWordHasOneSpelling() {
        assertEquals(listOf("shoegaze"), tagSpellings("shoegaze"))
    }

    @Test
    fun spellingsAreTrimmedAndBlankHasNone() {
        assertEquals(listOf("Trip-Hop", "Trip Hop"), tagSpellings("  Trip-Hop "))
        assertTrue(tagSpellings("   ").isEmpty())
    }

    @Test
    fun mergingKeepsEachArtistOnceMostVotedFirst() {
        val spellings = listOf("hip-hop", "hip hop")
        // The same artist comes back under both spellings with all of its tags, as a search returns them.
        val both = arrayOf("hip-hop" to 2, "hip hop" to 10, "rap" to 9)
        val few = listOf(artist("a", "hip-hop" to 1), artist("b", *both))
        val many = listOf(artist("c", "hip hop" to 30), artist("b", *both), artist("d", "hip hop" to 5))
        val merged = mergeTagSpellings(spellings, listOf(few, many), limit = 100)
        // Votes for the tag across both spellings: c 30, b 12 (the "rap" votes are another tag), d 5, a 1.
        assertEquals(listOf("c", "b", "d", "a"), merged.map { it.id })
        assertEquals(1, merged.count { it.id == "b" })
    }

    @Test
    fun mergingHonoursTheLimit() {
        val found = listOf(
            listOf(artist("a", "x-y" to 5)),
            listOf(artist("b", "x y" to 9), artist("c", "x y" to 7)),
        )
        assertEquals(listOf("b", "c"), mergeTagSpellings(listOf("x-y", "x y"), found, limit = 2).map { it.id })
    }

    @Test
    fun aSingleSpellingIsReturnedAsItCame() {
        val only = listOf(artist("z", "shoegaze" to 1), artist("y", "shoegaze" to 9))
        assertEquals(listOf("z", "y"), mergeTagSpellings(listOf("shoegaze"), listOf(only), limit = 100).map { it.id })
    }

    @Test
    fun aSpellingWithNobodyStillMergesTheOther() {
        val merged = mergeTagSpellings(
            listOf("hip-hop", "hip hop"),
            listOf(emptyList(), listOf(artist("c", "hip hop" to 3))),
            limit = 100,
        )
        assertEquals(listOf("c"), merged.map { it.id })
    }
}
