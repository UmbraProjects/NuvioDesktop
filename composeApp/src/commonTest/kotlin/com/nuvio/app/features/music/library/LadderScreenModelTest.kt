package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderScreenModelTest {
    private fun cand(id: String, name: String, votes: Map<String, Int>) = LadderCandidate(id = id, name = name, votes = votes)

    private val pool = listOf(
        cand("s1", "Slowdive", mapOf("shoegaze" to 9, "dream pop" to 7)),
        cand("s2", "Ride", mapOf("shoegaze" to 6, "dream pop" to 3)),
        cand("s3", "Lush", mapOf("shoegaze" to 5, "dream pop" to 5)),
        cand("s4", "Swervedriver", mapOf("shoegaze" to 4, "alternative rock" to 6)),
        cand("s5", "Chapterhouse", mapOf("shoegaze" to 8, "dream pop" to 2)),
    )
    private val mbv = cand("a1", "My Bloody Valentine", mapOf("shoegaze" to 14, "dream pop" to 5))
    private val me = listOf(LibraryArtist(id = "l1", name = "Cocteau Twins", genres = listOf("dream pop")))
    private fun resolver(vararg hits: LadderCandidate) = ArtistTargetResolver(now = { 0L }) { hits.toList() }

    @Test
    fun aKnownGenreGivesOneGenreOffer() {
        val offers = ladderOffers("shoegaze", "Whoever")
        assertEquals(listOf("Ladder into Shoegaze"), offers.map { it.label })
        assertEquals(LadderRequest.Genre("shoegaze"), offers.single().request)
    }

    @Test
    fun anAliasIsReadAsItsGenre() {
        val offer = ladderOffers("DnB", null).single()
        assertEquals("Ladder into Drum and bass".lowercase(), offer.label.lowercase().replace("drum & bass", "drum and bass"))
    }

    @Test
    fun anArtistQueryGivesIntoAndOutFromForTheFirstResult() {
        val offers = ladderOffers("my bloody val", "My Bloody Valentine")
        assertEquals(listOf("Similar", "Opposite"), offers.map { it.label })
        assertEquals(LadderRequest.Artist("My Bloody Valentine", LadderMode.Into), offers[0].request)
        assertEquals(LadderRequest.Artist("My Bloody Valentine", LadderMode.OutFrom), offers[1].request)
    }

    @Test
    fun noOfferForShortQueriesOrWithoutAnArtistResult() {
        assertTrue(ladderOffers("a", "A").isEmpty())
        assertTrue(ladderOffers("   ", "A").isEmpty())
        assertTrue(ladderOffers("some album", null).isEmpty())
        assertTrue(ladderOffers("some album", "  ").isEmpty())
    }

    @Test
    fun genreLadderIsReady() = runBlocking<Unit> {
        val state = loadLadder(LadderRequest.Genre("shoegaze"), me, resolver(), { pool })
        val view = assertIs<LadderState.Ready>(state).view
        assertEquals("Into Shoegaze", view.title)
        assertTrue(view.ladder.rungs.size in 1..5)
    }

    @Test
    fun genreWithNoArtistsOrNoConnectionIsAMessage() = runBlocking<Unit> {
        val none = assertIs<LadderState.Failed>(loadLadder(LadderRequest.Genre("shoegaze"), me, resolver(), { emptyList() }))
        assertTrue(!none.retry && "No artists" in none.message)
        val down = assertIs<LadderState.Failed>(loadLadder(LadderRequest.Genre("shoegaze"), me, resolver(), { null }))
        assertTrue(down.retry)
    }

    @Test
    fun intoArtistNamesTheGenre() = runBlocking<Unit> {
        val view = assertIs<LadderState.Ready>(
            loadLadder(LadderRequest.Artist("My Bloody Valentine", LadderMode.Into), me, resolver(mbv), { pool }),
        ).view
        assertEquals("Into My Bloody Valentine", view.title)
        assertTrue("shoegaze" in view.subtitle.orEmpty())
        assertTrue(view.alternatives.isEmpty())
    }

    @Test
    fun outFromArtistNamesTheDestinationAndOffersAlternatives() = runBlocking<Unit> {
        val wide = mbv.copy(votes = mapOf("shoegaze" to 14, "dream pop" to 8, "noise pop" to 5))
        val view = assertIs<LadderState.Ready>(
            loadLadder(LadderRequest.Artist("My Bloody Valentine", LadderMode.OutFrom), me, resolver(wide), { pool }),
        ).view
        assertEquals("Out from My Bloody Valentine", view.title)
        assertTrue("dream pop" in view.subtitle.orEmpty())
        assertEquals(listOf("noise pop"), view.alternatives)
    }

    @Test
    fun aBorrowedTargetSaysWhoLentTheGenres() = runBlocking<Unit> {
        val thin = cand("t", "Tiny Band", emptyMap())
        val lenders = listOf(pool[0], pool[1])
        val r = ArtistTargetResolver(now = { 0L }, relatedTags = { lenders }) { listOf(thin) }
        val view = assertIs<LadderState.Ready>(
            loadLadder(LadderRequest.Artist("Tiny Band", LadderMode.Into), emptyList(), r, { pool }),
        ).view
        val note = assertNotNull(view.note)
        assertTrue("Slowdive, Ride" in note)
    }

    @Test
    fun anEmptyLibraryAddsTheColdStartNote() = runBlocking<Unit> {
        val view = assertIs<LadderState.Ready>(loadLadder(LadderRequest.Genre("shoegaze"), emptyList(), resolver(), { pool })).view
        assertTrue("Library" in view.note.orEmpty())
        val withLibrary = assertIs<LadderState.Ready>(loadLadder(LadderRequest.Genre("shoegaze"), me, resolver(), { pool })).view
        assertNull(withLibrary.note)
    }

    @Test
    fun everyFailureReasonHasItsOwnMessage() {
        val messages = ArtistLadderFailure.entries.map { failureMessage(it, "X").message }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(failureMessage(ArtistLadderFailure.Unreachable, "X").retry)
        assertTrue(ArtistLadderFailure.entries.filter { it != ArtistLadderFailure.Unreachable }.none { failureMessage(it, "X").retry })
    }
}
