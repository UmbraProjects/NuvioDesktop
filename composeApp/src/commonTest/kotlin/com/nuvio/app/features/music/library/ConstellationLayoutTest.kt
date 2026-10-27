package com.nuvio.app.features.music.library

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConstellationLayoutTest {
    private fun artist(id: String, vararg genres: String) = LibraryArtist(id = id, name = id, genres = genres.toList())

    /** Three loose families: shoegaze, metal and jazz. */
    private val library = listOf(
        artist("a1", "shoegaze", "dream pop"),
        artist("a2", "shoegaze", "ambient"),
        artist("a3", "dream pop", "shoegaze", "noise rock"),
        artist("a4", "ambient", "drone"),
        artist("b1", "death metal", "thrash metal"),
        artist("b2", "thrash metal", "speed metal"),
        artist("b3", "death metal", "black metal"),
        artist("b4", "black metal", "doom metal"),
        artist("c1", "jazz", "bebop"),
        artist("c2", "jazz", "swing"),
    )

    private fun layout(artists: List<LibraryArtist>) = layoutConstellation(buildConstellationGraph(artists))

    private fun ConstellationLayout.dist(a: String, b: String): Double {
        val p = assertNotNull(point(a))
        val q = assertNotNull(point(b))
        return hypot(p.x - q.x, p.y - q.y)
    }

    @Test
    fun theSameLibraryAlwaysDrawsTheSameMapWhateverTheOrder() {
        val forward = layout(library)
        assertEquals(forward, layout(library))
        assertEquals(forward, layout(library.reversed()))
        assertEquals(library.map { it.id }.sorted(), forward.points.map { it.id })
    }

    @Test
    fun similarArtistsEndUpCloserThanDissimilarOnes() {
        val map = layout(library)
        assertTrue(map.dist("a1", "a2") < map.dist("a1", "b1"))
        assertTrue(map.dist("b1", "b2") < map.dist("b1", "c1"))
        assertTrue(map.dist("c1", "c2") < map.dist("c1", "a1"))
    }

    @Test
    fun noTwoStarsOverlapAndEveryStarIsInsideTheBox() {
        val crowd = (1..12).map { artist("s${it.toString().padStart(2, '0')}", "rock") } // twelve exactly alike
        for (map in listOf(layout(library), layout(crowd))) {
            for (p in map.points) {
                assertTrue(p.x - p.radius >= 0 && p.x + p.radius <= 1, "${p.id} x ${p.x}")
                assertTrue(p.y - p.radius >= 0 && p.y + p.radius <= 1, "${p.id} y ${p.y}")
            }
            for (i in map.points.indices) for (j in i + 1 until map.points.size) {
                val a = map.points[i]
                val b = map.points[j]
                assertTrue(hypot(a.x - b.x, a.y - b.y) >= a.radius + b.radius, "${a.id} overlaps ${b.id}")
            }
        }
    }

    @Test
    fun addingAnArtistInsideAFamilyMovesTheOthersOnlySlightly() {
        val before = layout(library)
        val after = layout(library + artist("a5", "shoegaze", "dream pop", "ambient"))
        val moved = before.points.maxOf { p -> hypot(p.x - after.point(p.id)!!.x, p.y - after.point(p.id)!!.y) }
        assertTrue(moved < 0.12, "largest move $moved")
    }

    @Test
    fun theSignOfEachAxisIsFixedByItsLargestLoading() {
        val axes = principalAxes(buildConstellationGraph(library).stars.map { it.profile })
        for (loading in listOf(axes.loading1, axes.loading2)) {
            val biggest = loading.indices.maxBy { abs(loading[it]) }
            assertTrue(loading[biggest] > 0, "largest loading ${loading[biggest]}")
        }
        // and the sign does not depend on input order
        val reversed = principalAxes(buildConstellationGraph(library.reversed()).stars.map { it.profile })
        assertEquals(axes.keys, reversed.keys)
    }

    @Test
    fun oneStarAndAnEmptyLibraryAreHandled() {
        assertTrue(layout(emptyList()).points.isEmpty())
        val one = layout(listOf(artist("solo", "shoegaze"))).points.single()
        assertEquals(0.5, one.x, 1e-9)
        assertEquals(0.5, one.y, 1e-9)
    }

    @Test
    fun aBiggerCatalogueMakesABiggerStarAndACrowdMakesThemSmaller() {
        val radii = starRadii(listOf(1, 4, 16))
        assertTrue(radii[0] < radii[1] && radii[1] < radii[2])
        assertEquals(MAX_STAR_RADIUS, radii[2], 1e-9)
        assertTrue(starRadii(List(400) { 5 }).first() < MAX_STAR_RADIUS)
        assertEquals(1, starRadii(listOf(0)).size)
    }

    @Test
    fun theFitKeepsAspectAndTheMargin() {
        val fitted = fitToUnitBox(listOf(0.0 to 0.0, 10.0 to 0.0, 5.0 to 2.0))
        assertEquals(LAYOUT_MARGIN, fitted[0][0], 1e-9)
        assertEquals(1 - LAYOUT_MARGIN, fitted[1][0], 1e-9)
        assertEquals(fitted[0][1], fitted[1][1], 1e-9) // same y: the aspect is kept, the short side is centred
        assertTrue(fitted[2][1] > fitted[0][1])
    }
}
