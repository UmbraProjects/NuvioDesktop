package com.nuvio.app.core.ui

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The whole path a poster provider's `Cache-Control` takes through Nuvio, driven end to end: a real
 * Coil `ImageLoader` built from the production desktop component chain, a real HTTP server that
 * answers the way PostersPlus (and AIOMetadata's proxy in front of it) answers, and a clock the
 * test moves instead of sleeping.
 *
 * What is being pinned is the contract in the user's words: the provider says how long to keep a
 * poster, Nuvio keeps it that long without asking again, and after that it asks again — as a
 * cheap `If-None-Match` that a 304 answers, or as a download when the poster really changed —
 * whether the copy was in memory or only on disk.
 */
class ImageCacheHeadersIntegrationTest {

    private lateinit var server: PosterServer
    private lateinit var cacheDir: Path
    private lateinit var loader: ImageLoader
    private var clockOffsetMillis = 0L

    @BeforeTest
    fun start() {
        ImageFreshnessRegistry.clear()
        ImageFreshnessRegistry.nowMillis = { System.currentTimeMillis() + clockOffsetMillis }
        server = PosterServer()
        cacheDir = Files.createTempDirectory("nuvio-image-cache-it")
        loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .memoryCache {
                DesktopArtworkMemoryCache(MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build())
            }
            .diskCache { DiskCache.Builder().directory(cacheDir.toOkioPath()).build() }
            .components { addDesktopArtworkComponents() }
            .build()
    }

    @AfterTest
    fun stop() {
        loader.shutdown()
        server.stop()
        ImageFreshnessRegistry.nowMillis = System::currentTimeMillis
        ImageFreshnessRegistry.clear()
        runCatching {
            Files.walk(cacheDir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `a poster is kept for its max-age, revalidated when that lapses, and replaced when it changed`() {
        server.serve(RED, etag = "\"red\"", cacheControl = "public, max-age=86400")

        // First sight: downloaded.
        assertEquals(DataSource.NETWORK, load().dataSource)
        assertEquals(RED_ARGB, load().pixel())
        assertEquals(1, server.requests.size)

        // Within the advertised day, at any size: never a request, not even a conditional one.
        assertEquals(DataSource.MEMORY_CACHE, load().dataSource)
        assertEquals(DataSource.DISK, load(size = 4).dataSource, "a new size decodes the disk copy")
        clockOffsetMillis = 23 * 3_600_000L
        assertEquals(DataSource.MEMORY_CACHE, load().dataSource)
        assertEquals(1, server.requests.size)

        // The day is up: the memory copy is no longer trusted, the disk copy is revalidated, and
        // the server's 304 costs no body. Freshness restarts from the 304's own headers.
        clockOffsetMillis = 25 * 3_600_000L
        val revalidated = load()
        assertEquals(2, server.requests.size)
        assertEquals("\"red\"", server.requests.last().ifNoneMatch)
        assertEquals(304, server.responses.last())
        assertEquals(RED_ARGB, revalidated.pixel())
        assertEquals(DataSource.MEMORY_CACHE, load().dataSource)
        assertEquals(2, server.requests.size)

        // Another day on, the poster really changed (a trending sash came off): the conditional
        // request is answered with new bytes, and every size stops serving the old ones.
        server.serve(BLUE, etag = "\"blue\"", cacheControl = "public, max-age=86400")
        clockOffsetMillis = 50 * 3_600_000L
        val replaced = load()
        assertEquals(3, server.requests.size)
        assertEquals("\"red\"", server.requests.last().ifNoneMatch)
        assertEquals(200, server.responses.last())
        assertEquals(BLUE_ARGB, replaced.pixel())
        val otherSize = load(size = 4)
        assertNotEquals(DataSource.MEMORY_CACHE, otherSize.dataSource, "decoded from the superseded bytes")
        assertEquals(BLUE_ARGB, otherSize.pixel())
        assertEquals(3, server.requests.size, "the other size came from the refreshed disk copy")
    }

    @Test
    fun `a no-store poster is asked for again on every disk lookup, but not on every scroll`() {
        // PostersPlus answers like this while a poster's quality badge is still being looked up.
        server.serve(RED, etag = null, cacheControl = "no-store, no-cache, must-revalidate")

        assertEquals(DataSource.NETWORK, load().dataSource)
        assertEquals(DataSource.MEMORY_CACHE, load().dataSource, "held a minute in memory")
        assertEquals(1, server.requests.size)

        clockOffsetMillis = 61_000L
        server.serve(BLUE, etag = null, cacheControl = "no-store, no-cache, must-revalidate")
        val refreshed = load()
        assertEquals(2, server.requests.size)
        assertEquals(200, server.responses.last())
        assertEquals(BLUE_ARGB, refreshed.pixel(), "the finished poster replaced the provisional one")
    }

    @Test
    fun `a refresh that fails transiently keeps showing the stale poster`() {
        server.serve(RED, etag = "\"red\"", cacheControl = "public, max-age=60")
        assertEquals(DataSource.NETWORK, load().dataSource)

        // A minute later the poster service is restarting.
        clockOffsetMillis = 61_000L
        server.fail(503)
        val stale = load()
        assertEquals(RED_ARGB, stale.pixel())
        assertEquals(2, server.requests.size, "the refresh was attempted")

        // And is not attempted again on every card for a while.
        assertEquals(DataSource.MEMORY_CACHE, load().dataSource)
        assertEquals(2, server.requests.size)

        // Once the grace period is over the service is back and the refresh goes through.
        clockOffsetMillis += 6 * 60_000L
        server.serve(BLUE, etag = "\"blue\"", cacheControl = "public, max-age=60")
        assertEquals(BLUE_ARGB, load().pixel())
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `a poster that is gone is not masked by its stale copy`() {
        server.serve(RED, etag = "\"red\"", cacheControl = "public, max-age=60")
        load()

        clockOffsetMillis = 61_000L
        server.fail(404)
        val result = runBlocking { loader.execute(request(size = 2)) }
        val error = assertIs<ErrorResult>(result, "a 404 is the answer, not an outage")
        assertEquals(404, assertIs<HttpException>(error.throwable).response.code)
    }

    private fun request(size: Int): ImageRequest = ImageRequest.Builder(PlatformContext.INSTANCE)
        .data(server.url)
        .size(size)
        .precision(Precision.INEXACT)
        .desktopArtworkCacheSize()
        .build()

    private fun load(size: Int = 2): SuccessResult = runBlocking {
        assertIs<SuccessResult>(loader.execute(request(size)))
    }

    private fun SuccessResult.pixel(): Int = assertIs<BitmapImage>(image).bitmap.getColor(0, 0)

    /**
     * One poster URL, answered the way a poster service answers: with whatever body, validator
     * and `Cache-Control` the test last set, a 304 for a matching `If-None-Match`, or a failure.
     */
    private class PosterServer {
        class Request(val ifNoneMatch: String?)

        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${http.address.port}/poster.png"
        val requests = CopyOnWriteArrayList<Request>()
        val responses = CopyOnWriteArrayList<Int>()

        @Volatile private var body = ByteArray(0)
        @Volatile private var etag: String? = null
        @Volatile private var cacheControl: String? = null
        @Volatile private var failureCode: Int? = null

        init {
            http.createContext("/poster.png") { exchange ->
                val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                requests += Request(ifNoneMatch)
                val failure = failureCode
                val code = when {
                    failure != null -> failure
                    ifNoneMatch != null && ifNoneMatch == etag -> 304
                    else -> 200
                }
                responses += code
                if (failure == null) {
                    cacheControl?.let { exchange.responseHeaders.set("Cache-Control", it) }
                    etag?.let { exchange.responseHeaders.set("ETag", it) }
                }
                if (code == 200) {
                    exchange.responseHeaders.set("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    exchange.sendResponseHeaders(code, -1)
                    exchange.close()
                }
            }
            http.start()
        }

        fun serve(body: ByteArray, etag: String?, cacheControl: String?) {
            this.body = body
            this.etag = etag
            this.cacheControl = cacheControl
            this.failureCode = null
        }

        fun fail(code: Int) {
            failureCode = code
        }

        fun stop() = http.stop(0)
    }

    private companion object {
        val RED = png(Color.RED)
        val BLUE = png(Color.BLUE)
        const val RED_ARGB = 0xFFFF0000.toInt()
        const val BLUE_ARGB = 0xFF0000FF.toInt()

        fun png(color: Color): ByteArray {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 2) for (y in 0 until 2) image.setRGB(x, y, color.rgb)
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }
}
