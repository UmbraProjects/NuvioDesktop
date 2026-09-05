package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the Bulk Add paste format against the field's own help text and placeholder (plan §11). */
class DiscoverBingeCatExportTest {
    @Test
    fun `ids are joined in the form BingeCat's placeholder shows`() {
        val list = bingeCatBulkList(listOf("tmdb:550", "tt0133093", "tmdb:238"))

        assertEquals("tmdb:550, tt0133093, tmdb:238", list.text)
        assertEquals(3, list.included)
        assertEquals(0, list.skipped)
    }

    @Test
    fun `tmdb ids keep their prefix rather than becoming bare numbers`() {
        // A bare 550 is not an id BingeCat can place: the prefix is what says which namespace it is.
        assertEquals("tmdb:550", bingeCatBulkList(listOf("tmdb:550")).text)
    }

    @Test
    fun `unreadable ids are counted, not silently dropped`() {
        val list = bingeCatBulkList(listOf("tmdb:550", "kitsu:1376", "mal:9253", "someaddon:x"))

        assertEquals("tmdb:550", list.text)
        assertEquals(1, list.included)
        assertEquals(3, list.skipped)
    }

    @Test
    fun `duplicates collapse without counting as losses`() {
        val list = bingeCatBulkList(listOf("tmdb:550", "tmdb:550", "tt0133093"))

        assertEquals("tmdb:550, tt0133093", list.text)
        assertEquals(2, list.included)
        // The row held the title twice and the paste holds it once. Nothing was lost, so reporting a
        // skip here would send the user looking for a title that is present.
        assertEquals(0, list.skipped)
    }

    @Test
    fun `no titles are ever emitted`() {
        // Both documented separators occur inside real titles, so a title could be split into two
        // entries that each match something else. Ids only — see bingeCatBulkList.
        val list = bingeCatBulkList(listOf("kitsu:1376"))

        assertEquals("", list.text)
        assertEquals(0, list.included)
    }

    @Test
    fun `malformed ids do not reach the paste`() {
        val list = bingeCatBulkList(listOf("tmdb:", "tmdb:abc", "tt", "ttnotanumber", "  "))

        assertEquals("", list.text)
        assertEquals(5, list.skipped)
    }

    @Test
    fun `whitespace around an id is trimmed`() {
        assertEquals("tmdb:550, tt0111161", bingeCatBulkList(listOf(" tmdb:550 ", "\ttt0111161")).text)
    }

    @Test
    fun `an empty row produces an empty paste rather than a separator`() {
        val list = bingeCatBulkList(emptyList())

        assertTrue(list.text.isEmpty())
        assertEquals(0, list.included)
        assertEquals(0, list.skipped)
    }
}
