package com.nuvio.app.features.music.nebula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A slowed, live or instrumental copy shares every core word with the original; it must not be taken for it. */
class NebulaVariantTest {
    private fun album(id: String, title: String, n: Int = 18, y: Int? = 2000) = NebulaAlbumSummary(id, title, "Eminem", n, y)

    private val plain = album("plain", "The Marshall Mathers LP")
    private val chopped = album("chopped", "The Marshall Mathers LP (Slowed & Chopped)")

    @Test
    fun aSlowedAndChoppedAlbumIsNotThePlainOne() {
        assertNull(strictNebulaMatch(listOf(plain), "The Marshall Mathers LP (Slowed & Chopped)", "Eminem", "2000", 18))
        assertNull(strictNebulaMatch(listOf(chopped), "The Marshall Mathers LP", "Eminem", "2000", 18))
        assertTrue(rankNebulaAlbums(listOf(plain), "The Marshall Mathers LP (Slowed & Chopped)", "Eminem", "2000", 18).isEmpty())
    }

    @Test
    fun eachTitleFindsItsOwnRecordWhenBothAreOffered() {
        val both = listOf(plain, chopped)
        assertEquals("chopped", strictNebulaMatch(both, "The Marshall Mathers LP (Slowed & Chopped)", "Eminem", "2000", 18)?.id)
        assertEquals("plain", strictNebulaMatch(both, "The Marshall Mathers LP", "Eminem", "2000", 18)?.id)
    }

    @Test
    fun editionWordsStillMatchButLiveAndInstrumentalDoNot() {
        assertNotNull(strictNebulaMatch(listOf(album("d", "The Marshall Mathers LP (Deluxe Edition)")), "The Marshall Mathers LP", "Eminem", "2000", 18))
        assertNull(strictNebulaMatch(listOf(album("l", "The Marshall Mathers LP (Live)")), "The Marshall Mathers LP", "Eminem", "2000", 18))
        assertNull(strictNebulaMatch(listOf(album("i", "The Marshall Mathers LP [Instrumentals]")), "The Marshall Mathers LP", "Eminem", "2000", 18))
        // Both sides live: the same record.
        assertNotNull(strictNebulaMatch(listOf(album("l", "Live at Ford Field")), "Live at Ford Field", "Eminem", "2025", 18))
    }

    @Test
    fun variantWordsAreReadFromBracketsAndSuffixes() {
        assertEquals(setOf("slowed", "chopped"), albumVariantWords("Album (Slowed & Chopped)"))
        assertEquals(setOf("sped", "nightcore"), albumVariantWords("Album - Sped Up Nightcore"))
        assertEquals(emptySet(), albumVariantWords("Alive and Delivered"))
    }
}
