package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** The whole flow over HTTP, against a fake portal. */
class StalkerClientHttpTest {

    private val mac = "00:1A:79:AB:CD:EF"
    private lateinit var server: MockWebServer

    /** The answer for an `action`, or null for a 404. Replaced by each test as it needs. */
    private var answer: (RecordedRequest, String) -> MockResponse? = { _, _ -> null }
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    private fun body(json: String) = MockResponse().setBody(json).addHeader("Content-Type", "application/json")

    private val goodLogin: (String) -> MockResponse? = { action ->
        when (action) {
            "handshake" -> body("""{"js": {"token": "HELLO", "random": "RND"}}""")
            "get_profile" -> body("""{"js": {"id": "9", "status": 0, "token": "PROFILE"}}""")
            else -> null
        }
    }

    private fun playlist(path: String = "/c/") = IptvPlaylist(
        id = "s", type = IptvPlaylistType.Stalker, name = "S", server = server.url(path).toString(), username = mac,
    )

    private fun actions() = requests.map { it.requestUrl!!.queryParameter("action") }

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val action = request.requestUrl!!.queryParameter("action").orEmpty()
                return answer(request, action) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        StalkerUrls.portalHosts.clear()
    }

    @Test
    fun `logs in, reads genres and channels, and identifies itself on every request`() = runBlocking {
        answer = { _, action ->
            goodLogin(action) ?: when (action) {
                "get_genres" -> body("""{"js": [{"id": "*", "title": "All"}, {"id": "1", "title": "News"}]}""")
                "get_all_channels" -> body(
                    """{"js": {"data": [
                        {"id": "10", "name": "One", "number": "5", "cmd": "ffmpeg http://localhost/ch/10_", "tv_genre_id": "1", "logo": "/logos/10.png", "xmltv_id": "one.tv"},
                        {"id": "11", "name": "Two", "cmd": "", "cmds": [{"url": "ffmpeg http://cdn.example.net/11.ts"}], "tv_genre_id": "9"},
                        {"id": "12", "name": "No command"}
                    ]}}""",
                )
                else -> null
            }
        }
        val content = StalkerClient.load(playlist())

        assertEquals(listOf("handshake", "get_profile", "get_genres", "get_all_channels"), actions())
        assertEquals(listOf("1", IptvPlaylistContent.OTHER_CATEGORY), content.categories.map { it.id })
        assertEquals(listOf("One", "Two"), content.channels.map { it.name })
        val one = content.channels.first()
        assertEquals("ffmpeg http://localhost/ch/10_", one.url)
        assertEquals(5, one.num)
        assertEquals(server.url("/logos/10.png").toString(), one.logo)
        assertEquals("one.tv", one.epgId)
        assertEquals(STALKER_USER_AGENT, one.requestHeaders()["User-Agent"])
        assertEquals("ffmpeg http://cdn.example.net/11.ts", content.channels[1].url)

        val first = requests.first()
        assertEquals("/portal.php", first.requestUrl!!.encodedPath)
        assertEquals(STALKER_USER_AGENT, first.getHeader("User-Agent"))
        assertEquals("Model: MAG250; Link: WiFi", first.getHeader("X-User-Agent"))
        assertTrue(first.getHeader("Referer")!!.endsWith("/c/"))
        assertTrue(first.getHeader("Cookie")!!.startsWith("mac=$mac; stb_lang=en; timezone="))
        assertEquals(null, first.getHeader("Authorization"))
        assertEquals("1-xml", first.requestUrl!!.queryParameter("JsHttpRequest"))

        assertEquals("Bearer HELLO", requests[1].getHeader("Authorization"))
        assertEquals("RND", requests[1].requestUrl!!.queryParameter("random"))
        // The profile's token replaces the handshake's.
        assertEquals("Bearer PROFILE", requests[2].getHeader("Authorization"))
        assertTrue(requests[2].getHeader("Cookie")!!.endsWith("; token=PROFILE"))
    }

    @Test
    fun `tries the next address when one is not a portal`() = runBlocking {
        answer = { request, action ->
            if (request.requestUrl!!.encodedPath == "/portal.php") {
                MockResponse().setBody("<html>not here</html>")
            } else {
                goodLogin(action) ?: body("""{"js": []}""")
            }
        }
        StalkerClient.load(playlist())
        assertEquals(listOf("/portal.php", "/server/load.php"), requests.map { it.requestUrl!!.encodedPath }.distinct())
    }

    @Test
    fun `a handshake token with a blank profile is a bad login and no channels are asked for`() = runBlocking {
        answer = { _, action ->
            when (action) {
                "handshake" -> body("""{"js": {"token": "HELLO"}}""")
                "get_profile" -> body("""{"js": {"id": null, "status": 0}}""")
                else -> body("""{"js": []}""")
            }
        }
        val error = assertFailsWith<IptvSourceException> { StalkerClient.load(playlist("/portal.php")) }
        assertEquals(IptvSourceException.Kind.BadMac, error.kind)
        assertEquals(listOf("handshake", "get_profile"), actions())
    }

    @Test
    fun `a blocked MAC stops at the first address that says so`() = runBlocking {
        answer = { _, action ->
            when (action) {
                "handshake" -> body("""{"js": {"token": "HELLO"}}""")
                else -> body("""{"js": {"id": "9", "status": 1}}""")
            }
        }
        assertEquals(IptvSourceException.Kind.BadMac, assertFailsWith<IptvSourceException> { StalkerClient.load(playlist()) }.kind)
        assertEquals(1, requests.count { it.requestUrl!!.queryParameter("action") == "handshake" })
    }

    @Test
    fun `no token from any address is a bad login`() = runBlocking {
        answer = { _, _ -> body("""{"js": {}}""") }
        assertEquals(IptvSourceException.Kind.BadMac, assertFailsWith<IptvSourceException> { StalkerClient.load(playlist()) }.kind)
        assertEquals(3, requests.size)
    }

    @Test
    fun `an expired token logs in again once and retries`() = runBlocking {
        var failures = 1
        answer = { _, action ->
            goodLogin(action) ?: if (action == "get_genres" && failures-- > 0) {
                MockResponse().setBody("Authorization failed.")
            } else {
                body("""{"js": []}""")
            }
        }
        StalkerClient.load(playlist("/portal.php"))
        assertEquals(listOf("handshake", "get_profile", "get_genres", "handshake", "get_profile", "get_genres", "get_all_channels"), actions())
    }

    @Test
    fun `HTTP 401 counts as an expired token, and a second one gives up`() = runBlocking {
        answer = { _, action -> goodLogin(action) ?: MockResponse().setResponseCode(401) }
        assertEquals(IptvSourceException.Kind.BadMac, assertFailsWith<IptvSourceException> { StalkerClient.load(playlist("/portal.php")) }.kind)
        assertEquals(listOf("handshake", "get_profile", "get_genres", "handshake", "get_profile", "get_genres"), actions())
    }

    @Test
    fun `censored genres are read page by page, up to what the portal says it has`() = runBlocking {
        answer = { request, action ->
            goodLogin(action) ?: when (action) {
                "get_genres" -> body("""{"js": [{"id": "1", "title": "News"}, {"id": "2", "title": "Adult", "censored": 1}]}""")
                "get_all_channels" -> body("""{"js": [{"id": "10", "name": "One", "cmd": "c", "tv_genre_id": "1"}]}""")
                "get_ordered_list" -> {
                    val page = request.requestUrl!!.queryParameter("p")!!.toInt()
                    body("""{"js": {"total_items": 3, "max_page_items": 2, "data": [{"id": "${20 + page}", "name": "A$page", "cmd": "c", "tv_genre_id": "2"}, {"id": "10", "name": "Dup", "cmd": "c"}]}}""")
                }
                else -> null
            }
        }
        val content = StalkerClient.load(playlist("/portal.php"))
        assertEquals(listOf("One", "A1", "A2"), content.channels.map { it.name })
        assertEquals(2, requests.count { it.requestUrl!!.queryParameter("action") == "get_ordered_list" })
        val page = requests.first { it.requestUrl!!.queryParameter("action") == "get_ordered_list" }.requestUrl!!
        assertEquals("2", page.queryParameter("genre"))
        assertEquals("number", page.queryParameter("sortby"))
    }

    @Test
    fun `a portal with no real genres gets its dividers as groups`() = runBlocking {
        answer = { _, action ->
            goodLogin(action) ?: when (action) {
                "get_genres" -> body("""{"js": [{"id": "*", "title": "All"}]}""")
                "get_all_channels" -> body(
                    """{"js": [
                        {"id": "1", "name": "✦●✦ SPORT ✦●✦", "cmd": "c"}, {"id": "2", "name": "Sport 1", "cmd": "c"},
                        {"id": "3", "name": "✦●✦ NEWS ✦●✦", "cmd": "c"}, {"id": "4", "name": "News 1", "cmd": "c"}
                    ]}""",
                )
                else -> null
            }
        }
        val content = StalkerClient.load(playlist("/portal.php"))
        assertEquals(listOf("SPORT", "NEWS"), content.categories.map { it.name })
        assertEquals(listOf("Sport 1", "News 1"), content.channels.map { it.name })
    }

    @Test
    fun `a channel link is asked for each time and read from its command`() = runBlocking {
        var link = 0
        answer = { _, action ->
            goodLogin(action) ?: if (action == "create_link") {
                link++
                body("""{"js": {"cmd": "ffmpeg http://cdn.example.net/$link.m3u8?t=$link"}}""")
            } else {
                null
            }
        }
        val channel = IptvChannel(uid = 0, key = "10", num = 1, name = "One", categoryId = "", url = "ffmpeg http://localhost/ch/10_")
        val first = StalkerClient.streamCandidates(playlist("/portal.php"), channel).single()
        assertEquals("http://cdn.example.net/1.m3u8?t=1", first.url)
        assertEquals(IptvStreamFormat.HLS, first.format)
        assertEquals("http://cdn.example.net/2.m3u8?t=2", StalkerClient.streamCandidates(playlist("/portal.php"), channel).single().url)

        val request = requests.first { it.requestUrl!!.queryParameter("action") == "create_link" }.requestUrl!!
        assertEquals("ffmpeg http://localhost/ch/10_", request.queryParameter("cmd"))
        assertEquals("undefined", request.queryParameter("forced_storage"))
        assertEquals(1, requests.count { it.requestUrl!!.queryParameter("action") == "handshake" }, "one login for both")
    }

    @Test
    fun `a reconnect gets a new link and channel up and down count from the channel being opened`() = runBlocking {
        var link = 0
        answer = { _, action ->
            goodLogin(action) ?: body("""{"js": {"cmd": "ffmpeg http://cdn.example.net/${++link}.ts"}}""")
        }
        val portal = playlist("/portal.php")
        val channels = (0..2).map { IptvChannel(uid = it, key = "$it", num = it + 1, name = "C$it", categoryId = "", url = "ffmpeg http://localhost/ch/${it}_") }
        val launch = IptvPlaybackSession.launchFor(portal, channels[0], channels, StalkerClient.streamCandidates(portal, channels[0]))!!
        try {
            assertEquals("http://cdn.example.net/1.ts", launch.sourceUrl)
            assertEquals("http://cdn.example.net/2.ts", IptvPlaybackSession.renewLink())
            assertTrue(IptvPlaybackSession.isIptvUrl("http://cdn.example.net/2.ts"))

            IptvPlaybackSession.setOpening(channels[1])
            assertEquals(channels[2], IptvPlaybackSession.neighbour(1))
            assertEquals(channels[0], IptvPlaybackSession.active.value!!.channel, "still playing until the link arrives")

            val url = IptvPlaybackSession.switchTo(channels[1], IptvPlaybackSession.linkCandidates(channels[1]))
            assertEquals("http://cdn.example.net/3.ts", url)
            assertEquals(channels[1], IptvPlaybackSession.active.value!!.channel)
            assertEquals(null, IptvPlaybackSession.active.value!!.opening)
        } finally {
            IptvPlaybackSession.clear()
        }
    }

    @Test
    fun `without an answer for the link the command's own URL is used`() = runBlocking {
        answer = { _, action -> goodLogin(action) ?: MockResponse().setResponseCode(500) }
        val channel = IptvChannel(uid = 0, key = "10", num = 1, name = "One", categoryId = "", url = "ffmpeg http://localhost/ch/10_")
        val candidate = StalkerClient.streamCandidates(playlist("/portal.php"), channel).single()
        assertEquals(server.url("/ch/10_").toString(), candidate.url)
    }

    @Test
    fun `the short guide is read in seconds and sorted`() = runBlocking {
        answer = { _, action ->
            goodLogin(action) ?: body(
                """{"js": [{"name": "Later", "start_timestamp": 200, "stop_timestamp": 300}, {"name": "Now", "start_timestamp": "100", "stop_timestamp": "200"}, {"name": "Broken"}]}""",
            )
        }
        val entries = StalkerClient.shortEpg(playlist("/portal.php"), "10")
        assertEquals(listOf(IptvEpgEntry("Now", 100_000, 200_000), IptvEpgEntry("Later", 200_000, 300_000)), entries)
        val request = requests.last().requestUrl!!
        assertEquals("10", request.queryParameter("ch_id"))
        assertEquals("4", request.queryParameter("size"))
    }
}
