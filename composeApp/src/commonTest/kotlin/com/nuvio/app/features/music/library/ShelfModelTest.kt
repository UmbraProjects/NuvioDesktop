package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShelfModelTest {
    private fun r(
        key: String,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String = "2020",
        date: String? = null,
        tracks: Int? = 10,
        yt: String? = "MPREb_$key",
    ) = LibraryRelease(key = key, title = key, kind = kind, year = year, releaseDate = date, trackCount = tracks, ytBrowseId = yt)

    private fun artist(id: String, vararg releases: LibraryRelease, top: List<String> = emptyList(), pinned: List<String> = emptyList()) =
        LibraryArtist(id = id, name = id, releases = releases.toList(), topAlbumKeys = top, pinnedTopKeys = pinned)

    @Test
    fun onlyPlayableReleasesBecomeTiles() {
        val a = artist("a", r("here"), r("gone", yt = null), r("gone single", ReleaseKind.Single, yt = null))
        val row = buildShelfRow(a)
        assertEquals(listOf("here"), row.tiles.map { it.release.key })
        assertEquals(3, row.totalReleases)
        assertEquals(1, row.playableReleases)
    }

    @Test
    fun topAlbumsLeadAndAreNumberedWhenRanked() {
        val a = artist(
            "a", r("a1", year = "2001"), r("a2", year = "2002"), r("a3", year = "2003"),
            top = listOf("a2", "a1"), pinned = listOf("a3"),
        )
        val row = buildShelfRow(a)
        assertEquals(listOf("a3", "a2", "a1"), row.top.map { it.release.key })
        assertEquals(listOf(1, 2, 3), row.top.map { it.rank })
        assertTrue(row.top.all { it.featured })
        assertEquals(listOf(true, false, false), row.top.map { it.pinned })
        assertEquals("Top 3", row.topLabel)
    }

    @Test
    fun theRestRunOldestToNewestAndNeverRepeatTheTop() {
        val albums = (1..6).map { r("a$it", year = "${2000 + it}") }
        val single = r("s", ReleaseKind.Single, year = "1999", tracks = 1)
        val row = buildShelfRow(artist("a", *(albums + single).toTypedArray(), top = listOf("a1")))
        assertEquals("a1", row.top.first().release.key)
        assertEquals(5, row.top.size)
        // Whatever is not in the top five follows, oldest first, with the single from 1999 ahead of the albums.
        val topKeys = row.top.map { it.release.key }.toSet()
        assertTrue(row.rest.none { it.release.key in topKeys })
        assertEquals(row.rest.map { it.release.key }, row.rest.map { it.release }.sortedBy { it.year }.map { it.key })
        assertEquals(7, row.tiles.size)
    }

    @Test
    fun withNothingRankedTheLabelSaysLatestAndNoTileIsNumbered() {
        val row = buildShelfRow(artist("a", r("a1", year = "2001"), r("a2", year = "2002")))
        assertEquals("Latest 2", row.topLabel)
        assertTrue(row.top.all { it.rank == null })
    }

    @Test
    fun demosAreNeverTilesButStillCounted() {
        val row = buildShelfRow(artist("a", r("alb"), r("Demos", year = "2010")))
        assertEquals(listOf("alb"), row.tiles.map { it.release.key })
        assertEquals(2, row.totalReleases)
    }

    @Test
    fun anArtistWithNothingPlayableHasAnEmptyShelf() {
        val row = buildShelfRow(artist("a", r("x", yt = null)))
        assertTrue(row.tiles.isEmpty())
        assertEquals(1, row.totalReleases)
    }

    @Test
    fun yearAgoPicksTheNearestPlayableReleaseOfLastYear() {
        val hozier = artist(
            "hozier",
            r("rbm", ReleaseKind.Single, "2025", "2025-10-24", 1, yt = "MPREb_a"),
            r("older", year = "2024", date = "2024-10-25", yt = "MPREb_b"),
        )
        val other = artist("other", r("far", year = "2025", date = "2025-09-01", yt = "MPREb_c"))
        val pick = pickYearAgo(listOf(other, hozier), 2026, 10, 25)
        assertEquals("rbm", pick?.release?.key)
        assertEquals("hozier", pick?.artist?.id)
    }

    @Test
    fun yearAgoPrefersPlayableOverCloser() {
        val a = artist(
            "a",
            r("near", ReleaseKind.Single, "2025", "2025-10-25", 1, yt = null),
            r("playable", ReleaseKind.Single, "2025", "2025-10-23", 1, yt = "MPREb_p"),
        )
        assertEquals("playable", pickYearAgo(listOf(a), 2026, 10, 25)?.release?.key)
    }

    @Test
    fun yearAgoIgnoresYearOnlyDatesAndOutOfWindow() {
        val a = artist("a", r("yearOnly", year = "2025"), r("late", year = "2025", date = "2025-11-20"))
        assertNull(pickYearAgo(listOf(a), 2026, 10, 25))
    }
}
