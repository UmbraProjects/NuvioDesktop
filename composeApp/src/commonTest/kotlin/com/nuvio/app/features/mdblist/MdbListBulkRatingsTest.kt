package com.nuvio.app.features.mdblist

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MdbListBulkRatingsTest {
    private val calls = mutableListOf<Pair<String, String>>()
    private val original = MdbListBulkRatings.transport

    private fun respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        MdbListBulkRatings.transport = { url, requestBody ->
            synchronized(calls) { calls += url to requestBody }
            RawHttpResponse(status, "", url, body, headers)
        }
    }

    @BeforeTest
    fun setUp() {
        MdbListBulkRatings.resetForTest()
        MdbListQuota.setForTest(null, null)
    }

    @AfterTest
    fun tearDown() {
        MdbListBulkRatings.transport = original
        MdbListBulkRatings.resetForTest()
        MdbListQuota.setForTest(null, null)
    }

    // Trimmed from a real bulk answer (2026-10-18): out of request order, unknown id left out.
    private val answer = """
        [{"title":"Pulp Fiction","type":"movie","status":"Released","released":"1994-09-10",
          "ids":{"imdb":"tt0110912","tmdb":680,"mal":null},
          "ratings":[{"source":"imdb","value":8.8,"votes":2400000,"url":1}]},
         {"title":"The Godfather","type":"movie","status":"Released","released":"1972-03-14",
          "ids":{"imdb":"tt0068646","tmdb":238,"mal":null},
          "ratings":[{"source":"imdb","value":9.2,"votes":2265173}]}]
    """.trimIndent()

    @Test
    fun `lookups close together share one request and match by id`() = runBlocking<Unit> {
        respond(200, answer, mapOf("x-ratelimit-limit" to "1000", "x-ratelimit-remaining" to "812"))
        val outcomes = coroutineScope {
            listOf("tt0068646", "tt0110912", "tt9999999").map { id ->
                async { MdbListBulkRatings.fetch("imdb", "movie", id, "KEY") }
            }.awaitAll()
        }
        assertEquals(1, calls.size)
        assertTrue(calls.single().first.startsWith("https://api.mdblist.com/imdb/movie?apikey=KEY"))
        assertEquals("""{"ids":["tt0068646","tt0110912","tt9999999"]}""", calls.single().second)
        assertEquals("tt0068646", assertIs<MdbListBulkRatings.Outcome.Found>(outcomes[0]).title.ids?.toIds()?.imdb)
        assertEquals("tt0110912", assertIs<MdbListBulkRatings.Outcome.Found>(outcomes[1]).title.ids?.toIds()?.imdb)
        assertEquals(MdbListBulkRatings.Outcome.Missing, outcomes[2])
        assertEquals(1000, MdbListQuota.dailyLimit)
        assertEquals(812, MdbListQuota.remaining)
    }

    @Test
    fun `tmdb ids match the numeric ids in the answer`() = runBlocking<Unit> {
        respond(200, answer)
        val outcome = MdbListBulkRatings.fetch("tmdb", "movie", "680", "KEY")
        assertIs<MdbListBulkRatings.Outcome.Found>(outcome)
    }

    @Test
    fun `different types and keys never share a request`() = runBlocking<Unit> {
        respond(200, "[]")
        coroutineScope {
            listOf(
                async { MdbListBulkRatings.fetch("imdb", "movie", "tt1", "A") },
                async { MdbListBulkRatings.fetch("imdb", "show", "tt2", "A") },
                async { MdbListBulkRatings.fetch("imdb", "movie", "tt3", "B") },
            ).awaitAll()
        }
        assertEquals(3, calls.size)
    }

    @Test
    fun `a full batch is sent at once`() = runBlocking<Unit> {
        respond(200, "[]")
        coroutineScope {
            (1..MdbListBulkRatings.MAX_IDS + 1).map { n ->
                async { MdbListBulkRatings.fetch("imdb", "movie", "tt$n", "KEY") }
            }.awaitAll()
        }
        assertEquals(2, calls.size)
        assertEquals(MdbListBulkRatings.MAX_IDS, calls.maxOf { it.second.count { c -> c == ',' } + 1 })
    }

    @Test
    fun `rate limit is reported without turning bulk off`() = runBlocking<Unit> {
        respond(429, "")
        assertEquals(MdbListBulkRatings.Outcome.RateLimited, MdbListBulkRatings.fetch("imdb", "movie", "tt1", "KEY"))
        assertTrue(MdbListBulkRatings.isAvailable())
    }

    @Test
    fun `a failed request turns bulk off and fails its waiters`() = runBlocking<Unit> {
        respond(500, "oops")
        assertEquals(MdbListBulkRatings.Outcome.Failed, MdbListBulkRatings.fetch("imdb", "movie", "tt1", "KEY"))
        assertFalse(MdbListBulkRatings.isAvailable())
        MdbListBulkRatings.resetForTest()
        respond(200, "not json")
        assertEquals(MdbListBulkRatings.Outcome.Failed, MdbListBulkRatings.fetch("imdb", "movie", "tt1", "KEY"))
        assertFalse(MdbListBulkRatings.isAvailable())
    }

    @Test
    fun `auto pace follows the key's daily limit`() {
        val auto = MdbListSettings()
        assertEquals(MdbListRefreshPreset.Auto, auto.refreshPreset)
        assertEquals(3.0, auto.refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
        MdbListQuota.setForTest(limit = 1000, left = 900)
        assertEquals(3.0, auto.refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
        MdbListQuota.setForTest(limit = 100_000, left = 99_000)
        assertEquals(1.5, auto.refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
        assertEquals(3.0, auto.copy(refreshPreset = MdbListRefreshPreset.Standard).refreshDays(MdbListRefreshTier.MovieEarlyStreaming))
    }

    @Test
    fun `cache keys parse back to their lookup`() {
        val (lookup, type) = MdbListMetadataService.parseCacheKey("v4:imdb:movie:tt0068646")!!
        assertEquals(MdbListLookup("imdb", "tt0068646"), lookup)
        assertEquals("movie", type)
        assertEquals(null, MdbListMetadataService.parseCacheKey("v3:imdb:movie:tt0068646"))
        assertEquals(null, MdbListMetadataService.parseCacheKey("v4:imdb:movie"))
    }

    @Test
    fun `entries from before adaptive refresh refresh their ratings in bulk`() {
        val day = 24L * 60L * 60L * 1000L
        val now = 100L * day
        val key = "v4:imdb:movie:tt0068646"
        val expires = now - day
        val fetchedAt = MdbListMetadataService.legacyFetchedAtMs(found = true, expiresAtMs = expires)
        assertEquals(expires - 7 * day, fetchedAt)
        // Failures and not-found answers keep no clock and stay on the single lookup.
        assertEquals(null, MdbListMetadataService.legacyFetchedAtMs(found = false, expiresAtMs = expires))

        val legacy = CachedRatings(
            ratings = listOf(MetaExternalRating(source = "imdb", value = 9.2)),
            keywords = listOf("mafia"),
            expiresAtMs = expires,
            fetchedAtMs = fetchedAt,
        )
        assertTrue(legacy.expiresAtMs(key, MdbListRatingsFreshness.DEFAULT_DAYS) <= now + 2 * day)
        assertFalse(legacy.extrasDue(key, now), "a found entry's cached keywords stand until the bulk answer brings facts")
        assertTrue(legacy.extrasDue(key, fetchedAt!! + 91 * day))

        // Once the bulk answer has supplied facts, the normal extras rules apply: a film in cinemas
        // with no digital date goes back to the single lookup.
        val inCinemas = legacy.copy(
            releaseFacts = MdbListReleaseFacts(isShow = false, status = "Released", releasedMs = now - 10 * day),
        )
        assertTrue(inCinemas.extrasDue(key, now))
    }
}
