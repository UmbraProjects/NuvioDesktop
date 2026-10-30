package com.nuvio.app.features.music.nebula

import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.ReleaseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 24L * 60 * 60 * 1000

class AddonAvailabilityCacheTest {
    private fun summary(id: String = "n1") = NebulaAlbumSummary(id, "Album", "Artist", 12, 2001)

    @Test fun foundIsTrustedForTwoWeeks() {
        val e = addonFound(1000, summary())
        assertTrue(e.has)
        assertEquals("n1", e.albumId)
        assertEquals(12, e.trackCount)
        assertTrue(e.isFresh(1000 + ADDON_FOUND_TTL_MS - 1))
        assertFalse(e.isFresh(1000 + ADDON_FOUND_TTL_MS))
    }

    @Test fun missBackoffGrowsThenStops() {
        val first = addonMissed(0, null)
        assertFalse(first.has)
        assertEquals(1, first.misses)
        assertTrue(first.isFresh(3 * DAY - 1))
        assertFalse(first.isFresh(3 * DAY))

        val second = addonMissed(0, first)
        assertEquals(2, second.misses)
        assertTrue(second.isFresh(7 * DAY - 1))
        assertFalse(second.isFresh(7 * DAY))

        val third = addonMissed(0, second)
        assertEquals(3, third.misses)
        assertTrue(third.isFresh(14 * DAY - 1))
        assertFalse(third.isFresh(14 * DAY))

        // Further misses stay at the longest backoff.
        assertEquals(14 * DAY, AddonAvailability(savedAt = 0, misses = 10).ttlMs())
    }

    @Test fun aMissAfterAMatchStartsTheCountAgain() {
        assertEquals(1, addonMissed(5, addonFound(0, summary())).misses)
    }

    @Test fun aClockThatWentBackwardsIsNotTrusted() {
        assertFalse(addonFound(1000, summary()).isFresh(0))
        assertFalse(addonMissed(1000, null).isFresh(999))
    }

    @Test fun keyIncludesTheArtist() {
        assertNotEquals(addonCacheKey("UC1", "Album|album"), addonCacheKey("UC2", "Album|album"))
        assertEquals(addonCacheKey("UC1", "mb-1"), addonCacheKey("UC1", "mb-1"))
    }

    @Test fun roundTripKeepsEveryField() {
        val book = AddonAvailabilityBook()
            .withEntry("k", AddonAvailability(5, "id", "T", 9, 0))
            .withEntry("m", addonMissed(7, null))
        assertEquals(book, decodeAddonAvailability(encodeAddonAvailability(book)))
    }

    @Test fun badJsonGivesEmptyBook() {
        assertTrue(decodeAddonAvailability("{nope").entries.isEmpty())
        assertTrue(decodeAddonAvailability(null).entries.isEmpty())
        assertTrue(decodeAddonAvailability("  ").entries.isEmpty())
    }

    @Test fun withoutRemovesOnlyThatKey() {
        val book = AddonAvailabilityBook()
            .withEntry("a", addonMissed(1, null))
            .withEntry("b", addonMissed(2, null))
            .without("a")
        assertFalse(book.entries.containsKey("a"))
        assertTrue(book.entries.containsKey("b"))
    }

    @Test fun pruningKeepsTheNewest() {
        var book = AddonAvailabilityBook()
        for (i in 0..ADDON_MAX_ENTRIES) book = book.withEntry("k$i", AddonAvailability(savedAt = i.toLong()))
        assertEquals(ADDON_MAX_ENTRIES, book.entries.size)
        assertFalse(book.entries.containsKey("k0"))
        assertTrue(book.entries.containsKey("k$ADDON_MAX_ENTRIES"))
    }
}

class AddonReleaseQueryTest {
    private val artist = LibraryArtist(id = "UC1", name = "Queen")

    @Test fun buildsTheQueryFromTheReleaseAndItsArtist() {
        val q = LibraryRelease(key = "mb-1", title = "Greatest Hits", kind = ReleaseKind.Album, releaseDate = "1981-10-26", trackCount = 17)
            .addonQuery(artist)
        assertEquals(addonCacheKey("UC1", "mb-1"), q.cacheKey)
        assertEquals("Greatest Hits", q.title)
        assertEquals("Queen", q.artist)
        assertEquals("1981", q.year)
        assertEquals(17, q.trackCount)
    }

    @Test fun theYearFieldWinsOverTheDateAndMissingOnesStayNull() {
        val withYear = LibraryRelease(key = "k", title = "T", kind = ReleaseKind.EP, year = "1999", releaseDate = "2001-01-01")
        assertEquals("1999", withYear.addonQuery(artist).year)
        val bare = LibraryRelease(key = "k", title = "T", kind = ReleaseKind.EP)
        assertNull(bare.addonQuery(artist).year)
        assertNull(bare.addonQuery(artist).trackCount)
    }

    @Test fun twoArtistsWithTheSameReleaseKeyDoNotShareAnAnswer() {
        val release = LibraryRelease(key = "greatest hits|album", title = "Greatest Hits", kind = ReleaseKind.Album)
        assertNotEquals(release.addonQuery(artist).cacheKey, release.addonQuery(LibraryArtist(id = "UC2", name = "ABBA")).cacheKey)
    }
}

class NebulaStrictMatchTest {
    private fun a(id: String, title: String, artist: String, n: Int? = null, y: Int? = null) =
        NebulaAlbumSummary(id, title, artist, n, y)

    @Test fun exactTitleAndArtistMatchesTheBestOne() {
        val m = strictNebulaMatch(
            listOf(a("1", "Abbey Road (Super Deluxe)", "The Beatles", 40), a("2", "Abbey Road", "The Beatles", 17, 1969)),
            "Abbey Road", "The Beatles", "1969", 17,
        )
        assertEquals("2", m?.id)
    }

    @Test fun aTitleThatOnlyContainsOursIsNotAMatch() {
        val candidates = listOf(a("1", "Abbey Road Sessions", "The Beatles"))
        // The looser ranking, used for a badge on a YouTube Music album, accepts it; the strict match does not.
        assertEquals(1, rankNebulaAlbums(candidates, "Abbey Road", "The Beatles", null, 0).size)
        assertNull(strictNebulaMatch(candidates, "Abbey Road", "The Beatles", null, 0))
    }

    @Test fun wrongArtistIsNotAMatch() {
        assertNull(strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen")), "Greatest Hits", "ABBA", null, 0))
    }

    @Test fun variousArtistsNeedTheYearOrTheCountToAgree() {
        val candidates = listOf(a("1", "Now 50", "Various Artists", 40, 2001))
        assertNull(strictNebulaMatch(candidates, "Now 50", "Various Artists", null, 0))
        assertEquals("1", strictNebulaMatch(candidates, "Now 50", "Various Artists", "2001", 0)?.id)
        assertEquals("1", strictNebulaMatch(candidates, "Now 50", "Various Artists", null, 40)?.id)
    }

    @Test fun anotherRecordWithTheSameTitleIsRejected() {
        // Year and track count both differ: a different "Greatest Hits".
        assertNull(strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen", 18, 2004)), "Greatest Hits", "Queen", "1981", 17))
    }

    @Test fun aRemasterWithTheSameCountStillMatches() {
        assertEquals("1", strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen", 17, 2011)), "Greatest Hits", "Queen", "1981", 17)?.id)
    }

    @Test fun anUnknownCountOrYearDoesNotReject() {
        assertEquals("1", strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen", null, 2011)), "Greatest Hits", "Queen", "1981", 17)?.id)
        assertEquals("1", strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen", 18, null)), "Greatest Hits", "Queen", "1981", 17)?.id)
        assertEquals("1", strictNebulaMatch(listOf(a("1", "Greatest Hits", "Queen", 18, 2004)), "Greatest Hits", "Queen", null, 0)?.id)
    }

    @Test fun skipsAConflictingCandidateForTheNextOne() {
        val m = strictNebulaMatch(
            listOf(a("1", "Greatest Hits", "Queen", 18, 2004), a("2", "Greatest Hits", "Queen")),
            "Greatest Hits", "Queen", "1981", 17,
        )
        assertEquals("2", m?.id)
    }

    @Test fun scoresAgreeWithTheRanking() {
        val candidates = listOf(a("1", "Abbey Road Sessions", "The Beatles"), a("2", "Abbey Road", "The Beatles", 17, 1969))
        val scored = scoreNebulaAlbums(candidates, "Abbey Road", "The Beatles", "1969", 17)
        assertEquals(listOf("2", "1"), scored.map { it.album.id })
        assertEquals(listOf(true, false), scored.map { it.exactTitle })
        assertEquals(80, scored.first().score)
        assertEquals(scored.map { it.album }, rankNebulaAlbums(candidates, "Abbey Road", "The Beatles", "1969", 17))
    }

    @Test fun blankTitleMatchesNothing() {
        assertNull(strictNebulaMatch(listOf(a("1", "Anything", "Anyone")), "()", "Anyone", null, 0))
    }
}
