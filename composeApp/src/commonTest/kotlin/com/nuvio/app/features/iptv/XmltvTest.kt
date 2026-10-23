package com.nuvio.app.features.iptv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from GoatTV's XmltvTest.
class XmltvTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <!-- comment with <tags> and "quotes -->
          <channel id="RTP1.pt">
            <display-name lang="pt">RTP 1</display-name>
            <icon src="https://example.com/rtp1.png"/>
          </channel>
          <channel id="sic.pt"><display-name>SIC HD</display-name></channel>
          <channel id="other.es"><display-name>Otro</display-name></channel>
          <programme start="20261019120000 +0100" stop="20261019130000 +0100" channel="RTP1.pt">
            <title lang="pt">Jornal da Tarde</title>
            <title lang="en">Afternoon News</title>
            <desc>Notícias &amp; mais</desc>
          </programme>
          <programme start="20261019130000 +0100" stop="20261019140000 +0100" channel="RTP1.pt">
            <title><![CDATA[Praça & Companhia]]></title>
          </programme>
          <programme start="20261019120000 +0000" stop="20261019123000 +0000" channel="sic.pt">
            <title>Primeiro Jornal &#8211; edição</title>
          </programme>
          <programme start="20261019120000 +0000" stop="20261019123000 +0000" channel="other.es">
            <title>Fuera</title>
          </programme>
          <programme start="20261001120000 +0000" stop="20261001123000 +0000" channel="sic.pt">
            <title>Antigo</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun channel(uid: Int, name: String, epgId: String? = null) =
        IptvChannel(uid = uid, key = "k$uid", num = uid + 1, name = name, logo = null, categoryId = "g", epgId = epgId)

    @Test
    fun `parses times with and without zone`() {
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019110000"))
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019120000 +0100"))
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019060000 -0500"))
        assertEquals(1792407600000L, XmltvParser.parseTime("202610191100"))
        assertNull(XmltvParser.parseTime("garbage"))
        assertNull(XmltvParser.parseTime("20261340110000"))
    }

    @Test
    fun `normalizes channel names`() {
        assertEquals("rtp1", XmltvGuide.normalizeName("PT: RTP 1 HD"))
        assertEquals("rtp1", XmltvGuide.normalizeName("RTP1"))
        assertEquals("sicnoticias", XmltvGuide.normalizeName("SIC Notícias FHD"))
        assertEquals("sporttv1", XmltvGuide.normalizeName("PT | Sport TV 1 4K"))
    }

    @Test
    fun `keeps only the playlist channels and the window, matching by id or name`() {
        val from = XmltvParser.parseTime("20261018000000")!!
        val to = XmltvParser.parseTime("20261021000000")!!
        val rtp = channel(0, "RTP 1", epgId = "rtp1.PT") // id in a different case
        val sic = channel(1, "PT: SIC") // no tvg-id: matched by name
        val none = channel(2, "Channel Without Guide")
        val guide = XmltvGuide.build("u", listOf(rtp, sic, none), from, to, fetchedAt = 1L) { it.parse(StringReader(xml)) }

        val rtpEpg = guide.entriesFor(rtp)
        assertEquals(listOf("Jornal da Tarde", "Praça & Companhia"), rtpEpg.map { it.title })
        assertEquals(XmltvParser.parseTime("20261019110000"), rtpEpg[0].startMs)

        val sicEpg = guide.entriesFor(sic)
        assertEquals(listOf("Primeiro Jornal – edição"), sicEpg.map { it.title }) // "Antigo" is outside the window
        assertTrue(guide.entriesFor(none).isEmpty())
        assertFalse(guide.programmes.containsKey("other.es"))
    }

    @Test
    fun `gzipped guides are detected by their magic bytes`() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
        val plain = ByteArrayInputStream(bytes).gunzipIfNeeded().readBytes().decodeToString()
        assertEquals(xml, plain)
        assertEquals(xml, ByteArrayInputStream(xml.toByteArray()).gunzipIfNeeded().readBytes().decodeToString())
    }

    @Test
    fun `overlapping programmes from merged sources keep one per slot`() {
        val m = 60_000L
        val entries = listOf(
            IptvEpgEntry("F1 GP Highlights", 10 * m, 70 * m),
            IptvEpgEntry("Formula 1", 15 * m, 60 * m), // a second source's copy of the same slot
            IptvEpgEntry("Press Conference", 70 * m, 100 * m),
            IptvEpgEntry("Drivers' Press Conference", 70 * m + 20_000, 100 * m), // seconds apart
            IptvEpgEntry("Next Show", 100 * m - 30_000, 130 * m), // starts within the minute of slack
        )
        assertEquals(listOf("F1 GP Highlights", "Press Conference", "Next Show"), entries.shuffled().withoutOverlaps().map { it.title })
    }

    @Test
    fun `refresh policy`() {
        val h = 3600_000L
        // Never downloaded: always.
        assertTrue(IptvRefreshPolicy.isDue(12, 0L, 10 * h, checkedSinceOpen = true))
        // "Every launch": once per launch.
        assertTrue(IptvRefreshPolicy.isDue(0, 9 * h, 10 * h, checkedSinceOpen = false))
        assertFalse(IptvRefreshPolicy.isDue(0, 9 * h, 10 * h, checkedSinceOpen = true))
        // By hours: only once the interval has passed.
        assertFalse(IptvRefreshPolicy.isDue(6, 5 * h, 10 * h, checkedSinceOpen = false))
        assertTrue(IptvRefreshPolicy.isDue(6, 4 * h, 10 * h, checkedSinceOpen = true))
    }
}
