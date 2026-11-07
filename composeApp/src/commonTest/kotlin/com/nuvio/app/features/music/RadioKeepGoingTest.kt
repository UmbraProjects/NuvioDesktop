package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioKeepGoingTest {
    private fun track(id: String) = MusicTrack(id, id, emptyList(), 0, 1, true)

    @Test
    fun onlyRadioQueuesAreAsked() {
        assertTrue(isRadioQueue("radio:abcdefghijk"))
        assertTrue(isRadioQueue("artist-radio:UCx"))
        assertTrue(isRadioQueue("list:RDTMAK5uy_discover"))
        assertFalse(isRadioQueue("list:PLfixed"))
        assertFalse(isRadioQueue("list:RDCLAK5uy_mood"))
        assertFalse(isRadioQueue("MPREb_album"))
        assertFalse(isRadioQueue(null))
    }

    @Test
    fun theQuestionComesInTheLastThreeTracks() {
        assertFalse(nearRadioEnd("radio:x", 96, 100))
        assertTrue(nearRadioEnd("radio:x", 97, 100))
        assertTrue(nearRadioEnd("radio:x", 99, 100))
        assertFalse(nearRadioEnd("radio:x", -1, 100))
        assertFalse(nearRadioEnd("MPREb_a", 99, 100))
    }

    @Test
    fun aTopUpSkipsWhatAlreadyPlayed() {
        val more = freshTracks(listOf(track("a"), track("b"), track("b"), track("c")), listOf(track("a")))
        assertEquals(listOf("b", "c"), more.map { it.videoId })
    }

    private fun queue(id: String, title: String, kind: String?) = MusicAlbum(id, title, emptyList(), null, kind, null, emptyList(), null)

    @Test
    fun theBarSaysWhichRadioOrPlaylistItIsPlayingFrom() {
        assertEquals("From Prince radio", playingFromLabel(queue("artist-radio:UCx", "Prince radio", "Radio")))
        assertEquals("From Road trip", playingFromLabel(queue("list:PLx", "Road trip", "Playlist")))
        assertEquals(null, playingFromLabel(queue("MPREb_a", "Purple Rain", "Album")))
        assertEquals(null, playingFromLabel(null))
    }
}
