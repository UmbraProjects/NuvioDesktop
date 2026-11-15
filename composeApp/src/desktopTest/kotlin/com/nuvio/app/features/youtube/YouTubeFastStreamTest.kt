package com.nuvio.app.features.youtube

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeFastStreamTest {
    private val file = ByteArray(10_500) { (it * 7 % 251).toByte() }
    /** Ranges starting here or later are answered 403. */
    private val refuseFrom = AtomicInteger(Int.MAX_VALUE)
    private val ranges = Collections.synchronizedList(mutableListOf<String>())
    private val cookies = Collections.synchronizedList(mutableListOf<String>())
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        createContext("/videoplayback") { exchange ->
            exchange.requestHeaders.getFirst("Cookie")?.let(cookies::add)
            val range = exchange.requestHeaders.getFirst("Range")!!
            val start = range.substringAfter('=').substringBefore('-').toInt()
            if (start >= refuseFrom.get()) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            ranges += range
            val end = range.substringAfter('-').toInt().coerceAtMost(file.size - 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${file.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(file, start, end - start + 1) }
        }
        start()
    }
    private val dir = Files.createTempDirectory("faststream-test").toFile()
    private val opened = mutableListOf<FastStreamDownload>()

    private val upstreamUrl get() = "http://127.0.0.1:${upstream.address.port}/videoplayback?itag=1&mime=video%2Fmp4"

    private fun download(connections: Int = 3) = FastStreamDownload(
        upstreamUrl = upstreamUrl,
        headers = YouTubeFastStream.forwardedHeaders(mapOf("User-Agent" to "test", "Cookie" to "SID=secret")),
        file = java.io.File(dir, "d${opened.size}.part"),
        connections = connections,
        chunkSize = 1_000,
        backoff = { 1L },
    ).also { opened += it; it.start() }

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        upstream.stop(0)
        dir.deleteRecursively()
    }

    private fun fetch(url: String, range: String?): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            range?.let { setRequestProperty("Range", it) }
        }

    @Test
    fun servesRangesFromTheParallelDownload() {
        val download = download()
        val url = FastStreamServer.urlFor(download)
        val connection = fetch(url, "bytes=2500-6999")
        assertEquals(206, connection.responseCode)
        assertEquals("bytes 2500-6999/${file.size}", connection.getHeaderField("Content-Range"))
        assertEquals("video/mp4", connection.contentType)
        assertContentEquals(file.copyOfRange(2500, 7000), connection.inputStream.readBytes())

        val whole = fetch(url, null)
        assertEquals(200, whole.responseCode)
        assertContentEquals(file, whole.inputStream.readBytes())

        val deadline = System.currentTimeMillis() + 5_000
        while (download.downloadedBytes < file.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(file.size.toLong(), download.downloadedBytes)
        // Bounded ranges only, each a chunk, and never the account's cookies.
        assertTrue(ranges.all { Regex("bytes=\\d+-\\d+").matches(it) })
        assertEquals(11, ranges.filter { it != "bytes=0-0" }.distinct().size)
        assertTrue(cookies.isEmpty())
    }

    @Test
    fun refusedStreamRedirectsThePlayerToYouTube() {
        refuseFrom.set(0)
        val download = download()
        val url = FastStreamServer.urlFor(download)
        val connection = fetch(url, "bytes=0-")
        assertEquals(302, connection.responseCode)
        assertEquals(upstreamUrl, connection.getHeaderField("Location"))
        assertTrue(download.failed)
    }

    @Test
    fun repeatedRefusalsGiveUpAfterSlowingDown() {
        refuseFrom.set(5_000)
        val download = download(connections = 3)
        assertTrue(download.awaitReady())
        // A read that can no longer be served ends, so mpv reconnects and gets the redirect.
        val url = FastStreamServer.urlFor(download)
        val connection = fetch(url, "bytes=9000-")
        runCatching { connection.inputStream.readBytes() }
        val deadline = System.currentTimeMillis() + 5_000
        while (!download.failed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(download.failed)
        assertTrue(download.throttled)
        assertEquals(302, fetch(url, "bytes=9000-").responseCode)
    }

    @Test
    fun closeDeletesTheFile() {
        val download = download()
        assertTrue(download.awaitReady())
        val file = dir.listFiles()!!.single()
        download.close()
        assertFalse(file.exists())
        assertEquals(404, fetch(FastStreamServer.urlFor(download), "bytes=0-").responseCode)
    }

    @Test
    fun onlyDirectGooglevideoStreamsQualify() {
        assertTrue(YouTubeFastStream.eligible("https://rr3---sn-abc.googlevideo.com/videoplayback?itag=137&clen=100"))
        assertFalse(YouTubeFastStream.eligible("https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x"))
        assertFalse(YouTubeFastStream.eligible("https://example.com/videoplayback"))
    }

    @Test
    fun rangeHeaders() {
        assertEquals(0L to 99L, FastStreamServer.parseRange(null, 100))
        assertEquals(10L to 99L, FastStreamServer.parseRange("bytes=10-", 100))
        assertEquals(10L to 20L, FastStreamServer.parseRange("bytes=10-20", 100))
        assertEquals(10L to 99L, FastStreamServer.parseRange("bytes=10-500", 100))
        assertEquals(90L to 99L, FastStreamServer.parseRange("bytes=-10", 100))
        assertNull(FastStreamServer.parseRange("bytes=100-", 100))
    }

    @Test
    fun audioTracksKeepTheirLanguageAndTitle() {
        assertEquals("en\tEnglish\thttps://a", YouTubeFastStream.playbackAudio("en\tEnglish\thttps://a"))
        assertNull(YouTubeFastStream.playbackAudio(null))
    }
}
