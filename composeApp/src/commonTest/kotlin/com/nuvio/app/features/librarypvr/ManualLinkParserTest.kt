package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualLinkParserTest {

    private val hash = "3b2b6f6d62b183685ae370af2e1f602524953289"

    @Test
    fun `passes a plain magnet through and reads its display name`() {
        val parsed = ManualLinkParser.parse("  magnet:?xt=urn:btih:$hash&dn=Friends.S03.REMUX  ")
        assertEquals("magnet:?xt=urn:btih:$hash&dn=Friends.S03.REMUX", parsed?.magnetUri)
        assertEquals("Friends.S03.REMUX", parsed?.displayName)
    }

    @Test
    fun `wraps a bare infohash`() {
        assertEquals("magnet:?xt=urn:btih:$hash", ManualLinkParser.parse(hash.uppercase())?.magnetUri)
    }

    @Test
    fun `decodes a torbox quick-add link carrying a base64 magnet`() {
        val quickAdd = "https://torbox.app/quickadd?magnet=bWFnbmV0Oj94dD11cm46YnRpaDozYjJiNmY2ZDYyYjE4" +
            "MzY4NWFlMzcwYWYyZTFmNjAyNTI0OTUzMjg5JmRuPUZyaWVuZHMuUzAzLlVIRC5CbHVSYXkuMjE2MHAuRFRTLUhE" +
            "Lk1BLjUuMS5EVi5IRVZDLlJFTVVYLUZyYU1lU1RvUg=="
        val parsed = ManualLinkParser.parse(quickAdd)
        assertEquals("magnet:?xt=urn:btih:$hash&dn=Friends.S03.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR", parsed?.magnetUri)
        assertEquals("Friends.S03.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR", parsed?.displayName)
    }

    @Test
    fun `decodes a percent-encoded magnet parameter`() {
        val link = "https://example.org/add?magnet=magnet%3A%3Fxt%3Durn%3Abtih%3A$hash"
        assertEquals("magnet:?xt=urn:btih:$hash", ManualLinkParser.parse(link)?.magnetUri)
    }

    @Test
    fun `reads a hash from the last path segment`() {
        assertEquals(
            "magnet:?xt=urn:btih:$hash",
            ManualLinkParser.parse("https://example.org/torrent/$hash")?.magnetUri,
        )
    }

    @Test
    fun `rejects input that is not a source link`() {
        assertNull(ManualLinkParser.parse(""))
        assertNull(ManualLinkParser.parse("Friends season 3"))
        assertNull(ManualLinkParser.parse("https://example.org/browse?page=2"))
    }

    @Test
    fun `keeps trackers attached to the magnet`() {
        val magnet = "magnet:?xt=urn:btih:$hash&tr=udp%3A%2F%2Ftracker.example%3A80"
        assertTrue(ManualLinkParser.parse(magnet)?.magnetUri?.contains("&tr=") == true)
    }
}
