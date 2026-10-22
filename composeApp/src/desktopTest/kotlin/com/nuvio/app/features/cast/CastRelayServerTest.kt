package com.nuvio.app.features.cast

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastRelayServerTest {
    private val file = ByteArray(100_000) { (it % 251).toByte() }
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/movie.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            if (range != null) {
                val start = range.substringAfter('=').substringBefore('-').toInt()
                val end = range.substringAfter('-').toIntOrNull() ?: (file.size - 1)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${file.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.use { it.write(file, start, end - start + 1) }
            } else {
                exchange.sendResponseHeaders(200, file.size.toLong())
                exchange.responseBody.use { it.write(file) }
            }
        }
        start()
    }
    private val relay = CastRelayServer()
    private val spoolDir = Files.createTempDirectory("cast-relay-test").toFile()

    @AfterTest
    fun tearDown() {
        upstream.stop(0)
        relay.clear()
        spoolDir.deleteRecursively()
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** A raw HTTP exchange, so the relay's exact framing (close-delimited, never chunked) is visible. */
    private fun get(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Reply {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            val request = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            val all = socket.getInputStream().readBytes()
            val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val status = head.first().split(' ')[1].toInt()
            val parsed = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Reply(status, parsed, all.copyOfRange(split + 4, all.size))
        }
    }

    @Test
    fun `passthrough forwards ranges and reports the full size on HEAD`() {
        val url = relay.publish(
            CastMedia.Passthrough("http://127.0.0.1:${upstream.address.port}/movie.mkv", emptyMap(), "video/x-matroska"),
            rendererHost = "127.0.0.1",
            extension = "mkv",
        )
        val head = get(url, method = "HEAD")
        assertEquals(200, head.status)
        assertEquals(file.size.toString(), head.headers["content-length"])
        assertEquals("video/x-matroska", head.headers["content-type"])
        assertTrue(head.headers["contentfeatures.dlna.org"]!!.contains("DLNA.ORG_OP=01"))

        val part = get(url, headers = mapOf("Range" to "bytes=1000-"))
        assertEquals(206, part.status)
        assertEquals("bytes 1000-${file.size - 1}/${file.size}", part.headers["content-range"])
        assertContentEquals(file.copyOfRange(1000, file.size), part.body)
        assertTrue("transfer-encoding" !in part.headers)
    }

    @Test
    fun `remux seek answers with the keyframe time and fragments only`() {
        val data = Fmp4TestData.stream(3)
        val router = DvRemuxRouter(
            byteRate = 3_000_000.0,
            sessionFactory = { requestedS ->
                SpooledRemuxSession(requestedS, if (requestedS > 0) requestedS - 1.0 else 0.0, ByteArrayInputStream(data), spoolDir)
            },
        )
        val url = relay.publish(CastMedia.Remux(router, durationS = 3388.4), rendererHost = "127.0.0.1", extension = "mp4")

        val start = get(url)
        assertEquals(200, start.status)
        assertContentEquals(data, start.body)
        assertTrue("timeseekrange.dlna.org" !in start.headers)

        val header = Fmp4TestData.header().size
        val seek = get(url, headers = mapOf("Range" to "bytes=$header-", "TimeSeekRange.dlna.org" to "npt=0:30:00.000-"))
        assertEquals("npt=1799.000-3388.400/3388.400", seek.headers["timeseekrange.dlna.org"])
        // A whole MP4: the start session's header, then fragments shifted to the seek time.
        assertContentEquals(Fmp4TestData.header(), seek.body.copyOf(header))
        assertEquals("moof", String(seek.body, header + 4, 4, Charsets.ISO_8859_1))
        assertEquals(Math.round(1799.0 * Fmp4TestData.VIDEO_TIMESCALE), Fmp4TestData.decodeTimes(seek.body).first().second)
    }

    @Test
    fun `unknown paths are refused`() {
        val url = relay.publish(
            CastMedia.Passthrough("http://127.0.0.1:${upstream.address.port}/movie.mkv", emptyMap(), "video/x-matroska"),
            rendererHost = "127.0.0.1",
            extension = "mkv",
        )
        val wrong = url.replaceAfter("/cast/", "not-the-token/media.mkv")
        assertEquals(404, get(wrong).status)
    }
}
