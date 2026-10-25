package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.identity.playingArtist
import com.nuvio.app.features.music.identity.viewedArtist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules behind "one artist page, two doors": what a previewed (unsaved) artist is and is not. */
class MusicPreviewTest {
    private fun release(key: String, yt: String?) =
        LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, ytBrowseId = yt)

    private fun artist(id: String, name: String = id, releases: List<LibraryRelease> = emptyList()) =
        LibraryArtist(id = id, name = name, releases = releases)

    @Test
    fun aPreviewedArtistIsReadableButNotInTheLibrary() {
        val state = MusicLibraryState(preview = mapOf("UC1" to artist("UC1")))
        assertFalse(state.contains("UC1"))
        assertNull(state.artist("UC1"))
        assertEquals("UC1", state.artistOrPreview("UC1")?.id)
        assertNull(state.artistOrPreview("UC404"))
    }

    @Test
    fun aSavedArtistWinsOverItsPreviewedCopy() {
        val saved = artist("UC1", name = "Saved")
        val previewed = artist("UC1", name = "Previewed")
        val state = MusicLibraryState(artists = listOf(saved), preview = mapOf("UC1" to previewed))
        assertTrue(state.contains("UC1"))
        assertEquals("Saved", state.artistOrPreview("UC1")?.name)
    }

    @Test
    fun anAlbumIsFoundOnAPreviewedArtistToo() {
        val previewed = artist("UC1", releases = listOf(release("r", yt = "MPREb_1")))
        val state = MusicLibraryState(preview = mapOf("UC1" to previewed))
        assertEquals("UC1", state.artistOfAlbum("MPREb_1")?.id)
        assertNull(state.artistOfAlbum("MPREb_404"))
    }

    @Test
    fun theAccentFollowsAPreviewedArtistLikeASavedOne() {
        val state = MusicLibraryState(preview = mapOf("UC1" to artist("UC1", name = "Hozier", releases = listOf(release("r", "MPREb_1")))))
        assertEquals("UC1", viewedArtist(state, "UC1", null)?.id)
        assertEquals("UC1", viewedArtist(state, null, "MPREb_1")?.id)
        val track = MusicTrack("v", "t", listOf(MusicArtistRef("Hozier", "UC1")), 100, 1, true)
        assertEquals("UC1", playingArtist(track, null, state)?.id)
        val album = MusicAlbum("MPREb_1", "A", listOf(MusicArtistRef("hozier")), null, null, null, emptyList(), null)
        assertEquals("UC1", playingArtist(null, album, state)?.id)
    }

    @Test
    fun theOldestPreviewIsDroppedPastTheLimit() {
        var previews = emptyMap<String, LibraryArtist>()
        listOf("a", "b", "c").forEach { previews = boundedPreview(previews, artist(it), max = 2) }
        assertEquals(listOf("b", "c"), previews.keys.toList())
    }

    @Test
    fun touchingAPreviewAgainKeepsItAndReplacesItsData() {
        var previews = emptyMap<String, LibraryArtist>()
        previews = boundedPreview(previews, artist("a"), max = 2)
        previews = boundedPreview(previews, artist("b"), max = 2)
        previews = boundedPreview(previews, artist("a", name = "Filled in"), max = 2)
        previews = boundedPreview(previews, artist("c"), max = 2)
        // "a" was touched after "b", so "b" is the one that goes.
        assertEquals(listOf("a", "c"), previews.keys.toList())
        assertEquals("Filled in", previews["a"]?.name)
    }
}
