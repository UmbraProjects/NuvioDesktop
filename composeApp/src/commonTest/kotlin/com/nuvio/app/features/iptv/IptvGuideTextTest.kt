package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvGuideTextTest {
    @Test
    fun `titles lose their episode number and marks`() {
        assertEquals(IptvCleanTitle("Hart Broken", "S2 E1", false, false), IptvGuideText.clean("Hart Broken - S02E01"))
        assertEquals(IptvCleanTitle("Soccer Saturday", null, false, true), IptvGuideText.clean("Soccer Saturday ᴸⁱᵛᵉ"))
        assertEquals(IptvCleanTitle("Newsround", null, true, false), IptvGuideText.clean("Newsround ᴺᵉʷ"))
        assertEquals(IptvCleanTitle("UFC Reloaded : UFC 289", null, true, false), IptvGuideText.clean("UFC Reloaded : UFC 289 ᴺᵉʷ"))
        assertEquals(IptvCleanTitle("Happy (Merry Xmas) Birthday!", "S4 E8", false, false), IptvGuideText.clean("Happy (Merry Xmas) Birthday! - S04E08"))
        assertEquals(IptvCleanTitle("Young Sheldon", "S1 E8", false, false), IptvGuideText.clean("Young Sheldon (S1 E8)"))
        assertEquals(IptvCleanTitle("Strictly Come Dancing", null, true, false), IptvGuideText.clean("New: Strictly Come Dancing"))
        assertEquals("Cheers", IptvGuideText.clean("Cheers").name)
        assertEquals("S01E02", IptvGuideText.clean("S01E02").name, "a title that is only a number stays as it is")
    }

    @Test
    fun `xmltv episode numbers`() {
        assertEquals("S2 E5", IptvGuideText.episode("xmltv_ns", "1.4.0/1"))
        assertEquals("S1 E1", IptvGuideText.episode("xmltv_ns", "0.0."))
        assertEquals("E3", IptvGuideText.episode("xmltv_ns", ".2."))
        assertEquals("S12 E3", IptvGuideText.episode("onscreen", "S12E03"))
        assertNull(IptvGuideText.episode("dd_progid", "EP01234567.0001"))
    }

    @Test
    fun `descriptions are cut at a word`() {
        val long = "Sam Malone runs a Boston bar where the regulars know everyone's name, and everyone's business, and they all come back night after night after night."
        val cut = IptvGuideText.shortDesc(long, 60)!!
        assertTrue(cut.endsWith("…") && cut.length <= 61, cut)
        assertEquals("Short.", IptvGuideText.shortDesc("  Short.  "))
        assertNull(IptvGuideText.shortDesc("   "))
        assertEquals(2013, IptvGuideText.year("20130712"))
        assertNull(IptvGuideText.year("n/a"))
    }

    @Test
    fun `filler channels and shopping`() {
        assertEquals(IptvChannelKind.Loops, IptvGuideText.fillerChannel("Sponge Bob Square Pants", "UK | 24/7 Shows"))
        assertEquals(IptvChannelKind.Radio, IptvGuideText.fillerChannel("Experts Only Radio", "UK | Music"))
        assertNull(IptvGuideText.fillerChannel("Channel 4", "UK | Entertainment"))
        assertTrue(IptvGuideText.isShopping("Teleshopping"))
        assertTrue(IptvGuideText.isShopping("Ideal World Shopping"))
        assertFalse(IptvGuideText.isShopping("Cheers"))
    }

    @Test
    fun `the guide keeps descriptions, episodes and pictures near now`() {
        val xml = """<tv><channel id="c4"><display-name>Channel 4</display-name></channel>
<programme start="20261114074000 +0000" stop="20261114081000 +0000" channel="c4"><title>Cheers - S05E12</title>
<desc>Sam Malone runs a Boston bar.</desc><episode-num system="xmltv_ns">4.11.</episode-num><icon src="https://img/x.jpg" /><new /><date>1987</date></programme>
<programme start="20261116074000 +0000" stop="20261116081000 +0000" channel="c4"><title>Cheers ᴺᵉʷ</title><desc>Later.</desc></programme>
</tv>"""
        val at = java.time.ZonedDateTime.of(2026, 11, 14, 7, 30, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val guide = XmltvGuide.build("u", listOf(IptvChannel(uid = 1, key = "k", num = 1, name = "Channel 4", categoryId = "c", epgId = "c4")), at - 86_400_000L, at + 4 * 86_400_000L, at) {
            it.parse(java.io.StringReader(xml))
        }
        val entries = guide.programmes.getValue("c4")
        val first = entries[0]
        assertEquals("Cheers", first.title)
        assertEquals("S5 E12", first.episode)
        assertEquals("Sam Malone runs a Boston bar.", first.desc)
        assertEquals("https://img/x.jpg", first.image)
        assertEquals(1987, first.year)
        assertTrue(first.isNew)
        assertNull(entries[1].desc, "two days out is past the detail window")
        assertTrue(entries[1].isNew)
    }
}
