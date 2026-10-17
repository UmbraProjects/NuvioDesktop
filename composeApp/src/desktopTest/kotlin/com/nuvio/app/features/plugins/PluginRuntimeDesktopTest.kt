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
import kotlin.test.assertTrue

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
     * Plugins log objects, arrays, errors and functions freely. The polyfill turns them into text
     * before they reach the host console, because the native build cannot convert them to Java
     * collections; logging must never be what fails a scrape.
     */
    @Test
    fun `logging objects does not fail the scrape`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function() {
                    var cyclic = { name: "loop" };
                    cyclic.self = cyclic;
                    console.log("config", { a: 1, nested: [1, 2] }, [3, 4], null, undefined, 5, true);
                    console.warn(cyclic, function named() {});
                    console.error(new Error("boom"));
                    return [{ title: "Logged", url: "https://example.test/logged.mp4" }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-console-test",
        )

        assertEquals("Logged", results.single().title)
    }

    /**
     * Unbounded recursion must end as a catchable JS "stack overflow", never as the thread running
     * off its real stack (which aborted the native build). The plugin catches it and still reports.
     */
    @Test
    fun `runaway recursion is a JS error, not a crash`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                function dive(depth) { return dive(depth + 1) + 1; }
                module.exports.getStreams = async function() {
                    var message = "none";
                    try { dive(0); } catch (e) { message = String(e); }
                    return [{ title: message, url: "https://example.test/deep.mp4" }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-recursion-test",
        )

        assertTrue(results.single().title.contains("stack overflow", ignoreCase = true), results.single().title)
    }

    /**
     * A playback-startup hold defers new scraper starts without cancelling them: the scraper runs
     * to completion the moment the hold is released. A stale hold (release never sent) must not
     * become a scraper that waits out its whole budget — that is what the cap is for, but it is
     * 30 s, so only the release path is exercised here.
     */
    @Test
    fun `playback startup hold defers scraper starts until released`() = runBlocking {
        PluginRuntime.setPlaybackStartupHold(true)
        try {
            val held = async {
                PluginRuntime.executePlugin(
                    code = """
                        module.exports.getStreams = async function() {
                            return [{ title: "after hold", url: "https://example.test/held.mp4" }];
                        };
                    """.trimIndent(),
                    tmdbId = "603",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    scraperId = "hold-test",
                )
            }
            kotlinx.coroutines.delay(400)
            assertEquals(false, held.isCompleted, "scraper started while the hold was active")
            PluginRuntime.setPlaybackStartupHold(false)
            assertEquals("after hold", held.await().single().title)
        } finally {
            PluginRuntime.setPlaybackStartupHold(false)
        }
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

    /**
     * Scrapers like VegaMovies wrap their fetches in setTimeout-based delays and timeouts; without
     * the globals they threw `'setTimeout' is not defined` and returned nothing. Also covers the
     * two ways a timer could hold the runtime slot: a cleared timer still waiting out its delay,
     * and a timer left pending when getStreams returns (the losing side of a Promise.race).
     */
    @Test
    fun `plugin timers fire in order, clear, and never outlive the run`() = runBlocking {
        val startedAt = System.nanoTime()
        val results = PluginRuntime.executePlugin(
            code = """
                function sleep(ms, value) {
                    return new Promise(function(resolve) { setTimeout(resolve, ms, value); });
                }
                module.exports.getStreams = async function() {
                    var events = [];
                    setTimeout(function() { events.push("late"); }, 60);
                    setTimeout(function(tag) { events.push(tag); }, 10, "early");
                    var cleared = setTimeout(function() { events.push("cleared"); }, 20000);
                    clearTimeout(cleared);
                    var ticks = 0;
                    var interval = setInterval(function() {
                        ticks++;
                        if (ticks === 3) clearInterval(interval);
                    }, 5);
                    var raced = await Promise.race([
                        sleep(30, "fast"),
                        new Promise(function(resolve) { setTimeout(resolve, 30000, "timeout"); }),
                    ]);
                    await sleep(100);
                    events.push(raced, "ticks=" + ticks);
                    return [{ title: events.join(","), url: "https://example.test/timers.mp4" }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "timer-test",
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("early,late,fast,ticks=3", results.single().title)
        assertTrue(elapsedMs < 10_000, "a cleared or abandoned timer held the run for $elapsedMs ms")
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
