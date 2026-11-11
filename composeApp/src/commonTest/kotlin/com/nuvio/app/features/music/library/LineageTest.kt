package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LineageTest {
    private val reply = """Here you go: {
      "before": [
        {"artist": "Stevie Wonder", "title": "Songs in the Key of Life", "year": 1976, "why": "Big warm funk-soul"},
        {"artist": "Jamiroquai", "title": "Emergency on Planet Earth", "year": 1993, "why": "their own debut"},
        {"artist": "Herbie Hancock", "title": "Head Hunters", "year": "1973", "why": "Jazz-funk grooves"},
        {"artist": "Future Band", "title": "Wrong Side", "year": 2001, "why": "after, not before"},
        {"artist": "Stevie Wonder", "title": "Songs in the Key of Life (Deluxe)", "year": 1976, "why": "repeat"},
        {"artist": "No Year", "title": "Untitled", "why": "dropped"}
      ],
      "after": [{"artist": "Daft Punk", "title": "Discovery", "year": 2001, "why": "Disco and funk rebuilt"}],
      "before_thread": "Warm 70s funk with a London finish.",
      "after_thread": "Later records with the same live funk feel."
    }"""

    @Test
    fun theReplyIsCheckedAgainstTheAlbumsYearAndArtist() {
        val l = parseLineage(reply, 1994, "Jamiroquai")!!
        assertEquals(listOf("Songs in the Key of Life", "Head Hunters"), l.before.map { it.title })
        assertEquals(1973, l.before[1].year)
        assertEquals(listOf("Discovery"), l.after.map { it.title })
        assertEquals("Warm 70s funk with a London finish.", l.beforeThread)
        assertNull(parseLineage("{\"before\": [], \"after\": []}", 1994, "X"))
        assertNull(parseLineage("nothing", 1994, "X"))
    }

    @Test
    fun gapsReadBothWays() {
        assertEquals("−18 yrs", lineageGap(1994, 1976))
        assertEquals("+1 yr", lineageGap(1994, 1995))
        assertEquals("Same year", lineageGap(1994, 1994))
    }

    @Test
    fun aRecordIsFoundInTheLibraryByArtistAndTitle() {
        val daft = LibraryArtist(
            id = "UCdp", name = "Daft Punk",
            releases = listOf(
                LibraryRelease("s", "Discovery", ReleaseKind.Single, ytBrowseId = "MPREb_s"),
                LibraryRelease("a", "Discovery (Remastered)", ReleaseKind.Album, ytBrowseId = "MPREb_a"),
                LibraryRelease("h", "Homework", ReleaseKind.Album),
            ),
        )
        val beatles = LibraryArtist(id = "UCb", name = "The Beatles", releases = listOf(LibraryRelease("r", "Revolver", ReleaseKind.Album, ytBrowseId = "MPREb_r")))
        val m = findInLibrary(LineageRecord("Daft Punk", "Discovery", 2001, ""), listOf(beatles, daft))
        assertNotNull(m)
        assertEquals("MPREb_a", m.release.ytBrowseId) // the album over the single of the same name
        assertNull(findInLibrary(LineageRecord("Daft Punk", "Homework", 1997, ""), listOf(daft))) // nothing to open it with
        assertNotNull(findInLibrary(LineageRecord("Beatles", "Revolver", 1966, ""), listOf(beatles)))
        assertNull(findInLibrary(LineageRecord("Someone Else", "Discovery", 2001, ""), listOf(daft)))
    }

    @Test
    fun theAlbumYearIsRead() {
        assertEquals(2011, lineageYear("2011-01-19"))
        assertNull(lineageYear(null))
        assertNull(lineageYear("n/a"))
    }
}
