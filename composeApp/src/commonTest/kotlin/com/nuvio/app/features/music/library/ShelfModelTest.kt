package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShelfModelTest {
    private fun r(
        key: String,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String = "2020",
        date: String? = null,
        tracks: Int? = 10,
        yt: String? = null,
    ) = LibraryRelease(key = key, title = key, kind = kind, year = year, releaseDate = date, trackCount = tracks, ytBrowseId = yt)

    private fun artist(id: String, vararg releases: LibraryRelease) =
        LibraryArtist(id = id, name = id, releases = releases.toList())

    @Test
    fun spineWidthFollowsTheMockRule() {
        assertEquals(28, spineWidthDp(1))
        assertEquals(65, spineWidthDp(26))
        assertEquals(65, spineWidthDp(60))
        assertEquals(SPINE_DEFAULT_WIDTH_DP, spineWidthDp(null))
        assertEquals(SPINE_DEFAULT_WIDTH_DP, spineWidthDp(0))
    }

    @Test
    fun everythingFitsWithNoMorePill() {
        val a = artist("a", r("one", year = "2014"), r("two", year = "2019"))
        val row = buildShelfRow(a, availableWidthDp = 1000)
        assertEquals(listOf("one", "two"), row.spines.map { it.release.key })
        assertEquals(0, row.hidden)
        assertEquals(2, row.totalReleases)
    }

    @Test
    fun aFullShelfKeepsAlbumsOverSinglesAndCountsTheRestAsMore() {
        val singles = (1..30).map { r("s$it", ReleaseKind.Single, year = "20${10 + it % 10}", tracks = 1) }
        val albums = listOf(r("alb1", year = "2001"), r("alb2", year = "2005"))
        val a = artist("a", *(singles + albums).toTypedArray())
        val row = buildShelfRow(a, availableWidthDp = 300)
        assertTrue(row.spines.map { it.release.key }.containsAll(listOf("alb1", "alb2")))
        assertEquals(32, row.totalReleases)
        assertEquals(32 - row.spines.size, row.hidden)
        val used = row.spines.sumOf { it.widthDp + SPINE_GAP_DP }
        assertTrue(used <= 300 - 96, "spines must leave room for the +N more pill")
    }

    @Test
    fun spinesAreLaidOutOldestFirst() {
        val a = artist("a", r("new", year = "2024"), r("old", year = "2001"), r("mid", year = "2012"))
        assertEquals(listOf("old", "mid", "new"), buildShelfRow(a, 1000).spines.map { it.release.key })
    }

    @Test
    fun demosAreHiddenButStillCounted() {
        val a = artist("a", r("alb"), r("Demos", year = "2010"))
        val row = buildShelfRow(a, 1000)
        assertEquals(listOf("alb"), row.spines.map { it.release.key })
        assertEquals(1, row.hidden)
        assertEquals(2, row.totalReleases)
    }

    @Test
    fun generatedSpineColoursAreStableAndReadable() {
        val (bg1, fg1) = spineColors(r("Same"), null)
        val (bg2, _) = spineColors(r("Same"), null)
        assertEquals(bg1, bg2)
        assertNotEquals(bg1, spineColors(r("Different title"), null).first)
        assertTrue(fg1 == 0xFFFFFFFFL || fg1 == 0xFF0B0B0BL)
        val palette = ArtworkPalette(accent = 0xFFE8913A, onAccent = 0xFF1A1007, tint = 0xFF4A3220, ground = 0xFF120D08)
        assertEquals(0xFF4A3220, spineColors(r("x"), palette).first)
        assertEquals(0xFF111111, spineColors(r("x").copy(palette = palette.copy(tint = 0xFF111111)), palette).first)
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
