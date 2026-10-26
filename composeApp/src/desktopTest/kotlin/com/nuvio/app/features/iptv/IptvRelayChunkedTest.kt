package com.nuvio.app.features.iptv

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class IptvRelayChunkedTest {
    @Test
    fun `a body without a length is re-chunked to mpv, not left to run to the close`() {
        IptvRepository.setProxy(IptvProxy())
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/seg.ts") { exchange ->
            exchange.sendResponseHeaders(200, 0) // 0 = chunked, no Content-Length
            exchange.responseBody.use { it.write(ByteArray(200_000) { 0x47 }) }
        }
        upstream.start()
        try {
            val proxy = IptvProxyRelay.mpvProxyUrl().toHttpUrl()
            val auth = Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password}".toByteArray())
            val target = "http://127.0.0.1:${upstream.address.port}/seg.ts"
            val raw = Socket(proxy.host, proxy.port).use { socket ->
                socket.getOutputStream().write(
                    "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Authorization: Basic $auth\r\n\r\n".toByteArray(),
                )
                socket.getInputStream().readBytes()
            }
            val text = String(raw, Charsets.ISO_8859_1)
            val head = text.substringBefore("\r\n\r\n")
            assertTrue("Transfer-Encoding: chunked" in head, head)
            assertTrue(text.endsWith("0\r\n\r\n"), "terminated with the last chunk")
            // 200,000 bytes of body made it through, between the chunk framing.
            assertTrue(text.count { it == 'G' } >= 200_000, "whole body")
        } finally {
            upstream.stop(0)
        }
    }
}
