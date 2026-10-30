package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderCandidatesTest {
    private val shoegazeAnswer = """
    {"created":"2026-10-26T10:00:00.000Z","count":4,"offset":0,"artists":[
      {"id":"a1","name":"My Bloody Valentine","score":100,"type":"Group","country":"IE",
       "tags":[{"count":14,"name":"shoegaze"},{"count":6,"name":"noise pop"},{"count":3,"name":"alternative rock"},{"count":2,"name":"irish"}]},
      {"id":"a2","name":"Slowdive","score":98,
       "tags":[{"count":9,"name":"shoegaze"},{"count":7,"name":"Dream Pop"},{"count":0,"name":"seen live"}]},
      {"id":"a3","name":"Untagged Newcomer","score":80},
      {"id":"a4","score":70,"tags":[{"count":1,"name":"shoegaze"}]},
      {"name":"No Id","score":60,"tags":[{"count":1,"name":"shoegaze"}]}
    ]}"""

    @Test
    fun tagSearchParsesArtistsWithTheirVotes() {
        val found = parseMbTagSearch(shoegazeAnswer)
        assertEquals(listOf("a1", "a2", "a3"), found.map { it.id })
        val mbv = found.first { it.id == "a1" }
        assertEquals("My Bloody Valentine", mbv.name)
        assertEquals(mapOf("shoegaze" to 14, "noise pop" to 6, "alternative rock" to 3, "irish" to 2), mbv.votes)
        assertTrue(mbv.playable)
        assertNull(mbv.fans)
    }

    @Test
    fun tagSearchDropsZeroVoteTagsAndKeepsUntaggedArtists() {
        val found = parseMbTagSearch(shoegazeAnswer)
        assertEquals(mapOf("shoegaze" to 9, "Dream Pop" to 7), found.first { it.id == "a2" }.votes)
        assertTrue(found.first { it.id == "a3" }.votes.isEmpty())
    }

    @Test
    fun tagSearchMergesRepeatedTagNames() {
        val body = """{"artists":[{"id":"x","name":"X","tags":[{"count":2,"name":"shoegaze"},{"count":3,"name":"shoegaze"}]}]}"""
        assertEquals(mapOf("shoegaze" to 5), parseMbTagSearch(body).single().votes)
    }

    @Test
    fun tagSearchOnGarbageIsEmptyNotAnError() {
        assertTrue(parseMbTagSearch("").isEmpty())
        assertTrue(parseMbTagSearch("not json").isEmpty())
        assertTrue(parseMbTagSearch("""{"error":"rate limited"}""").isEmpty())
        assertTrue(parseMbTagSearch("""{"artists":[]}""").isEmpty())
    }

    @Test
    fun parsedCandidatesFeedTheLadderScoring() {
        val found = parseMbTagSearch(shoegazeAnswer)
        val key = assertNotNullKey("shoegaze")
        val mbv = found.first { it.id == "a1" }
        // 14 of 14 + 6 + 3 genre votes ("irish" is junk and dropped): 14 / 23.
        assertEquals(14.0 / 23.0, purity(mbv.votes, key), 1e-9)
    }

    private fun assertNotNullKey(raw: String): String = targetGenreKey(raw) ?: error("not a genre: $raw")

    // region cache

    private fun candidate(id: String) = LadderCandidate(id, "Artist $id", mapOf("shoegaze" to 3))
    private fun many(n: Int) = (1..n).map { candidate("c$it") }

    @Test
    fun cacheAsksOncePerGenreEvenWhenSpelledDifferently() = runBlocking {
        var calls = 0
        val seen = ArrayList<String>()
        val cache = LadderCandidateCache(now = { 0L }) { genre -> calls++; seen += genre; many(10) }
        assertEquals(10, cache.candidates("Post Punk")?.size)
        assertEquals(10, cache.candidates("post-punk")?.size)
        assertEquals(10, cache.candidates("postpunk")?.size)
        assertEquals(1, calls)
        assertEquals(1, seen.size)
    }

    @Test
    fun cacheRefetchesAfterTheDay() = runBlocking {
        var clock = 0L
        var calls = 0
        val cache = LadderCandidateCache(now = { clock }) { calls++; many(10) }
        cache.candidates("shoegaze")
        clock = CANDIDATE_TTL_MS - 1
        cache.candidates("shoegaze")
        assertEquals(1, calls)
        clock = CANDIDATE_TTL_MS + 1
        cache.candidates("shoegaze")
        assertEquals(2, calls)
    }

    @Test
    fun aFailedFetchIsNotCached() = runBlocking {
        var calls = 0
        val cache = LadderCandidateCache(now = { 0L }) { calls++; if (calls == 1) null else many(10) }
        assertNull(cache.candidates("shoegaze"))
        assertEquals(10, cache.candidates("shoegaze")?.size)
        assertEquals(2, calls)
    }

    @Test
    fun aThinAnswerIsKeptOnlyBriefly() = runBlocking {
        var clock = 0L
        var calls = 0
        val cache = LadderCandidateCache(now = { clock }) { calls++; many(2) }
        cache.candidates("shoegaze")
        clock = THIN_CANDIDATE_TTL_MS - 1
        cache.candidates("shoegaze")
        assertEquals(1, calls)
        clock = THIN_CANDIDATE_TTL_MS + 1
        cache.candidates("shoegaze")
        assertEquals(2, calls)
    }

    @Test
    fun aNonGenreNeverReachesTheNetwork() = runBlocking {
        var calls = 0
        val cache = LadderCandidateCache(now = { 0L }) { calls++; many(10) }
        assertEquals(emptyList(), cache.candidates("seen live"))
        assertEquals(emptyList(), cache.candidates("   "))
        assertEquals(0, calls)
    }

    @Test
    fun theOldestGenreIsDroppedPastTheLimit() = runBlocking {
        val asked = ArrayList<String>()
        val cache = LadderCandidateCache(now = { 0L }) { genre -> asked += genre; many(10) }
        val genres = (1..MAX_CACHED_GENRES + 1).map { "genre number $it" }
        genres.forEach { cache.candidates(it) }
        val before = asked.size
        cache.candidates(genres.last())
        assertEquals(before, asked.size)
        cache.candidates(genres.first())
        assertEquals(before + 1, asked.size)
    }

    // endregion
}
