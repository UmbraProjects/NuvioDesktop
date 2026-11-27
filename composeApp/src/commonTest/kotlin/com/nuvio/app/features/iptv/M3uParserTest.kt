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

    @Test
    fun `reads KODIPROP clearkey, EXTHTTP cookies, http-origin, and pipe headers without bleeding`() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="143" group-title="English",CNBC TV18 Prime
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=https://example.site/jtv/license?id=143
            #EXTVLCOPT:http-user-agent=Chrome/140
            https://example.site/jtv/manifest.mpd?id=143

            #EXTINF:-1 tvg-id="9487112" group-title="Movies",&Pictures HD
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=0771502eee1e4de3ba76894175f3c5ea:13f23aaf5d66b038385bb2137bf5dcd8
            https://cdn.example/And_Pictures_HD.mpd

            #EXTINF:-1 tvg-id="144" group-title="Entertainment",Colors HD
            #EXTVLCOPT:http-user-agent=Hotstar/25
            #EXTHTTP:{"cookie":"hdntl=token123"}
            #EXTVLCOPT:http-origin=https://www.hotstar.com
            #EXTVLCOPT:http-referrer=https://www.hotstar.com/
            https://cdn.example/ColorsHD.m3u8

            #EXTINF:-1,Pipe Channel
            https://cdn.example/pipe.m3u8|User-Agent=PipeUA%2F1.0&Cookie=sid%3D42
            """.trimIndent(),
        )
        assertEquals(4, r.channels.size)

        val jtv = r.channels[0]
        assertEquals("clearkey", jtv.drmScheme)
        assertEquals("https://example.site/jtv/license?id=143", jtv.drmLicenseKey)
        assertEquals("Chrome/140", jtv.userAgent)
        assertEquals("https://example.site/jtv/manifest.mpd?id=143", jtv.url)

        val dish = r.channels[1]
        assertEquals("org.w3.clearkey", dish.drmScheme)
        assertEquals("0771502eee1e4de3ba76894175f3c5ea:13f23aaf5d66b038385bb2137bf5dcd8", dish.drmLicenseKey)
        assertNull(dish.userAgent)
        assertNull(dish.headers)

        val colors = r.channels[2]
        assertNull(colors.drmScheme)
        assertNull(colors.drmLicenseKey)
        assertEquals(
            mapOf(
                "Cookie" to "hdntl=token123",
                "Origin" to "https://www.hotstar.com",
                "User-Agent" to "Hotstar/25",
                "Referer" to "https://www.hotstar.com/",
            ),
            colors.requestHeaders(),
        )

        val pipe = r.channels[3]
        assertEquals("https://cdn.example/pipe.m3u8", pipe.url)
        assertEquals("PipeUA/1.0", pipe.userAgent)
        assertEquals("sid=42", pipe.headers?.get("Cookie"))
    }

    @Test
    fun `reads KODIPROP placed before EXTINF`() {
        val r = parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=0771502eee1e4de3ba76894175f3c5ea:13f23aaf5d66b038385bb2137bf5dcd8
            #EXTINF:-1,Before Extinf
            https://cdn.example/live.mpd
            #EXTINF:-1,Plain Next
            https://cdn.example/plain.m3u8
            """.trimIndent(),
        )
        assertEquals(2, r.channels.size)
        assertEquals("org.w3.clearkey", r.channels[0].drmScheme)
        assertNull(r.channels[1].drmScheme)
        assertNull(r.channels[1].drmLicenseKey)
    }
}
