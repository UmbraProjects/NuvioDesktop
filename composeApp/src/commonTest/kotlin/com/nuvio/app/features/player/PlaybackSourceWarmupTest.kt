package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSourceWarmupTest {

    private val notResolvedByDebrid: (StreamItem) -> Boolean = { false }

    @Test
    fun anAddonUrlIsWarmedWithTheHeadersThePlayerWouldSend() {
        val stream = StreamItem(
            name = "AIOStreams",
            url = "https://aio.example/api/v1/usenet/playback/abc",
            addonName = "AIO",
            addonId = "addon:aio",
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://aio.example")),
            ),
        )
        val warmable = PlaybackSourceWarmup.warmableSource(stream, notResolvedByDebrid)
        assertEquals("https://aio.example/api/v1/usenet/playback/abc", warmable?.first)
        assertEquals(sanitizePlaybackHeaders(mapOf("Referer" to "https://aio.example")), warmable?.second)
    }

    @Test
    fun aStreamTheAppResolvesThroughDebridIsLeftToTheDebridPreparer() {
        val stream = StreamItem(url = "https://addon.example/resolve/xyz", addonName = "Addon", addonId = "addon:x")
        assertNull(PlaybackSourceWarmup.warmableSource(stream) { true })
    }

    @Test
    fun torrentsAndNonHttpLinksAreNotWarmed() {
        val magnet = StreamItem(url = "magnet:?xt=urn:btih:abcdef", addonName = "Addon", addonId = "addon:x")
        assertNull(PlaybackSourceWarmup.warmableSource(magnet, notResolvedByDebrid))
        val local = StreamItem(url = "file:///C:/Videos/episode.mkv", addonName = "Addon", addonId = "addon:x")
        assertNull(PlaybackSourceWarmup.warmableSource(local, notResolvedByDebrid))
    }

    @Test
    fun youTubeStreamsTakeTheirOwnPath() {
        val stream = StreamItem(ytId = "dQw4w9WgXcQ", url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", addonName = "Addon", addonId = "addon:x")
        assertNull(PlaybackSourceWarmup.warmableSource(stream, notResolvedByDebrid))
    }
}
