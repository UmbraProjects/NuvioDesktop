package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongStoryTest {
    private val article = "Jackson wrote it in 1982. Quincy Jones said: \"It was the best bass line I ever heard\" about it."

    @Test
    fun quotesNotInTheArticleAreDropped() {
        val reply = "{\"made\": \"Jackson wrote it in 1982 and Quincy Jones produced it.\", \"quotes\": [" +
            "{\"text\": \"It was the best bass line I ever heard\", \"who\": \"Quincy Jones\"}," +
            "{\"text\": \"I wrote this song in my sleep one night\", \"who\": \"Michael Jackson\"}]}"
        val story = assertNotNull(parseSongStory(reply, article))
        assertEquals(1, story.quotes.size)
        assertEquals("Quincy Jones", story.quotes.first().who)
        assertTrue(story.ai)
    }

    @Test
    fun aReplyWithNothingUsableGivesNoStory() {
        assertNull(parseSongStory("{\"made\": \"short\"}", article))
        assertNull(parseSongStory("no json here", article))
    }

    @Test
    fun theStoryPageIsKeptForASongThatHasOnlyAStory() {
        val request = BookletRequest("A", "B", null, listOf(BookletTrack("One", 100)))
        val booklet = AlbumBooklet("intro text of the album here", emptyList(), emptyMap(), BookletSource.Ai)
        val story = SongStory("Made in a day, by hand, in a room.", null, null, emptyList(), ai = false)
        val pages = linerPages(booklet, request, playing = 1, story = story)
        val track = pages.filterIsInstance<LinerPage.Track>().single()
        assertEquals(story, track.story)
        assertTrue(linerPages(booklet, request, playing = 1).none { it is LinerPage.Track })
    }

    @Test
    fun aWebSearchReplyMayQuotePagesItReadButNotItsOwnAnswer() {
        val reply = "Snippet from an interview: Jackson told the magazine \"I wrote the bassline first and the words came after\" in 1983.\n\n" +
            "{\"made\": \"Jackson wrote the bassline first and the words followed, in 1982.\", \"quotes\": [" +
            "{\"text\": \"I wrote the bassline first and the words came after\", \"who\": \"Michael Jackson\"}," +
            "{\"text\": \"This is a line only the answer ever says out loud\", \"who\": \"Michael Jackson\"}]}"
        val story = assertNotNull(parseSongStory(reply, article = ""))
        assertEquals(listOf("I wrote the bassline first and the words came after"), story.quotes.map { it.text })
    }
}
