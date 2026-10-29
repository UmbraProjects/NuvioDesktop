package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistPageModelTest {
    private fun release(
        key: String,
        title: String,
        kind: ReleaseKind = ReleaseKind.Album,
        year: String? = "2024",
        yt: String? = null,
        tracks: Int? = null,
        types: List<String> = emptyList(),
        date: String? = null,
    ) = LibraryRelease(
        key = key, title = title, kind = kind, year = year, ytBrowseId = yt,
        trackCount = tracks, secondaryTypes = types, releaseDate = date,
    )

    @Test
    fun sameTitleSameYearFoldsIntoOneCardAndThePlayableVersionLeads() {
        val groups = buildDiscography(
            listOf(
                release("a", "Vincent's Tale", ReleaseKind.EP, "2026", yt = null, tracks = 7),
                release("b", "Vincent's Tale", ReleaseKind.EP, "2026", yt = "MPREb_x", tracks = 11),
            ),
            playableOnly = false, filter = ReleaseFilter.All,
        ).eras.single().full
        assertEquals(1, groups.size)
        assertEquals("b", groups.single().primary.key)
        assertEquals(1, groups.single().extraVersions)
    }

    @Test
    fun editionSuffixesDoNotSplitARelease() {
        assertEquals(
            release("a", "Asylum").dedupeKey(),
            release("b", "Asylum (Deluxe Edition)").dedupeKey(),
        )
    }

    @Test
    fun differentVolumesStaySeparate() {
        assertTrue(release("a", "Sick Sick Soul (Vol. 1)").dedupeKey() != release("b", "Sick Sick Soul (Vol. 2)").dedupeKey())
    }

    @Test
    fun demosLeaveTheErasAndGoToTheArchive() {
        val d = buildDiscography(
            listOf(
                release("a", "Asylum", year = "2026"),
                release("b", "Demos (Do Not Share), Vol 1", year = "2020"),
                release("c", "Something", year = "2020", types = listOf("Demo")),
            ),
            playableOnly = false, filter = ReleaseFilter.All,
        )
        assertEquals(listOf("2026"), d.eras.map { it.year })
        assertEquals(2, d.demos.size)
    }

    @Test
    fun demoWordMatchOnlyOnWholeWords() {
        assertFalse(release("a", "Demolition").isDemo())
        assertTrue(release("a", "Demos Vol 2.").isDemo())
    }

    @Test
    fun erasRunNewestFirstWithUndatedLastAndSinglesApart() {
        val d = buildDiscography(
            listOf(
                release("a", "Old", year = "2016"),
                release("b", "New", year = "2026"),
                release("c", "S", ReleaseKind.Single, "2026"),
                release("d", "Nameless", year = null),
            ),
            playableOnly = false, filter = ReleaseFilter.All,
        )
        assertEquals(listOf("2026", "2016", ""), d.eras.map { it.year })
        assertEquals(1, d.eras.first().singles.size)
        assertEquals(1, d.eras.first().full.size)
    }

    @Test
    fun playableOnlyAndTypeFilterApplyAndCountsStayWhole() {
        val releases = listOf(
            release("a", "A", ReleaseKind.Album, yt = "x"),
            release("b", "B", ReleaseKind.Album),
            release("c", "C", ReleaseKind.Single, yt = "y"),
        )
        val d = buildDiscography(releases, playableOnly = true, filter = ReleaseFilter.Albums)
        assertEquals(listOf("a"), d.eras.flatMap { it.full }.map { it.primary.key })
        assertEquals(2, d.playableCount)
        assertEquals(3, d.totalCount)
        assertEquals(2, d.filterCounts[ReleaseFilter.Albums])
        assertEquals(1, d.filterCounts[ReleaseFilter.Singles])
        assertNull(d.filterCounts[ReleaseFilter.EPs])
    }

    @Test
    fun latestPrefersPlayableNewestAndSkipsDemos() {
        val latest = latestRelease(
            listOf(
                release("demo", "Demos", year = "2026", yt = "z", types = listOf("Demo")),
                release("unplayable", "Newest", year = "2026"),
                release("playable", "Newer playable", year = "2025", yt = "p"),
                release("old", "Old", year = "2020", yt = "q"),
            ),
        )
        assertEquals("playable", latest?.key)
    }

    @Test
    fun latestPrefersAFullReleaseOverASingleInTheSameYear() {
        val latest = latestRelease(
            listOf(
                release("single", "S", ReleaseKind.Single, "2026", yt = "a", date = "2026-05-01"),
                release("album", "A", ReleaseKind.Album, "2026", yt = "b", date = "2026-02-01"),
            ),
        )
        assertEquals("album", latest?.key)
    }

    @Test
    fun linksAreDedupedByLabelAndGrouped() {
        val grouped = groupLinks(
            listOf(
                ArtistLink("Tidal", "https://tidal.com/1"),
                ArtistLink("Tidal", "https://tidal.com/2"),
                ArtistLink("Instagram", "https://instagram.com/x"),
                ArtistLink("Discogs", "https://discogs.com/x"),
                ArtistLink("Spotify", "https://open.spotify.com/x"),
            ),
        )
        assertEquals(listOf(LinkGroup.Listen, LinkGroup.Social, LinkGroup.Info), grouped.keys.toList())
        assertEquals(listOf("Tidal", "Spotify"), grouped.getValue(LinkGroup.Listen).map { it.label })
        assertEquals("https://tidal.com/1", grouped.getValue(LinkGroup.Listen).first().url)
    }

    @Test
    fun headlineJoinsWhatItHas() {
        val a = LibraryArtist(
            id = "1", name = "N", genres = listOf("hip hop", "rapcore", "Rap", "extra"),
            area = "United Kingdom", beginYear = "1990",
        )
        assertEquals("hip hop / rapcore / Rap · United Kingdom · since 1990", artistHeadline(a))
    }


    @Test
    fun releaseCountIsTheSameForTheGridAndThePage() {
        val releases = listOf(
            release("a", "Asylum"),
            release("b", "Asylum (Deluxe Edition)"), // same record listed twice by two sources
            release("c", "Demos Vol 1", year = "2020"),
            release("d", "Hurricane", ReleaseKind.Single, "2025"),
        )
        val total = buildDiscography(releases, playableOnly = false, filter = ReleaseFilter.All).totalCount
        assertEquals(3, countReleaseCards(releases))
        assertEquals(total, countReleaseCards(releases))
        assertTrue(countReleaseCards(releases) < releases.size)
    }

    @Test
    fun aSecondaryTypeThatRepeatsTheKindIsNotPrintedTwice() {
        assertEquals(emptyList(), release("a", "Live at X", ReleaseKind.Live, types = listOf("Live")).extraTypes())
        assertEquals(listOf("Remix"), release("b", "Y", ReleaseKind.Live, types = listOf("live", "Remix")).extraTypes())
        assertEquals(listOf("Soundtrack"), release("c", "Z", ReleaseKind.Album, types = listOf("Soundtrack")).extraTypes())
    }
}
