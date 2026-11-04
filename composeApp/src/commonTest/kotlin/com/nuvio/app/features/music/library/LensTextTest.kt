package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LensTextTest {
    // The cut seen on the HTPC (Adele, Rumour Has It): the stored text stopped mid-quote with an ellipsis.
    private val cutMidQuote = "\"Rumour Has It\" was written by Adele and Ryan Tedder and produced by Tedder. Talking about the " +
        "collaboration with Tedder on \"Rumour Has It\", Adele revealed: \"You can really…"

    @Test
    fun aCutTextGoesBackToItsLastFullStop() {
        assertEquals("\"Rumour Has It\" was written by Adele and Ryan Tedder and produced by Tedder.", wholeSentences(cutMidQuote))
    }

    @Test
    fun threeDotsCountAsACutToo() {
        assertEquals("One. Two.", wholeSentences("One. Two. Thr..."))
    }

    @Test
    fun anOpenQuoteWithNoEllipsisIsTakenBack() {
        assertEquals("She said yes.", wholeSentences("She said yes. Then: “It was the best night"))
    }

    @Test
    fun wholeTextIsLeftAlone() {
        val t = "He said \"hello.\" Then he left."
        assertEquals(t, wholeSentences(t))
        assertEquals("No stop at all…", wholeSentences("No stop at all…"))
    }

    @Test
    fun madeAndMeaningShareTheBoxWhenBothFit() {
        val r = lensStoryText("Made here.", "It means this.")!!
        assertEquals("Made here.\n\nIt means this.", r.text)
        assertTrue(r.usedMeaning)
        val long = lensStoryText("A".repeat(500) + ".", "B".repeat(500) + ".")!!
        assertFalse(long.usedMeaning)
        assertEquals(lensStoryText(null, "Only meaning.")!!.text, "Only meaning.")
        assertNull(lensStoryText(null, null))
    }
}
