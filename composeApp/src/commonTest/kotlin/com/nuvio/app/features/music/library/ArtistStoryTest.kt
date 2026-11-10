package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistStoryTest {
    private fun rel(
        key: String,
        year: String?,
        kind: ReleaseKind = ReleaseKind.Album,
        secondary: List<String> = emptyList(),
        date: String? = null,
    ) = LibraryRelease(key = key, title = "Title $key", kind = kind, secondaryTypes = secondary, year = year, releaseDate = date)

    private fun artist(
        releases: List<LibraryRelease>,
        top: List<String> = emptyList(),
        type: String? = "Group",
        begin: String? = "1987",
        ended: Boolean = false,
    ) = LibraryArtist(id = "UC1", name = "Gang Starr", type = type, beginYear = begin, ended = ended, releases = releases, topAlbumKeys = top)

    private val career = listOf(
        rel("nomr", "1989", date = "1989-03-28"),
        rel("stepin", "1991"),
        rel("hardtoearn", "1994"),
        rel("moment", "1998"),
        rel("live", "1999", secondary = listOf("Live")),
        rel("comp", "2000", ReleaseKind.Compilation),
        rel("ownlane", "2003"),
        rel("return", "2019"),
    )

    @Test
    fun theStoryIsTheCareerInTimeOrderWithOneRecordPerPoint() {
        val points = artistStoryPoints(artist(career, top = listOf("hardtoearn")))
        assertEquals(
            listOf(
                Triple(1989, StoryKind.Debut, "nomr"),
                Triple(1994, StoryKind.BestKnown, "hardtoearn"),
                Triple(2019, StoryKind.Comeback, "return"),
            ),
            points.map { Triple(it.year, it.kind, it.release.key) },
        )
        assertEquals(points.size, points.map { it.release.key }.distinct().size)
    }

    @Test
    fun theComebackIsTheLongestGapOfFourYearsOrMoreBetweenAlbumsAndEps() {
        val points = artistStoryPoints(artist(career, top = listOf("hardtoearn")))
        val comeback = points.first { it.kind == StoryKind.Comeback }
        assertEquals("Back after 16 years without a new album or EP", comeback.line)
        // Live records and compilations do not fill the gap.
        val noGap = artistStoryPoints(artist(listOf(rel("a", "2000"), rel("b", "2003"), rel("c", "2006"))))
        assertTrue(noGap.none { it.kind == StoryKind.Comeback })
    }

    @Test
    fun aGroupsDebutSaysHowLongAfterFormingAndAPersonsDoesNot() {
        val group = artistStoryPoints(artist(career)).first { it.kind == StoryKind.Debut }
        assertEquals("First record, 2 years after they formed in 1987", group.line)
        val person = artistStoryPoints(artist(career, type = "Person", begin = "1971")).first { it.kind == StoryKind.Debut }
        assertEquals("Where the story starts", person.line)
    }

    @Test
    fun aSingleDebutGetsAFirstAlbumPointAndTheBestKnownDebutIsSaidOnce() {
        val releases = listOf(rel("single", "1990", ReleaseKind.Single), rel("album", "1992"), rel("later", "1995"))
        val points = artistStoryPoints(artist(releases, top = listOf("album")))
        assertEquals(StoryKind.Debut to "single", points[0].kind to points[0].release.key)
        assertTrue(points.any { it.kind == StoryKind.FirstAlbum && it.release.key == "album" })
        // The first album is also the best known: it is one point, not two.
        assertEquals(1, points.count { it.release.key == "album" })

        val oneHit = artistStoryPoints(artist(listOf(rel("first", "1990"), rel("second", "1995")), top = listOf("first")))
        assertEquals("Where the story starts, and still the one they are best known for", oneHit.first().line)
    }

    @Test
    fun latestIsTheNewestRecordAndSaysSoWhenTheArtistHasEnded() {
        val releases = listOf(rel("a", "1990"), rel("b", "1993"), rel("c", "1996"))
        assertEquals("Their most recent record", artistStoryPoints(artist(releases)).last().line)
        assertEquals("Their last record", artistStoryPoints(artist(releases, ended = true)).last().line)
    }

    @Test
    fun nothingDatedGivesNoStoryAndNoSummary() {
        assertEquals(emptyList(), artistStoryPoints(artist(listOf(rel("a", null), rel("b", "abcd")))))
        assertNull(artistStorySummary(artist(listOf(rel("a", null)))))
        assertEquals(emptyList(), artistStoryPoints(artist(emptyList())))
    }

    @Test
    fun theSummaryCountsAlbumsAndEpsAndGivesTheSpan() {
        val releases = listOf(
            rel("a", "1989"), rel("b", "1991"), rel("ep", "1993", ReleaseKind.EP),
            rel("live", "1995", secondary = listOf("Live")), rel("s", "1998", ReleaseKind.Single),
        )
        assertEquals("2 albums · 1 EP · 1989–1998", artistStorySummary(artist(releases)))
        assertEquals("1 album · 1989", artistStorySummary(artist(listOf(rel("a", "1989")))))
    }
}
