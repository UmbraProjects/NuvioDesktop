package com.nuvio.app.features.iptv

import com.nuvio.app.features.watchprogress.isLiveEventContentType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvPlaybackSessionTest {

    private val playlist = IptvPlaylist(
        id = "session-p", type = IptvPlaylistType.Xtream, name = "Home",
        server = "http://host:8080", username = "user", password = "pass",
    )
    private val channel = IptvChannel(uid = 3, key = "42", num = 4, name = "News", logo = "http://l/x.png", categoryId = "c", streamId = "42")

    @AfterTest
    fun clear() = IptvPlaybackSession.clear()

    @Test
    fun `launch is live, untracked and identifies the channel`() {
        val launch = assertNotNull(IptvPlaybackSession.launchFor(playlist, channel))
        assertEquals("http://host:8080/live/user/pass/42.ts", launch.sourceUrl)
        assertEquals(IPTV_META_TYPE, launch.parentMetaType)
        assertTrue(launch.contentType.isLiveEventContentType(), "keeps no progress and sends no scrobbles")
        assertTrue(launch.disableProgressTracking)
        assertEquals("iptv:session-p:42", launch.parentMetaId)
        assertEquals("News", launch.title)
        assertEquals(IPTV_USER_AGENT, launch.sourceHeaders["User-Agent"])
    }

    @Test
    fun `a failed format falls back to the other, then to nothing`() {
        val launch = assertNotNull(IptvPlaybackSession.launchFor(playlist, channel))
        assertTrue(IptvPlaybackSession.isIptvUrl(launch.sourceUrl))
        val next = assertNotNull(IptvPlaybackSession.nextCandidateAfter(launch.sourceUrl))
        assertEquals("http://host:8080/live/user/pass/42.m3u8", next.url)
        assertTrue(IptvPlaybackSession.isIptvUrl(next.url))
        assertNull(IptvPlaybackSession.nextCandidateAfter(next.url))
        assertFalse(IptvPlaybackSession.isIptvUrl("http://elsewhere/x.ts"))
    }

    @Test
    fun `channel up and down walk the zap list and wrap round`() {
        val list = (1..3).map { n -> channel.copy(uid = n, key = "k$n", num = n * 10, name = "C$n", streamId = "$n") }
        assertNotNull(IptvPlaybackSession.launchFor(playlist, list[0], zapList = list))
        assertEquals("C2", IptvPlaybackSession.neighbour(1)?.name)
        assertEquals("C3", IptvPlaybackSession.neighbour(-1)?.name, "wraps from the first to the last")

        val url = assertNotNull(IptvPlaybackSession.switchTo(list[2]))
        assertEquals("http://host:8080/live/user/pass/3.ts", url)
        assertEquals("C3", IptvPlaybackSession.active.value?.channel?.name)
        assertTrue(IptvPlaybackSession.isIptvUrl(url))
        assertFalse(IptvPlaybackSession.isIptvUrl("http://host:8080/live/user/pass/1.ts"), "the old channel's URLs are gone")
        assertEquals("C1", IptvPlaybackSession.neighbour(1)?.name)

        assertEquals("C2", IptvPlaybackSession.byNumber(20)?.name)
        assertNull(IptvPlaybackSession.byNumber(99))
    }

    @Test
    fun `a channel without a stream cannot launch`() {
        assertNull(IptvPlaybackSession.launchFor(playlist, channel.copy(streamId = null)))
        assertFalse(IptvPlaybackSession.isIptvUrl("http://host:8080/live/user/pass/42.ts"))
    }
}
