package com.nuvio.app.features.plugins

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SLOW_ENDPOINT_DELAY_MS = 300L

class PluginRuntimeDesktopTest {
    /**
     * Overlapping QuickJS runtimes used to be serialized to one at a time because quickjs-kt 1.0.5
     * could fault natively when several evaluations overlapped. A repository like All-in-One has
     * 60+ scrapers and every one of them fans out at once, so this is the shape that mattered.
     */
    @Test
    fun `overlapping scraper runtimes all complete`() = runBlocking {
        val scraperCount = 40
        val results = (0 until scraperCount).map { index ->
            async {
                PluginRuntime.executePlugin(
                    code = """
                        module.exports.getStreams = async function() {
                            var digest = CryptoJS.SHA256("scraper-$index").toString();
                            var ${'$'} = cheerio.load("<div class='q'>1080p</div>");
                            return [{
                                title: "scraper-$index",
                                url: "https://example.test/$index.mp4?d=" + digest,
                                quality: ${'$'}(".q").text()
                            }];
                        };
                    """.trimIndent(),
                    tmdbId = "603",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    scraperId = "concurrent-$index",
                )
            }
        }.awaitAll()

        assertEquals(
            (0 until scraperCount).map { "scraper-$it" },
            results.map { it.single().title },
        )
        assertEquals(List(scraperCount) { "1080p" }, results.map { it.single().quality })
    }

    @Test
    fun `desktop runtime executes scraper code`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function(tmdbId, mediaType) {
                    return [{
                        title: "Desktop stream " + tmdbId + " " + mediaType,
                        url: "https://example.test/movie.mp4",
                        quality: "1080p",
                        provider: "Desktop Test"
                    }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-runtime-test",
        )

        assertEquals(1, results.size)
        assertEquals("Desktop stream 603 movie", results.single().title)
        assertEquals("https://example.test/movie.mp4", results.single().url)
        assertEquals("1080p", results.single().quality)
        assertEquals("Desktop Test", results.single().provider)
    }

    /**
     * `fetch` is backed by an async binding, so a plugin awaiting several requests at once really
     * does issue them at once. While the binding was a blocking one, `Promise.all` was a lie: each
     * call held the JS engine until the response came back, so five 300 ms mirrors cost 1.5 s.
     */
    @Test
    fun `plugin fetches inside Promise all run in parallel`() = runBlocking {
        val peakConcurrentRequests = withSlowLocalServer { baseUrl ->
            val results = PluginRuntime.executePlugin(
                code = """
                    module.exports.getStreams = async function() {
                        var mirrors = [1, 2, 3, 4, 5].map(function(n) {
                            return fetch("$baseUrl/mirror/" + n).then(function(r) { return r.text(); });
                        });
                        var bodies = await Promise.all(mirrors);
                        return bodies.map(function(body) {
                            return { title: body, url: "https://example.test/" + body + ".mp4" };
                        });
                    };
                """.trimIndent(),
                tmdbId = "603",
                mediaType = "movie",
                season = null,
                episode = null,
                scraperId = "parallel-fetch-test",
            )

            assertEquals(
                listOf("mirror-1", "mirror-2", "mirror-3", "mirror-4", "mirror-5"),
                results.map { it.title },
            )
        }

        // Asserting on what the server saw rather than on elapsed time: the wall clock also
        // includes creating the runtime and evaluating the polyfill, which dwarfs the requests on
        // a cold JVM and would make this flaky.
        assertEquals(5, peakConcurrentRequests, "plugin fetches were not in flight at the same time")
    }

    /** Runs [block] against a local server that holds each request open, and reports the peak
     *  number of requests it saw in flight simultaneously. */
    private inline fun withSlowLocalServer(block: (baseUrl: String) -> Unit): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(8)
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()
        server.executor = executor
        server.createContext("/mirror") { exchange ->
            val concurrent = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { maxOf(it, concurrent) }
            Thread.sleep(SLOW_ENDPOINT_DELAY_MS)
            inFlight.decrementAndGet()
            val body = "mirror-${exchange.requestURI.path.substringAfterLast('/')}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
        return peakInFlight.get()
    }
}
