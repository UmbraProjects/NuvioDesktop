package com.nuvio.app.features.iptv

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** IPTV requests through an HTTP proxy (with and without auth) and a SOCKS5 proxy with a login. */
class IptvProxyTest {

    private val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",One\nhttp://h/1.ts\n"
    private val servers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun reset() {
        IptvRepository.setProxy(IptvProxy())
        servers.forEach { runCatching { it.close() } }
    }

    private fun playlist(url: String) = IptvPlaylist(id = "p", type = IptvPlaylistType.M3u, name = "p", url = url)

    @Test
    fun `http proxy carries the request and answers a 407 with credentials`() = runBlocking {
        val proxy = MockWebServer()
        servers += AutoCloseable { proxy.shutdown() }
        val seen = mutableListOf<RecordedRequest>()
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return if (request.getHeader("Proxy-Authorization") == null) {
                    MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=\"p\"")
                } else {
                    MockResponse().setBody(m3u)
                }
            }
        }
        proxy.start()
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Http, "127.0.0.1", proxy.port, "user", "pa ss"))

        val content = IptvSources.loadPlaylist(playlist("http://blocked-provider.test/list.m3u"))
        assertEquals(listOf("One"), content.channels.map { it.name })
        // The proxy saw the provider's URL (absolute form), first bare, then with Basic credentials.
        assertEquals("GET http://blocked-provider.test/list.m3u HTTP/1.1", seen.last().requestLine)
        assertEquals(okhttp3.Credentials.basic("user", "pa ss"), seen.last().getHeader("Proxy-Authorization"))
        assertEquals(2, seen.size)
    }

    @Test
    fun `wrong http proxy credentials surface as 407`() = runBlocking {
        val proxy = MockWebServer()
        servers += AutoCloseable { proxy.shutdown() }
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=\"p\"")
        }
        proxy.start()
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Http, "127.0.0.1", proxy.port, "user", "wrong"))
        val error = assertFailsWith<IptvSourceException> { IptvSources.loadPlaylist(playlist("http://blocked-provider.test/list.m3u")) }
        assertEquals(407, error.httpCode)
    }

    @Test
    fun `socks5 proxy with a login resolves the provider name remotely`() = runBlocking {
        val origin = MockWebServer()
        servers += AutoCloseable { origin.shutdown() }
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(m3u)
        }
        origin.start()
        val socks = MiniSocks5(user = "me", pass = "secret", forwardPort = origin.port)
        servers += socks
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Socks, "127.0.0.1", socks.port, "me", "secret"))

        val content = IptvSources.loadPlaylist(playlist("http://blocked-provider.test:${origin.port}/list.m3u"))
        assertEquals(listOf("One"), content.channels.map { it.name })
        assertEquals("blocked-provider.test", socks.requestedHost)
        assertTrue(socks.authenticated)
    }

    @Test
    fun `an unreachable proxy reads as a proxy failure`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        IptvRepository.setProxy(IptvProxy(IptvProxyType.Http, "127.0.0.1", closedPort))
        val error = assertFailsWith<IptvSourceException> { IptvSources.loadPlaylist(playlist("http://blocked-provider.test/list.m3u")) }
        assertTrue(error.isUnreachable(), describeIptvFailure(error))
    }

    @Test
    fun `host and port parsing and the mpv proxy url`() {
        assertEquals("1.2.3.4" to 8080, IptvProxy.parseHostAndPort("1.2.3.4:8080"))
        assertEquals("p.example" to 1080, IptvProxy.parseHostAndPort("socks5://u:p@p.example:1080/"))
        assertEquals("p.example" to 0, IptvProxy.parseHostAndPort(" p.example "))
        assertEquals("http://a%20b:c%40d@h:3128", IptvProxy(IptvProxyType.Http, "h", 3128, "a b", "c@d").mpvHttpProxyUrl())
        assertNull(IptvProxy(IptvProxyType.Socks, "h", 1080).mpvHttpProxyUrl())
        assertNull(IptvProxy(IptvProxyType.Http, "h", 0).mpvHttpProxyUrl())
    }

    /** A one-connection-at-a-time SOCKS5 server (RFC 1928/1929) that forwards to localhost:[forwardPort]. */
    internal class MiniSocks5(private val user: String, private val pass: String, private val forwardPort: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        @Volatile var requestedHost: String? = null
        @Volatile var authenticated = false

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
                }
            }
        }

        private fun handle(client: Socket) {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            check(input.readUnsignedByte() == 5)
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            check(2.toByte() in methods) { "client did not offer username/password" }
            output.write(byteArrayOf(5, 2))
            check(input.readUnsignedByte() == 1)
            val u = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val p = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            authenticated = u == user && p == pass
            output.write(byteArrayOf(1, if (authenticated) 0 else 1))
            if (!authenticated) return
            check(input.readUnsignedByte() == 5)
            check(input.readUnsignedByte() == 1) // CONNECT
            input.readUnsignedByte()
            requestedHost = when (input.readUnsignedByte()) {
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                else -> error("unsupported address type")
            }
            input.readUnsignedShort()
            val upstream = Socket("127.0.0.1", forwardPort)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()
            val a = thread(isDaemon = true) { pipe(input, upstream.getOutputStream()) }
            pipe(upstream.getInputStream(), output)
            a.join(2000)
            upstream.close()
        }

        private fun pipe(from: InputStream, to: OutputStream) {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            }
        }

        override fun close() = server.close()
    }
}
