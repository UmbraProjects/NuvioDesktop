package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvidLinksTest {
    @Test
    fun oneLinkStaysOne() {
        assertEquals(listOf("https://avid.wiki/THX"), splitAvidLinks("https://avid.wiki/THX"))
    }

    @Test
    fun severalLinksSplitOnSpacesCommasSemicolonsAndNewLines() {
        val raw = "https://avid.wiki/THX, https://avid.wiki/Dolby_Atmos;https://youtu.be/abcdefghijk\nhttps://avid.wiki/X"
        assertEquals(
            listOf(
                "https://avid.wiki/THX",
                "https://avid.wiki/Dolby_Atmos",
                "https://youtu.be/abcdefghijk",
                "https://avid.wiki/X",
            ),
            splitAvidLinks(raw),
        )
    }

    @Test
    fun blanksAndRepeatsAreDropped() {
        assertTrue(splitAvidLinks("  ,; ").isEmpty())
        assertEquals(listOf("https://avid.wiki/THX"), splitAvidLinks("https://avid.wiki/THX https://avid.wiki/THX"))
    }

    @Test
    fun theBumperDefaultIsTheThxPage() {
        assertEquals("https://avid.wiki/THX", AVID_BUMPER_PAGE)
    }
}
