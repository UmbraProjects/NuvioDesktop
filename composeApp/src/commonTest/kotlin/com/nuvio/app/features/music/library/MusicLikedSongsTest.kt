package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicLikedSongsTest {
    private fun track(id: String) = MusicTrack(id, "T $id", emptyList(), 200, 1, true)

    @Test
    fun aSongIsLikedWhenTheAccountHoldsIt() {
        assertTrue(MusicLikedSongs.isLiked("a", listOf(track("a")), emptyMap()))
        assertFalse(MusicLikedSongs.isLiked("b", listOf(track("a")), emptyMap()))
    }

    @Test
    fun thisSessionsChangeWinsOverTheAccountList() {
        assertFalse(MusicLikedSongs.isLiked("a", listOf(track("a")), mapOf("a" to false)))
        assertTrue(MusicLikedSongs.isLiked("b", listOf(track("a")), mapOf("b" to true)))
    }
}
