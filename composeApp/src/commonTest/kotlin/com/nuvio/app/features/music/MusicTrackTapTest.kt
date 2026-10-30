package com.nuvio.app.features.music

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicTrackTapTest {
    private fun album(id: String = "MPREb_a", available: List<Boolean> = listOf(true, true, true)) = MusicAlbum(
        id, "Album", emptyList(), "2020", "Album", null,
        available.mapIndexed { i, ok -> MusicTrack("id$i", "Track $i", emptyList(), 200, i + 1, ok) }, null,
    )

    private fun playing(album: MusicAlbum, mode: MusicPlayMode = MusicPlayMode.Audio) =
        MusicPlayerState(album = album, track = album.tracks.first(), trackIndex = 0, mode = mode)

    @Test
    fun tapOnTheLoadedAlbumJumpsInsideItsQueue() {
        val a = album()
        assertEquals(
            MusicTrackTap.JumpInQueue(2),
            musicTrackTap(playing(a, MusicPlayMode.Video), a, 2, MusicPlayMode.Audio),
        )
    }

    @Test
    fun tapWithNothingLoadedStartsTheAlbumInTheChosenMode() {
        assertEquals(
            MusicTrackTap.StartAlbum(1, MusicPlayMode.Video),
            musicTrackTap(MusicPlayerState(), album(), 1, MusicPlayMode.Video),
        )
    }

    @Test
    fun tapWhileAnotherAlbumPlaysStartsThisOne() {
        val other = album("MPREb_other")
        assertEquals(
            MusicTrackTap.StartAlbum(0, MusicPlayMode.Audio),
            musicTrackTap(playing(other), album(), 0, MusicPlayMode.Audio),
        )
    }

    @Test
    fun holdsNeedsAnActiveTrack() {
        val a = album()
        assertTrue(playing(a).holds(a))
        assertFalse(MusicPlayerState(album = a).holds(a))
    }

    @Test
    fun shuffleStartSkipsGreyedOutTracks() {
        val a = album(available = listOf(false, true, false, true))
        repeat(50) { seed ->
            assertTrue(randomPlayableIndex(a, Random(seed)) in listOf(1, 3))
        }
        assertEquals(0, randomPlayableIndex(album(available = listOf(false, false))))
    }
}
