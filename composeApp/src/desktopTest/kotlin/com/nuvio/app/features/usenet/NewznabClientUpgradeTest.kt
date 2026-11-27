package com.nuvio.app.features.usenet

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NewznabClientUpgradeTest {
    private class FakeIndexer(private val handler: (Map<String, String>) -> Pair<Int, String>) : AutoCloseable {
        val queries: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api") { exchange ->
                val query = exchange.requestURI.rawQuery.orEmpty()
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
                queries += query
                val (code, body) = handler(query)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        fun indexer(fetchResultsLimit: Int? = null) = UsenetIndexer(
            id = "idx",
            name = "Test",
            apiUrl = "http://127.0.0.1:${server.address.port}",
            apiPath = "/api",
            apiKey = "secret",
            fetchResultsLimit = fetchResultsLimit,
        )

        fun page(title: String, id: Int) =
            """<rss xmlns:n="urn:newznab"><channel><n:response total="1"/><item><title>$title</title><link>http://127.0.0.1:${server.address.port}/get?id=$id</link></item></channel></rss>"""

        override fun close() = server.stop(0)
    }

    @Test fun `only refusals of the search itself count as rejections`() {
        assertTrue(isSearchRejection(NewznabUnsupportedSearchException()))
        listOf(400, 404, 405, 422).forEach { assertTrue("HTTP $it", isSearchRejection(IndexerHttpException(it))) }
        listOf(401, 403, 408, 429, 500, 502, 503).forEach { assertFalse("HTTP $it", isSearchRejection(IndexerHttpException(it))) }
        assertFalse(isSearchRejection(IOException("timeout")))
        assertFalse(isSearchRejection(IndexerCooldownException(0)))
        assertFalse(isSearchRejection(NewznabAuthException()))
    }

    @Test fun `a Newznab season refusal is remembered across episodes`() = runBlocking {
        var fake: FakeIndexer? = null
        fake = FakeIndexer { q ->
            when (val ep = q["ep"]) {
                null -> 200 to """<error code="201" description="Incorrect parameter"/>"""
                else -> 200 to fake!!.page("Show.S04E0$ep.1080p", ep.toInt())
            }
        }
        fake.use { server ->
            val client = NewznabClient()
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 4, episode = n)
            assertEquals(listOf("Show.S04E01.1080p"), client.search(server.indexer(), episode(1), NewznabCapabilities()).map { it.title })
            assertEquals(listOf("Show.S04E02.1080p"), client.search(server.indexer(), episode(2), NewznabCapabilities()).map { it.title })
            assertEquals(listOf(null, "1", "2").sortedBy { it ?: "" }, server.queries.map { it["ep"] }.sortedBy { it ?: "" })
            assertEquals(1, server.queries.count { it["ep"] == null })
        }
    }

    @Test fun `a season search that fails with HTTP 503 is retried on the next episode`() = runBlocking {
        val failSeason = AtomicBoolean(true)
        var fake: FakeIndexer? = null
        fake = FakeIndexer { q ->
            when (val ep = q["ep"]) {
                null -> if (failSeason.get()) 503 to "unavailable" else 200 to fake!!.page("Show.S03.1080p", 9)
                else -> 200 to fake!!.page("Show.S03E0$ep.1080p", ep.toInt())
            }
        }
        fake.use { server ->
            val client = NewznabClient()
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 3, episode = n)
            assertEquals(listOf("Show.S03E01.1080p"), client.search(server.indexer(), episode(1), NewznabCapabilities()).map { it.title })
            failSeason.set(false)
            server.queries.clear()
            assertEquals(
                listOf("Show.S03E02.1080p", "Show.S03.1080p"),
                client.search(server.indexer(), episode(2), NewznabCapabilities()).map { it.title },
            )
            assertEquals(1, server.queries.count { it["ep"] == null })
        }
    }

    @Test fun `per-indexer fetch limit override supports unlimited paging and exact last-page limit`() = runBlocking {
        val fake = FakeIndexer { q ->
            val offset = q["offset"]!!.toInt()
            val limit = q["limit"]!!.toInt()
            val items = (offset until minOf(offset + limit, 250)).joinToString("") { i ->
                """<item><title>Movie.2020.1080p-G$i</title><link>http://127.0.0.1/get?id=$i</link></item>"""
            }
            200 to """<rss xmlns:n="urn:newznab"><channel><n:response total="250"/>$items</channel></rss>"""
        }
        fake.use { server ->
            val unlimitedIndexer = server.indexer(fetchResultsLimit = 0)
            val config = UsenetSourceConfiguration(indexerResultsLimit = 100, indexers = listOf(unlimitedIndexer))
            assertEquals(0, config.indexerResults(unlimitedIndexer))
            val allFound = NewznabClient().search(
                unlimitedIndexer,
                UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(limit = 100),
                results = config.indexerResults(unlimitedIndexer),
            )
            assertEquals(250, allFound.size)
            assertEquals(listOf("0", "100", "200"), server.queries.map { it["offset"] })

            server.queries.clear()
            val cappedIndexer = server.indexer(fetchResultsLimit = 175)
            assertEquals(175, config.indexerResults(cappedIndexer))
            val cappedFound = NewznabClient().search(
                cappedIndexer,
                UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(limit = 75),
                results = config.indexerResults(cappedIndexer),
            )
            assertEquals(175, cappedFound.size)
            assertEquals(listOf("0", "75", "150"), server.queries.map { it["offset"] })
            assertEquals(listOf("75", "75", "25"), server.queries.map { it["limit"] })
        }
    }

    @Test fun `unlimited fetch stops after thirty pages`() = runBlocking {
        val fake = FakeIndexer { q ->
            val offset = q["offset"]!!.toInt()
            val limit = q["limit"]!!.toInt()
            val items = (offset until offset + limit).joinToString("") { i ->
                """<item><title>Movie.2020.1080p-G$i</title><link>http://127.0.0.1/get?id=$i</link></item>"""
            }
            200 to """<rss xmlns:n="urn:newznab"><channel><n:response total="10000"/>$items</channel></rss>"""
        }
        fake.use { server ->
            val indexer = server.indexer(fetchResultsLimit = 0)
            val found = NewznabClient().search(
                indexer,
                UsenetSearchRequest(imdbId = "tt1"),
                NewznabCapabilities(limit = 100),
                results = 0,
            )
            assertEquals(3000, found.size)
            assertEquals(30, server.queries.size)
        }
    }
}
