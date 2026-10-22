package com.nuvio.app.features.cast

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTest {
    /** Trimmed from the LG CX's own description (served at :1190). */
    private val lgDescription = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[LG] webOS TV OLED55CX5LB</friendlyName>
            <manufacturer>LG Electronics</manufacturer>
            <modelName>LG TV</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/ConnectionManager/74357fa3/control.xml</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/AVTransport/74357fa3/control.xml</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `reads name and AVTransport control from a renderer description`() {
        val renderer = DlnaDiscovery.parseDescription("uuid:74357fa3", URI("http://192.168.0.221:1190/"), lgDescription)!!
        assertEquals("[LG] webOS TV OLED55CX5LB", renderer.device.name)
        assertEquals("LG Electronics LG TV", renderer.device.model)
        assertEquals("192.168.0.221", renderer.host)
        assertEquals(URI("http://192.168.0.221:1190/AVTransport/74357fa3/control.xml"), renderer.avTransportControl)
    }

    @Test
    fun `recognises Kodi from its description but not the LG`() {
        val kodi = DlnaRenderer(CastDevice("uuid:k", "Kodi (JordanPC)", "XBMC Foundation Kodi"), "192.168.0.239", URI("http://x/"))
        val lg = DlnaDiscovery.parseDescription("uuid:74357fa3", URI("http://192.168.0.221:1190/"), lgDescription)!!
        assertTrue(kodi.isKodi)
        assertTrue(!lg.isKodi)
    }

    @Test
    fun `parses SSDP reply headers case-insensitively`() {
        val headers = DlnaDiscovery.parseSsdpHeaders(
            "HTTP/1.1 200 OK\r\nLocation: http://192.168.0.221:1190/\r\nUSN: uuid:74357fa3::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )
        assertEquals("http://192.168.0.221:1190/", headers["location"])
        assertTrue(headers["usn"]!!.startsWith("uuid:74357fa3"))
    }

    @Test
    fun `parses and formats DLNA times`() {
        assertEquals(3_388_385L, parseHms("0:56:28.385"))
        assertEquals(35_000L, parseHms("00:00:35"))
        assertNull(parseHms("NOT_IMPLEMENTED"))
        assertEquals("0:56:28", formatHms(3_388_385))
        assertEquals("0:56:28.385", formatHmsMillis(3_388_385))
    }

    @Test
    fun `DIDL metadata carries protocol info, duration and an escaped URL`() {
        val didl = didlMetadata(
            title = "Pluribus - S01E01 & more",
            url = "http://192.168.0.239:5000/cast/abc/media.mp4?a=1&b=2",
            mime = "video/mp4",
            dlnaFeatures = DLNA_FEATURES_TIMESEEK,
            sizeBytes = null,
            durationMs = 3_388_416,
        )
        assertTrue("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=10;" in didl)
        assertTrue("duration=\"0:56:28.416\"" in didl)
        assertTrue("media.mp4?a=1&amp;b=2" in didl)
        assertTrue("S01E01 &amp; more" in didl)
    }
}
