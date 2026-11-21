package com.nuvio.app.features.usenet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UsenetStatsTest {
    private fun diagnostics(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun readsTheEngineProviderCountersByServerIndex() {
        val counters = providerCounters(
            diagnostics(
                """{"providers":[{"server":0,"articles":5,"bytes":3400000,"missing":2,"errors":1,"ttfbMs":217.4,"connects":4,"connectMs":800},
                   {"server":1,"articles":0,"bytes":0,"missing":0,"errors":0,"connects":0,"connectMs":0}]}""",
            ),
        )
        assertEquals(2, counters.size)
        assertEquals(5, counters.getValue(0).articles)
        assertEquals(217.4, counters.getValue(0).firstByteMs)
        assertNull(counters.getValue(1).firstByteMs)
        assertEquals(emptyMap(), providerCounters(null))
    }

    @Test
    fun addsOnlyWhatGrewSinceTheSessionsLastAnswer() {
        val ids = listOf("easynews", "backup")
        val first = mapOf(0 to ProviderCounters(10, 1_000, 1, 0, 200.0, 8, 1_600.0))
        val second = mapOf(0 to ProviderCounters(30, 5_000, 4, 1, 100.0, 10, 2_000.0))
        val book = UsenetStatsBook()
            .addSession(ids, previous = null, current = first, now = 1)
            .addSession(ids, previous = first, current = second, now = 2)
        val stats = book.providers.getValue("easynews")
        assertEquals(30, stats.articles)
        assertEquals(5_000, stats.bytes)
        assertEquals(4, stats.missing)
        assertEquals(1, stats.errors)
        assertEquals(1, stats.sessions)
        // Lifetime connection counts are only differenced from the session's second answer.
        assertEquals(2, stats.connects)
        assertEquals(200, stats.averageConnectMs)
        assertEquals(150, stats.averageFirstByteMs)
        assertEquals(2, stats.lastUsed)
        assertEquals(4 * 100.0 / 35, stats.missingPercent)
    }

    @Test
    fun ignoresServersItCannotName() {
        val book = UsenetStatsBook().addSession(
            listOf("only"), previous = null,
            current = mapOf(3 to ProviderCounters(1, 1, 0, 0, null, 0, 0.0)), now = 1,
        )
        assertEquals(emptyMap(), book.providers)
    }

    @Test
    fun creditsAnNzbToTheIndexerOnItsSite() {
        val indexers = listOf(
            UsenetIndexer(id = "alt", name = "AltHub", apiUrl = "https://api.althub.co.za"),
            UsenetIndexer(id = "ninja", name = "Ninja", apiUrl = "https://ninjacentral.co.za"),
        )
        assertEquals("alt", indexerByHost("https://althub.co.za/getnzb/abc.nzb", indexers))
        assertEquals("ninja", indexerByHost("https://www.ninjacentral.co.za/getnzb?id=1", indexers))
        assertNull(indexerByHost("https://elsewhere.example/x.nzb", indexers))
        assertNull(indexerByHost("not a url", indexers))
    }

    @Test
    fun namesTheSlowestIndexerOnlyWhenItClearlyHoldsSearchesUp() {
        val a = UsenetIndexer(id = "a", name = "A")
        val b = UsenetIndexer(id = "b", name = "B")
        fun book(aMs: Long, bMs: Long, searches: Long = 4) = UsenetStatsBook(
            indexers = mapOf(
                "a" to IndexerStats(searches = searches, searchMs = aMs * searches),
                "b" to IndexerStats(searches = searches, searchMs = bMs * searches),
            ),
        )
        assertEquals(b to 6_000L, slowestIndexer(book(1_200, 6_000), listOf(a, b)))
        assertNull(slowestIndexer(book(4_000, 5_000), listOf(a, b)), "not half again slower")
        assertNull(slowestIndexer(book(1_200, 6_000, searches = 2), listOf(a, b)), "too few searches")
        assertNull(slowestIndexer(book(1_200, 6_000), listOf(a, b.copy(enabled = false))), "nothing to compare")
    }

    @Test
    fun namesTheIndexerWithMostlyDeadReleases() {
        val a = UsenetIndexer(id = "a", name = "A")
        val b = UsenetIndexer(id = "b", name = "B")
        val book = UsenetStatsBook(
            indexers = mapOf("a" to IndexerStats(opens = 10, incomplete = 1), "b" to IndexerStats(opens = 4, incomplete = 3)),
        )
        assertEquals(b to 75.0, mostIncompleteIndexer(book, listOf(a, b)))
        assertNull(mostIncompleteIndexer(book, listOf(a)))
    }

    @Test
    fun formatsUnitsTheSameInEveryLanguage() {
        assertEquals("840 ms", UsenetInfoFormat.duration(840))
        assertEquals("2.1 s", UsenetInfoFormat.duration(2_140))
        assertEquals("25 s", UsenetInfoFormat.duration(25_000))
        assertEquals("—", UsenetInfoFormat.duration(null))
        assertEquals("3.1%", UsenetInfoFormat.percent(3.14))
        assertEquals("<0.1%", UsenetInfoFormat.percent(0.01))
        assertEquals("29%", UsenetInfoFormat.percent(28.6))
        assertEquals("1.5 GB", UsenetInfoFormat.bytes(1_610_612_736))
        assertEquals("12 B", UsenetInfoFormat.bytes(12))
        assertEquals("9,812", UsenetInfoFormat.count(9_812))
    }

    @Test
    fun anIncompleteFailureIsMissingArticles() {
        assertEquals(true, usenetIncompleteFailure("missing-article"))
        assertEquals(true, usenetIncompleteFailure("hole-limit"))
        assertEquals(false, usenetIncompleteFailure("archive-encrypted"))
        assertEquals(false, usenetIncompleteFailure(null))
    }
}
