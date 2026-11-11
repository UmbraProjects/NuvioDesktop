package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstellationPlayTest {
    private fun release(key: String, year: String, kind: ReleaseKind = ReleaseKind.Album, yt: String? = "MPREb_$key") =
        LibraryRelease(key = key, title = key, kind = kind, year = year, releaseDate = year, ytBrowseId = yt)

    private fun artist(name: String, vararg releases: LibraryRelease, topKeys: List<String> = emptyList()) =
        LibraryArtist(id = "id-$name", name = name, genres = listOf("shoegaze"), releases = releases.toList(), topAlbumKeys = topKeys)

    // region Play on a star

    @Test
    fun aStarPlaysItsTopAlbumsRankedFirst() {
        val a = artist(
            "a",
            release("old", "1991"), release("mid", "1995"), release("new", "2005"),
            topKeys = listOf("mid"),
        )
        // The ranked album leads; the rest are filled from the newest.
        assertEquals(listOf("mid", "new", "old"), starPlayReleases(a).map { it.key })
    }

    @Test
    fun aStarWithOnlyPlayableSinglesStillPlaysThem() {
        val a = artist("a", release("hit", "2000", kind = ReleaseKind.Single), release("lost", "2001", yt = null))
        assertEquals(listOf("hit"), starPlayReleases(a).map { it.key })
    }

    @Test
    fun aStarWithNothingPlayableGivesNothing() {
        assertTrue(starPlayReleases(artist("a", release("lost", "2000", yt = null))).isEmpty())
        assertTrue(starPlayReleases(artist("b")).isEmpty())
    }

    // endregion

    // region Play cluster

    @Test
    fun aClusterTakesTurnsAcrossItsArtistsBestFirst() {
        val a = artist("a", release("a1", "2001"), release("a2", "2002"))
        val b = artist("b", release("b1", "2003"))
        // Newest album of each artist first, the artists alternating, in name order.
        assertEquals(listOf("a2", "b1", "a1"), clusterPlayReleases(listOf(b, a)).map { it.key })
    }

    @Test
    fun theSameGroupGivesTheSameAlbumsWhateverTheInputOrder() {
        val a = artist("a", release("a1", "2001"))
        val b = artist("b", release("b1", "2002"))
        val c = artist("c", release("c1", "2003"))
        assertEquals(clusterPlayReleases(listOf(a, b, c)), clusterPlayReleases(listOf(c, a, b)))
    }

    @Test
    fun aCapKeepsEveryArtistsBestBeforeAnyonesSecond() {
        val artists = (1..4).map { n -> artist("x$n", release("x$n-1", "2000"), release("x$n-2", "2001")) }
        val picked = clusterPlayReleases(artists, max = 5).map { it.key }
        assertEquals(5, picked.size)
        assertEquals(setOf("x1-2", "x2-2", "x3-2", "x4-2"), picked.take(4).toSet())
    }

    @Test
    fun aClusterWithNothingPlayableOrNoArtistsGivesNothing() {
        assertTrue(clusterPlayReleases(listOf(artist("a", release("lost", "2000", yt = null)))).isEmpty())
        assertTrue(clusterPlayReleases(emptyList()).isEmpty())
    }

    // endregion
}
