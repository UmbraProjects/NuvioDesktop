package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from GoatTV's M3uParserTest.
class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.lineSequence())

    @Test
    fun `reads attributes, name and url`() {
        val r = parse(
            """
            #EXTM3U x-tvg-url="https://example.com/epg.xml"
            #EXTINF:-1 tvg-id="rtp1.pt" tvg-name="RTP 1" tvg-logo="https://example.com/rtp1.png" group-title="Generalistas",RTP 1
            http://host/stream/123.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.com/epg.xml", r.epgUrl)
        val ch = r.channels.single()
        assertEquals("rtp1.pt", ch.key)
        assertEquals("rtp1.pt", ch.epgId)
        assertEquals("RTP 1", ch.name)
        assertEquals("https://example.com/rtp1.png", ch.logo)
        assertEquals("Generalistas", ch.categoryId)
        assertEquals("http://host/stream/123.m3u8", ch.url)
        assertEquals(listOf("Generalistas"), r.categories.map { it.name })
    }

    @Test
    fun `commas inside attributes do not break the name`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Notícias, Info",CNN Portugal, HD
            http://host/a.ts
            """.trimIndent(),
        )
        val ch = r.channels.single()
        assertEquals("CNN Portugal, HD", ch.name)
        assertEquals("Notícias, Info", ch.categoryId)
    }

    @Test
    fun `channels without group go to Other and without tvg-id get a url hash`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,No group
            http://host/x.m3u8
            """.trimIndent(),
        )
        val ch = r.channels.single()
        assertEquals(IptvPlaylistContent.OTHER_CATEGORY, ch.categoryId)
        assertEquals(M3uParser.urlKey("http://host/x.m3u8"), ch.key)
        assertTrue(r.categories.isEmpty(), "Other is added by IptvPlaylistContent.build, not the parser")
    }

    @Test
    fun `missing name falls back to tvg-name, then empty`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="From attr",
            http://host/a.ts
            #EXTINF:-1,
            http://host/b.ts
            """.trimIndent(),
        )
        assertEquals(listOf("From attr", ""), r.channels.map { it.name })
    }

    @Test
    fun `skips movies and series`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,Film
            http://host/movie/u/p/1.mp4
            #EXTINF:-1,Series
            http://host/series/u/p/2.mkv
            #EXTINF:-1,Other film
            http://host/vod/3.mkv?token=1
            #EXTINF:-1,Channel
            http://host/live/u/p/4.ts
            """.trimIndent(),
        )
        assertEquals(listOf("Channel"), r.channels.map { it.name })
        assertEquals(1, r.channels.single().num)
    }

    @Test
    fun `repeated tvg-id gets a distinct key`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="sic.pt",SIC
            http://host/sic.m3u8
            #EXTINF:-1 tvg-id="sic.pt",SIC HD
            http://host/sichd.m3u8
            """.trimIndent(),
        )
        assertEquals("sic.pt", r.channels[0].key)
        assertNotEquals("sic.pt", r.channels[1].key)
        // Both still match the guide by tvg-id.
        assertEquals("sic.pt", r.channels[1].epgId)
    }

    @Test
    fun `reads EXTGRP and EXTVLCOPT and ignores orphan urls`() {
        val r = parse(
            "﻿#EXTM3U\n" +
                "http://host/orphan.ts\n" +
                "#EXTINF:-1,Channel\n" +
                "#EXTGRP:Sport\n" +
                "#EXTVLCOPT:http-user-agent=Foo/1.0\n" +
                "#EXTVLCOPT:http-referrer=https://ref.example/\n" +
                "\n" +
                "http://host/c.ts\n",
        )
        val ch = r.channels.single()
        assertEquals("Sport", ch.categoryId)
        assertEquals("Foo/1.0", ch.userAgent)
        assertEquals("https://ref.example/", ch.referrer)
        assertNull(r.epgUrl)
        assertEquals(mapOf("User-Agent" to "Foo/1.0", "Referer" to "https://ref.example/"), ch.requestHeaders())
    }

    @Test
    fun `vod heuristic`() {
        assertTrue(M3uParser.isVod("http://h/movie/1.ts"))
        assertTrue(M3uParser.isVod("http://h/a/b.MP4"))
        assertFalse(M3uParser.isVod("http://h/live/1.ts"))
    }
}
