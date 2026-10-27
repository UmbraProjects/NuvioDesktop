package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryErasTest {
    private fun r(
        key: String,
        title: String = key,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String? = null,
        date: String? = null,
        tracks: Int? = 10,
        yt: String? = "MPREb_$key",
        secondary: List<String> = emptyList(),
    ) = LibraryRelease(
        key = key, title = title, kind = kind, year = year, releaseDate = date,
        trackCount = tracks, ytBrowseId = yt, secondaryTypes = secondary,
    )

    private fun artist(id: String, vararg releases: LibraryRelease, added: Long = 0L) =
        LibraryArtist(id = id, name = id, releases = releases.toList(), addedAtEpochMs = added)

    /** One artist with one album per year, so only the span matters. */
    private fun spanning(vararg years: Int) =
        listOf(artist("a", *years.map { r("r$it", year = "$it") }.toTypedArray()))

    // region bucket width

    @Test
    fun bandWidthFollowsTheSpanAtItsBoundaries() {
        assertEquals(1, eraBandWidth(0))
        assertEquals(1, eraBandWidth(12))
        assertEquals(5, eraBandWidth(13))
        assertEquals(5, eraBandWidth(40))
        assertEquals(10, eraBandWidth(41))
    }

    @Test
    fun aTenYearLibraryGetsOneYearBandsAndASixtyYearOneGetsTen() {
        val short = buildLibraryEras(spanning(2010, 2020))
        assertEquals(1, short.bandWidth)
        assertEquals(11, short.eras.size)

        val medium = buildLibraryEras(spanning(1990, 2010))
        assertEquals(5, medium.bandWidth)

        val long = buildLibraryEras(spanning(1950, 2010))
        assertEquals(10, long.bandWidth)
        assertEquals(listOf("1950 – 1959", "1960 – 1969", "1970 – 1979", "1980 – 1989", "1990 – 1999", "2000 – 2009", "2010 – 2019"), long.eras.map { it.years })
    }

    // endregion

    // region bands

    @Test
    fun bandsStartOnRoundYearsAndAGapIsKeptButEmpty() {
        val eras = buildLibraryEras(spanning(1991, 2006))
        assertEquals(listOf("1990 – 1994", "1995 – 1999", "2000 – 2004", "2005 – 2009"), eras.eras.map { it.years })
        assertEquals(listOf(0, 1, 2, 3), eras.eras.map { it.index })
        assertEquals(listOf(1, 0, 0, 1), eras.eras.map { it.releaseCount })
        val gap = eras.eras[1]
        assertTrue(gap.isEmpty)
        assertNull(gap.lead)
        assertEquals(0, gap.artistCount)
        assertFalse(gap.hasPlayable)
        assertEquals(1990, eras.startYear)
        assertEquals(2009, eras.endYear)
    }

    @Test
    fun aOneYearBandIsLabelledWithJustTheYear() {
        val eras = buildLibraryEras(spanning(2019, 2021))
        assertEquals(listOf("2019", "2020", "2021"), eras.eras.map { it.years })
        assertEquals(listOf(1, 0, 1), eras.eras.map { it.releaseCount })
    }

    @Test
    fun releaseDateWinsOverYearAndAPlaceholderFallsBackToYear() {
        val a = artist(
            "a",
            r("dated", year = "2001", date = "1999-05-04"),
            r("zeroed", year = "2003", date = "0000-00-00"),
        )
        val eras = buildLibraryEras(listOf(a))
        val years = eras.eras.filter { !it.isEmpty }.associate { it.fromYear to it.entries.map { e -> e.release.key } }
        assertEquals(mapOf(1999 to listOf("dated"), 2003 to listOf("zeroed")), years)
    }

    // endregion

    // region undated

    @Test
    fun undatedReleasesAreCollectedAndKeptOffTheRuler() {
        val a = artist("a", r("old", year = "2000"), r("new", year = "2002"), r("mystery"), r("zero", year = "0000"))
        val eras = buildLibraryEras(listOf(a))
        assertEquals(listOf("mystery", "zero"), eras.undated.map { it.release.key })
        assertTrue(eras.undated.all { it.year == null })
        assertEquals(2, eras.eras.sumOf { it.releaseCount })
        assertEquals(2000, eras.startYear)
        assertEquals(2002, eras.endYear)
    }

    @Test
    fun aLibraryOfOnlyUndatedReleasesHasNoRuler() {
        val eras = buildLibraryEras(listOf(artist("a", r("x"), r("y"))))
        assertTrue(eras.eras.isEmpty())
        assertEquals(2, eras.undated.size)
        assertFalse(eras.isEmpty)
        assertNull(eras.startYear)
        assertEquals(-1, eras.defaultEraIndex())
    }

    @Test
    fun anEmptyLibraryIsEmpty() {
        val eras = buildLibraryEras(emptyList())
        assertTrue(eras.isEmpty)
        assertEquals(0, eras.maxReleaseCount)
        assertEquals(-1, eras.defaultEraIndex())
    }

    // endregion

    // region lead

    @Test
    fun theLeadIsTheFullestAlbumAndKnowsItsArtist() {
        val a = artist(
            "a",
            r("single", kind = ReleaseKind.Single, year = "2000", tracks = 20),
            r("ep", kind = ReleaseKind.EP, year = "2000", tracks = 6),
            r("short", year = "2000", tracks = 9),
        )
        val b = artist("b", r("long", year = "2000", tracks = 12))
        val era = buildLibraryEras(listOf(a, b)).eras.single()
        assertEquals("long", era.lead?.release?.key)
        assertEquals("b", era.lead?.artist?.id)
    }

    @Test
    fun withNoAlbumTheLeadFallsBackToTheFullestEp() {
        val a = artist(
            "a",
            r("single", kind = ReleaseKind.Single, year = "2000", tracks = 20),
            r("ep", kind = ReleaseKind.EP, year = "2000", tracks = 4),
        )
        assertEquals("ep", buildLibraryEras(listOf(a)).eras.single().lead?.release?.key)
    }

    // endregion

    // region folding

    @Test
    fun demosAndDuplicateVersionsAreFolded() {
        val a = artist(
            "a",
            r("plain", title = "Record", year = "2000"),
            r("deluxe", title = "Record (Deluxe Edition)", year = "2000"),
            r("demo", title = "Early Demos", year = "2000"),
            r("tagged", title = "Rough Mix", year = "2000", secondary = listOf("Demo")),
            r("other", title = "Another", year = "2000"),
        )
        val era = buildLibraryEras(listOf(a)).eras.single()
        assertEquals(2, era.releaseCount)
        val folded = era.entries.first { it.release.title.startsWith("Record") }
        assertEquals(1, folded.group.extraVersions)
        assertTrue(era.entries.none { it.release.key == "demo" || it.release.key == "tagged" })
    }

    @Test
    fun anUnplayableReleaseIsKeptAndFlagged() {
        val a = artist("a", r("here", year = "2000"), r("gone", year = "2000", yt = null))
        val era = buildLibraryEras(listOf(a)).eras.single()
        assertEquals(2, era.releaseCount)
        assertEquals(listOf(true, false), era.entries.sortedBy { it.release.key == "gone" }.map { it.isPlayable })
        assertTrue(era.hasPlayable)

        val none = buildLibraryEras(listOf(artist("b", r("gone", year = "2000", yt = null)))).eras.single()
        assertFalse(none.hasPlayable)
    }

    // endregion

    // region counts and order

    @Test
    fun anEraCountsReleasesAndDistinctArtists() {
        val a = artist("a", r("a1", year = "2000"), r("a2", year = "2000", kind = ReleaseKind.EP))
        val b = artist("b", r("b1", year = "2000"))
        val c = artist("c", r("c1", year = "2005"))
        val eras = buildLibraryEras(listOf(a, b, c))
        val first = eras.eras.first()
        assertEquals(3, first.releaseCount)
        assertEquals(2, first.artistCount)
        assertEquals(listOf("a", "b"), first.artists.map { it.id })
        assertEquals(3, eras.maxReleaseCount)
        assertEquals(eras.eras.last().index, eras.defaultEraIndex())
    }

    @Test
    fun theNewestBandWithSomethingInItIsTheDefault() {
        val eras = buildLibraryEras(spanning(2000, 2004)).let { it.copy(eras = it.eras + it.eras.last().copy(index = 5, entries = emptyList(), lead = null)) }
        assertEquals(4, eras.defaultEraIndex())
    }

    @Test
    fun entriesRunOldestFirstThenByArtistAndTheInputOrderDoesNotMatter() {
        val a = artist("a", r("a-late", year = "2001", date = "2001-09-01"), r("a-early", year = "2001", date = "2001-02-01"))
        val b = artist("b", r("b-mid", year = "2001", date = "2001-05-01"), r("b-bare", year = "2001"))
        val forward = buildLibraryEras(listOf(a, b))
        val backward = buildLibraryEras(listOf(b, a.copy(releases = a.releases.reversed())))
        val expected = listOf("a-early", "b-mid", "b-bare", "a-late") // a bare year sits mid-year
        assertEquals(expected, forward.eras.single().entries.map { it.release.key })
        assertEquals(expected, backward.eras.single().entries.map { it.release.key })
    }

    // endregion

    // region added axis

    @Test
    fun theAddedAxisPlacesEveryReleaseAtTheYearItsArtistJoined() {
        val a = artist("a", r("a1", year = "1970"), r("a2", year = "1995"), added = 1623749400000L) // 2021-06-15
        val b = artist("b", r("b1", year = "2005"), added = 1704067200000L) // 2024-01-01
        val released = buildLibraryEras(listOf(a, b), ErasAxis.Released)
        val added = buildLibraryEras(listOf(a, b), ErasAxis.Added)

        assertEquals(ErasAxis.Added, added.axis)
        assertEquals(1, added.bandWidth) // 2021 to 2024 is a short span
        assertEquals(listOf("2021", "2022", "2023", "2024"), added.eras.map { it.years })
        assertEquals(listOf(2, 0, 0, 1), added.eras.map { it.releaseCount })
        assertEquals(listOf("a1", "a2"), added.eras.first().entries.map { it.release.key })

        // the same library re-buckets: the Released axis spans 1970 to 2005, 35 years
        assertEquals(5, released.bandWidth)
        assertEquals(1970, released.startYear)
    }

    @Test
    fun anArtistWithNoAddedTimeIsUndatedOnTheAddedAxis() {
        val a = artist("a", r("a1", year = "2000"))
        val b = artist("b", r("b1", year = "2001"), added = 1623749400000L)
        val added = buildLibraryEras(listOf(a, b), ErasAxis.Added)
        assertEquals(listOf("a1"), added.undated.map { it.release.key })
        assertEquals(1, added.eras.sumOf { it.releaseCount })
    }

    @Test
    fun yearOfEpochMsReadsTheUtcCalendarYear() {
        assertEquals(2024, yearOfEpochMs(1709208000000L)) // 2024-02-29 (leap day)
        assertEquals(2023, yearOfEpochMs(1704067199000L)) // 2023-12-31 23:59:59
        assertEquals(2024, yearOfEpochMs(1704067200000L)) // 2024-01-01 00:00:00
        assertEquals(1999, yearOfEpochMs(946684799000L))
        assertEquals(2000, yearOfEpochMs(951868800000L)) // 2000-03-01
        assertEquals(1970, yearOfEpochMs(1000L))
        assertNull(yearOfEpochMs(0L))
        assertNull(yearOfEpochMs(-5L))
    }

    // endregion

    @Test
    fun theLeadEntryOfAnEraIsOneOfItsEntries() {
        val era = buildLibraryEras(spanning(2000, 2001, 2002)).eras.first { !it.isEmpty }
        assertNotNull(era.lead)
        assertTrue(era.entries.any { it === era.lead })
    }

    // region selection and header (E1)

    @Test
    fun steppingSkipsGapsAndRestsAtTheEnds() {
        val eras = buildLibraryEras(spanning(1991, 2006)).eras // bands 0 and 3 hold releases, 1 and 2 are gaps
        assertEquals(3, stepEra(eras, 0, +1))
        assertEquals(0, stepEra(eras, 3, -1))
        assertEquals(3, stepEra(eras, 3, +1))
        assertEquals(0, stepEra(eras, 0, -1))
    }

    @Test
    fun steppingWithNothingSelectedLandsOnTheNewestBandWithSomething() {
        val eras = buildLibraryEras(spanning(1991, 2006)).eras
        assertEquals(3, stepEra(eras, -1, +1))
        assertEquals(3, stepEra(eras, 99, -1))
        assertEquals(-1, stepEra(emptyList(), -1, +1))
    }

    @Test
    fun theHeaderReadsLikeTheMock() {
        val a = artist("a", r("a1", year = "1991"), r("a2", year = "1992"))
        val b = artist("b", r("b1", year = "1993"), r("far", year = "2006"))
        val eras = buildLibraryEras(listOf(a, b))
        assertEquals("1990 to 1994 · 3 releases · 2 artists", eraHeader(eras.eras[0]))
        assertEquals("1995 to 1999 · nothing in your library", eraHeader(eras.eras[1]))
        assertEquals("2005 to 2009 · 1 release · 1 artist", eraHeader(eras.eras[3]))
        assertEquals("2019 · 1 release · 1 artist", eraHeader(buildLibraryEras(spanning(2019, 2021)).eras[0]))
    }

    // endregion
}
