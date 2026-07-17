package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSourceFailureTest {

    @Test
    fun `recognizes AIOStreams debrid rate limit placeholder`() {
        val url = "https://aio.myaio.xyz/cache/hash/seg.ts?loop=1&title=Too+many+requests&" +
            "body=Your+debrid+provider+is+rate-limiting+requests."

        assertEquals(PlaybackSourceFailure.DebridRateLimited, playbackSourceFailure(url))
    }

    @Test
    fun `does not classify normal AIOStreams playback URL as an error`() {
        assertNull(playbackSourceFailure("https://aio.myaio.xyz/cache/hash/video.mkv"))
    }

    @Test
    fun `recognizes FFmpeg HTTP 429 playback failure`() {
        assertEquals(
            PlaybackSourceFailure.DebridRateLimited,
            playbackErrorFailure("https: HTTP error 429 Too Many Requests"),
        )
    }

    @Test
    fun `recognizes HTTP 429 attached to a post-load seek failure`() {
        assertEquals(
            PlaybackSourceFailure.DebridRateLimited,
            playbackErrorFailure(
                "https: HTTP error 429 Too Many Requests; Seek failed (to 738209342, size 162)",
            ),
        )
    }

    @Test
    fun `does not classify an unrelated playback error as rate limited`() {
        assertNull(playbackErrorFailure("Playback loading failed: loading failed"))
    }
}
