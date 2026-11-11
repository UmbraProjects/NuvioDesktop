package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's Now Playing song notes, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.library.parseLastFmTrackWiki
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryBackupTest {
    @Test
    fun lastFmWikiLosesItsLinkAndLicence() {
        val body = """{"track":{"name":"Glory Box","wiki":{"summary":"short","content":"Glory Box is the final track on Portishead&#39;s debut album Dummy, released as a single in January 1995.\n\nIt samples Isaac Hayes. <a href=\"https://www.last.fm/music/Portishead/_/Glory+Box\">Read more on Last.fm</a>. User-contributed text is available under the Creative Commons By-SA License; additional terms may apply."}}}"""
        val text = parseLastFmTrackWiki(body)
        assertNotNull(text)
        assertTrue(text.startsWith("Glory Box is the final track on Portishead's debut"))
        assertTrue("Read more" !in text && "Creative Commons" !in text && "<a" !in text)
    }

    @Test
    fun noWikiIsNoText() {
        assertNull(parseLastFmTrackWiki("""{"track":{"name":"Song"}}"""))
        assertNull(parseLastFmTrackWiki("not json"))
    }

    @Test
    fun lastFmStoryPutsTheOpeningInTheBox() {
        val story = lastFmSongStory("A".repeat(10) + " opening sentence that is long enough to stand on its own here. " + "x".repeat(260) + ".\n\nLater it was covered by many artists over the years.")
        assertNotNull(story)
        assertNotNull(story.made)
        assertEquals("Later it was covered by many artists over the years.", story.after)
        assertTrue(!story.ai)
    }

    @Test
    fun geminiTextAndRetryDelayAreRead() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"made\": "},{"text":"\"x\"}"}]},"groundingMetadata":{"webSearchQueries":["a","b"]}}]}"""
        assertEquals("{\"made\": \"x\"}", GeminiSearch.answerText(body))
        assertEquals(37_000L, GeminiSearch.retryDelayMs("""{"error":{"details":[{"retryDelay":"37s"}]}}"""))
        assertNull(GeminiSearch.retryDelayMs("{}"))
    }
}
