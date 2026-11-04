package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongWikipediaTest {
    private fun search(vararg pages: Triple<String, Int, String>) =
        "{\"query\":{\"pages\":[" + pages.joinToString(",") { (t, i, e) -> "{\"title\":\"$t\",\"index\":$i,\"extract\":\"$e\"}" } + "]}}"

    @Test
    fun theSongArticleIsPickedNotTheAlbumOrTheArtist() {
        val body = search(
            Triple("Thriller (album)", 1, "Thriller is the sixth studio album by Michael Jackson."),
            Triple("Michael Jackson", 2, "Michael Jackson was an American singer."),
            Triple("Billie Jean", 3, "\\\"Billie Jean\\\" is a song by the American singer Michael Jackson, released as a single."),
        )
        assertEquals("Billie Jean", pickWikipediaSongTitle(body, "Billie Jean", "Michael Jackson"))
    }

    @Test
    fun aSongByAnotherArtistIsNotTaken() {
        val body = search(Triple("Human Nature (Madonna song)", 1, "\\\"Human Nature\\\" is a song by Madonna."))
        assertNull(pickWikipediaSongTitle(body, "Human Nature", "Michael Jackson"))
    }

    @Test
    fun sectionsDropTheListsAndKeepTheStory() {
        val text = "Lead text about the song.\n\n== Background ==\nIt was written at home.\n\n== Charts ==\nNo. 1.\n\n== Legacy ==\nIt has been sampled."
        val sections = songSections(text)
        assertEquals(setOf("", "Background", "Legacy"), sections.keys)
        assertEquals("It was written at home.", songMadeText(sections, 200))
        assertEquals("It has been sampled.", songAfterText(sections, 200))
        assertNull(songMeaningText(sections, 200))
    }

    @Test
    fun aQuoteMustStandWordForWordInTheArticle() {
        val article = "Jones said: \"It was the best bass line I ever heard\", and left."
        assertTrue(quoteIsVerbatim("It was the best bass line I ever heard", article))
        assertFalse(quoteIsVerbatim("It was the greatest bass line ever recorded", article))
        assertFalse(quoteIsVerbatim("short", "short"))
    }
}
