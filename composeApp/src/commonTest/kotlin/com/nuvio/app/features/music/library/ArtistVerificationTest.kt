package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistVerificationTest {
    private val own = "UCaaaaaaaaaaaaaaaaaaaaaa"
    private val other = "UCbbbbbbbbbbbbbbbbbbbbbb"

    private fun link(url: String) = ArtistLink("YouTube", url)

    private val realAlbums = listOf("Santana", "Abraxas", "Santana III", "Caravanserai", "Welcome", "Amigos", "Moonflower")
    private val namesakeSingles = listOf("A entrevista", "Amante sentimental", "De boca em boca", "Doce Pecado", "Maromba")

    @Test
    fun aChannelMusicBrainzListsIsConfirmedWhateverTheTitles() {
        val links = listOf(link("https://music.youtube.com/channel/$own"))
        assertEquals(ChannelVerdict.Confirmed, verifyChannel(own, links, namesakeSingles, realAlbums))
    }

    @Test
    fun sharedTitlesConfirmEvenWithoutALink() {
        assertEquals(ChannelVerdict.Confirmed, verifyChannel(own, emptyList(), realAlbums.take(5) + "Extra", realAlbums))
    }

    @Test
    fun aNamesakeWithAnotherListedChannelIsAMismatchPointingAtIt() {
        val links = listOf(link("https://www.youtube.com/channel/$other"), link("https://www.youtube.com/@santana"))
        assertEquals(ChannelVerdict.Mismatch(other), verifyChannel(own, links, namesakeSingles, realAlbums))
    }

    @Test
    fun musicYoutubeChannelsAreSuggestedFirst() {
        val third = "UCcccccccccccccccccccccc"
        val links = listOf(link("https://www.youtube.com/channel/$other"), link("https://music.youtube.com/channel/$third"))
        assertEquals(listOf(third, other), youTubeChannelIds(links))
    }

    @Test
    fun noLinkAndNothingInCommonOverARealDiscographyIsAMismatchWithNoSuggestion() {
        assertEquals(ChannelVerdict.Mismatch(null), verifyChannel(own, emptyList(), namesakeSingles, realAlbums))
    }

    @Test
    fun tooLittleToCompareIsTrusted() {
        assertEquals(ChannelVerdict.Unconfirmed, verifyChannel(own, emptyList(), listOf("One", "Two"), realAlbums))
        assertEquals(ChannelVerdict.Unconfirmed, verifyChannel(own, emptyList(), namesakeSingles, listOf("Only", "Two")))
        // Three titles each is enough to doubt a listed channel, but not to doubt an unlisted one.
        assertEquals(ChannelVerdict.Unconfirmed, verifyChannel(own, emptyList(), namesakeSingles.take(3), realAlbums.take(3)))
        assertEquals(ChannelVerdict.Mismatch(other), verifyChannel(own, listOf(link("https://youtube.com/channel/$other")), namesakeSingles.take(3), realAlbums.take(3)))
    }

    @Test
    fun overlapIgnoresEditionWordsAndAccents() {
        assertEquals(1.0, releaseTitleOverlap(listOf("Abraxas (Remastered)", "Café"), listOf("Abraxas", "Cafe Deluxe Edition")), 1e-9)
        assertEquals(0.0, releaseTitleOverlap(emptyList(), listOf("A")), 1e-9)
        assertEquals(0.5, releaseTitleOverlap(listOf("A", "B"), listOf("a")), 1e-9)
    }

    @Test
    fun handlesAndUserUrlsCarryNoChannelId() {
        assertTrue(youTubeChannelIds(listOf(link("https://www.youtube.com/@santana"), link("https://www.youtube.com/user/santana"))).isEmpty())
    }

    @Test
    fun storedPlaceholderDatesAreCleanedOnLoad() {
        fun r(key: String, year: String?, date: String?) = LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, year = year, releaseDate = date)
        val artist = LibraryArtist(
            id = "UC1",
            name = "A",
            releases = listOf(r("zero", "0", "0000-00-00"), r("ok", "2014", "2014-05-20"), r("none", null, null)),
        )
        val clean = artist.withSaneDates()
        assertNull(clean.releases[0].year)
        assertNull(clean.releases[0].releaseDate)
        assertEquals("2014-05-20", clean.releases[1].releaseDate)
        assertNull(clean.releases[2].year)
        // Nothing to clean: the very same object comes back.
        assertTrue(clean.withSaneDates() === clean)
    }

    @Test
    fun mergedReleasesNeverCarryPlaceholderDates() {
        val merged = mergeReleases(
            ReleaseSources(
                deezer = listOf(DeezerAlbum(id = 1, title = "Mystery", cover = null, recordType = "album", releaseDate = "0000-00-00", explicit = null)),
                musicBrainz = listOf(MbReleaseGroup(id = "g1", title = "Real", primaryType = "Album", secondaryTypes = emptyList(), firstReleaseDate = "1972-06-01")),
            ),
        )
        val mystery = merged.first { it.title == "Mystery" }
        assertNull(mystery.year)
        assertNull(mystery.releaseDate)
        val real = merged.first { it.title == "Real" }
        assertEquals("1972", real.year)
        assertEquals("1972-06-01", real.releaseDate)
        // Deezer's album page can also report the placeholder; it must not replace an unknown date.
        val detailed = mystery.withDeezerDetail(DeezerAlbumDetail(label = null, trackCount = 5, durationSeconds = null, genres = emptyList(), upc = null, releaseDate = "0000-00-00"))
        assertNull(detailed.releaseDate)
    }
}
