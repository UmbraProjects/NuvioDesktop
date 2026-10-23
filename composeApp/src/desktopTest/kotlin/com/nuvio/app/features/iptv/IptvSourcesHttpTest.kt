package com.nuvio.app.features.iptv

import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** The streamed HTTP path end to end, against a fake Xtream server and M3U/XMLTV files. */
class IptvSourcesHttpTest {

    private lateinit var server: MockWebServer
    private var acceptLogin = true

    private fun xmltv(): String {
        val now = System.currentTimeMillis()
        fun t(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC))
        return """
            <tv>
              <channel id="one.tv"><display-name>One</display-name></channel>
              <programme start="${t(now - 600_000)}" stop="${t(now + 600_000)}" channel="one.tv"><title>On now</title></programme>
              <programme start="${t(now + 600_000)}" stop="${t(now + 1_800_000)}" channel="one.tv"><title>Up next</title></programme>
            </tv>
        """.trimIndent()
    }

    private fun gzip(text: String): Buffer {
        val buffer = Buffer()
        GZIPOutputStream(buffer.outputStream()).use { it.write(text.toByteArray()) }
        return buffer
    }

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/player_api.php" -> {
                        if (url.queryParameter("username") != "u" || url.queryParameter("password") != "p w") {
                            return MockResponse().setResponseCode(401)
                        }
                        when (url.queryParameter("action")) {
                            null -> MockResponse().setBody(
                                if (acceptLogin) {
                                    """{"user_info":{"auth":1,"status":"Active","max_connections":"1","exp_date":"1900000000","allowed_output_formats":["ts"]},"server_info":{"timezone":"Europe/London"}}"""
                                } else {
                                    """{"user_info":{"auth":0}}"""
                                },
                            )
                            "get_live_categories" -> MockResponse().setBody("""[{"category_id":"1","category_name":"News"}]""")
                            "get_live_streams" -> MockResponse().setBody(
                                """[{"num":1,"name":"One","stream_id":11,"category_id":"1","epg_channel_id":"one.tv"},
                                   {"num":2,"name":"Two","stream_id":"12","category_id":"7"}]""",
                            )
                            "get_short_epg" -> MockResponse().setBody(
                                """{"epg_listings":[{"title":"${java.util.Base64.getEncoder().encodeToString("Short".toByteArray())}","start_timestamp":"${System.currentTimeMillis() / 1000 - 60}","stop_timestamp":"${System.currentTimeMillis() / 1000 + 600}"}]}""",
                            )
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    "/xmltv.php" -> MockResponse().setBody(gzip(xmltv()))
                    "/list.m3u" -> MockResponse().setBody(
                        "#EXTM3U x-tvg-url=\"${server.url("/xmltv.php")}\"\n#EXTINF:-1 tvg-id=\"one.tv\" group-title=\"G\",One\nhttp://h/1.ts\n",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun xtream() = IptvPlaylist(
        id = "x1", type = IptvPlaylistType.Xtream, name = "X",
        server = server.url("/").toString(), username = "u", password = "p w",
    )

    @Test
    fun `xtream playlist loads channels, account and guide url`() = runBlocking {
        val content = IptvSources.loadPlaylist(xtream())
        assertEquals(listOf("One", "Two"), content.channels.map { it.name })
        assertEquals(listOf("1", IptvPlaylistContent.OTHER_CATEGORY), content.categories.map { it.id })
        val account = assertNotNull(content.account)
        assertEquals(1, account.maxConnections)
        assertEquals(setOf("ts"), account.allowedFormats)
        assertEquals(1900000000L, account.expiresAtSeconds)
        assertTrue(content.guideUrl!!.contains("/xmltv.php?username=u&password=p+w"))

        val guide = IptvSources.loadGuide(content.guideUrl!!, content.channels)
        assertEquals(listOf("On now", "Up next"), guide.entriesFor(content.channels[0]).map { it.title })
    }

    @Test
    fun `rejected xtream login is BadLogin`() = runBlocking {
        acceptLogin = false
        val error = assertFailsWith<IptvSourceException> { IptvSources.loadPlaylist(xtream()) }
        assertEquals(IptvSourceException.Kind.BadLogin, error.kind)
    }

    @Test
    fun `wrong credentials surface the http status`() = runBlocking {
        val error = assertFailsWith<IptvSourceException> { IptvSources.loadPlaylist(xtream().copy(password = "nope")) }
        assertEquals(IptvSourceException.Kind.Http, error.kind)
        assertEquals(401, error.httpCode)
    }

    @Test
    fun `xtream short epg decodes base64 titles`() = runBlocking {
        val entries = XtreamClient.shortEpg(xtream(), "11")
        assertEquals(listOf("Short"), entries.map { it.title })
    }

    @Test
    fun `m3u playlist takes its guide from the header`() = runBlocking {
        val playlist = IptvPlaylist(id = "m1", type = IptvPlaylistType.M3u, name = "M", url = server.url("/list.m3u").toString())
        val content = IptvSources.loadPlaylist(playlist)
        assertEquals(listOf("One"), content.channels.map { it.name })
        assertEquals(server.url("/xmltv.php").toString(), content.guideUrl)

        val missing = assertFailsWith<IptvSourceException> { IptvSources.loadPlaylist(playlist.copy(url = server.url("/gone.m3u").toString())) }
        assertEquals(404, missing.httpCode)
    }

    @Test
    fun `repository caches channels on disk and cleans up on removal`() = runBlocking {
        IptvRepository.onProfileChanged()
        val stored = IptvRepository.addPlaylist(xtream())
        try {
            IptvRepository.refresh(stored.id)
            val state = IptvRepository.contentState(stored.id).value
            assertNull(state.error)
            assertEquals(2, state.content?.channels?.size)

            // A fresh process (simulated): settings come back from storage, channels from the disk cache.
            server.shutdown()
            IptvRepository.onProfileChanged()
            assertNotNull(IptvRepository.playlist(stored.id))
            IptvRepository.refresh(stored.id)
            assertEquals(2, IptvRepository.contentState(stored.id).value.content?.channels?.size)

            IptvRepository.toggleFavourite(stored.id, "11")
            assertTrue(IptvRepository.isFavourite(stored.id, "11"))
        } finally {
            IptvRepository.removePlaylist(stored.id)
        }
        assertNull(IptvRepository.playlist(stored.id))
        assertTrue(IptvRepository.settings.value.favourites.none { it.startsWith(stored.id) })
        assertNull(IptvCacheStorage.openRead("playlist_${stored.id}.json.gz"))
    }
}
