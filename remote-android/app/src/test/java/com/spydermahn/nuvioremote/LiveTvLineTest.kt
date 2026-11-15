package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same line the PC's PhoneRemoteLiveTvTest builds: change both together. */
class LiveTvLineTest {
    @Test
    fun readsAStage() {
        val line = "LIVETV 1 Three Three%20things Pick%20one%3B%20there%20is%20never%20a%20fourth. Three,Three%20things;Soon,Starting%20soon cards,0,SAFE,Bake%20Off,Channel%204%20%C2%B7%20on%20now,https%3A%2F%2Fx%2Fp.jpg,1,250,Watch;-,1,FOLLOWING,Doctor%20Who,Nothing%20in%20the%20guide%20yet,-,0,-1,- deal,0,Deal%20again,0 Nothing%20to%20pick%20right%20now."
        val screen = (ServerLine.parse(line, 0L) as ServerLine.LiveTv).screen!!
        assertEquals("Three", screen.stage)
        assertEquals("Three things", screen.heading)
        assertEquals("Pick one; there is never a fourth.", screen.sub)
        assertEquals(listOf("Three" to "Three things", "Soon" to "Starting soon"), screen.stages)
        assertEquals(2, screen.items.size)
        val card = screen.items[0]
        assertEquals("cards", card.group)
        assertEquals(0, card.index)
        assertEquals("Bake Off", card.title)
        assertEquals("Channel 4 · on now", card.sub)
        assertEquals("https://x/p.jpg", card.picture)
        assertTrue(card.live)
        assertEquals(250, card.progress)
        assertEquals("Watch", card.action)
        val note = screen.items[1]
        assertEquals("-", note.group)
        assertFalse(note.live)
        assertEquals(-1, note.progress)
        assertNull(note.picture)
        assertEquals(listOf(LiveTvButtonInfo("deal", 0, "Deal again", false)), screen.buttons)
        assertEquals("Nothing to pick right now.", screen.empty)
    }

    @Test
    fun noStage() {
        assertNull((ServerLine.parse("LIVETV 0", 0L) as ServerLine.LiveTv).screen)
    }
}
