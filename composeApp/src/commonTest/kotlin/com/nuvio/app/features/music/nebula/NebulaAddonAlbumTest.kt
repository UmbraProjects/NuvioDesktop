package com.nuvio.app.features.music.nebula

import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.MusicLibraryState
import com.nuvio.app.features.music.library.ReleaseArtwork
import com.nuvio.app.features.music.library.ReleaseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonAlbumIdTest {
    @Test
    fun anIdRoundTripsWhateverItsPartsContain() {
        val refs = listOf(
            AddonAlbumRef("123", "UCabc", "mb-1"),
            AddonAlbumRef("a:b|c d", "UC:x", "Björk – Homogénic|Album"),
            AddonAlbumRef("日本語", "UC%41", "100% & more"),
        )
        for (ref in refs) {
            val id = addonAlbumId(ref)
            assertTrue(id.startsWith(ADDON_ALBUM_PREFIX))
            assertEquals(ref, parseAddonAlbumId(id), id)
        }
    }

    @Test
    fun anythingElseIsNotAnAddonId() {
        assertNull(parseAddonAlbumId("MPREb_abc"))
        assertNull(parseAddonAlbumId("addon:a:b"))
        assertNull(parseAddonAlbumId("addon:a:b:c:d"))
        assertNull(parseAddonAlbumId("addon:a::c"))
        assertNull(parseAddonAlbumId("addon:a:b:%zz"))
        assertNull(parseAddonAlbumId("addon:a:b:%4"))
        assertNull(parseAddonAlbumId("nebula:a:b:c"))
    }

    @Test
    fun decodingReversesEncoding() {
        for (text in listOf("plain", "a b", "é ü 日本", "50%/50&=?", "a:b|c")) {
            assertEquals(text, urlDecodeQuery(urlEncodeQuery(text)), text)
        }
        assertNull(urlDecodeQuery("%"))
        assertNull(urlDecodeQuery("%g1"))
        assertNull(urlDecodeQuery("é"))
    }
}

class AddonAlbumBuildTest {
    private val ref = AddonAlbumRef("77", "UCartist", "rg-1")
    private val detail = NebulaAlbum(
        NebulaAlbumSummary("77", "Addon Title", "Addon Artist", 2, 1999),
        listOf(
            NebulaTrack("t1", "One", "", 100, null),
            NebulaTrack("t2", "Two", "Guest", 200, null),
            NebulaTrack("t3", "Three", "the artist", 300, null),
        ),
    )
    private val release = LibraryRelease(
        key = "rg-1", title = "Library Title", kind = ReleaseKind.EP, year = "2001",
        art = ReleaseArtwork(covers = listOf("https://img/cover.jpg")),
    )
    private val artist = LibraryArtist(id = "UCartist", name = "The Artist", releases = listOf(release))

    @Test
    fun theLibraryWinsAndTracksPointAtTheAddon() {
        val album = buildAddonAlbum(ref, detail, artist, release)
        assertEquals(addonAlbumId(ref), album.browseId)
        assertEquals("Library Title", album.title)
        assertEquals("2001", album.year)
        assertEquals("EP", album.kind)
        assertEquals("https://img/cover.jpg", album.thumbnailUrl)
        assertEquals("UCartist", album.artists.single().browseId)
        assertEquals(listOf("nebula:t1", "nebula:t2", "nebula:t3"), album.tracks.map { it.videoId })
        assertEquals(listOf(1, 2, 3), album.tracks.map { it.trackNumber })
        assertTrue(album.tracks.all { it.isAvailable })
        assertNull(album.audioPlaylistId)
    }

    @Test
    fun blankOrSameArtistTracksUseTheAlbumArtistAndGuestsKeepTheirName() {
        val album = buildAddonAlbum(ref, detail, artist, release)
        assertEquals("The Artist", album.tracks[0].artists.single().name)
        assertEquals("Guest", album.tracks[1].artists.single().name)
        assertNull(album.tracks[1].artists.single().browseId)
        assertEquals("UCartist", album.tracks[2].artists.single().browseId)
    }

    @Test
    fun theAddonStandsInWhenTheLibraryIsGone() {
        val album = buildAddonAlbum(ref, detail, null, null)
        assertEquals("Addon Title", album.title)
        assertEquals("1999", album.year)
        assertEquals("Album", album.kind)
        assertNull(album.thumbnailUrl)
        assertEquals("Addon Artist", album.artists.single().name)
        assertNull(album.artists.single().browseId)
    }
}

class AddonAlbumLookupTest {
    private val release = LibraryRelease(key = "rg-1", title = "Gap", kind = ReleaseKind.Album, ytBrowseId = null)
    private val other = LibraryRelease(key = "rg-2", title = "On YT", kind = ReleaseKind.Album, ytBrowseId = "MPREb_2")
    private val artist = LibraryArtist(id = "UCartist", name = "A", releases = listOf(release, other))

    @Test
    fun anArtistFindsItsReleaseByEitherKindOfId() {
        val id = addonAlbumId(AddonAlbumRef("77", "UCartist", "rg-1"))
        assertEquals("rg-1", artist.releaseOfAlbum(id)?.key)
        assertEquals("rg-2", artist.releaseOfAlbum("MPREb_2")?.key)
        assertNull(artist.releaseOfAlbum(addonAlbumId(AddonAlbumRef("77", "UCartist", "missing"))))
        assertNull(artist.releaseOfAlbum("MPREb_nope"))
    }

    @Test
    fun theStateFindsTheOwningArtistForAnAddonId() {
        val state = MusicLibraryState(artists = listOf(artist))
        assertNotNull(state.artistOfAlbum(addonAlbumId(AddonAlbumRef("77", "UCartist", "rg-1"))))
        assertNull(state.artistOfAlbum(addonAlbumId(AddonAlbumRef("77", "UCother", "rg-1"))))
        assertEquals("UCartist", state.artistOfAlbum("MPREb_2")?.id)
    }
}
