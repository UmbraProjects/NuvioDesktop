package com.nuvio.app.features.youtube

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Automatic FastStream: when the player turns it on, and the in-memory window it uses then. */
class YouTubeFastStreamAutoTest {
    private val gv = "https://rr1---sn-x.googlevideo.com/videoplayback"

    @Test
    fun fastSpeedsAndFourKTurnItOnAndNothingElseDoes() {
        // 1080p60: about 6 Mbit/s (45 MB over 60 s).
        val hd = "$gv?itag=299&clen=45000000&dur=60.000"
        // 4K VP9: about 20 Mbit/s (150 MB over 60 s).
        val uhd = "$gv?itag=313&clen=150000000&dur=60.000"
        assertFalse(YouTubeFastStream.autoWanted(hd, 1f))
        assertFalse(YouTubeFastStream.autoWanted(hd, 1.2f))
        assertTrue(YouTubeFastStream.autoWanted(hd, 1.25f))
        assertTrue(YouTubeFastStream.autoWanted(hd, 2f))
        assertTrue(YouTubeFastStream.autoWanted(uhd, 1f))
        // Not googlevideo (a manifest, another host): never.
        assertFalse(YouTubeFastStream.autoWanted("https://example.com/videoplayback?clen=150000000&dur=60", 2f))
    }

    @Test
    fun theBitrateComesFromTheStreamsOwnSizeAndLength() {
        assertEquals(6_000_000L, YouTubeFastStream.estimatedBitrate("$gv?clen=45000000&dur=60.000"))
        assertNull(YouTubeFastStream.estimatedBitrate("$gv?clen=45000000"))
        assertNull(YouTubeFastStream.estimatedBitrate("$gv?dur=60"))
    }

    // --- The window ---

    private val file = ByteArray(20_000) { (it * 13 % 251).toByte() }
    private val ranges = Collections.synchronizedList(mutableListOf<String>())
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        createContext("/videoplayback") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")!!
            ranges += range
            val start = range.substringAfter('=').substringBefore('-').toInt()
            val end = range.substringAfter('-').toInt().coerceAtMost(file.size - 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${file.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(file, start, end - start + 1) }
        }
        start()
    }
    private val dir = Files.createTempDirectory("faststream-auto-test").toFile()
    private val opened = mutableListOf<FastStreamDownload>()

    private fun windowed(window: Int) = FastStreamDownload(
        upstreamUrl = "http://127.0.0.1:${upstream.address.port}/videoplayback?itag=1&mime=video%2Fwebm",
        headers = emptyMap(),
        file = java.io.File(dir, "w${opened.size}.part"),
        connections = 2,
        chunkSize = 1_000,
        backoff = { 1L },
        windowChunks = window,
    ).also { opened += it; it.start() }

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        upstream.stop(0)
        dir.deleteRecursively()
    }

    private fun fetch(url: String, range: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Range", range) }

    private fun fetchedChunks(): Set<Int> =
        ranges.filter { it != "bytes=0-0" }.map { it.substringAfter('=').substringBefore('-').toInt() / 1_000 }.toSet()

    @Test
    fun theWindowFetchesOnlyJustAheadAndNeverToDisk() {
        val download = windowed(window = 3)
        val url = FastStreamServer.urlFor(download)
        assertContentEquals(file.copyOfRange(0, 1_500), fetch(url, "bytes=0-1499").inputStream.readBytes())
        Thread.sleep(300)
        // Reading chunk 1 at most: the window reaches chunk 3, nothing beyond, so 20 chunks were never all fetched.
        assertTrue(fetchedChunks().all { it <= 4 }, "fetched ${fetchedChunks()}")
        assertFalse(java.io.File(dir, "w0.part").exists(), "the window mode must not write a file")
    }

    @Test
    fun theButtonTurnsTheWindowIntoTheWholeVideoInPlace() {
        val download = windowed(window = 3)
        val url = FastStreamServer.urlFor(download)
        assertContentEquals(file.copyOfRange(0, 1_500), fetch(url, "bytes=0-1499").inputStream.readBytes())
        assertTrue(download.windowed)
        download.downloadWhole()
        assertFalse(download.windowed)
        val deadline = System.currentTimeMillis() + 5_000
        while (fetchedChunks().size < 20 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals((0 until 20).toSet(), fetchedChunks())
        // The same address goes on serving, now from the file, start to end.
        assertContentEquals(file, fetch(url, "bytes=0-").inputStream.readBytes())
        assertTrue(java.io.File(dir, "w0.part").exists())
        download.close()
        assertFalse(java.io.File(dir, "w0.part").exists())
    }

    @Test
    fun aSeekMovesTheWindowAndAnySpanReadsBack() {
        val download = windowed(window = 3)
        val url = FastStreamServer.urlFor(download)
        assertContentEquals(file.copyOfRange(0, 1_000), fetch(url, "bytes=0-999").inputStream.readBytes())
        // A seek far ahead, across chunk edges.
        assertContentEquals(file.copyOfRange(15_250, 17_750), fetch(url, "bytes=15250-17749").inputStream.readBytes())
        // And back to the start: the chunks dropped on the way are fetched again.
        assertContentEquals(file.copyOfRange(100, 2_100), fetch(url, "bytes=100-2099").inputStream.readBytes())
        assertTrue(fetchedChunks().none { it in 6..13 }, "the gap the seek jumped was fetched: ${fetchedChunks()}")
    }
}
