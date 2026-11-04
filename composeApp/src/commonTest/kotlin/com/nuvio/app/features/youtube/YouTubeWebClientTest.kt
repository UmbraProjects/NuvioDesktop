package com.nuvio.app.features.youtube

import com.nuvio.app.features.youtubemusic.mergeSetCookies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeWebClientTest {
    @Test
    fun `the signed-in page tells the session state, visitor id, client version and account`() {
        // Fields as youtube.com's ytcfg carries them (read live, Nov 2, 2026).
        val html = """<script>ytcfg.set({"INNERTUBE_CLIENT_VERSION":"2.20261030.02.00","LOGGED_IN":true,""" +
            """"SESSION_INDEX":"1","VISITOR_DATA":"CgtEbEo1bzFHWUVmWSih%3D%3D","DATASYNC_ID":"x||"});</script>"""
        val shell = YouTubeWebClient.parseShell(html)!!
        assertEquals(true, shell.loggedIn)
        assertEquals("CgtEbEo1bzFHWUVmWSih%3D%3D", shell.visitorData)
        assertEquals("2.20261030.02.00", shell.clientVersion)
        assertEquals("1", shell.sessionIndex)
        assertEquals(false, YouTubeWebClient.parseShell(""""LOGGED_IN":false,"VISITOR_DATA":"Cgt"""")!!.loggedIn)
    }

    @Test
    fun `a page that is not youtube's tells nothing`() {
        assertNull(YouTubeWebClient.parseShell("<html><body>Before you continue to YouTube</body></html>"))
        assertNull(YouTubeWebClient.parseShell(""))
    }

    @Test
    fun `headers are one desktop browser with the page's identity`() {
        val headers = YouTubeWebClient.headers(YouTubeWebShell(true, "CgtV", "2.20261030.02.00", "1"))
        assertTrue(headers.getValue("user-agent").contains("Windows NT 10.0"))
        assertFalse(headers.getValue("user-agent").contains("Android"))
        assertEquals("https://www.youtube.com/", headers["referer"])
        assertEquals("https://www.youtube.com", headers["x-origin"])
        assertEquals("2.20261030.02.00", headers["x-youtube-client-version"])
        assertEquals("CgtV", headers["x-goog-visitor-id"])
        assertEquals("1", headers["x-goog-authuser"])
        val unread = YouTubeWebClient.headers(null)
        assertEquals("0", unread["x-goog-authuser"])
        assertEquals(YouTubeWebClient.FALLBACK_CLIENT_VERSION, unread["x-youtube-client-version"])
        assertFalse("x-goog-visitor-id" in unread)
        assertEquals("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", YouTubeWebClient.url("browse"))
    }

    @Test
    fun `a refusal is the Sorry page or a 429, not a plain 403`() {
        assertTrue(YouTubeWebClient.isRefusal(403, "<title>Sorry...</title>"))
        assertTrue(YouTubeWebClient.isRefusal(429, ""))
        assertFalse(YouTubeWebClient.isRefusal(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}"))
    }

    @Test
    fun `refreshed cookies replace saved ones, deletions and new names do not`() {
        val jar = "SID=old; HSID=h; SIDCC=cc1; __Secure-1PSIDTS=ts1; SAPISID=s/x"
        val (merged, changed) = mergeSetCookies(
            jar,
            listOf(
                "SIDCC=cc2; expires=Tue, 02-Nov-2027 18:00:00 GMT; path=/; domain=.youtube.com; priority=high",
                "__Secure-1PSIDTS=ts2; expires=Tue, 02-Nov-2027 18:00:00 GMT; path=/; domain=.youtube.com; Secure; HttpOnly",
                "SID=; Domain=.youtube.com; Path=/",
                "HSID=gone; Domain=.youtube.com; Expires=Tue, 06-Feb-2024 18:42:41 GMT; Path=/",
                "SAPISID=s/x; Max-Age=0",
                "YSC=new; Domain=.youtube.com; Path=/; Secure",
            ),
        )
        assertEquals("SID=old; HSID=h; SIDCC=cc2; __Secure-1PSIDTS=ts2; SAPISID=s/x", merged)
        assertEquals(listOf("SIDCC", "__Secure-1PSIDTS"), changed)
        assertTrue(mergeSetCookies(jar, listOf("SIDCC=cc1; Path=/")).second.isEmpty())
    }

    @Test
    fun `player script url and signatureTimestamp are read`() {
        val html = """ytcfg.set({"PLAYER_JS_URL":"\/s\/player\/6740c111\/player_ias.vflset\/en_US\/base.js","LOGGED_IN":true});"""
        assertEquals("https://www.youtube.com/s/player/6740c111/player_ias.vflset/en_US/base.js", YouTubeWebClient.playerJsUrl(html))
        assertEquals("https://www.youtube.com/s/player/6740c111/player_ias.vflset/en_US/base.js", YouTubeWebClient.parseShell(html)?.playerJsUrl)
        assertEquals(20391, YouTubeWebClient.signatureTimestamp("var a={x:1,signatureTimestamp:20391,y:2}"))
        assertEquals(20391, YouTubeWebClient.signatureTimestamp("e.sts=1;f={sts:20391}"))
        assertEquals(null, YouTubeWebClient.signatureTimestamp("nothing here"))
    }

    @Test
    fun `playability summary names the refusal`() {
        val body = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
        assertEquals("LOGIN_REQUIRED \"Sign in to confirm you're not a bot\"", YouTubeWebClient.playabilitySummary(body))
        assertEquals("no playabilityStatus", YouTubeWebClient.playabilitySummary("{}"))
    }
}
