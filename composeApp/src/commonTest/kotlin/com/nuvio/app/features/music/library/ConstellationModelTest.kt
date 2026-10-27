package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstellationModelTest {
    private fun artist(id: String, vararg genres: String, favourite: Boolean = false) =
        LibraryArtist(id = id, name = id, genres = genres.toList(), favourite = favourite)

    private val library = listOf(
        artist("a1", "shoegaze", "dream pop"),
        artist("a2", "shoegaze", "ambient"),
        artist("a3", "shoegaze", "noise rock"),
        artist("b1", "death metal", "thrash metal"),
        artist("b2", "death metal", "black metal"),
        artist("n1"), // no tags: not placed
    )

    @Test
    fun keyIgnoresWhatDoesNotMoveTheMap() {
        val before = constellationKey(library)
        val toggled = library.map { if (it.id == "a1") it.copy(favourite = true, name = "Renamed") else it }
        assertEquals(before, constellationKey(toggled))
        assertEquals(before, constellationKey(library.reversed()), "list order does not matter")
    }

    @Test
    fun keyChangesWhenTagsOrArtistsChange() {
        val before = constellationKey(library)
        assertNotEquals(before, constellationKey(library.map { if (it.id == "n1") it.copy(genres = listOf("jazz")) else it }))
        assertNotEquals(before, constellationKey(library + artist("c1", "jazz")))
        assertNotEquals(before, constellationKey(library.drop(1)))
    }

    @Test
    fun modelPlacesTaggedArtistsAndSetsUntaggedAside() {
        val model = buildConstellationModel(library)
        assertEquals(5, model.graph.placedCount)
        assertEquals(listOf("n1"), model.graph.untagged.map { it.id })
        assertEquals(model.graph.stars.map { it.id }, model.layout.points.map { it.id })
        assertTrue(model.graph.canDraw)
        assertEquals(constellationKey(library), model.key)
    }

    @Test
    fun squareCanvasUsesTheWholeBox() {
        val f = mapFrame(400f, 400f)
        assertEquals(0f, f.left)
        assertEquals(0f, f.top)
        assertEquals(400f, f.x(1.0))
        assertEquals(200f, f.y(0.5))
        assertEquals(40f, f.radius(0.1))
    }

    @Test
    fun wideCanvasIsStretchedOnlyUpToTheCapAndCentred() {
        val f = mapFrame(1600f, 500f)
        assertEquals(800f, f.width, 0.001f) // 500 * 1.6
        assertEquals(500f, f.height)
        assertEquals(400f, f.left, 0.001f)
        assertEquals(400f, f.x(0.0), 0.001f)
        assertEquals(1200f, f.x(1.0), 0.001f)
        assertEquals(500f, f.unit) // radii follow the shorter side
    }

    @Test
    fun tallCanvasIsStretchedOnlyUpToTheCapAndCentred() {
        val f = mapFrame(300f, 900f)
        assertEquals(300f, f.width)
        assertEquals(480f, f.height, 0.001f) // 300 * 1.6
        assertEquals(210f, f.top, 0.001f)
    }

    @Test
    fun stretchingNeverBringsTwoRelaxedStarsCloserThanTheirRadii() {
        val model = buildConstellationModel(library.filter { it.id != "n1" })
        val f = mapFrame(1280f, 600f)
        val p = model.layout.points
        for (i in p.indices) for (j in i + 1 until p.size) {
            val dx = f.x(p[i].x) - f.x(p[j].x)
            val dy = f.y(p[i].y) - f.y(p[j].y)
            val dist = kotlin.math.hypot(dx, dy)
            assertTrue(dist >= f.radius(p[i].radius) + f.radius(p[j].radius) - 0.01f, "${p[i].id} and ${p[j].id} overlap")
        }
    }

    @Test
    fun paddedFrameKeepsThePaddingClear() {
        val f = paddedFrame(400f, 300f, 24f)
        assertEquals(24f, f.left, 0.001f)
        assertEquals(24f, f.top, 0.001f)
        assertEquals(352f, f.width, 0.001f)
        assertEquals(252f, f.height, 0.001f)
    }

    @Test
    fun degenerateCanvasDoesNotBlowUp() {
        val f = mapFrame(0f, 0f)
        assertEquals(0f, f.x(0.5))
        assertEquals(0f, f.radius(0.5))
    }

    @Test
    fun linkAlphaGrowsWithSimilarityAndMutuality() {
        val faint = linkAlpha(CONSTELLATION_MIN_SIMILARITY, mutual = false)
        val firm = linkAlpha(1.0, mutual = false)
        assertTrue(faint < firm)
        assertTrue(linkAlpha(0.6, mutual = true) > linkAlpha(0.6, mutual = false))
        assertTrue(linkAlpha(5.0, mutual = true) <= 0.5f + 1e-6f, "out of range similarity is clamped")
    }

    @Test
    fun clusterLeadIsTheBiggestStarThenLowestId() {
        val layout = ConstellationLayout(
            listOf(StarPoint("x", 0.2, 0.2, 0.03), StarPoint("y", 0.4, 0.4, 0.05), StarPoint("z", 0.6, 0.6, 0.05)),
        )
        val cluster = StarCluster("k", "k", listOf("x", "y", "z"), 0.4, 0.4, 0.3)
        assertEquals("y", clusterLead(cluster, layout))
        assertNull(clusterLead(StarCluster("e", "e", emptyList(), 0.5, 0.5, 0.0), layout))
    }

    @Test
    fun onlyGroupsOfTwoOrMoreGetALabel() {
        assertTrue(drawsClusterLabel(StarCluster("k", "k", listOf("a", "b"), 0.5, 0.5, 0.1)))
        assertTrue(!drawsClusterLabel(StarCluster("k", "k", listOf("a"), 0.5, 0.5, 0.05)))
    }
}
