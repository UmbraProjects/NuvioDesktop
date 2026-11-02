package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeMusicWebClientTest {
    // Shape of a real sw.js_data answer (Nov 2, 2026), trimmed: the XSSI guard, then nested arrays with an
    // IPv6 address and other strings ahead of the visitor id.
    private val visitor =
        "Cgt4ZDFPY0RMbEV5QSjV7aLXBmKbAgqYAkNzOEJDQVVTZ1FFQkRCNlBERUx5TThJYWJOQlBFdERmMFo3eXNWSjZqc3pLM3NnRC1Wd282YkEtY0ZYRlpWT3pLLXBqZHc3M0VrV21GL%3D%3D"
    private val swJsData = ")]}'\n\n[[\"yt.sw.adr\",null,[[[\"en\",\"US\",null,\"fda3:e722:ac3:10:211:7450:fefe:c663\"," +
        "null,null,null,null,null,null,null,\"\",\"\",\"$visitor\",null,[\"CgtzaG9ydA\"]]]]]]"

    @Test
    fun `the visitor id is found by its shape in sw_js_data`() {
        assertEquals(visitor, YouTubeMusicWebClient.visitorFromSwJsData(swJsData))
    }

    @Test
    fun `no visitor id in a body without one or in a body that is not JSON`() {
        assertNull(YouTubeMusicWebClient.visitorFromSwJsData(")]}'\n[[\"en\",\"US\",\"Cgshort\"]]"))
        assertNull(YouTubeMusicWebClient.visitorFromSwJsData("<html><title>Sorry...</title></html>"))
        assertNull(YouTubeMusicWebClient.visitorFromSwJsData(""))
    }

    @Test
    fun `headers are the site's, with no Android TV user agent`() {
        val headers = YouTubeMusicWebClient.headers("67", "1.20260707.12.00", "CgtVISITOR")
        assertEquals("https://music.youtube.com", headers["origin"])
        assertEquals("https://music.youtube.com", headers["x-origin"])
        assertEquals("https://music.youtube.com/", headers["referer"])
        assertEquals("67", headers["x-youtube-client-name"])
        assertEquals("CgtVISITOR", headers["x-goog-visitor-id"])
        assertTrue(headers.getValue("user-agent").contains("Windows NT 10.0"))
        assertFalse(headers.getValue("user-agent").contains("Android"))
        assertFalse("x-goog-visitor-id" in YouTubeMusicWebClient.headers("67", "v", null))
    }

    @Test
    fun `the url carries no API key`() {
        assertEquals("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", YouTubeMusicWebClient.url("browse"))
    }

    @Test
    fun `a refusal is the Sorry page or a 429, not any 403`() {
        assertTrue(YouTubeMusicWebClient.isRefusal(403, "<html><head><title>Sorry...</title>"))
        assertTrue(YouTubeMusicWebClient.isRefusal(429, ""))
        assertFalse(YouTubeMusicWebClient.isRefusal(403, "{\"error\":{\"code\":403}}"))
        assertFalse(YouTubeMusicWebClient.isRefusal(500, "Sorry"))
    }

    @Test
    fun `a fresh visitor id at most every ten minutes`() {
        val now = 100_000_000L
        assertTrue(YouTubeMusicWebClient.mayRemint(0L, now))
        assertFalse(YouTubeMusicWebClient.mayRemint(now - 60_000L, now))
        assertTrue(YouTubeMusicWebClient.mayRemint(now - YouTubeMusicWebClient.REMINT_MIN_INTERVAL_MS, now))
    }
}
