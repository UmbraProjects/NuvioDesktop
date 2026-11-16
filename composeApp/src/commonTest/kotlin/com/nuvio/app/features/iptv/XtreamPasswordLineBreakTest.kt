package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XtreamPasswordLineBreakTest {
    private val playlist = IptvPlaylist(
        id = "p",
        type = IptvPlaylistType.Xtream,
        name = "Test",
        server = "http://example.test:8080",
        username = "user",
        password = "secret\r\n",
    )

    @Test
    fun streamUrlHasNoEncodedLineBreak() {
        val url = XtreamUrls.streamUrl(playlist, "1544243", "ts")
        assertEquals("http://example.test:8080/live/user/secret/1544243.ts", url)
    }

    @Test
    fun apiAndGuideUrlsHaveNoEncodedLineBreak() {
        assertFalse(XtreamUrls.api(playlist, "get_live_streams").contains("%0A", ignoreCase = true))
        assertFalse(XtreamUrls.xmltv(playlist).contains("%0D", ignoreCase = true))
    }

    @Test
    fun spacesInAPasswordAreKept() {
        assertEquals("a b", "a b\n".withoutLineBreaks())
    }
}
