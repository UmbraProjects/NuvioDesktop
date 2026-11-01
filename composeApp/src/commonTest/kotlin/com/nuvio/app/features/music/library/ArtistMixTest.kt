package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicTrack
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistMixTest {
    private fun release(key: String, yt: String? = "MPREb_$key") =
        LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, year = "2000", releaseDate = null, trackCount = 10, ytBrowseId = yt)

    private fun track(videoId: String, title: String, available: Boolean = true) = MusicTrack(
        videoId = videoId, title = title, artists = emptyList(), durationSeconds = 200, trackNumber = 1, isAvailable = available,
    )

    private fun album(id: String, thumb: String?, vararg tracks: MusicTrack) = MusicAlbum(
        browseId = id, title = "Album $id", artists = emptyList(), year = null, kind = "Album", thumbnailUrl = thumb,
        tracks = tracks.toList(), audioPlaylistId = null,
    )

    private val artist = LibraryArtist(id = "UC1", name = "Michael Jackson")

    @Test
    fun mixHoldsEveryAvailableTrackOnce() {
        val a = album("A", "artA", track("v1", "Thriller"), track("v2", "Beat It"), track("v3", "Gone", available = false))
        val b = album("B", "artB", track("v1", "Thriller"), track("v4", "Thriller (Remastered)"), track("v5", "thriller"), track("v6", "Bad"))
        val mix = assertNotNull(buildArtistMix(artist, listOf(a, b)))
        // v1 repeats by id, "thriller" repeats by title, the greyed-out track is left out.
        assertEquals(listOf("v1", "v2", "v4", "v6"), mix.tracks.map { it.videoId })
        assertEquals(listOf(1, 2, 3, 4), mix.tracks.map { it.trackNumber })
    }

    @Test
    fun tracksKeepTheirOwnCoverAndAlbum() {
        val a = album("A", "artA", track("v1", "One"))
        val b = album("B", null, track("v2", "Two"))
        val mix = assertNotNull(buildArtistMix(artist, listOf(a, b)))
        assertEquals("artA", mix.tracks[0].coverUrl)
        assertEquals("Album A", mix.tracks[0].albumTitle)
        assertEquals("A", mix.tracks[0].albumBrowseId)
        assertNull(mix.tracks[1].coverUrl)
        assertEquals("B", mix.tracks[1].albumBrowseId)
    }

    @Test
    fun mixIsNamedAfterTheArtistAndIdentifiable() {
        val mix = assertNotNull(buildArtistMix(artist, listOf(album("A", "artA", track("v1", "One")))))
        assertEquals("Michael Jackson, shuffled", mix.title)
        assertEquals(artistMixAlbumId("UC1"), mix.browseId)
        assertEquals("artA", mix.thumbnailUrl)
    }

    @Test
    fun nothingPlayableMakesNoMix() {
        assertNull(buildArtistMix(artist, emptyList()))
        assertNull(buildArtistMix(artist, listOf(album("A", null, track("v1", "One", available = false)))))
    }

    @Test
    fun pickedAlbumsAreDistinctPlayableAndCapped() {
        val releases = listOf(release("a"), release("b", yt = null), release("c"), release("c2", yt = "MPREb_c"))
        assertEquals(listOf("MPREb_a", "MPREb_c"), pickMixAlbumIds(releases))

        val many = (1..100).map { release("r$it") }
        val picked = pickMixAlbumIds(many, limit = 40, random = Random(7))
        assertEquals(40, picked.size)
        assertEquals(40, picked.toSet().size)
        assertTrue(picked.all { id -> many.any { it.ytBrowseId == id } })
    }
}
