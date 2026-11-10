package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same lines the PC's PhoneRemoteTonightTest builds: change both together. */
class TonightLineTest {
    @Test
    fun readsAQuestion() {
        val line = "TONIGHT 1 question - 0 2 3 214 41 0 Laugh%2C%20or%20think%3F Laugh Light%20and%20easy Think Something%20with%20weight A%20film -"
        val screen = (ServerLine.parse(line, 0L) as ServerLine.Tonight).screen!!
        assertEquals("question", screen.view)
        assertNull(screen.mode)
        assertEquals(2, screen.number)
        assertEquals(3, screen.total)
        assertEquals(214, screen.countFrom)
        assertEquals(41, screen.countLeft)
        assertEquals("Laugh, or think?", screen.prompt)
        assertEquals("Laugh", screen.leftLabel)
        assertEquals("Something with weight", screen.rightDetail)
        assertEquals(listOf("A film"), screen.answers)
        assertTrue(screen.cards.isEmpty())
    }

    @Test
    fun readsTheCards() {
        val line = "TONIGHT 1 cards - 0 0 0 0 0 1 - - - - - A%20film;Laugh SAFE,Heat,Seen%20before,1995%20%C2%B7%202h%2050m,https%3A%2F%2Fimg%2Fh.jpg"
        val screen = (ServerLine.parse(line, 0L) as ServerLine.Tonight).screen!!
        assertEquals("cards", screen.view)
        assertTrue(screen.reshuffleLeft)
        assertEquals(listOf("A film", "Laugh"), screen.answers)
        assertEquals(listOf(TonightCardInfo(1, "SAFE", "Heat", "Seen before", "1995 · 2h 50m", "https://img/h.jpg")), screen.cards)
    }

    @Test
    fun offAndBrokenLines() {
        assertNull((ServerLine.parse("TONIGHT 0", 0L) as ServerLine.Tonight).screen)
        assertEquals(ServerLine.Other, ServerLine.parse("TONIGHT 1 cards", 0L))
    }
}
