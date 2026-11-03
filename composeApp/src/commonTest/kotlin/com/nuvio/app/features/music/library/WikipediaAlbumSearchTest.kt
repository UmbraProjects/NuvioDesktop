package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WikipediaAlbumSearchTest {
    private val body = "Travelling Without Moving is the third studio album by the British acid jazz band Jamiroquai, released in 1996. ".repeat(3)

    private fun answer(vararg pages: Triple<String, Int, String>): String =
        "{\"query\":{\"pages\":[" + pages.joinToString(",") { (title, index, extract) ->
            "{\"pageid\":$index,\"title\":\"$title\",\"index\":$index,\"extract\":\"$extract\"}"
        } + "]}}"

    @Test
    fun takesTheAlbumPageAndSkipsTheSong() {
        val json = answer(
            Triple("Travelling Without Moving (song)", 1, "The song by Jamiroquai. ".repeat(20)),
            Triple("Travelling Without Moving", 2, body),
        )
        assertEquals(body.trim(), parseWikipediaAlbumSearch(json, "Travelling Without Moving", "Jamiroquai"))
    }

    @Test
    fun anEditionSuffixOnTheAlbumTitleStillMatches() {
        val json = answer(Triple("Travelling Without Moving", 1, body))
        assertEquals(body.trim(), parseWikipediaAlbumSearch(json, "Travelling Without Moving (Deluxe)", "Jamiroquai"))
    }

    @Test
    fun anUnbracketedEditionStillMatches() {
        val damn = "Damn is the fourth studio album by the American rapper Kendrick Lamar, released on April 14, 2017. ".repeat(3)
        val json = answer(
            Triple("Damn Yankees (band)", 1, "Damn Yankees was an American hard rock supergroup formed in 1989. ".repeat(5)),
            Triple("Damn (album)", 2, damn),
        )
        assertEquals(damn.trim(), parseWikipediaAlbumSearch(json, "DAMN. COLLECTORS EDITION.", "Kendrick Lamar"))
    }

    @Test
    fun editionWordsComeOffTheSearchTitle() {
        assertEquals("DAMN.", albumSearchTitle("DAMN. COLLECTORS EDITION."))
        assertEquals("Rumours", albumSearchTitle("Rumours (Super Deluxe Edition)"))
        assertEquals("Purple Rain", albumSearchTitle("Purple Rain Deluxe Expanded Edition"))
        assertEquals("Born To Run", albumSearchTitle("Born To Run 30th Anniversary Edition"))
        assertEquals("Nevermind", albumSearchTitle("Nevermind Remastered"))
        // A title that only looks like an edition word is left alone.
        assertEquals("Grand Tour", albumSearchTitle("Grand Tour"))
        assertEquals("ABBA Gold", albumSearchTitle("ABBA Gold"))
        assertEquals("Edition", albumSearchTitle("Edition"))
    }

    @Test
    fun aPageThatDoesNotNameTheArtistIsNotTheAlbum() {
        val json = answer(Triple("Travelling Without Moving", 1, "A book about trains and journeys by someone else. ".repeat(10)))
        assertNull(parseWikipediaAlbumSearch(json, "Travelling Without Moving", "Jamiroquai"))
    }

    @Test
    fun aStubIsNotEnough() {
        assertNull(parseWikipediaAlbumSearch(answer(Triple("Travelling Without Moving", 1, "A Jamiroquai album.")), "Travelling Without Moving", "Jamiroquai"))
    }

    @Test
    fun noPagesNoText() {
        assertNull(parseWikipediaAlbumSearch("{\"batchcomplete\":true}", "A", "B"))
        assertNull(parseWikipediaAlbumSearch("not json", "A", "B"))
    }
}

class WikipediaAlbumPickTest {
    private val offTheWall = "Off the Wall is the fifth studio album by the American singer Michael Jackson, released on August 10, 1979, by Epic Records. ".repeat(3)

    private fun answer(vararg pages: Triple<String, Int, String>): String =
        "{\"query\":{\"pages\":[" + pages.joinToString(",") { (title, index, extract) ->
            "{\"pageid\":$index,\"title\":\"$title\",\"index\":$index,\"extract\":\"$extract\"}"
        } + "]}}"

    @Test
    fun givesTheTitleToFetchTheFullPageWith() {
        val pick = pickWikipediaAlbum(answer(Triple("Off the Wall", 1, offTheWall)), "Off the Wall", "Michael Jackson")
        assertEquals("Off the Wall", pick?.title)
    }

    @Test
    fun aBandPageWithTheAlbumsNameIsNotTheAlbum() {
        val band = "Metallica is an American heavy metal band formed in Los Angeles in 1981 by Lars Ulrich and James Hetfield. ".repeat(3)
        val album = "Metallica is the self-titled fifth studio album by the American heavy metal band Metallica, released in 1991. ".repeat(3)
        val pick = pickWikipediaAlbum(answer(Triple("Metallica", 1, band), Triple("Metallica (album)", 2, album)), "Metallica", "Metallica")
        assertEquals("Metallica (album)", pick?.title)
    }

    @Test
    fun readsTheExtractOfOnePage() {
        val body = "{\"query\":{\"pages\":[{\"pageid\":1,\"title\":\"Off the Wall\",\"extract\":\" Full text here. \"}]}}"
        assertEquals("Full text here.", parseWikipediaPageExtract(body))
        assertNull(parseWikipediaPageExtract("{\"query\":{\"pages\":[{\"pageid\":1,\"title\":\"X\"}]}}"))
    }
}
