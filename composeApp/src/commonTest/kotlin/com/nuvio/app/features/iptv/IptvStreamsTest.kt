package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

// Ported from GoatTV's StreamsTest.
class IptvStreamsTest {

    private val xtream = IptvPlaylist(
        id = "p1", type = IptvPlaylistType.Xtream, name = "Home",
        server = "http://host:8080", username = "user", password = "pass",
    )
    private val m3u = IptvPlaylist(id = "p2", type = IptvPlaylistType.M3u, name = "PT", url = "https://x/list.m3u")

    private fun channel(url: String? = null, streamId: String? = null) =
        IptvChannel(uid = 0, key = "k", num = 1, name = "C", logo = null, categoryId = "c", streamId = streamId, url = url)

    @Test
    fun `xtream tries ts then m3u8 by default`() {
        val c = iptvStreamCandidates(xtream, channel(streamId = "123"), null)
        assertEquals(
            listOf("http://host:8080/live/user/pass/123.ts", "http://host:8080/live/user/pass/123.m3u8"),
            c.map { it.url },
        )
        assertEquals(listOf(IptvStreamFormat.TS, IptvStreamFormat.HLS), c.map { it.format })
    }

    @Test
    fun `xtream respects preferred format`() {
        val c = iptvStreamCandidates(xtream, channel(streamId = "123"), IptvStreamFormat.HLS)
        assertEquals(listOf(IptvStreamFormat.HLS, IptvStreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `xtream skips formats the server does not allow`() {
        val c = iptvStreamCandidates(xtream, channel(streamId = "1"), IptvStreamFormat.HLS, setOf("ts"))
        assertEquals(listOf(IptvStreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `xtream server without scheme`() {
        val c = iptvStreamCandidates(xtream.copy(server = "host:8080/"), channel(streamId = "9"), null)
        assertEquals("http://host:8080/live/user/pass/9.ts", c.first().url)
    }

    @Test
    fun `xtream credentials are escaped in paths and queries`() {
        val odd = xtream.copy(username = "a b", password = "p/w&x")
        assertEquals("http://host:8080/live/a%20b/p%2Fw%26x/5.ts", XtreamUrls.streamUrl(odd, "5", "ts"))
        assertEquals(
            "http://host:8080/player_api.php?username=a+b&password=p%2Fw%26x&action=get_live_streams",
            XtreamUrls.api(odd, "get_live_streams"),
        )
        assertEquals("http://host:8080/xmltv.php?username=a+b&password=p%2Fw%26x", XtreamUrls.xmltv(odd))
    }

    @Test
    fun `m3u swaps extension keeping query`() {
        val c = iptvStreamCandidates(m3u, channel(url = "http://h/live/1.m3u8?token=abc"), null)
        assertEquals(listOf("http://h/live/1.m3u8?token=abc", "http://h/live/1.ts?token=abc"), c.map { it.url })
        assertEquals(listOf(IptvStreamFormat.HLS, IptvStreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `m3u without extension tries hls first unless ts preferred`() {
        val url = "http://h/play/42"
        assertEquals(
            listOf(IptvStreamFormat.HLS, IptvStreamFormat.TS),
            iptvStreamCandidates(m3u, channel(url = url), null).map { it.format },
        )
        assertEquals(
            listOf(IptvStreamFormat.TS, IptvStreamFormat.HLS),
            iptvStreamCandidates(m3u, channel(url = url), IptvStreamFormat.TS).map { it.format },
        )
    }

    @Test
    fun `content groups unknown categories into Other`() {
        val content = IptvPlaylistContent.build(
            playlist = xtream,
            categories = listOf(IptvCategory("1", "News"), IptvCategory("2", "Empty")),
            channels = listOf(
                channel(streamId = "a").copy(uid = 0, key = "a", categoryId = "1"),
                channel(streamId = "b").copy(uid = 1, key = "b", categoryId = "99"),
            ),
        )
        assertEquals(listOf("1", IptvPlaylistContent.OTHER_CATEGORY), content.categories.map { it.id })
        assertEquals(IptvPlaylistContent.OTHER_CATEGORY, content.byKey["b"]?.categoryId)
    }
}
