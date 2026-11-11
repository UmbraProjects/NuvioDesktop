package com.nuvio.app.features.music.library

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConstellationClustersTest {
    private fun artist(id: String, vararg genres: String, mood: String? = null, tags: List<String> = emptyList()) =
        LibraryArtist(id = id, name = id, genres = genres.toList(), tags = tags, mood = mood)

    /** Three families with a clear main genre each, and one loner whose own main genre nobody else has. */
    private val library = listOf(
        artist("a1", "shoegaze", "dream pop"),
        artist("a2", "shoegaze", "ambient"),
        artist("a3", "shoegaze", "noise rock"),
        artist("b1", "death metal", "thrash metal"),
        artist("b2", "death metal", "black metal"),
        artist("b3", "death metal", "doom metal"),
        artist("b4", "death metal"),
        artist("c1", "jazz", "bebop"),
        artist("c2", "jazz", "swing"),
        artist("z1", "noise rock", "shoegaze"), // main genre noise rock: alone, but sounds like the shoegaze family
    )

    private fun build(artists: List<LibraryArtist>): ConstellationClusters {
        val graph = buildConstellationGraph(artists)
        return buildConstellationClusters(graph, layoutConstellation(graph))
    }

    @Test
    fun starsAreGroupedByTheirMainGenreAndNamedAfterIt() {
        val result = build(library)
        assertEquals(listOf("deathmetal", "shoegaze", "jazz"), result.clusters.map { it.key })
        assertEquals(listOf("death metal", "shoegaze", "jazz"), result.clusters.map { it.label })
        assertEquals(listOf("b1", "b2", "b3", "b4"), result.clusters[0].memberIds)
    }

    @Test
    fun aGroupOfOneJoinsTheGroupOfTheStarItIsMostLike() {
        val result = build(library)
        assertEquals("shoegaze", result.clusterOf("z1")?.key)
        assertEquals(listOf("a1", "a2", "a3", "z1"), result.clusterOf("a1")?.memberIds)
        assertTrue(result.clusters.none { it.key == "noiserock" }, "the lone noise rock group is gone")
    }

    @Test
    fun everyPlacedStarBelongsToExactlyOneCluster() {
        val result = build(library)
        assertEquals(library.size, result.clusters.sumOf { it.size })
        assertEquals(library.map { it.id }.toSet(), result.clusterKeyOf.keys)
        for (cluster in result.clusters) for (id in cluster.memberIds) assertEquals(cluster.key, result.clusterKeyOf[id])
    }

    @Test
    fun whenNoTwoStarsShareAMainGenreNothingMerges() {
        val loners = listOf(artist("s1", "shoegaze"), artist("s2", "jazz"), artist("s3", "techno"), artist("s4", "folk"))
        val result = build(loners)
        assertEquals(4, result.clusters.size)
        assertTrue(result.clusters.all { it.size == 1 })
    }

    @Test
    fun aSingletonThatSharesNothingFallsBackToTheNearestStarOnTheMap() {
        val withStranger = library + artist("zz", "polka") // nothing in common with anyone
        val graph = buildConstellationGraph(withStranger)
        val layout = layoutConstellation(graph)
        val result = buildConstellationClusters(graph, layout)
        val stranger = assertNotNull(layout.point("zz"))
        val nearest = layout.points.filter { it.id != "zz" }.minBy { hypot(it.x - stranger.x, it.y - stranger.y) }
        assertEquals(result.clusterKeyOf[nearest.id], result.clusterKeyOf["zz"])
        assertEquals(withStranger.size, result.clusters.sumOf { it.size })
    }

    @Test
    fun chipsAreTheEightBiggestClustersBiggestFirstThenByKey() {
        val genres = listOf("shoegaze", "jazz", "techno", "folk", "punk", "country", "funk", "grunge", "trance", "soul")
        val artists = genres.flatMapIndexed { g, name ->
            val count = if (g == 3) 4 else 2 // folk is the biggest
            List(count) { artist("g${g}x$it", name) }
        }
        val result = build(artists)
        assertEquals(10, result.clusters.size)
        assertEquals(8, result.chips.size)
        assertEquals("folk", result.chips.first().key)
        val sizes = result.chips.map { it.size }
        assertEquals(sizes.sortedDescending(), sizes)
        val sameSize = result.chips.drop(1)
        assertEquals(sameSize.map { it.key }.sorted(), sameSize.map { it.key }, "equal sizes are ordered by key")
    }

    @Test
    fun aMoodNeverNamesAGroupWhileTheArtistHasAGenre() {
        // The mood (0.6) outweighs a lone tag (0.5), but it is not a genre.
        val moody = listOf(
            artist("m1", tags = listOf("jazz"), mood = "Melancholy"),
            artist("m2", tags = listOf("jazz"), mood = "Melancholy"),
            artist("m3", "jazz"),
        )
        val result = build(moody)
        assertEquals(listOf("jazz"), result.clusters.map { it.key })
        assertEquals("jazz", result.clusters.single().label)
    }

    @Test
    fun theLabelSitsAtTheCentroidAndTheHaloCoversEveryStar() {
        val graph = buildConstellationGraph(library)
        val layout = layoutConstellation(graph)
        val result = buildConstellationClusters(graph, layout)
        for (cluster in result.clusters) {
            val pts = cluster.memberIds.map { assertNotNull(layout.point(it)) }
            assertEquals(pts.map { it.x }.average(), cluster.x, 1e-9)
            assertEquals(pts.map { it.y }.average(), cluster.y, 1e-9)
            for (p in pts) assertTrue(hypot(p.x - cluster.x, p.y - cluster.y) + p.radius <= cluster.haloRadius + 1e-9)
        }
    }

    @Test
    fun theSameLibraryAlwaysGivesTheSameClustersWhateverTheOrder() {
        val forward = build(library)
        assertEquals(forward, build(library))
        assertEquals(forward, build(library.reversed()))
    }

    @Test
    fun anEmptyLibraryHasNoClusters() {
        val result = build(emptyList())
        assertTrue(result.clusters.isEmpty() && result.chips.isEmpty())
    }
}
