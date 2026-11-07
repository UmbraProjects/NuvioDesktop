package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicPlayLogTest {
    private fun play(at: Long, album: String?, source: String? = album, kind: String? = "Album", title: String = "t$at") =
        PlayEntry(atMs = at, videoId = "v$at", title = title, albumBrowseId = album, albumTitle = album, sourceId = source, sourceTitle = source, sourceKind = kind)

    @Test
    fun lastPlayedAndCountsAreByAlbum() {
        val log = listOf(play(30, "MPREb_a"), play(20, "MPREb_b"), play(10, "MPREb_a"))
        assertEquals(mapOf("MPREb_a" to 30L, "MPREb_b" to 20L), lastPlayedByAlbum(log))
        assertEquals(mapOf("MPREb_a" to 2, "MPREb_b" to 1), playsByAlbum(log))
    }

    @Test
    fun recentPlaysListEachAlbumOrQueueOnceNewestFirst() {
        val log = listOf(
            play(50, "MPREb_x", source = "list:RDTMAK5uy_discover", kind = "Radio"),
            play(40, "MPREb_a"),
            play(30, "MPREb_y", source = "list:RDTMAK5uy_discover", kind = "Radio"),
            play(20, "MPREb_a"),
            play(10, null, source = "radio:abcdefghijk", kind = "Radio"),
            play(5, null, source = "home:remixes", kind = "Mix"),
        )
        val recent = recentPlays(log)
        assertEquals(listOf("queue:list:RDTMAK5uy_discover", "album:MPREb_a", "queue:radio:abcdefghijk"), recent.map { it.key })
        val mix = recent.first() as RecentPlay.Queue
        assertEquals("RDTMAK5uy_discover", mix.playlistId)
        assertTrue((recent[2] as RecentPlay.Queue).playlistId == null, "a song radio has no list id")
    }
}
