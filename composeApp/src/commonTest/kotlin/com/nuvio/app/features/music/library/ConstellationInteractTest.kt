package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstellationInteractTest {
    private fun artist(id: String, vararg genres: String, name: String = id, tags: List<String> = emptyList()) =
        LibraryArtist(id = id, name = name, genres = genres.toList(), tags = tags)

    private val identity = ScreenMap(MapFrame(0f, 0f, 400f, 400f), Camera())

    private fun star(id: String, x: Double, y: Double, r: Double = 0.03) = StarPoint(id, x, y, r)

    // region camera

    @Test
    fun homeCameraCannotPan() {
        assertEquals(Camera(), Camera().panned(50f, -30f, 400f, 300f))
    }

    @Test
    fun zoomKeepsThePointUnderTheCursorStill() {
        val before = Camera()
        val after = before.zoomedAt(100f, 80f, 2f, 400f, 300f)
        assertEquals(2f, after.zoom)
        // the base point that sat at (100, 80) is still at (100, 80)
        val base = (100f - before.panX) / before.zoom
        assertEquals(100f, after.x(base), 0.001f)
        val baseY = (80f - before.panY) / before.zoom
        assertEquals(80f, after.y(baseY), 0.001f)
    }

    @Test
    fun zoomStaysInRange() {
        var c = Camera()
        repeat(40) { c = c.zoomedAt(200f, 150f, 2f, 400f, 300f) }
        assertEquals(MAX_ZOOM, c.zoom)
        repeat(40) { c = c.zoomedAt(200f, 150f, 0.5f, 400f, 300f) }
        assertEquals(MIN_ZOOM, c.zoom)
        assertEquals(0f, c.panX, 0.001f)
        assertEquals(0f, c.panY, 0.001f)
    }

    @Test
    fun panIsKeptSoTheMapStillCoversTheCanvas() {
        val zoomed = Camera(zoom = 2f).panned(500f, 500f, 400f, 300f)
        assertEquals(0f, zoomed.panX)
        assertEquals(0f, zoomed.panY)
        val far = Camera(zoom = 2f).panned(-5000f, -5000f, 400f, 300f)
        assertEquals(-400f, far.panX)
        assertEquals(-300f, far.panY)
    }

    @Test
    fun revealingPansOnlyAsFarAsNeeded() {
        val c = Camera(zoom = 4f, panX = -300f, panY = -200f)
        val left = c.revealing(screenX = -50f, screenY = 100f, width = 400f, height = 300f, margin = 20f)
        assertEquals(-230f, left.panX, 0.001f) // 20 - (-50) = 70 more
        assertEquals(-200f, left.panY, 0.001f) // y was already inside
        val right = c.revealing(screenX = 450f, screenY = 100f, width = 400f, height = 300f, margin = 20f)
        assertEquals(-370f, right.panX, 0.001f) // 400 - 20 - 450 = -70
        assertEquals(c, c.revealing(200f, 100f, 400f, 300f, 20f))
    }

    // endregion

    // region hit test

    @Test
    fun hitFindsTheStarUnderThePixelAndNothingElsewhere() {
        val points = listOf(star("a", 0.25, 0.25), star("b", 0.75, 0.75))
        assertEquals("a", hitStar(points, identity, 100f, 100f))
        assertEquals("b", hitStar(points, identity, 300f, 305f))
        assertNull(hitStar(points, identity, 200f, 200f))
    }

    @Test
    fun hitPicksTheNearestCentreWhenStarsOverlap() {
        val points = listOf(star("a", 0.50, 0.5, 0.05), star("b", 0.54, 0.5, 0.05))
        assertEquals("a", hitStar(points, identity, 201f, 200f))
        assertEquals("b", hitStar(points, identity, 215f, 200f))
    }

    @Test
    fun hitFollowsTheCamera() {
        val points = listOf(star("a", 0.25, 0.25))
        val zoomed = ScreenMap(MapFrame(0f, 0f, 400f, 400f), Camera(zoom = 2f, panX = -100f, panY = -50f))
        // base (100, 100) -> screen (100, 150)
        assertEquals("a", hitStar(points, zoomed, 100f, 150f))
        assertNull(hitStar(points, zoomed, 100f, 100f))
    }

    // endregion

    // region keyboard

    private val grid = listOf(
        star("c", 0.2, 0.2), star("d", 0.5, 0.2), star("e", 0.8, 0.2),
        star("f", 0.2, 0.8), star("g", 0.5, 0.8), star("h", 0.8, 0.8),
        star("m", 0.5, 0.5),
    )

    @Test
    fun arrowsMoveToTheNearestStarThatWay() {
        assertEquals("m", stepStar(grid, "d", StepDirection.Down))
        assertEquals("g", stepStar(grid, "m", StepDirection.Down))
        assertEquals("e", stepStar(grid, "d", StepDirection.Right))
        assertEquals("c", stepStar(grid, "d", StepDirection.Left))
        assertEquals("d", stepStar(grid, "m", StepDirection.Up))
    }

    @Test
    fun arrowWithNothingThatWayStaysPut() {
        assertNull(stepStar(grid, "e", StepDirection.Right))
        assertNull(stepStar(grid, "c", StepDirection.Up))
    }

    @Test
    fun arrowOffTheAxisStillFindsAStarWhenTheConeIsEmpty() {
        val points = listOf(star("a", 0.1, 0.5), star("b", 0.3, 0.95))
        // b is far below a's line but still to its right: the fallback takes it
        assertEquals("b", stepStar(points, "a", StepDirection.Right))
    }

    @Test
    fun arrowWithNoSelectionStartsNearTheMiddle() {
        assertEquals("m", stepStar(grid, null, StepDirection.Right))
        assertEquals("m", startStar(grid))
    }

    @Test
    fun arrowsStayInsideTheFocusSet() {
        assertEquals("e", stepStar(grid, "c", StepDirection.Right, among = setOf("c", "e")))
        assertNull(stepStar(grid, "c", StepDirection.Right, among = setOf("c", "f")))
    }

    @Test
    fun tabWalksEveryStarOnceAndWraps() {
        val seen = ArrayList<String>()
        var at: String? = null
        repeat(grid.size) {
            at = cycleStar(grid, at, 1)
            seen += at!!
        }
        assertEquals(grid.map { it.id }.toSet(), seen.toSet())
        assertEquals(grid.size, seen.size)
        assertEquals(seen.first(), cycleStar(grid, seen.last(), 1))
        assertEquals(seen.last(), cycleStar(grid, null, -1))
        assertEquals(seen.last(), cycleStar(grid, seen.first(), -1))
    }

    @Test
    fun tabSkipsDimmedStars() {
        val among = setOf("c", "h")
        assertEquals("c", cycleStar(grid, null, 1, among))
        assertEquals("h", cycleStar(grid, "c", 1, among))
        assertEquals("c", cycleStar(grid, "h", 1, among))
    }

    // endregion

    // region filter

    private val library = listOf(
        artist("a1", "shoegaze", "dream pop", name = "Slowdive"),
        artist("a2", "shoegaze", "ambient", name = "Ride"),
        artist("a3", "shoegaze", "noise rock", name = "Lush"),
        artist("b1", "death metal", "thrash metal", name = "Morbid Angel"),
        artist("b2", "death metal", "black metal", name = "Entombed"),
    )

    @Test
    fun queryMatchesNameGenreOrTagIgnoringCase() {
        val a = artist("x", "shoegaze", name = "Slowdive", tags = listOf("90s"))
        assertTrue(matchesQuery(a, "SLOW"))
        assertTrue(matchesQuery(a, " shoe "))
        assertTrue(matchesQuery(a, "90"))
        assertTrue(!matchesQuery(a, "jazz"))
        assertTrue(matchesQuery(a, "   "))
    }

    @Test
    fun noFilterMeansNothingIsDimmed() {
        val model = buildConstellationModel(library)
        assertNull(focusIds(model, library.associateBy { it.id }, "", null))
        assertNull(focusIds(model, library.associateBy { it.id }, "  ", null))
    }

    @Test
    fun typingLightsOnlyTheMatches() {
        val model = buildConstellationModel(library)
        val byId = library.associateBy { it.id }
        assertEquals(setOf("b1", "b2"), focusIds(model, byId, "metal", null))
        assertEquals(setOf("a1"), focusIds(model, byId, "slowdive", null))
    }

    @Test
    fun aChipLightsItsClusterAndTheQueryNarrowsIt() {
        val model = buildConstellationModel(library)
        val byId = library.associateBy { it.id }
        val shoegaze = assertNotNull(model.clusters.clusterOf("a1")).key
        assertEquals(setOf("a1", "a2", "a3"), focusIds(model, byId, "", shoegaze))
        assertEquals(setOf("a2"), focusIds(model, byId, "ambient", shoegaze))
        assertEquals(emptySet<String>(), focusIds(model, byId, "metal", shoegaze))
    }

    @Test
    fun liveArtistDataWinsOverTheModelSnapshot() {
        val model = buildConstellationModel(library)
        val renamed = library.associateBy { it.id } + ("a1" to library[0].copy(name = "Renamed"))
        assertEquals(setOf("a1"), focusIds(model, renamed, "renamed", null))
    }

    @Test
    fun topGenresAreDistinctAndLimited() {
        val a = artist("x", "shoegaze", "dream pop", "ambient", "noise rock", tags = listOf("shoegaze"))
        val top = topGenres(a, 3)
        assertEquals(3, top.size)
        assertEquals(top.size, top.map { genreKey(it) }.toSet().size)
        assertTrue(topGenres(artist("y")).isEmpty())
    }

    // endregion
}
