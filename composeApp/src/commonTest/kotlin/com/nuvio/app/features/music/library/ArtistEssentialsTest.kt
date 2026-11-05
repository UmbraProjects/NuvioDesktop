package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistEssentialsTest {
    private val pac = MusicArtistRef("2Pac", "UCpac")

    private fun track(id: String, title: String, seconds: Int, available: Boolean = true) =
        MusicTrack(id, title, listOf(pac), seconds, 1, available)

    private val eyez = MusicAlbum(
        browseId = "MPREb_eyez", title = "All Eyez On Me", artists = listOf(pac), year = "1996", kind = "Album",
        thumbnailUrl = "https://img/eyez", audioPlaylistId = null,
        tracks = listOf(track("amb", "Ambitionz Az A Ridah", 279), track("cal", "California Love (Remix)", 386)),
    )

    private val top = listOf(
        MusicTopSong("cal-video", "California Love (Remix)", "MPREb_eyez"),
        MusicTopSong("chg", "Changes", "MPREb_hits"),
        MusicTopSong("cal-again", "California Love", "MPREb_other"),
        MusicTopSong("amb", "Ambitionz Az A Ridah", "MPREb_eyez"),
    )

    @Test
    fun albumsAreOpenedOnceInSongOrder() {
        assertEquals(listOf("MPREb_eyez", "MPREb_hits", "MPREb_other"), essentialAlbumIds(top))
        assertEquals(listOf("MPREb_eyez"), essentialAlbumIds(top, limit = 1))
    }

    @Test
    fun rowsAreFilledFromTheirAlbumsAndEachSongComesOnce() {
        val rows = essentialRows(top, mapOf("MPREb_eyez" to eyez))
        assertEquals(listOf("California Love (Remix)", "Changes", "Ambitionz Az A Ridah"), rows.map { it.title })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
        // Matched by title on its album, so it plays the album's audio track, with its length and cover.
        assertEquals("cal", rows[0].videoId)
        assertEquals(386, rows[0].durationSeconds)
        assertEquals("All Eyez On Me", rows[0].albumTitle)
        assertEquals("https://img/eyez", rows[0].coverUrl)
        // Its album was not opened: shown as YouTube Music lists it, with no length.
        assertEquals("chg", rows[1].videoId)
        assertEquals(0, rows[1].durationSeconds)
        assertNull(rows[1].albumTitle)
        assertEquals("MPREb_hits", rows[1].albumBrowseId)
    }

    @Test
    fun theMixKeepsOrderCoversAndLeavesOutGreyedSongs() {
        val greyed = eyez.copy(tracks = listOf(track("amb", "Ambitionz Az A Ridah", 279, available = false), track("cal", "California Love (Remix)", 386)))
        val rows = essentialRows(top, mapOf("MPREb_eyez" to greyed))
        val mix = essentialsMix("UCpac", "2Pac", "https://img/pac", rows)!!
        assertEquals("essentials:UCpac", mix.browseId)
        assertEquals(listOf("cal", "chg"), mix.tracks.map { it.videoId })
        assertEquals(listOf(1, 2), mix.tracks.map { it.trackNumber })
        assertEquals("All Eyez On Me", mix.tracks[0].albumTitle)
        assertEquals("2Pac", mix.tracks[1].artists.single().name)
        assertEquals("https://img/pac", mix.thumbnailUrl)
    }

    @Test
    fun nothingToPlayGivesNoMix() {
        assertNull(essentialsMix("UCpac", "2Pac", null, emptyList()))
    }
}
