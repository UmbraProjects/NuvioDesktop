package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.Fmp4TestData.AUDIO_TIMESCALE
import com.nuvio.app.features.cast.Fmp4TestData.AUDIO_TRACK
import com.nuvio.app.features.cast.Fmp4TestData.VIDEO_TIMESCALE
import com.nuvio.app.features.cast.Fmp4TestData.VIDEO_TRACK
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Fmp4RemuxTest {
    private val spoolDir = Files.createTempDirectory("cast-test").toFile()

    @AfterTest
    fun cleanUp() {
        spoolDir.deleteRecursively()
    }

    @Test
    fun `reads timescales and the header length`() {
        val data = Fmp4TestData.stream(3)
        val headerLength = Fmp4TestData.header().size
        assertEquals(headerLength, Fmp4.firstMoofOffset(data))
        assertEquals(mapOf(VIDEO_TRACK to VIDEO_TIMESCALE, AUDIO_TRACK to AUDIO_TIMESCALE), Fmp4.timescales(data))
    }

    @Test
    fun `shifting a moof moves each track by its own timescale`() {
        val moof = Fmp4.boxes(Fmp4TestData.fragment(1, 100, 300)).first().let { Fmp4TestData.fragment(1, 100, 300).copyOf(it.size) }
        val shifted = Fmp4.shiftMoof(moof, mapOf(VIDEO_TRACK to 16_000L, AUDIO_TRACK to 48_000L))
        assertEquals(listOf(VIDEO_TRACK to 16_100L, AUDIO_TRACK to 48_300L), Fmp4TestData.decodeTimes(shifted))
        assertEquals(moof.size, shifted.size)
    }

    @Test
    fun `start session passes the remux through unchanged`() {
        val data = Fmp4TestData.stream(5)
        val session = SpooledRemuxSession(0.0, 0.0, ByteArrayInputStream(data), spoolDir)
        val out = ByteArrayOutputStream()
        session.stream(out, 0)
        assertContentEquals(data, out.toByteArray())
        assertContentEquals(Fmp4TestData.header(), session.initSegment())
        session.close()
    }

    @Test
    fun `seek session sends fragments only, shifted to the keyframe time`() {
        val data = Fmp4TestData.stream(4)
        val keyS = 1798.505
        val session = SpooledRemuxSession(1800.0, keyS, ByteArrayInputStream(data), spoolDir)
        val out = ByteArrayOutputStream()
        session.stream(out, 0)
        val bytes = out.toByteArray()

        assertEquals(data.size - Fmp4TestData.header().size, bytes.size)
        assertEquals("moof", String(bytes, 4, 4, Charsets.ISO_8859_1))
        val times = Fmp4TestData.decodeTimes(bytes)
        assertEquals(VIDEO_TRACK to Math.round(keyS * VIDEO_TIMESCALE), times[0])
        assertEquals(AUDIO_TRACK to Math.round(keyS * AUDIO_TIMESCALE), times[1])
        assertEquals(VIDEO_TRACK to Math.round(keyS * VIDEO_TIMESCALE) + 2 * VIDEO_TIMESCALE, times[2])
        session.close()
    }

    @Test
    fun `a reconnect into a seek session continues byte for byte`() {
        val data = Fmp4TestData.stream(4, mdatBytes = 10_000)
        val whole = ByteArrayOutputStream().also {
            SpooledRemuxSession(600.0, 598.0, ByteArrayInputStream(data), spoolDir).apply { stream(it, 0); close() }
        }.toByteArray()
        for (skip in listOf(5, 777, 10_123, whole.size - 3)) {
            val part = ByteArrayOutputStream().also {
                SpooledRemuxSession(600.0, 598.0, ByteArrayInputStream(data), spoolDir).apply { stream(it, skip.toLong()); close() }
            }.toByteArray()
            assertContentEquals(whole.copyOfRange(skip, whole.size), part, "skip=$skip")
        }
    }

    @Test
    fun `duration is written into the header without changing its length`() {
        val header = Fmp4TestData.ffmpegLikeHeader()
        val patched = Fmp4.withDuration(header, 3388.416)
        assertEquals(header.size, patched.size)

        val moov = Fmp4.boxes(patched).first { it.type == "moov" }
        val inMoov = Fmp4.boxes(patched, moov.offset + moov.headerSize, moov.offset + moov.size).toList()
        assertEquals(listOf("mvhd", "trak", "trak", "mvex", "free"), inMoov.map { it.type })
        val mvhd = inMoov[0]
        assertEquals(3_388_416L, Fmp4.readU32(patched, mvhd.offset + mvhd.headerSize + 16))
        val mvex = inMoov[3]
        val mehd = Fmp4.boxes(patched, mvex.offset + mvex.headerSize, mvex.offset + mvex.size).first()
        assertEquals("mehd", mehd.type)
        assertEquals(3_388_416L, java.nio.ByteBuffer.wrap(patched).getLong(mehd.offset + mehd.headerSize + 4))
        // Track timescales still read back, so seek sessions shift by the same units.
        assertEquals(mapOf(VIDEO_TRACK to VIDEO_TIMESCALE, AUDIO_TRACK to AUDIO_TIMESCALE), Fmp4.timescales(patched))
    }

    @Test
    fun `start session sends its header with the duration and every later byte unmoved`() {
        val header = Fmp4TestData.ffmpegLikeHeader()
        val data = Fmp4TestData.stream(3, header = header)
        val session = SpooledRemuxSession(0.0, 0.0, ByteArrayInputStream(data), spoolDir, durationS = 3388.416)
        val out = ByteArrayOutputStream().also { session.stream(it, 0) }.toByteArray()
        assertEquals(data.size, out.size)
        assertContentEquals(Fmp4.withDuration(header, 3388.416), out.copyOf(header.size))
        assertContentEquals(data.copyOfRange(header.size, data.size), out.copyOfRange(header.size, out.size))
        session.close()
    }

    @Test
    fun `seek session continues the fragment numbering past earlier sessions`() {
        val data = Fmp4TestData.stream(3)
        val session = SpooledRemuxSession(600.0, 598.0, ByteArrayInputStream(data), spoolDir, sequenceOffset = 200_000)
        val out = ByteArrayOutputStream().also { session.stream(it, 0) }.toByteArray()
        assertEquals(listOf(200_001L, 200_002L, 200_003L), Fmp4TestData.sequenceNumbers(out))
        session.close()
    }

    @Test
    fun `parses time and range headers`() {
        assertEquals(1878.06, parseNptStart("npt=0:31:18.060-")!!, 1e-6)
        assertEquals(32.0, parseNptStart("npt=32-")!!, 1e-6)
        assertEquals(0.0, parseNptStart("npt=-")!!, 1e-6)
        assertEquals(76_898_304L, parseRangeStart("bytes=76898304-"))
        assertEquals(0L, parseRangeStart("bytes=0-0"))
    }
}
