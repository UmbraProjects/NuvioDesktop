package com.nuvio.app.features.usenet

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The search modes against an indexer that answers IDs and titles differently: it files the
 * episode under the title only, and a season pack only under "Show S07".
 */
class UsenetSearchModeTest {
    private val asked = CopyOnWriteArrayList<Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api") { exchange ->
            val params = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            asked += params
            val body = when {
                params["t"] == "caps" -> CAPS
                params["imdbid"] != null || params["tvdbid"] != null -> rss() // The ID search finds nothing here.
                params["q"] == "A Show S07E05" -> rss(item("A.Show.S07E05.1080p.WEB-DL-GRP", "/get?id=episode"))
                params["q"] == "A Show S07" -> rss(item("A.Show.S07.1080p.WEB-DL-GRP", "/get?id=pack"))
                else -> rss()
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val client = NewznabClient()
    private val request = UsenetSearchRequest(imdbId = "tt1", title = "A Show", series = true, season = 7, episode = 5)

    private fun indexer(mode: UsenetSearchMode) = UsenetIndexer(
        id = mode.name, name = mode.name, apiUrl = "http://127.0.0.1:${server.address.port}/api", apiKey = "k", searchMode = mode,
    )

    private fun search(mode: UsenetSearchMode): List<String> = runBlocking {
        val indexer = indexer(mode)
        client.search(indexer, request, client.capabilities(indexer)).map { it.nzbUrl.substringAfter("id=") }.sorted()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `automatic falls back to the title when the id search finds nothing, packs included`() {
        assertEquals(listOf("episode", "pack"), search(UsenetSearchMode.AUTO))
        assertTrue(asked.any { it["imdbid"] == "1" }, "the id search came first")
    }

    @Test
    fun `id only never asks by title`() {
        assertEquals(emptyList(), search(UsenetSearchMode.ID))
        assertTrue(asked.none { it["q"] != null })
    }

    @Test
    fun `title only never asks by id`() {
        assertEquals(listOf("episode", "pack"), search(UsenetSearchMode.TITLE))
        assertTrue(asked.none { it["imdbid"] != null || it["tvdbid"] != null })
    }

    @Test
    fun `both asks by id and by title and merges`() {
        assertEquals(listOf("episode", "pack"), search(UsenetSearchMode.BOTH))
        assertTrue(asked.any { it["imdbid"] != null } && asked.any { it["q"] == "A Show S07E05" })
    }

    private companion object {
        const val CAPS = """<caps><limits max="100"/><searching><search available="yes"/>
            <tv-search available="yes" supportedParams="q,imdbid,season,ep"/><movie-search available="yes" supportedParams="q,imdbid"/>
            </searching></caps>"""

        fun item(title: String, link: String) = """<item><title>$title</title><pubDate>Fri, 09 Oct 2026 12:00:00 +0000</pubDate>
            <enclosure type="application/x-nzb" url="$link" length="1000"/><newznab:attr name="size" value="2000000000"/></item>"""

        fun rss(vararg items: String) = """<rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
            <newznab:response offset="0" total="${items.size}"/>${items.joinToString("")}</channel></rss>"""
    }
}
