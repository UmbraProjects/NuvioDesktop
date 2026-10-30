package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryConstellationTest {
    private fun artist(id: String, vararg genres: String, tags: List<String> = emptyList()) =
        LibraryArtist(id = id, name = id, genres = genres.toList(), tags = tags)

    @Test
    fun similarArtistsLinkAndDissimilarOnesDoNot() {
        val a = artist("a", "shoegaze", "dream pop")
        val b = artist("b", "shoegaze", "ambient")
        val c = artist("c", "death metal", "thrash metal")
        val g = buildConstellationGraph(listOf(a, b, c))
        assertEquals(1, g.links.size)
        val link = g.links.single()
        assertEquals("a", link.a)
        assertEquals("b", link.b)
        assertTrue(link.similarity in 0.79..0.81) // one shared genre of two each: 1 / 1.25
        assertTrue(g.neighbours("c").isEmpty())
        assertEquals(listOf("b"), g.neighbours("a").map { it.first })
    }

    @Test
    fun aSimilarityBelowTheThresholdMakesNoLine() {
        // The only shared genre is each one's weakest: cosine 0.25 / 1.42 is about 0.18.
        val a = artist("a", "shoegaze", "ambient", "drone", "noise")
        val b = artist("b", "jazz", "bebop", "swing", "shoegaze")
        val g = buildConstellationGraph(listOf(a, b))
        assertTrue(g.links.isEmpty())
        assertEquals(2, g.placedCount)
    }

    @Test
    fun untaggedArtistsAreSetAsideNotPlaced() {
        val g = buildConstellationGraph(
            listOf(
                artist("a", "shoegaze"),
                artist("blank"),
                artist("junk", tags = listOf("seen live", "british", "90s")),
                artist("b", "shoegaze"),
            ),
        )
        assertEquals(listOf("a", "b"), g.stars.map { it.id })
        assertEquals(listOf("blank", "junk"), g.untagged.map { it.id })
        assertEquals(2, g.placedCount)
        assertEquals(1, g.links.size)
    }

    @Test
    fun aMapNeedsAtLeastThreePlacedArtists() {
        assertFalse(buildConstellationGraph(listOf(artist("a", "shoegaze"), artist("b", "shoegaze"))).canDraw)
        assertTrue(buildConstellationGraph(listOf(artist("a", "shoegaze"), artist("b", "shoegaze"), artist("c", "shoegaze"))).canDraw)
        assertFalse(buildConstellationGraph(emptyList()).canDraw)
    }

    @Test
    fun eachStarPicksItsNearestThreeAndALineIsMutualOnlyWhenBothPick() {
        // Five identical artists: everyone ties, so each picks the three lowest ids other than itself.
        val g = buildConstellationGraph(listOf("a", "b", "c", "d", "e").map { artist(it, "rock") })
        fun link(x: String, y: String) = g.links.firstOrNull { it.a == x && it.b == y }
        assertEquals(9, g.links.size)
        assertEquals(6, g.links.count { it.mutual })
        assertEquals(false, link("a", "e")?.mutual) // e picks a; a does not pick e
        assertEquals(true, link("a", "b")?.mutual)
        assertNull(link("d", "e")) // neither picks the other
        assertTrue(g.stars.all { star -> g.neighbours(star.id).size >= CONSTELLATION_NEIGHBOURS })
    }

    @Test
    fun theSameLibraryAlwaysGivesTheSameGraph() {
        val library = listOf(
            artist("a", "shoegaze", "dream pop"),
            artist("b", "shoegaze", "ambient"),
            artist("c", "ambient", "drone"),
            artist("d", "black metal"),
            artist("e"),
        )
        val forward = buildConstellationGraph(library)
        val backward = buildConstellationGraph(library.reversed())
        assertEquals(forward.stars.map { it.id }, backward.stars.map { it.id })
        assertEquals(forward.links, backward.links)
        assertEquals(forward.untagged.map { it.id }, backward.untagged.map { it.id })
    }

    @Test
    fun theLineCarriesTheRealTagSimilarity() {
        val a = artist("a", "shoegaze", "dream pop")
        val b = artist("b", "shoegaze", "dream pop")
        val g = buildConstellationGraph(listOf(a, b, artist("c", "shoegaze")))
        val ab = g.links.first { it.a == "a" && it.b == "b" }
        assertTrue(ab.similarity > 0.999)
        assertTrue(g.links.filter { it !== ab }.all { it.similarity < ab.similarity })
    }
}
