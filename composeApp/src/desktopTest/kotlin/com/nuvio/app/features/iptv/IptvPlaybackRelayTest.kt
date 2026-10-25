package com.nuvio.app.features.iptv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * mpv's path for IPTV behind a SOCKS5 proxy: mpv (played here by an OkHttp client configured the
 * way FFmpeg is) talks HTTP to [IptvProxyRelay], which goes through the SOCKS5 proxy to the origin.
 */
class IptvPlaybackRelayTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun reset() {
        IptvRepository.setProxy(IptvProxy())
        IptvPlaybackSession.clear()
        IptvProxyRelay.stopForTest()
        closeables.forEach { runCatching { it.close() } }
    }

    private fun origin(): MockWebServer = MockWebServer().also { server ->
        closeables += AutoCloseable { server.shutdown() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/live/u/p/1.ts" -> MockResponse().setBody("TS-BYTES").addHeader("Content-Type", "video/mp2t")
                    // MockWebServer ignores Connection: close; the CONNECT test reads to EOF.
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                "/moved" -> MockResponse().setResponseCode(302).addHeader("Location", "/live/u/p/1.ts")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun socksTo(originPort: Int): IptvProxyTest.MiniSocks5 =
        IptvProxyTest.MiniSocks5(user = "me", pass = "secret", forwardPort = originPort).also { closeables += it }

    /** An HTTP client that uses the relay the way FFmpeg does with `http_proxy=http://user:pass@…`. */
    private fun mpvLikeClient(): OkHttpClient {
        val relay = URI(IptvProxyRelay.mpvProxyUrl())
        val (user, pass) = relay.userInfo.split(':', limit = 2)
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(relay.host, relay.port)))
            .proxyAuthenticator { _, response ->
                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
            }
            .followRedirects(false)
            .build()
    }

    @Test
    fun `relay carries a stream through the socks proxy and passes redirects back`() {
        val origin = origin()
        val socks = socksTo(origin.port)
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Socks, "127.0.0.1", socks.port, "me", "secret"))
        val client = mpvLikeClient()

        client.newCall(Request.Builder().url("http://blocked-provider.test:${origin.port}/live/u/p/1.ts").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("TS-BYTES", it.body!!.string())
            assertEquals("video/mp2t", it.header("Content-Type"))
        }
        assertEquals("blocked-provider.test", socks.requestedHost)

        client.newCall(Request.Builder().url("http://blocked-provider.test:${origin.port}/moved").build()).execute().use {
            assertEquals(302, it.code, "mpv follows redirects itself, through the relay again")
            assertEquals("/live/u/p/1.ts", it.header("Location"))
        }
    }

    @Test
    fun `relay refuses requests without its password`() {
        val relay = URI(IptvProxyRelay.mpvProxyUrl())
        Socket(relay.host, relay.port).use { socket ->
            socket.getOutputStream().write("GET http://example.test/ HTTP/1.1\r\nHost: example.test\r\n\r\n".toByteArray())
            val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            assertEquals("HTTP/1.1 407 Proxy Authentication Required", status)
        }
    }

    @Test
    fun `connect tunnels raw bytes through the socks proxy`() {
        val origin = origin()
        val socks = socksTo(origin.port)
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Socks, "127.0.0.1", socks.port, "me", "secret"))
        val relay = URI(IptvProxyRelay.mpvProxyUrl())
        val (user, pass) = relay.userInfo.split(':', limit = 2)
        Socket(relay.host, relay.port).use { socket ->
            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            out.write(
                ("CONNECT secure-provider.test:443 HTTP/1.1\r\nHost: secure-provider.test:443\r\n" +
                    "Proxy-Authorization: ${Credentials.basic(user, pass)}\r\n\r\n").toByteArray(),
            )
            assertEquals("HTTP/1.1 200 Connection Established", reader.readLine())
            assertEquals("", reader.readLine())
            // Through the tunnel: a plain request to the origin (TLS would sit here in real use).
            out.write("GET /live/u/p/1.ts HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            val body = reader.readLines()
            assertTrue(body.first().startsWith("HTTP/1.1 200"), body.first())
            assertEquals("TS-BYTES", body.last())
        }
        assertEquals("secure-provider.test", socks.requestedHost)
    }

    @Test
    fun `mpv options only for the playing channel, and without the proxy password`() {
        val playlist = IptvPlaylist(id = "relay-p", type = IptvPlaylistType.Xtream, name = "P", server = "http://h:80", username = "u", password = "p")
        val channel = IptvChannel(uid = 0, key = "1", num = 1, name = "One", categoryId = "c", streamId = "1")
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Socks, "socks.example", 1080, "me", "TopSecret"))

        assertTrue(iptvMpvOptions("http://h:80/live/u/p/1.ts").isEmpty(), "no session yet")
        val launch = assertNotNull(IptvPlaybackSession.launchFor(playlist, channel))
        val options = iptvMpvOptions(launch.sourceUrl)
        assertTrue("ytdl=no" in options && "stream-lavf-o-append=reconnect_streamed=1" in options, options.toString())
        val proxyOption = options.single { it.startsWith("http-proxy=") }
        assertTrue(proxyOption.startsWith("http-proxy=http://nuvio:") && "@127.0.0.1:" in proxyOption, proxyOption)
        assertTrue(options.none { "TopSecret" in it || "socks.example" in it }, options.toString())
        assertTrue(iptvMpvOptions("https://other.example/movie.mkv").isEmpty())

        IptvRepository.setProxy(IptvProxy())
        assertNull(iptvMpvOptions(launch.sourceUrl).firstOrNull { it.startsWith("http-proxy=") }, "no proxy, direct")
    }

    @Test
    fun `a longer rewind buffer goes to disk and only for live tv`() {
        val playlist = IptvPlaylist(id = "rewind-p", type = IptvPlaylistType.Xtream, name = "P", server = "http://h:80", username = "u", password = "p")
        val channel = IptvChannel(uid = 0, key = "1", num = 1, name = "One", categoryId = "c", streamId = "1")
        val launch = assertNotNull(IptvPlaybackSession.launchFor(playlist, channel))
        try {
            assertTrue(iptvRewindMpvOptions(launch.sourceUrl).isEmpty(), "standard buffer: mpv's own settings")
            IptvRepository.setRewindMinutes(30)
            val options = iptvRewindMpvOptions(launch.sourceUrl)
            assertTrue("cache-on-disk=yes" in options, options.toString())
            assertTrue("demuxer-max-back-bytes=${30L * 90 * 1024 * 1024}" in options, options.toString())
            assertTrue(iptvRewindActive(launch.sourceUrl))
            assertTrue(iptvRewindMpvOptions("https://other.example/movie.mkv").isEmpty())
            IptvRepository.setRewindMinutes(17)
            assertEquals(0, IptvRepository.settings.value.rewindMinutes, "only the offered choices are kept")
        } finally {
            IptvRepository.setRewindMinutes(0)
        }
    }
}
