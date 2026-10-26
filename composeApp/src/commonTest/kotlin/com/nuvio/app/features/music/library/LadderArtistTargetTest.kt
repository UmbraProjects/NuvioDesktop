package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderArtistTargetTest {
    private fun cand(id: String, name: String, votes: Map<String, Int>, fans: Long? = null) =
        LadderCandidate(id = id, name = name, votes = votes, fans = fans)

    private val mbv = cand("a1", "My Bloody Valentine", mapOf("shoegaze" to 14, "noise pop" to 6, "irish" to 2))

    private val pool = listOf(
        mbv,
        cand("a2", "Slowdive", mapOf("shoegaze" to 9, "dream pop" to 7)),
        cand("a3", "Ride", mapOf("shoegaze" to 6, "britpop" to 3, "alternative rock" to 4)),
        cand("a4", "Lush", mapOf("shoegaze" to 5, "dream pop" to 5)),
        cand("a5", "Swervedriver", mapOf("shoegaze" to 4, "alternative rock" to 6)),
        cand("a6", "Chapterhouse", mapOf("shoegaze" to 8, "dream pop" to 2)),
        cand("a7", "Not Shoegaze", mapOf("jazz" to 9)),
    )

    private val me = listOf(
        LibraryArtist(id = "lib1", name = "Cocteau Twins", genres = listOf("dream pop", "ambient"), mood = "dreamy"),
    )

    @Test
    fun hitMatchingTheTypedNameWinsOverTheBestScoringHit() {
        val hits = listOf(cand("x", "Slowdive Tribute", mapOf("shoegaze" to 1)), cand("y", "Slowdive", mapOf("shoegaze" to 9)))
        assertEquals("y", pickArtistHit("slow dive", hits)?.id)
        assertEquals("y", pickArtistHit("SLOWDIVE", hits)?.id)
    }

    @Test
    fun withNoExactMatchTheBestHitIsUsedAndNoHitsGivesNull() {
        val hits = listOf(cand("x", "Slowdive", mapOf("shoegaze" to 9)))
        assertEquals("x", pickArtistHit("Slowdiv", hits)?.id)
        assertNull(pickArtistHit("anything", emptyList()))
    }

    @Test
    fun targetIsTheMostVotedGenreAfterCleanup() {
        val t = artistTarget(cand("a", "A", mapOf("seen live" to 20, "Post Punk" to 3, "post-punk" to 4, "goth rock" to 5)))
        assertEquals("post-punk", genreKey(t!!.genre))
        assertEquals(2, t.genres.size)
    }

    @Test
    fun artistWithNoUsableGenreHasNoTarget() {
        assertNull(artistTarget(cand("a", "A", mapOf("seen live" to 20, "british" to 3))))
        assertNull(artistTarget(cand("a", "A", emptyMap())))
    }

    @Test
    fun theArtistNeverStandsOnTheirOwnLadder() {
        val target = artistTarget(mbv)!!
        val ladder = buildArtistLadder(target, me, pool)
        assertFalse(ladder.isEmpty)
        assertTrue(ladder.rungs.none { it.candidate.id == "a1" })
        assertTrue(ladder.rungs.none { it.candidate.name == "My Bloody Valentine" })
        assertTrue(ladder.rungs.none { it.candidate.id == "a7" })
    }

    @Test
    fun artistLeftOutByNameEvenWhenTheIdDiffers() {
        val target = artistTarget(mbv)!!
        val twin = cand("zz", "my bloody valentine", mapOf("shoegaze" to 10))
        val ladder = buildArtistLadder(target, me, pool + twin)
        assertTrue(ladder.rungs.none { it.candidate.id == "zz" })
    }

    @Test
    fun ladderPurityRisesAcrossRungs() {
        val ladder = buildArtistLadder(artistTarget(mbv)!!, me, pool)
        val purities = ladder.rungs.map { it.purity }
        assertEquals(purities.sorted(), purities)
    }

    @Test
    fun resolverAsksOncePerArtistEvenWhenSpelledDifferently() = runBlocking {
        var calls = 0
        val resolver = ArtistTargetResolver(now = { 0L }) { calls++; listOf(mbv) }
        val a = resolver.resolve("My Bloody Valentine")
        val b = resolver.resolve("my bloody  valentine")
        assertEquals(1, calls)
        assertIs<ArtistLookup.Found>(a)
        assertIs<ArtistLookup.Found>(b)
    }

    @Test
    fun resolverRefetchesAfterTheDay() = runBlocking {
        var calls = 0
        var clock = 0L
        val resolver = ArtistTargetResolver(now = { clock }) { calls++; listOf(mbv) }
        resolver.resolve("My Bloody Valentine")
        clock = ARTIST_TARGET_TTL_MS + 1
        resolver.resolve("My Bloody Valentine")
        assertEquals(2, calls)
    }

    @Test
    fun anUnreachableSourceIsNotCached() = runBlocking {
        var calls = 0
        val resolver = ArtistTargetResolver(now = { 0L }) { calls++; if (calls == 1) null else listOf(mbv) }
        val first = resolver.resolve("My Bloody Valentine")
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.Unreachable), first)
        assertIs<ArtistLookup.Found>(resolver.resolve("My Bloody Valentine"))
        assertEquals(2, calls)
    }

    @Test
    fun unknownArtistAndUntaggedArtistFailDifferently() = runBlocking {
        val none = ArtistTargetResolver(now = { 0L }) { emptyList() }
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoArtist), none.resolve("Nobody"))
        val bare = ArtistTargetResolver(now = { 0L }) { listOf(cand("b", "Nobody", emptyMap())) }
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoGenre), bare.resolve("Nobody"))
    }

    @Test
    fun blankQueryNeverReachesTheNetwork() = runBlocking {
        var calls = 0
        val resolver = ArtistTargetResolver(now = { 0L }) { calls++; emptyList() }
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoArtist), resolver.resolve("   "))
        assertEquals(0, calls)
    }

    @Test
    fun fullPathBuildsALadderFromTheArtistQuery() = runBlocking {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(mbv) }
        var askedGenre: String? = null
        val out = ladderInto(resolver, { g -> askedGenre = g; pool }, "My Bloody Valentine", me)
        val ready = assertIs<ArtistLadderOutcome.Ready>(out)
        assertEquals("shoegaze", genreKey(askedGenre!!))
        assertEquals("My Bloody Valentine", ready.target.artist.name)
        assertTrue(ready.ladder.rungs.isNotEmpty())
        assertTrue(ready.ladder.rungs.none { it.candidate.id == "a1" })
    }

    @Test
    fun fullPathReportsUnreachableCandidates() = runBlocking {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(mbv) }
        val out = ladderInto(resolver, { null }, "My Bloody Valentine", me)
        assertEquals(ArtistLadderOutcome.Failed(ArtistLadderFailure.Unreachable), out)
    }
}
