package com.nuvio.app.features.iptv

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class IptvHlsSubtitlesTest {
    // Two consecutive segments as Pluto TV served them: the cue crossing the boundary is in both,
    // the repeat with `align:center` added.
    private val segment1 = """
        WEBVTT
        X-TIMESTAMP-MAP=MPEGTS:90000,LOCAL:00:00:00.000

        00:03:50.142 --> 00:03:52.978 line:0% align:center
        His father Bill Baterman
        was grief-struck.

        00:03:52.978 --> 00:03:55.897 line:90%
        So grief-struck
        he buried his son up there
    """.trimIndent() + "\n"

    private val segment2 = """
        WEBVTT
        X-TIMESTAMP-MAP=MPEGTS:90000,LOCAL:00:00:00.000

        00:03:52.978 --> 00:03:55.897 line:90% align:center
        So grief-struck
        he buried his son up there

        00:03:55.897 --> 00:03:57.941 line:0% align:center
        before he ever had the chance
    """.trimIndent() + "\n"

    @Test
    fun `a cue repeated in the next segment is dropped there`() {
        val deduper = WebVttCueDeduper()
        assertEquals(segment1 to 0, deduper.filter("s1", segment1))
        val (second, dropped) = deduper.filter("s2", segment2)
        assertEquals(1, dropped)
        assertFalse("he buried his son" in second, second)
        assertTrue(second.startsWith("WEBVTT\nX-TIMESTAMP-MAP="), second)
        assertTrue("before he ever had the chance" in second, second)
    }

    @Test
    fun `the segment that first carried a cue keeps it when fetched again`() {
        val deduper = WebVttCueDeduper()
        deduper.filter("s1", segment1)
        deduper.filter("s2", segment2)
        assertEquals(segment1 to 0, deduper.filter("s1", segment1))
    }

    @Test
    fun `cues are matched on the stream clock, not the segment's own`() {
        val deduper = WebVttCueDeduper()
        deduper.filter("s1", "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nHello\n")
        // The same moment, mapped differently: 10s + 1s == 0s + 11s.
        val (_, dropped) = deduper.filter("s2", "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n00:00:11.000 --> 00:00:12.000\nHello\n")
        assertEquals(1, dropped)
        // Same text at another time is a different line.
        val (_, other) = deduper.filter("s3", "WEBVTT\n\n00:00:20.000 --> 00:00:21.000\nHello\n")
        assertEquals(0, other)
    }

    @Test
    fun `the timestamp map is applied to the cues, which FFmpeg would take raw`() {
        // Pluto's map: cue time 0 is video timestamp 1 s, so every cue moves a second later.
        val shifted = applyWebVttTimestampMap(segment1)
        assertFalse("X-TIMESTAMP-MAP" in shifted, shifted)
        assertTrue("00:03:51.142 --> 00:03:53.978 line:0% align:center" in shifted, shifted)
        assertTrue("00:03:53.978 --> 00:03:56.897 line:90%" in shifted, shifted)
        assertTrue("he buried his son up there" in shifted, shifted)
        // No map, or one mapping 0 to 0: untouched.
        val plain = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        assertEquals(plain, applyWebVttTimestampMap(plain))
        val zero = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        assertEquals(zero, applyWebVttTimestampMap(zero))
        assertEquals("27:00:00.005", formatVttTimestamp(97_200_005L))
    }

    @Test
    fun `a subtitle segment the video offset places is left as the map and offset say`() {
        // Pluto's map (+1 s) plus an offset that puts 00:03:50.142 at 13:50.142 of the output.
        val placed = placeWebVttSegment(segment1, videoStartMs = 830_000, segmentMs = 5_000, shiftMs = 600_000)
        assertEquals(applyWebVttTimestampMap(segment1, 600_000), placed)
        assertTrue("00:13:51.142 --> 00:13:53.978" in placed, placed)
    }

    @Test
    fun `a subtitle segment the offset would put minutes away goes onto its video segment`() {
        // The programme's video counted from near the 26-hour wrap: its offset sends the cues to
        // nowhere near the video segment, which starts at 2:00.000 of the output.
        val placed = placeWebVttSegment(segment1, videoStartMs = 120_000, segmentMs = 5_000, shiftMs = -95_000_000)
        // First cue 3:50.142 (+1 s map = 3:51.142) is in the window 3:50-3:55, moved to start at 2:00.
        assertTrue("00:02:01.142 --> 00:02:03.978" in placed, placed)
        assertTrue("00:02:03.978 --> 00:02:06.897" in placed, placed)
        assertFalse("X-TIMESTAMP-MAP" in placed, placed)
    }

    @Test
    fun `a segment with no cues gets an invisible one so the subtitle stream keeps moving`() {
        val empty = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:90000,LOCAL:00:00:00.000\n"
        val filled = withFillerCue(applyWebVttTimestampMap(empty), 725_000)
        assertTrue("00:12:05.000 --> 00:12:05.001\n​" in filled, filled)
        assertEquals(725.0, firstCueSeconds(filled))
        // A segment with real cues is left as it is.
        assertEquals(segment1, withFillerCue(segment1, 1_000))
    }

    @Test
    fun `an older copy of a live playlist is flagged instead of numbered backwards`() {
        val base = "https://cdn.example/v/720.m3u8".toHttpUrl()
        val normalizer = HlsSequenceNormalizer()
        fun playlist(from: Int) = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$from\n" + (from until from + 3).joinToString("") { "#EXTINF:5,\ns$it.ts\n" }
        for (from in 10..20) rewriteHlsMediaPlaylist(playlist(from), base, normalizer)
        // Pluto answering with the window from earlier: its URIs are known, at old numbers.
        val old = rewriteHlsMediaPlaylist(playlist(12), base, normalizer)
        assertTrue(old.second!!.stale)
        // One refresh behind still ends on a listed segment, and the numbering carries on.
        assertTrue(!rewriteHlsMediaPlaylist(playlist(20), base, normalizer).second!!.stale)
        val next = rewriteHlsMediaPlaylist(playlist(21), base, normalizer)
        assertTrue(!next.second!!.stale)
        assertEquals(21, next.second!!.mediaSequence)
    }

    @Test
    fun `holding back leaves the newest live segments for the next refresh`() {
        val base = "https://cdn.example/v/720.m3u8".toHttpUrl()
        val live = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n" + (10..14).joinToString("") { "#EXTINF:5,\ns$it.ts\n" }
        val held = rewriteHlsMediaPlaylist(live, base, HlsSequenceNormalizer(), holdBack = 2).first
        assertEquals(listOf("s10", "s11", "s12"), held.lines().filter { it.startsWith("https") }.map { it.substringAfterLast('/').removeSuffix(".ts") })
        // Never below one segment, and a finished playlist is left whole.
        val short = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:5,\na.ts\n#EXTINF:5,\nb.ts\n"
        assertEquals(1, rewriteHlsMediaPlaylist(short, base, HlsSequenceNormalizer(), holdBack = 2).first.lines().count { it.startsWith("https") })
        val vod = live + "#EXT-X-ENDLIST\n"
        assertEquals(5, rewriteHlsMediaPlaylist(vod, base, HlsSequenceNormalizer(), holdBack = 2).first.lines().count { it.startsWith("https") })
    }

    @Test
    fun `timestamps parse with and without hours`() {
        assertEquals(230_142L, parseVttTimestampMs("00:03:50.142"))
        assertEquals(230_142L, parseVttTimestampMs("03:50.142"))
        assertEquals(null, parseVttTimestampMs("nonsense"))
    }

    @Test
    fun `the master keeps video and audio upstream and sends subtitles local`() {
        val base = "https://cdn.example/live/channel/master.m3u8?token=t".toHttpUrl()
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subtitle/en/playlist.m3u8?sid=1",LANGUAGE="en"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000,SUBTITLES="subs",AUDIO="audio"
            video/720.m3u8
        """.trimIndent()
        assertTrue(hlsMasterHasSubtitles(master))
        val rewritten = rewriteHlsMaster(master, base, localSubtitlePlaylist = { upstream -> "http://local/sub?u=$upstream" })
        assertTrue("URI=\"http://local/sub?u=https://cdn.example/live/channel/subtitle/en/playlist.m3u8?sid=1\"" in rewritten, rewritten)
        assertTrue("URI=\"https://cdn.example/live/channel/audio/en.m3u8\"" in rewritten, rewritten)
        assertTrue("\nhttps://cdn.example/live/channel/video/720.m3u8" in rewritten, rewritten)
        assertFalse(hlsMasterHasSubtitles("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8"))
    }

    // Shaped like Pluto TV's: five variants, the programme audio muxed into the video (no URI) and
    // an audio-description rendition with its own playlist.
    private val plutoMaster = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,FORCED=NO,URI="subtitle/en/playlist.m3u8",LANGUAGE="en"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="en",NAME="Original",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="2"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="en",NAME="English",AUTOSELECT=YES,CHANNELS="2",URI="ad/audio.m3u8",CHARACTERISTICS="public.accessibility.describes-video"
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1042180,SUBTITLES="subs",AUDIO="audio"
        v/360.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3321280,SUBTITLES="subs",AUDIO="audio"
        v/720.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,AVERAGE-BANDWIDTH=9999999,BANDWIDTH=2294080,SUBTITLES="subs",AUDIO="audio"
        v/576.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="v/iframes.m3u8"
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=640930,SUBTITLES="subs",AUDIO="audio"
        v/240.m3u8
    """.trimIndent()

    @Test
    fun `a master keeps only the highest variant, its main audio and its subtitles`() {
        assertEquals(4, hlsVariantCount(plutoMaster))
        val trimmed = trimHlsMaster(plutoMaster)
        val lines = trimmed.lines()
        // BANDWIDTH, not AVERAGE-BANDWIDTH, decides: 720 (3.3 Mbit/s) over 576.
        assertEquals(listOf("v/720.m3u8"), lines.filter { it.startsWith("v/") }, trimmed)
        assertEquals(1, lines.count { it.startsWith("#EXT-X-STREAM-INF") }, trimmed)
        assertTrue(lines.any { "NAME=\"Original\"" in it }, trimmed)
        assertFalse("ad/audio.m3u8" in trimmed, trimmed)
        assertFalse("iframes" in trimmed, trimmed)
        assertTrue(hlsMasterHasSubtitles(trimmed), trimmed)
    }

    @Test
    fun `audio description stays when it is the only audio, and other groups go`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="hi",NAME="Audio Description",URI="hi/ad.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="lo",NAME="Main",DEFAULT=YES,URI="lo/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO="hi"
            hi.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="lo"
            lo.m3u8
        """.trimIndent()
        val trimmed = trimHlsMaster(master)
        assertTrue("hi/ad.m3u8" in trimmed, trimmed)
        assertFalse("lo/main.m3u8" in trimmed || "\nlo.m3u8" in trimmed, trimmed)
    }

    @Test
    fun `stream ID switches are merged by the reader each format uses`() {
        assertEquals("demuxer-lavf-o=seg_format_options=[merge_pmt_versions=1]", iptvDemuxerOption("https://jmp2.uk/plu-1.m3u8?x=1"))
        assertEquals("demuxer-lavf-o=merge_pmt_versions=1", iptvDemuxerOption("http://h:80/live/u/p/1.ts"))
    }

    @Test
    fun `a single-variant master is left alone`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8"
        assertEquals(master, trimHlsMaster(master))
        assertEquals(1, hlsVariantCount(master))
    }

    @Test
    fun `encrypted or fmp4 subtitle segments are left to mpv`() {
        val base = "https://cdn.example/subs/playlist.m3u8".toHttpUrl()
        val plain = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5,\n00046.vtt"
        val local = rewriteHlsMediaPlaylist(plain, base, HlsSequenceNormalizer(), localSegment = { url, _ -> "local:$url" }).first
        assertTrue(local.endsWith("local:https://cdn.example/subs/00046.vtt"), local)
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x1\n#EXTINF:5,\n00046.vtt"
        val left = rewriteHlsMediaPlaylist(encrypted, base, HlsSequenceNormalizer(), localSegment = { url, _ -> "local:$url" }).first
        assertTrue(left.endsWith("\nhttps://cdn.example/subs/00046.vtt"), left)
        assertTrue("URI=\"https://cdn.example/subs/key\"" in left, left)
    }

    /** A Pluto video playlist refresh: [sequence] then segments named [names], under [clip]. */
    private fun plutoRefresh(sequence: Int, clip: String, names: List<String>, discontinuityBefore: String? = null) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n")
        append("#EXT-X-MEDIA-SEQUENCE:$sequence\n")
        names.forEach { name ->
            if (name == discontinuityBefore) append("#EXT-X-DISCONTINUITY\n")
            append("#EXT-X-KEY:METHOD=AES-128,URI=\"https://siloh.example/key\",IV=0x01\n#EXTINF:5,\n")
            append("https://siloh.example/$clip/hls/$name.ts\n")
        }
        append("#PLUTO-SESSION-ID:x\n")
    }

    private fun sequenceOf(playlist: String) = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(playlist)!!.groupValues[1].toLong()
    private fun discontinuitySequenceOf(playlist: String) =
        Regex("#EXT-X-DISCONTINUITY-SEQUENCE:(\\d+)").find(playlist)!!.groupValues[1].toLong()

    @Test
    fun `a restart in the provider's numbering continues where it left off`() {
        // The poll at 22:25:48: 250, 251, 252, then the next clip numbered from its own segment 77.
        val base = "https://stitcher.example/v/720.m3u8".toHttpUrl()
        val normalizer = HlsSequenceNormalizer()
        fun refresh(text: String) = rewriteHlsMediaPlaylist(text, base, normalizer).first
        val seg = { from: Int -> (from until from + 5).map { "seg_%05d".format(it) } }

        assertEquals(250, sequenceOf(refresh(plutoRefresh(250, "clipA", seg(108)))))
        assertEquals(251, sequenceOf(refresh(plutoRefresh(251, "clipA", seg(109)))))
        val beforeReset = refresh(plutoRefresh(252, "clipA", seg(110)))
        assertEquals(252, sequenceOf(beforeReset))
        assertEquals(3, discontinuitySequenceOf(beforeReset))

        val reset = refresh(plutoRefresh(77, "clipB", seg(77)))
        assertEquals(257, sequenceOf(reset), reset) // right after 252's last segment (256)
        val firstSegment = reset.lines().indexOfFirst { it.endsWith("clipB/hls/seg_00077.ts") }
        assertTrue(reset.lines().take(firstSegment).contains("#EXT-X-DISCONTINUITY"), reset)

        val after = refresh(plutoRefresh(78, "clipB", seg(78)))
        assertEquals(258, sequenceOf(after), after)
        // The discontinuity is still in this window (it marks seg 77, now scrolled off): counted.
        assertEquals(4, discontinuitySequenceOf(after), after)
        assertFalse(after.lines().contains("#EXT-X-DISCONTINUITY"), after)
    }

    @Test
    fun `an ad break the provider marks keeps its marks and numbering`() {
        // The poll at 22:26:33-46: an ad (hls_600-0000x) after an upstream discontinuity, then a
        // restart at the next clip's segment 12.
        val base = "https://stitcher.example/v/720.m3u8".toHttpUrl()
        val normalizer = HlsSequenceNormalizer()
        fun refresh(text: String) = rewriteHlsMediaPlaylist(text, base, normalizer).first
        val ad = listOf("seg_00087", "seg_00088", "seg_00089", "hls_600-00000", "hls_600-00001")
        val first = refresh(plutoRefresh(87, "clipC", ad, discontinuityBefore = "hls_600-00000"))
        assertEquals(87, sequenceOf(first))
        assertEquals(1, first.lines().count { it == "#EXT-X-DISCONTINUITY" }, first)
        val restart = refresh(plutoRefresh(12, "clipD", (12 until 17).map { "seg_%05d".format(it) }))
        assertEquals(92, sequenceOf(restart), restart)
        assertEquals(1, restart.lines().count { it == "#EXT-X-DISCONTINUITY" }, restart)
    }

    @Test
    fun `a key without an IV keeps the one its upstream number implied`() {
        val base = "https://cdn.example/v/720.m3u8".toHttpUrl()
        val normalizer = HlsSequenceNormalizer()
        val playlist = { sequence: Int, from: Int ->
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$sequence\n#EXT-X-KEY:METHOD=AES-128,URI=\"k%20ey\"\n" +
                (from until from + 2).joinToString("") { "#EXTINF:5,\ns$it.ts\n" }
        }
        rewriteHlsMediaPlaylist(playlist(100, 100), base, normalizer)
        val restarted = rewriteHlsMediaPlaylist(playlist(5, 900), base, normalizer).first
        assertEquals(102, sequenceOf(restarted), restarted)
        val keys = restarted.lines().filter { it.startsWith("#EXT-X-KEY") }
        assertEquals(
            listOf("0x00000000000000000000000000000005", "0x00000000000000000000000000000006"),
            keys.map { it.substringAfter("IV=") },
            restarted,
        )
        assertTrue(keys.all { "URI=\"https://cdn.example/v/k%20ey\"" in it }, restarted)
    }

    /** A minimal MPEG-TS segment: ten video PES starts on PID 0x100, a frame (40 ms) apart from [raw]. */
    private fun tsSegment(raw: Long): ByteArray {
        fun timestamp(prefix: Int, v: Long) = byteArrayOf(
            ((prefix shl 4) or (((v shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
            (v shr 22).toInt().toByte(),
            ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
            (v shr 7).toInt().toByte(),
            (((v and 0x7F).toInt() shl 1) or 1).toByte(),
        )
        return (0 until 10).fold(ByteArray(0)) { acc, i ->
            val t = raw + i * 3_600L
            val p = ByteArray(MPEGTS_PACKET) { 0xFF.toByte() }
            p[0] = 0x47; p[1] = 0x41; p[2] = 0x00; p[3] = 0x10 // PUSI, PID 0x100, payload only
            (byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0xC0.toByte(), 10) + timestamp(0x3, t) + timestamp(0x1, t))
                .copyInto(p, 4)
            acc + p
        }
    }

    private var upstream: HttpServer? = null

    @AfterTest
    fun stopUpstream() {
        upstream?.stop(0)
        IptvRepository.setProxy(IptvProxy())
    }

    @Test
    fun `a playlist refresh the provider fails gets the last good copy, not an error`() {
        IptvRepository.setProxy(IptvProxy())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { upstream = it }
        var failing = false
        fun route(path: String, body: () -> String) = server.createContext(path) { exchange ->
            if (failing && path.endsWith("subs.m3u8")) {
                exchange.sendResponseHeaders(503, -1); exchange.close(); return@createContext
            }
            val bytes = body().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        route("/c/master.m3u8") {
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"en\",URI=\"subs.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,SUBTITLES=\"s\"\nv.m3u8\n"
        }
        route("/c/subs.m3u8") { "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:3\n#EXTINF:5,\n3.vtt\n" }
        server.start()
        val local = IptvHlsSubtitleServer.playbackUrlFor("http://127.0.0.1:${server.address.port}/c/master.m3u8", emptyList())
        val http = OkHttpClient()
        fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }
        val subsUrl = Regex("URI=\"([^\"]+)\"").find(get(local).second)!!.groupValues[1]
        val (firstCode, first) = get(subsUrl)
        assertEquals(200, firstCode)
        failing = true
        val (againCode, again) = get(subsUrl)
        assertEquals(200, againCode, "no error passed on to FFmpeg")
        assertEquals(first, again)
    }

    @Test
    fun `end to end, mpv's view of a channel has each line once`() {
        IptvRepository.setProxy(IptvProxy())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { upstream = it }
        val seenHeaders = mutableListOf<String?>()
        fun routeBytes(path: String, bytes: ByteArray) = server.createContext(path) { exchange ->
            seenHeaders += exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fun route(path: String, body: String) = routeBytes(path, body.toByteArray())
        route(
            "/ch/master.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"English\",URI=\"subs/en.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,SUBTITLES=\"s\"\nvideo.m3u8\n",
        )
        // Numbered like the video below, so each subtitle segment has its video segment to go with.
        route("/ch/subs/en.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:5,\n1.vtt\n#EXTINF:5,\n2.vtt\n")
        route("/ch/subs/1.vtt", segment1)
        route("/ch/subs/2.vtt", segment2)
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"

        val local = IptvHlsSubtitleServer.playbackUrlFor("$origin/ch/master.m3u8", listOf("User-Agent: NuvioTest"))
        assertTrue(local.startsWith("http://127.0.0.1:") && local != "$origin/ch/master.m3u8", local)
        assertTrue(IptvHlsSubtitleServer.isLocalUrl(local.toHttpUrl()))

        val http = OkHttpClient()
        fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        val master = get(local)
        // The video playlist is served locally too (renumbered), and with the audio inside it its
        // segments come through the server re-timed: a non-TS body passes through unchanged.
        val videoUrl = master.lines().single { it.startsWith("http://127.0.0.1:") && "/media/" in it }
        routeBytes("/ch/v7.ts", tsSegment(raw = 1_000L * 90_000))
        routeBytes("/ch/v8.ts", tsSegment(raw = 1_005L * 90_000))
        route("/ch/v9.ts", "not a transport stream")
        // v10 and v11 are the newest two, held back on a channel with subtitles.
        route("/ch/video.m3u8", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n" + (7..11).joinToString("") { "#EXTINF:5,\nv$it.ts\n" })
        val video = get(videoUrl)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:7" in video, video)
        val segmentUrls = video.lines().filter { it.startsWith("http://127.0.0.1:") }
        assertEquals(3, segmentUrls.size, video)
        assertTrue("/ts/7.ts" in segmentUrls[0], video)
        assertEquals("not a transport stream", get(segmentUrls[2]))
        val subsUrl = Regex("URI=\"([^\"]+)\"").find(master)!!.groupValues[1]
        val subs = get(subsUrl)
        val segments = subs.lines().filter { it.startsWith("http") }
        assertEquals(2, segments.size, subs)
        val text = segments.joinToString("\n") { get(it) }
        assertEquals(1, Regex("he buried his son up there").findAll(text).count(), text)
        assertTrue(seenHeaders.all { it == "NuvioTest" }, seenHeaders.toString())

        // Not HLS, or a media playlist rather than a master: mpv keeps the original URL.
        assertEquals("$origin/ch/live.ts", IptvHlsSubtitleServer.playbackUrlFor("$origin/ch/live.ts", emptyList()))
        assertEquals("$origin/ch/subs/en.m3u8", IptvHlsSubtitleServer.playbackUrlFor("$origin/ch/subs/en.m3u8", emptyList()))

        // Several variants and no subtitles: served trimmed, the kept variant renumbered locally.
        route("/multi/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=9000\nhigh.m3u8\n")
        route("/multi/high.m3u8", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:3\n#EXTINF:5,\nh3.ts\n")
        val multi = IptvHlsSubtitleServer.playbackUrlFor("$origin/multi/master.m3u8", emptyList())
        assertTrue(multi.startsWith("http://127.0.0.1:"), multi)
        val trimmed = get(multi)
        assertTrue("low.m3u8" !in trimmed, trimmed)
        val high = get(trimmed.lines().single { it.startsWith("http://127.0.0.1:") })
        assertTrue(high.lines().any { it.startsWith("http://127.0.0.1:") && it.endsWith("/ts/3.ts") }, high)

        // Separate audio playlists: no re-timing, segments stay upstream.
        route(
            "/sep/master.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"\nvideo.m3u8\n",
        )
        route("/sep/video.m3u8", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:5,\nv1.ts\n")
        val separate = get(IptvHlsSubtitleServer.playbackUrlFor("$origin/sep/master.m3u8", emptyList()))
        // The variant line (the audio rendition's URI sits in its tag line).
        val sepVideo = get(separate.lines().single { it.startsWith("http://127.0.0.1:") && "/media/" in it })
        assertTrue("\n$origin/sep/v1.ts" in sepVideo, sepVideo)
    }
}
