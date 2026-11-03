package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsDemuxerOptionTest {

    @Test
    fun hlsPlaylistsGetAConnectionPerRequest() {
        assertEquals(
            "demuxer-lavf-o=http_persistent=0,extension_picky=0",
            hlsDemuxerMpvOption("https://cdn.example/tv/1/S03/E01/master.m3u8"),
        )
        assertEquals(
            "demuxer-lavf-o=http_persistent=0,extension_picky=0",
            hlsDemuxerMpvOption("https://cdn.example/v/index.m3u8?token=abc"),
        )
    }

    @Test
    fun otherSourcesAreLeftAlone() {
        assertNull(hlsDemuxerMpvOption("https://cdn.example/movie.mkv"))
        assertNull(hlsDemuxerMpvOption("https://cdn.example/manifest.mpd"))
    }
}
