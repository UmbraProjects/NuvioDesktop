package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackCreditsTest {
    private val release = """
        {"id":"r1","media":[
          {"tracks":[
            {"title":"One","recording":{"relations":[
              {"type":"producer","artist":{"name":"Quincy Jones"}},
              {"type":"performance","work":{"title":"One","relations":[
                {"type":"writer","artist":{"name":"Michael Jackson"}},
                {"type":"composer","artist":{"name":"Rod Temperton"}}]}}]}},
            {"title":"Two","recording":{"relations":[]}}]},
          {"tracks":[
            {"title":"Three","recording":{"relations":[{"type":"producer","artist":{"name":"Jones"}}]}}]}]}
    """.trimIndent()

    @Test
    fun creditsAreOneLinePerTrackCountingAcrossDiscs() {
        val credits = parseMbTrackCredits(release)
        assertEquals("Written by Michael Jackson, Rod Temperton · Produced by Quincy Jones", credits[1])
        assertNull(credits[2])
        assertEquals("Produced by Jones", credits[3])
    }

    @Test
    fun aDamagedReplyGivesNothing() {
        assertTrue(parseMbTrackCredits("nope").isEmpty())
        assertNull(parseMbReleaseSearchId("{}"))
    }

    @Test
    fun theFirstReleaseOfASearchIsTaken() {
        assertEquals("abc", parseMbReleaseSearchId("""{"releases":[{"id":"abc"},{"id":"def"}]}"""))
    }

    @Test
    fun releaseSearchIdsKeepEveryHitInOrder() {
        assertEquals(listOf("abc", "def"), parseMbReleaseSearchIds("""{"releases":[{"id":"abc"},{"id":"def"}]}"""))
        assertEquals(emptyList(), parseMbReleaseSearchIds("{}"))
    }
}
