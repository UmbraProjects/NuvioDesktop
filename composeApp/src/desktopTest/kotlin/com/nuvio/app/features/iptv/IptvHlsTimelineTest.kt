package com.nuvio.app.features.iptv

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class IptvHlsTimelineTest {
    /** A 33-bit timestamp in the 5-byte PES form, with its 4-bit [prefix] and marker bits. */
    private fun pesTimestamp(prefix: Int, value: Long): ByteArray {
        val v = value and (MPEGTS_WRAP - 1)
        return byteArrayOf(
            ((prefix shl 4) or (((v shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
            (v shr 22).toInt().toByte(),
            ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
            (v shr 7).toInt().toByte(),
            (((v and 0x7F).toInt() shl 1) or 1).toByte(),
        )
    }

    private fun readPes(b: ByteArray, i: Int): Long =
        (((b[i].toLong() and 0xFF) shr 1) and 0x07 shl 30) or ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[i + 3].toLong() and 0xFF) shl 7) or
            ((b[i + 4].toLong() and 0xFF) shr 1)

    /** One packet on [pid]: adaptation field with a PCR, then the start of a PES ([streamId]) with PTS and DTS. */
    private fun packet(pcr: Long, pts: Long, dts: Long, pid: Int = 0x100, streamId: Int = 0xE0): ByteArray {
        val p = ByteArray(MPEGTS_PACKET) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = (0x40 or (pid shr 8)).toByte(); p[2] = pid.toByte(); p[3] = 0x30
        p[4] = 7; p[5] = 0x10 // adaptation field: 7 bytes, PCR present
        val c = pcr and (MPEGTS_WRAP - 1)
        p[6] = (c shr 25).toInt().toByte(); p[7] = (c shr 17).toInt().toByte()
        p[8] = (c shr 9).toInt().toByte(); p[9] = (c shr 1).toInt().toByte()
        p[10] = (((c and 1).toInt() shl 7) or 0x7E).toByte(); p[11] = 0x00
        val pes = byteArrayOf(0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(), 0xC0.toByte(), 10) +
            pesTimestamp(0x3, pts) + pesTimestamp(0x1, dts)
        pes.copyInto(p, 12)
        return p
    }

    private fun readPcr(p: ByteArray, off: Int = 0): Long =
        ((p[off + 6].toLong() and 0xFF) shl 25) or ((p[off + 7].toLong() and 0xFF) shl 17) or ((p[off + 8].toLong() and 0xFF) shl 9) or
            ((p[off + 9].toLong() and 0xFF) shl 1) or ((p[off + 10].toLong() and 0xFF) shr 7)

    @Test
    fun `PCR, PTS and DTS move by the offset and keep their marker bits`() {
        val p = packet(pcr = 900_000, pts = 912_000, dts = 909_000)
        assertEquals(909_000, MpegTsTimestamps.decodeTimestamp(p, 0)) // DTS when present
        MpegTsTimestamps.shift(p, 0, 45_000)
        assertEquals(945_000, readPcr(p))
        assertEquals(957_000, readPes(p, 21))
        assertEquals(954_000, readPes(p, 26))
        assertEquals(0x7E, p[10].toInt() and 0x7E, "PCR reserved bits untouched")
        assertEquals(0x31, (p[21].toInt() and 0xF1), "PTS prefix and marker untouched")
        assertEquals(0x11, (p[26].toInt() and 0xF1), "DTS prefix and marker untouched")
        assertEquals(1, p[23].toInt() and 1); assertEquals(1, p[25].toInt() and 1)
    }

    @Test
    fun `shifting wraps at 2^33 like the stream clock`() {
        val nearWrap = MPEGTS_WRAP - 90_000 // 1 s before the wrap
        val p = packet(pcr = nearWrap, pts = nearWrap, dts = nearWrap)
        MpegTsTimestamps.shift(p, 0, 180_000) // +2 s
        assertEquals(90_000, readPes(p, 21))
        assertEquals(90_000, readPcr(p))
    }

    @Test
    fun `packets with no PES start or scrambled payload keep their PES timestamps`() {
        val continuation = packet(0, 1_000, 1_000).also { it[1] = 0x01 } // PUSI cleared
        assertNull(MpegTsTimestamps.decodeTimestamp(continuation, 0))
        val scrambled = packet(0, 1_000, 1_000).also { it[3] = 0xB0.toByte() }
        val before = scrambled.copyOf()
        MpegTsTimestamps.shift(scrambled, 0, 5_000)
        // Only the PCR (in the clear adaptation field) moves.
        assertContentEquals(before.copyOfRange(12, MPEGTS_PACKET), scrambled.copyOfRange(12, MPEGTS_PACKET))
    }

    @Test
    fun `PES kinds tell video and audio from metadata`() {
        assertEquals(MpegTsTimestamps.Kind.Video, MpegTsTimestamps.pesKind(packet(0, 1_000, 1_000), 0))
        assertEquals(MpegTsTimestamps.Kind.Audio, MpegTsTimestamps.pesKind(packet(0, 1_000, 1_000, streamId = 0xC0), 0))
        assertEquals(MpegTsTimestamps.Kind.Other, MpegTsTimestamps.pesKind(packet(0, 1_000, 1_000, streamId = 0xBD), 0))
        assertNull(MpegTsTimestamps.pesKind(packet(0, 1_000, 1_000).also { it[1] = 0x01 }, 0))
        assertEquals(0x101, MpegTsTimestamps.pid(packet(0, 0, 0, pid = 0x101), 0))
    }

    /** A packet stream: video every 40 ms, audio every 21.3 ms (90 kHz: 3,600 and 1,920), from raw times. */
    private class Feed {
        val packets = ArrayList<ByteArray>()
        fun video(raw: Long) { packets += build(raw, 0x100, 0xE0) }
        fun audio(raw: Long) { packets += build(raw, 0x101, 0xC0) }
        private fun build(raw: Long, pid: Int, sid: Int) = IptvHlsTimelineTest().packet(raw, raw, raw, pid, sid)
        fun body(): ByteArray = packets.fold(ByteArray(0)) { acc, p -> acc + p }
    }

    private fun outputs(body: ByteArray, pid: Int): List<Long> =
        (0 until body.size / MPEGTS_PACKET).map { it * MPEGTS_PACKET }
            .filter { MpegTsTimestamps.pid(body, it) == pid }
            .map { readPes(body, it + 26) }

    /** Steps between consecutive output times, unwrapped, in 90 kHz units. */
    private fun steps(times: List<Long>): List<Long> = times.zipWithNext { a, b -> signedWrap(b - a) }

    @Test
    fun `a join inside a segment keeps each stream continuous and in step`() {
        val continuity = MpegTsContinuity()
        val av = 9_000L // the source's audio runs 100 ms ahead of its video
        // Segment 1: the programme (raw 100 s).
        val one = Feed()
        for (i in 0 until 50) { one.video(9_000_000L + i * 3_600); one.audio(9_000_000L + av + i * 3_600) }
        val first = one.body()
        continuity.retime(first)
        // Segment 2: the programme's last 10 frames, then an ad whose clock is at 95,102 s (raw),
        // its video starting before the old audio has finished.
        val two = Feed()
        for (i in 50 until 60) two.video(9_000_000L + i * 3_600)
        val ad = 95_102L * 90_000
        for (i in 0 until 5) two.video(ad + i * 3_600)
        for (i in 50 until 60) two.audio(9_000_000L + av + i * 3_600) // trailing audio of the programme
        for (i in 0 until 40) { two.video(ad + (5 + i) * 3_600); two.audio(ad + av + i * 3_600) }
        val second = two.body()
        val result = continuity.retime(second)
        assertEquals(1, result.joins.size, "one video join reported")

        val video = outputs(first, 0x100) + outputs(second, 0x100)
        val audio = outputs(first, 0x101) + outputs(second, 0x101)
        assertTrue(steps(video).all { it in 1..45_000 }, "video always moves forward, by under 0.5 s: ${steps(video)}")
        assertTrue(steps(audio).all { it in 1..45_000 }, "audio always moves forward, by under 0.5 s")
        // After the join the ad's own audio/video gap is kept: its first audio sits `av` after its first video.
        val adVideoOut = outputs(second, 0x100)[10]
        val adAudioOut = outputs(second, 0x101)[10]
        assertEquals(av, signedWrap(adAudioOut - adVideoOut))
    }

    @Test
    fun `an ad on a different video PID continues the same video clock`() {
        val continuity = MpegTsContinuity()
        val programme = Feed()
        for (i in 0 until 20) { programme.video(9_000_000L + i * 3_600); programme.audio(9_000_000L + i * 3_600) }
        val first = programme.body()
        continuity.retime(first)
        // The ad: its own clock 30 s behind, its video on PID 0x1E1 instead of 0x100.
        val adRaw = 9_000_000L + 20 * 3_600 - 30L * 90_000
        val ad = ArrayList<ByteArray>()
        for (i in 0 until 20) {
            ad += packet(adRaw + i * 3_600, adRaw + i * 3_600, adRaw + i * 3_600, pid = 0x1E1, streamId = 0xE0)
            ad += packet(adRaw + i * 3_600, adRaw + i * 3_600, adRaw + i * 3_600, pid = 0x101, streamId = 0xC0)
        }
        val second = ad.fold(ByteArray(0)) { acc, p -> acc + p }
        continuity.retime(second)
        val video = outputs(first, 0x100) + outputs(second, 0x1E1)
        val audio = outputs(first, 0x101) + outputs(second, 0x101)
        assertTrue(steps(video).all { it in 1..45_000 }, "one video clock across the PIDs: ${steps(video)}")
        assertTrue(steps(audio).all { it in 1..45_000 }, "audio continuous")
        assertTrue(abs(signedWrap(outputs(second, 0x1E1).first() - outputs(second, 0x101).first())) < 9_000, "in step")
    }

    @Test
    fun `a file that wraps inside itself is not a join`() {
        val continuity = MpegTsContinuity()
        val feed = Feed()
        val start = MPEGTS_WRAP - 2 * 90_000 // 2 s before the wrap
        for (i in 0 until 100) feed.video(start + i * 3_600) // runs 4 s, across it
        val body = feed.body()
        assertTrue(continuity.retime(body).joins.isEmpty())
        assertTrue(steps(outputs(body, 0x100)).all { it == 3_600L })
    }

    @Test
    fun `a stream first seen later joins the others' clock with its own offset kept`() {
        val continuity = MpegTsContinuity()
        val feed = Feed()
        for (i in 0 until 10) feed.video(50_000_000L + i * 3_600)
        for (i in 0 until 10) feed.audio(50_000_000L + 4_500 + i * 1_920) // 50 ms after the video
        val body = feed.body()
        continuity.retime(body)
        assertEquals(MpegTsContinuity.START, outputs(body, 0x100).first())
        assertEquals(MpegTsContinuity.START + 4_500, outputs(body, 0x101).first())
    }

    @Test
    fun `even a small step backwards is a join, so the output never goes back`() {
        val continuity = MpegTsContinuity()
        val feed = Feed()
        for (i in 0 until 10) feed.video(2_000_000L + i * 3_600)
        for (i in 0 until 10) feed.video(2_000_000L + 9 * 3_600 - 36_000 + i * 3_600) // 0.4 s back
        val body = feed.body()
        assertEquals(1, continuity.retime(body).joins.size)
        assertTrue(steps(outputs(body, 0x100)).all { it > 0 }, "never backwards")
    }

    @Test
    fun `the first video output time is reported for subtitle placement`() {
        val continuity = MpegTsContinuity()
        val body = Feed().apply { for (i in 0 until 3) video(7_000_000L + i * 3_600) }.body()
        assertEquals(MpegTsContinuity.START, continuity.retime(body).videoStart)
    }

    @Test
    fun `a lost segment closes up instead of leaving a gap`() {
        val continuity = MpegTsContinuity()
        val a = Feed().apply { for (i in 0 until 10) video(1_000_000L + i * 3_600) }.body()
        val c = Feed().apply { for (i in 0 until 10) video(1_000_000L + (10 + 125 + i) * 3_600) }.body() // 5 s later
        continuity.retime(a)
        continuity.retime(c)
        val step = signedWrap(outputs(c, 0x100).first() - outputs(a, 0x100).last())
        assertTrue(step in 1..45_000, "closed up: $step")
    }

    @Test
    fun `keys parse with explicit and implied IVs`() {
        val key = parseHlsKey("#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/a?b=1\",IV=0x0000000000000000000000000000002a")!!
        assertEquals("https://k.example/a?b=1", key.uri)
        assertEquals(0x2a, key.iv[15].toInt())
        assertTrue(key.iv.take(15).all { it == 0.toByte() })
        assertNull(parseHlsKey("#EXT-X-KEY:METHOD=NONE"))
    }

    @Test
    fun `a re-timed playlist points segments local and drops the keys`() {
        val base = "https://stitcher.example/v/720.m3u8".toHttpUrl()
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:40
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example/key"
            #EXTINF:5.005,
            a/40.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example/key2",IV=0x01
            #EXTINF:4.5,
            b/41.ts
        """.trimIndent()
        val seen = ArrayList<HlsMediaSegment>()
        val (text, _) = rewriteHlsMediaPlaylist(playlist, base, HlsSequenceNormalizer(), localMediaSegment = { segment ->
            seen += segment
            "http://local/ts/${segment.sequence}.ts"
        })
        assertFalse("#EXT-X-KEY" in text, text)
        assertTrue("\nhttp://local/ts/40.ts" in text && "\nhttp://local/ts/41.ts" in text, text)
        assertEquals(listOf(5_005L, 4_500L), seen.map { it.durationMs })
        assertEquals(listOf(false, true), seen.map { it.discontinuity })
        assertEquals("https://stitcher.example/v/a/40.ts", seen[0].uri)
        assertEquals(40, seen[0].key!!.iv[15].toInt(), "implied IV: the upstream sequence number")
        assertEquals("https://k.example/key2", seen[1].key!!.uri)
        assertEquals(1, seen[1].key!!.iv[15].toInt())
        assertTrue(abs(seen[0].durationMs - 5_000) < 10)
    }
}
