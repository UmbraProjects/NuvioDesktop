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
