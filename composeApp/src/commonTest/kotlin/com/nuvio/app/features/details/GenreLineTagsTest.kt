package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class GenreLineTagsTest {
    @Test
    fun takesTwoSubgenresFirstThenThemes() {
        val card = GenreSubgenres.Card(subgenres = listOf("Folk Horror"), themes = listOf("Found footage", "Slasher"))
        assertEquals(listOf("Folk Horror", "Found footage"), GenreSubgenres.lineTags(card, listOf("Horror")))
    }

    @Test
    fun dropsTagsThatRepeatAGenreOrEachOther() {
        val card = GenreSubgenres.Card(subgenres = listOf("Horror", "Satire"), themes = listOf("satire", "Revenge"))
        assertEquals(listOf("Satire", "Revenge"), GenreSubgenres.lineTags(card, listOf("Horror", "Comedy")))
    }

    @Test
    fun emptyCardAddsNothing() {
        assertEquals(emptyList(), GenreSubgenres.lineTags(GenreSubgenres.Card(emptyList(), emptyList()), listOf("Drama")))
    }
}
