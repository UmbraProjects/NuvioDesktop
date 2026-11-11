package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutToFitTest {
    private val titles = listOf("Opener", "Hit", "Deep Cut", "Ballad", "Hit (Club Mix)", "Closer")
    private val secs = listOf(530, 240, 300, 280, 420, 360)

    @Test
    fun bestOfPutsTheMostPlayedFirstAndDropsRemixes() {
        val p = bestOfPick(titles, listOf(0.3f, 1f, null, 0.6f, 0.9f, null))
        assertEquals(listOf(1, 3, 0, 2, 5), p.order)
        assertEquals("Remixes and demos", p.leavesOut)
    }

    @Test
    fun timeTakesFromTheTopAndPlaysInAlbumOrder() {
        // 20 minutes: Hit 240 + Ballad 280 + Opener 530 = 1050 <= 1200; Deep Cut 300 would make 1350, Closer too.
        val r = cutToFit(listOf(1, 3, 0, 2, 5), secs, 20)
        assertEquals(listOf(0, 1, 3), r.indices)
        assertEquals(1050, r.seconds)
        assertEquals(mapOf(0 to 1, 1 to 2, 3 to 3), r.order)
    }

    @Test
    fun aLongSongIsPassedOverForAShorterOne() {
        val r = cutToFit(listOf(0, 1, 2), secs, 10) // 530 fits, 240 would make 770 > 600, 300 too
        assertEquals(listOf(0), r.indices)
        val s = cutToFit(listOf(2, 0, 1), secs, 10) // 300 fits, 530 doesn't, 240 does
        assertEquals(listOf(1, 2), s.indices)
    }

    @Test
    fun fullKeepsTheWholePickAndTooShortStillKeepsOne() {
        assertEquals(listOf(1, 3, 5), cutToFit(listOf(5, 1, 3), secs, null).indices)
        assertEquals(listOf(0), cutToFit(listOf(0), secs, 1).indices)
    }

    @Test
    fun theAiReplyIsReadAndCheckedAgainstTheTrackList() {
        val reply = "Sure! {\"order\": [2, \"4\", 9, 2, 6], \"energy\": \"medium to high\", \"leaves_out\": \"slow openers\", \"why\": \"Hit keeps it moving.\"}"
        val p = parseCutPick(reply, 6)!!
        assertEquals(listOf(1, 3, 5), p.order)
        assertEquals("medium to high", p.energy)
        assertTrue(p.ai)
        assertNull(parseCutPick("{\"order\": [12]}", 6))
        assertNull(parseCutPick("no json here", 6))
    }

    @Test
    fun runningTimeReads() {
        assertEquals("44:55", cutLength(2695))
        assertEquals("1:02:10", cutLength(3730))
    }
}
