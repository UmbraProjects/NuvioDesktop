package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals

class TopAlbumsTest {
    private fun r(
        key: String,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String = "2000",
        deezer: Long? = null,
        yt: String? = "MPREb_$key",
        title: String = key,
    ) = LibraryRelease(key = key, title = title, kind = kind, year = year, deezerId = deezer, ytBrowseId = yt)

    private fun t(album: Long, rank: Long = 100) = DeezerTopTrack(album, rank)

    private fun artist(releases: List<LibraryRelease>, top: List<String> = emptyList(), pinned: List<String> = emptyList()) =
        LibraryArtist(id = "a", name = "a", releases = releases, topAlbumKeys = top, pinnedTopKeys = pinned)

    @Test
    fun everyTrackScoresForItsAlbum() {
        val rel = listOf(r("a", deezer = 1), r("b", deezer = 2), r("c", deezer = 3))
        val tracks = listOf(t(2, 500), t(1, 300), t(1, 250), t(3, 100))
        // a has two top tracks (550) and beats b's single bigger one (500).
        assertEquals(listOf("a", "b", "c"), rankTopAlbums(rel, tracks))
    }

    @Test
    fun compilationsSinglesAndUnplayableAlbumsAreNotRanked() {
        val rel = listOf(
            r("comp", ReleaseKind.Compilation, deezer = 1),
            r("single", ReleaseKind.Single, deezer = 2),
            r("nope", deezer = 3, yt = null),
            r("real", deezer = 4),
        )
        assertEquals(listOf("real"), rankTopAlbums(rel, listOf(t(1), t(2), t(3), t(4))))
    }

    @Test
    fun versionsFoldedIntoOneCardScoreTogether() {
        val rel = listOf(
            r("x", deezer = 10, title = "X"),
            r("xd", deezer = 11, title = "X (Deluxe Edition)"),
            r("y", deezer = 12, title = "Y"),
        )
        // x and its deluxe are one card: 300 + 100 = 400 beats y's 350.
        assertEquals(listOf("x", "y"), rankTopAlbums(rel, listOf(t(11, 300), t(10, 100), t(12, 350))))
    }

    @Test
    fun withoutRanksTheOrderOfTheListDecides() {
        val rel = listOf(r("a", deezer = 1), r("b", deezer = 2))
        assertEquals(listOf("b", "a"), rankTopAlbums(rel, listOf(t(2, 0), t(1, 0))))
    }

    @Test
    fun noTopTracksMeansNoRanking() {
        assertEquals(emptyList(), rankTopAlbums(listOf(r("a", deezer = 1)), emptyList()))
        assertEquals(emptyList(), rankTopAlbums(listOf(r("a", deezer = 1)), listOf(t(99))))
    }

    @Test
    fun theRankingStopsAtTheLimit() {
        val rel = (1..8).map { r("a$it", deezer = it.toLong()) }
        assertEquals(5, rankTopAlbums(rel, (1..8).map { t(it.toLong(), 1000L - it) }).size)
    }

    @Test
    fun starredThenRankedThenNewestFillTheFive() {
        val rel = (1..6).map { r("a$it", year = "${2000 + it}") }
        val picked = selectTopAlbums(artist(rel, top = listOf("a2", "a1"), pinned = listOf("a3")))
        assertEquals(listOf("a3", "a2", "a1", "a6", "a5"), picked.releases.map { it.key })
        assertEquals(3, picked.rankedCount)
    }

    @Test
    fun withNothingRankedItIsTheNewestFive() {
        val rel = (1..6).map { r("a$it", year = "${2000 + it}") }
        val picked = selectTopAlbums(artist(rel))
        assertEquals(listOf("a6", "a5", "a4", "a3", "a2"), picked.releases.map { it.key })
        assertEquals(0, picked.rankedCount)
    }

    @Test
    fun aKeyThatIsNoLongerPlayableIsSkipped() {
        val rel = listOf(r("gone", yt = null), r("here", year = "2001"))
        val picked = selectTopAlbums(artist(rel, top = listOf("gone", "here"), pinned = listOf("missing")))
        assertEquals(listOf("here"), picked.releases.map { it.key })
        assertEquals(1, picked.rankedCount)
    }

    @Test
    fun anArtistWithNoPlayableAlbumsHasNoTopAlbums() {
        val picked = selectTopAlbums(artist(listOf(r("a", yt = null), r("s", ReleaseKind.Single))))
        assertEquals(emptyList(), picked.releases)
    }

    @Test
    fun togglingAPinAddsRemovesAndKeepsTheNewest() {
        assertEquals(listOf("a"), togglePinned(emptyList(), "a"))
        assertEquals(emptyList(), togglePinned(listOf("a"), "a"))
        assertEquals(listOf("b", "c"), togglePinned(listOf("a", "b"), "c", limit = 2))
    }

    @Test
    fun parsesDeezerTopTracksAndSkipsWhatHasNoAlbum() {
        val body = """{"data":[{"id":1,"title":"x","rank":900000,"album":{"id":42,"title":"A"}},{"id":2,"title":"y","rank":0,"album":{"id":43}},{"id":3,"title":"no album"}],"total":3}"""
        assertEquals(listOf(DeezerTopTrack(42, 900000), DeezerTopTrack(43, 0)), parseDeezerTopTracks(body))
        assertEquals(emptyList(), parseDeezerTopTracks("""{"error":{"type":"Exception"}}"""))
        assertEquals(emptyList(), parseDeezerTopTracks("not json"))
    }
}
