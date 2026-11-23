package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class XmltvGuideSharingTest {

    // Built with StringBuilder so each is a distinct String instance, as JSON decoding makes them.
    private fun fresh(text: String) = StringBuilder(text).toString()

    @Test
    fun repeatedTextsShareOneStringAndNothingElseChanges() {
        val a = IptvEpgEntry(title = fresh("News"), startMs = 0, endMs = 10, desc = fresh("Headlines"), flags = 1)
        val b = IptvEpgEntry(title = fresh("News"), startMs = 10, endMs = 20, desc = fresh("Headlines"))
        val c = IptvEpgEntry(title = fresh("News"), startMs = 0, endMs = 30, kind = fresh("sport"))
        assertNotSame(a.title, b.title)
        val guide = XmltvGuide(
            url = "u",
            fetchedAt = 5,
            programmes = mapOf("one" to listOf(a, b), "two" to listOf(c)),
            names = mapOf("bbc one" to "one"),
        )

        val shared = guide.sharingStrings()

        val (sa, sb) = shared.programmes.getValue("one")
        val sc = shared.programmes.getValue("two").single()
        assertSame(sa.title, sb.title)
        assertSame(sa.title, sc.title)
        assertSame(sa.desc, sb.desc)
        assertEquals(listOf(a, b), shared.programmes.getValue("one"))
        assertEquals(listOf(c), shared.programmes.getValue("two"))
        assertEquals(guide.names, shared.names)
        assertEquals(guide.fetchedAt, shared.fetchedAt)
    }
}
