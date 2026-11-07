package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShelfBrowserModelTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun r(key: String, year: String = "2020", yt: String? = "MPREb_$key", tracks: Int? = 10, seconds: Int? = null) =
        LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, year = year, trackCount = tracks, durationSeconds = seconds, ytBrowseId = yt)

    private fun artist(
        name: String,
        vararg releases: LibraryRelease,
        sortName: String? = null,
        favourite: Boolean = false,
        addedDaysAgo: Long? = null,
        pinned: List<String> = emptyList(),
        genres: List<String> = emptyList(),
    ) = LibraryArtist(
        id = name.lowercase(),
        name = name,
        sortName = sortName,
        favourite = favourite,
        addedAtEpochMs = addedDaysAgo?.let { now - it * day } ?: 0L,
        releases = releases.toList(),
        pinnedTopKeys = pinned,
        genres = genres,
    )

    @Test
    fun artistsRunAToZIgnoringALeadingThe() {
        val list = shelfArtists(listOf(artist("The Cure"), artist("Blur"), artist("ABBA")), ShelfArtistFilter.All, "", now)
        assertEquals(listOf("ABBA", "Blur", "The Cure"), list.map { it.name })
        assertEquals('C', artist("The Cure").shelfLetter())
    }

    @Test
    fun aSortNameWinsAndOtherScriptsFileUnderHash() {
        assertEquals('J', artist("Michael Jackson", sortName = "Jackson, Michael").shelfLetter())
        assertEquals('#', artist("2Pac").shelfLetter())
        assertEquals('#', artist("坂本龍一").shelfLetter())
    }

    @Test
    fun starredListsOnlyStarredArtists() {
        val list = shelfArtists(listOf(artist("A", favourite = true), artist("B")), ShelfArtistFilter.Starred, "", now)
        assertEquals(listOf("A"), list.map { it.name })
    }

    @Test
    fun addedRecentlyIsNewestFirstAndAlwaysShowsAFew() {
        val artists = (1..20).map { artist("Artist$it", addedDaysAgo = it * 30L) }
        val list = shelfArtists(artists, ShelfArtistFilter.AddedRecently, "", now)
        // Only two were added in the last 60 days, so the newest twelve show, newest first.
        assertEquals(12, list.size)
        assertEquals("Artist1", list.first().name)
        assertTrue(list.zipWithNext().all { (a, b) -> a.addedAtEpochMs >= b.addedAtEpochMs })
    }

    @Test
    fun theHeaderFilterNarrowsByNameOrGenre() {
        val artists = listOf(artist("Massive Attack", genres = listOf("trip hop")), artist("Metallica", genres = listOf("metal")))
        assertEquals(listOf("Massive Attack"), shelfArtists(artists, ShelfArtistFilter.All, "trip", now).map { it.name })
    }

    @Test
    fun aLetterJumpsToItsFirstArtistOrTheNextLetterThatHasOne() {
        val list = shelfArtists(listOf(artist("Abba"), artist("Blur"), artist("Bowie"), artist("Dido")), ShelfArtistFilter.All, "", now)
        val index = shelfLetterIndex(list)
        assertEquals(1, shelfJumpTo(index, 'B'))
        assertEquals(3, shelfJumpTo(index, 'C'))
        assertNull(shelfJumpTo(index, 'E'))
    }

    @Test
    fun theWallSortsAndNarrowsByDecade() {
        val artists = listOf(
            artist("Prince", r("Purple Rain", "1984"), r("Sign", "1987"), addedDaysAgo = 5),
            artist("Abba", r("Arrival", "1976"), addedDaysAgo = 50),
            artist("Unplayable", r("Gone", "1985", yt = null)),
        )
        val all = shelfAllAlbums(artists, "")
        assertEquals(3, all.size)
        val choices = shelfShowChoices(all)
        assertEquals(listOf("All", "Starred", "1970s", "1980s"), choices.map { it.label })
        val eighties = shelfWall(all, ShelfAlbumSort.Year, ShelfAlbumShow.Decade(1980))
        assertEquals(listOf("Sign", "Purple Rain"), eighties.map { it.release.title })
        assertEquals("The 1980s", shelfWallTitle(ShelfAlbumShow.Decade(1980)))
        assertEquals(listOf("Arrival", "Purple Rain", "Sign"), shelfWall(all, ShelfAlbumSort.Album, ShelfAlbumShow.All).map { it.release.title })
        assertEquals("Prince", shelfWall(all, ShelfAlbumSort.Added, ShelfAlbumShow.All).first().artist.name)
    }

    @Test
    fun starredOnTheWallMeansStarredAlbums() {
        val a = artist("Prince", r("Purple Rain", "1984"), r("Sign", "1987"), pinned = listOf("Sign"))
        val starred = shelfWall(shelfAllAlbums(listOf(a), ""), ShelfAlbumSort.Album, ShelfAlbumShow.Starred)
        assertEquals(listOf("Sign"), starred.map { it.release.title })
    }

    @Test
    fun theWallFilterMatchesAlbumTitlesToo() {
        val artists = listOf(artist("Prince", r("Purple Rain", "1984"), r("Sign", "1987")))
        assertEquals(listOf("Purple Rain"), shelfAllAlbums(artists, "purple").map { it.release.title })
        assertEquals(2, shelfAllAlbums(artists, "prince").size)
    }

    @Test
    fun pagesTurnRatherThanScroll() {
        val items = (1..20).toList()
        assertEquals(3, shelfPageCount(items.size, SHELF_ARTIST_PAGE))
        assertEquals(1, shelfPageCount(0, SHELF_ARTIST_PAGE))
        assertEquals((17..20).toList(), items.shelfPage(2, SHELF_ARTIST_PAGE))
        assertTrue(items.shelfPage(3, SHELF_ARTIST_PAGE).isEmpty())
    }

    @Test
    fun theLinesSayOnlyWhatIsKnown() {
        assertEquals("2020 · 9 songs · 42 min", shelfAlbumLine(r("x", tracks = 9, seconds = 42 * 60 + 10)))
        assertEquals("Prince · 2020", shelfAlbumLine(r("x", tracks = null), artistName = "Prince"))
        assertEquals("11 releases · 9 play here · 4 lossless on your addon", shelfArtistLine(11, 9, 4))
        assertEquals("1 release", shelfArtistLine(1, 1, 0))
    }

    // ---- smart shelves ----

    private fun dated(key: String, date: String) =
        LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, year = date.take(4), releaseDate = date, ytBrowseId = "MPREb_$key")

    @Test
    fun civilDaysRoundTrip() {
        assertEquals(0L, civilToDays(1970, 1, 1))
        assertEquals(20_399L, civilToDays(2025, 11, 7))
        assertEquals(2026, yearOfDay(civilToDays(2026, 11, 7)))
        assertEquals(2025, yearOfDay(civilToDays(2025, 12, 31)))
    }

    @Test
    fun newFromYourArtistsIsTheLastSixMonthsNewestFirst() {
        val today = civilToDays(2026, 11, 7) * day
        val a = artist("Prince", dated("fresh", "2026-10-30"), dated("summer", "2026-06-01"), dated("old", "2025-01-01"))
        val new = shelfNew(shelfAlbumsOf(a), today)
        assertEquals(listOf("fresh", "summer"), new.map { it.release.key })
    }

    @Test
    fun mostPlayedNeedsTwoPlays() {
        val a = artist("Prince", r("one"), r("two"), r("three"))
        val plays = mapOf("MPREb_one" to 5, "MPREb_two" to 1, "MPREb_three" to 2)
        assertEquals(listOf("one", "three"), shelfMostPlayed(shelfAlbumsOf(a), plays).map { it.release.key })
    }

    @Test
    fun gatheringDustIsTheLongestForgottenFirst() {
        val a = artist("Prince", r("lastYear"), r("lastWeek"), r("twoYears"))
        val last = mapOf("MPREb_lastYear" to now - 365 * day, "MPREb_lastWeek" to now - 7 * day, "MPREb_twoYears" to now - 730 * day)
        val dust = shelfDust(shelfAlbumsOf(a), last, now, daySeed = 1)
        assertEquals(listOf("twoYears", "lastYear"), dust.map { it.release.key })
    }

    @Test
    fun starredHasStarredAlbumsThenStarredArtistsTopAlbums() {
        val starredAlbum = artist("A", r("pinned"), r("other"), pinned = listOf("pinned"))
        val starredArtist = artist("B", r("hit"), favourite = true)
        val shelf = shelfStarred(shelfAlbumsOf(starredAlbum) + shelfAlbumsOf(starredArtist))
        assertEquals("pinned", shelf.first().release.key)
        assertTrue(shelf.any { it.release.key == "hit" })
        assertTrue(shelf.none { it.release.key == "other" && !it.artist.favourite && !it.pinned && it.rank == null })
    }

    @Test
    fun losslessIsWhatTheAddonIsKnownToHave() {
        val a = artist("Prince", r("x"), r("y"))
        assertEquals(listOf("y"), shelfLossless(shelfAlbumsOf(a), setOf("prince|y")).map { it.release.key })
    }

    @Test
    fun unplayedIsWhatTheLogNeverSawAndDropsUnplayable() {
        val a = artist("Prince", r("one"), r("two"), r("three"), r("gone", yt = null))
        val unplayed = shelfUnplayed(shelfAlbumsOf(a), mapOf("MPREb_two" to 3), daySeed = 1)
        assertEquals(setOf("one", "three"), unplayed.map { it.release.key }.toSet())
    }

    @Test
    fun likedAndListenAgainComeFromTheAccount() {
        val lists = listOf(
            com.nuvio.app.features.music.MusicPlaylistSummary("PLx", "Mine", null, null),
            com.nuvio.app.features.music.MusicPlaylistSummary("LM", "Liked Music", null, null),
        )
        assertEquals(listOf("LM"), shelfLiked(lists).map { it.playlistId })
        val home = listOf(
            com.nuvio.app.features.music.MusicHomeShelf("Mixed for you", listOf(lists[0])),
            com.nuvio.app.features.music.MusicHomeShelf("Listen again", listOf(lists[1], lists[1])),
        )
        assertEquals(listOf("LM"), shelfListenAgain(home).map { it.playlistId })
    }
}
