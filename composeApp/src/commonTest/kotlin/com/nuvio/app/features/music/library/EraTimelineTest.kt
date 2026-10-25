package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EraTimelineTest {
    private fun r(
        key: String,
        title: String = key,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String? = null,
        date: String? = null,
        tracks: Int? = null,
        yt: String? = null,
    ) = LibraryRelease(key = key, title = title, kind = kind, year = year, releaseDate = date, trackCount = tracks, ytBrowseId = yt)

    private val hozierLike = listOf(
        r("debut", "Hozier", year = "2014", date = "2014-05-20", tracks = 13),
        r("wb", "Wasteland, Baby!", year = "2019", date = "2019-03-01", tracks = 14),
        r("uu", "Unreal Unearth", year = "2023", date = "2023-08-18", tracks = 16),
        r("unaired", "Unaired", ReleaseKind.EP, "2023", tracks = 3),
        r("unending", "Unreal Unearth: Unending", year = "2024", tracks = 26),
        r("rbm", "Rubber Band Man", ReleaseKind.Single, "2025", "2025-10-24", 1),
        r("lrpd", "Like Real People Do", ReleaseKind.Single, "2025", tracks = 1),
    )

    @Test
    fun gapsAndTwoYearSpansSplitErasLikeTheMock() {
        val t = buildEraTimeline(hozierLike)
        assertEquals(listOf("2014", "2019", "2023 – 2024", "2025"), t.eras.map { it.years })
    }

    @Test
    fun theFullestAlbumNamesTheEra() {
        val t = buildEraTimeline(hozierLike)
        assertEquals("Unreal Unearth: Unending", t.eras[2].name)
        assertTrue(t.eras[3].name in setOf("Rubber Band Man", "Like Real People Do"))
    }

    @Test
    fun eraTotalsAndOrder() {
        val era = buildEraTimeline(hozierLike).eras[2]
        assertEquals(3, era.releaseCount)
        assertEquals(16 + 3 + 26, era.trackTotal)
        assertEquals(listOf("unaired", "uu", "unending"), era.groups.map { it.primary.key })
    }

    @Test
    fun demosAndUndatedStayOffTheRuler() {
        val t = buildEraTimeline(hozierLike + r("demo", "Demos", year = "2012") + r("nodate", "Mystery"))
        assertEquals(2014, t.startYear)
        assertEquals(listOf("nodate"), t.undated.map { it.primary.key })
        assertTrue(t.dots.none { it.release.key == "demo" || it.release.key == "nodate" })
    }

    @Test
    fun positionUsesTheDateWhenKnownAndMidYearOtherwise() {
        assertEquals(2023.5, releasePosition(r("a", year = "2023"))!!, 1e-9)
        assertEquals(2023.0 + 5.5 / 12.0, releasePosition(r("a", year = "2023", date = "2023-06"))!!, 1e-9)
        val full = releasePosition(r("a", date = "2023-01-01"))!!
        assertEquals(2023.0, full, 1e-9)
        assertNull(releasePosition(r("a")))
    }

    @Test
    fun dotSizeGrowsWithTracksAndIsCapped() {
        assertEquals(11, timelineDotSize(1))
        assertEquals(timelineDotSize(26), timelineDotSize(80))
        assertTrue(timelineDotSize(13) > timelineDotSize(3))
        assertEquals(11, timelineDotSize(null))
    }

    @Test
    fun defaultEraIsTheOneHoldingTheLatestRelease() {
        val t = buildEraTimeline(hozierLike)
        val latest = hozierLike.first { it.key == "rbm" }
        assertEquals(3, t.defaultEraIndex(latest))
        assertEquals(3, t.defaultEraIndex(null))
        assertEquals(-1, buildEraTimeline(emptyList()).defaultEraIndex(null))
    }

    @Test
    fun eraColourFallsBackToTheArtistPalette() {
        val era = buildEraTimeline(hozierLike).eras[0]
        val artistPalette = ArtworkPalette(accent = 1, onAccent = 2, tint = 3, ground = 4)
        assertEquals(artistPalette, era.palette(artistPalette))
        val own = ArtworkPalette(accent = 9, onAccent = 9, tint = 9, ground = 9)
        val withOwn = buildEraTimeline(listOf(r("x", year = "2014").copy(palette = own))).eras[0]
        assertEquals(own, withOwn.palette(artistPalette))
        assertNotNull(withOwn.lead)
    }

    @Test
    fun placeholderYearsAreUndatedAndDoNotStretchTheRuler() {
        // Deezer sends "0000-00-00" for an unknown date, and one source once stored a bare "0".
        val t = buildEraTimeline(hozierLike + r("zero", "Zero", year = "0") + r("zeros", "Zeros", year = "0000", date = "0000-00-00"))
        assertEquals(2014, t.startYear)
        assertEquals(2025, t.endYear)
        assertEquals(setOf("zero", "zeros"), t.undated.map { it.primary.key }.toSet())
        assertTrue(t.dots.none { it.release.key.startsWith("zero") })
        assertNull(releasePosition(r("z", year = "0000")))
        assertNull(releasePosition(r("z", date = "0000-00-00")))
    }

    @Test
    fun releaseYearOrNullOnlyAcceptsRealYears() {
        assertEquals(2014, releaseYearOrNull("2014"))
        assertEquals(1972, releaseYearOrNull("1972-06"))
        assertEquals(2025, releaseYearOrNull(" 2025-10-24 "))
        assertNull(releaseYearOrNull("0"))
        assertNull(releaseYearOrNull("0000"))
        assertNull(releaseYearOrNull("0000-00-00"))
        assertNull(releaseYearOrNull("9999"))
        assertNull(releaseYearOrNull("20145"))
        assertNull(releaseYearOrNull(null))
        assertEquals("2014-05", sanePartialDate("2014-05"))
        assertNull(sanePartialDate("0000-00-00"))
    }
}
