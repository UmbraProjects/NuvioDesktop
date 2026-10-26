package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderRelatedTest {
    private fun cand(name: String, votes: Map<String, Int>) = LadderCandidate(id = "id-$name", name = name, votes = votes)

    private val slowdive = cand("Slowdive", mapOf("shoegaze" to 9, "dream pop" to 7))
    private val ride = cand("Ride", mapOf("shoegaze" to 6, "alternative rock" to 4))
    private val bare = cand("Bare", emptyMap())

    @Test
    fun borrowedVotesFollowTheRelatedArtistsGenres() {
        val b = assertNotNull(borrowGenres(listOf(slowdive, ride)))
        val ranked = b.votes.entries.sortedByDescending { it.value }.map { genreKey(it.key) }
        assertEquals("shoegaze", ranked.first())
        assertEquals(listOf("Slowdive", "Ride"), b.from)
    }

    @Test
    fun oneHugelyTaggedArtistCannotDrownTheOthers() {
        val loud = cand("Loud", mapOf("post-punk" to 5000))
        val a = cand("A", mapOf("shoegaze" to 3))
        val b = cand("B", mapOf("shoegaze" to 2))
        val votes = assertNotNull(borrowGenres(listOf(loud, a, b))).votes
        assertTrue(votes.getValue("shoegaze") > votes.getValue("post-punk"))
    }

    @Test
    fun closerRelatedArtistsWeighMore() {
        val first = cand("First", mapOf("dream pop" to 4))
        val second = cand("Second", mapOf("shoegaze" to 4))
        val votes = assertNotNull(borrowGenres(listOf(first, second))).votes
        assertTrue(votes.getValue("dream pop") > votes.getValue("shoegaze"))
    }

    @Test
    fun untaggedAndJunkOnlyArtistsLendNothingAndAreNotNamed() {
        val junk = cand("Junk", mapOf("seen live" to 30))
        val b = assertNotNull(borrowGenres(listOf(bare, slowdive, junk, ride)))
        assertEquals(listOf("Slowdive", "Ride"), b.from)
    }

    @Test
    fun tooFewLendersGivesNothing() {
        assertNull(borrowGenres(listOf(slowdive, bare)))
        assertNull(borrowGenres(emptyList()))
    }

    @Test
    fun onlyTheFirstFewRelatedNamesAreLookedUpAndTheArtistIsSkipped() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val names = listOf("Me", "A", "B", "C", "D", "E", "F")
        val out = relatedArtistTags(
            artist = cand("Me", emptyMap()),
            related = { names },
            tagged = { n -> asked += n; listOf(cand(n, mapOf("shoegaze" to 3))) },
        )
        assertEquals(listOf("A", "B", "C", "D"), asked)
        assertEquals(RELATED_LOOKUPS, out!!.size)
    }

    @Test
    fun aNamesakeHitIsNeverLent() = runBlocking<Unit> {
        val out = relatedArtistTags(
            artist = cand("Me", emptyMap()),
            related = { listOf("Ride", "Slowdive", "Lush") },
            tagged = { n -> listOf(cand("$n Tribute Band", mapOf("jazz" to 9)), cand(n, mapOf("shoegaze" to 5))) },
        )
        assertEquals(listOf("Ride", "Slowdive", "Lush"), out!!.map { it.name })
        assertTrue(out.all { "shoegaze" in it.votes })
    }

    @Test
    fun relatedArtistsWithNoTagsOrNoHitAreDropped() = runBlocking<Unit> {
        val out = relatedArtistTags(
            artist = cand("Me", emptyMap()),
            related = { listOf("A", "B", "C") },
            tagged = { n -> if (n == "A") emptyList() else listOf(cand(n, if (n == "B") emptyMap() else mapOf("shoegaze" to 2))) },
        )
        assertEquals(listOf("C"), out!!.map { it.name })
    }

    @Test
    fun unreachableDeezerGivesNull() = runBlocking<Unit> {
        assertNull(relatedArtistTags(cand("Me", emptyMap()), related = { null }, tagged = { emptyList() }))
    }

    @Test
    fun musicBrainzDroppingMidwayKeepsWhatIsEnoughAndOtherwiseGivesNull() = runBlocking<Unit> {
        fun run(failAt: String) = runBlocking {
            relatedArtistTags(
                cand("Me", emptyMap()),
                related = { listOf("A", "B", "C", "D") },
                tagged = { n -> if (n == failAt) null else listOf(cand(n, mapOf("shoegaze" to 2))) },
            )
        }
        assertEquals(listOf("A", "B"), run("C")!!.map { it.name })
        assertNull(run("B"))
    }

    @Test
    fun resolverBorrowsGenresForAThinlyTaggedArtist() = runBlocking<Unit> {
        val thin = cand("Tiny Band", mapOf("seen live" to 3))
        val resolver = ArtistTargetResolver(now = { 0L }, relatedTags = { listOf(slowdive, ride) }) { listOf(thin) }
        val found = assertIs<ArtistLookup.Found>(resolver.resolve("Tiny Band"))
        assertEquals("shoegaze", genreKey(found.target.genre))
        assertEquals(listOf("Slowdive", "Ride"), found.target.borrowedFrom)
        assertEquals("Tiny Band", found.target.artist.name)
    }

    @Test
    fun anArtistWithOwnTagsNeverAsksForRelatedOnes() = runBlocking<Unit> {
        var asked = 0
        val own = cand("Tagged", mapOf("shoegaze" to 5))
        val resolver = ArtistTargetResolver(now = { 0L }, relatedTags = { asked++; emptyList() }) { listOf(own) }
        val found = assertIs<ArtistLookup.Found>(resolver.resolve("Tagged"))
        assertEquals(0, asked)
        assertTrue(found.target.borrowedFrom.isEmpty())
    }

    @Test
    fun withoutAFallbackAThinArtistStillFailsWithNoGenre() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(cand("Tiny", emptyMap())) }
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoGenre), resolver.resolve("Tiny"))
    }

    @Test
    fun unreachableRelatedSourceIsNotCachedButNoGenreIs() = runBlocking<Unit> {
        var related = 0
        var fetches = 0
        val resolver = ArtistTargetResolver(
            now = { 0L },
            relatedTags = { related++; if (related == 1) null else emptyList() },
        ) { fetches++; listOf(cand("Tiny", emptyMap())) }
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.Unreachable), resolver.resolve("Tiny"))
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoGenre), resolver.resolve("Tiny"))
        assertEquals(ArtistLookup.Failed(ArtistLadderFailure.NoGenre), resolver.resolve("Tiny"))
        assertEquals(2, fetches)
        assertEquals(2, related)
    }

    @Test
    fun aBorrowedTargetStillBuildsALadder() = runBlocking<Unit> {
        val thin = cand("Tiny Band", emptyMap())
        val pool = listOf(
            cand("P1", mapOf("shoegaze" to 9, "dream pop" to 7)),
            cand("P2", mapOf("shoegaze" to 6)),
            cand("P3", mapOf("shoegaze" to 3, "noise pop" to 3)),
        )
        val resolver = ArtistTargetResolver(now = { 0L }, relatedTags = { listOf(slowdive, ride) }) { listOf(thin) }
        val out = ladderInto(resolver, { pool }, "Tiny Band", emptyList())
        val ready = assertIs<ArtistLadderOutcome.Ready>(out)
        assertTrue(ready.ladder.rungs.isNotEmpty())
        assertTrue(ready.target.borrowedFrom.isNotEmpty())
    }
}
