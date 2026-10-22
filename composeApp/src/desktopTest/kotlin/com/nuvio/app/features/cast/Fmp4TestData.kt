package com.nuvio.app.features.cast

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Builds tiny but structurally valid fragmented-MP4 byte streams for the remux tests. */
internal object Fmp4TestData {
    const val VIDEO_TRACK = 1
    const val AUDIO_TRACK = 2
    const val VIDEO_TIMESCALE = 16_000L
    const val AUDIO_TIMESCALE = 48_000L

    fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .put(payload)
            .array()
    }

    private fun fullBox(type: String, version: Int, body: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0) + body)

    private fun tkhd(trackId: Int): ByteArray =
        fullBox("tkhd", 0, ByteBuffer.allocate(80).putInt(0).putInt(0).putInt(trackId).array())

    private fun mdhd(timescale: Long): ByteArray =
        fullBox("mdhd", 0, ByteBuffer.allocate(20).putInt(0).putInt(0).putInt(timescale.toInt()).putInt(0).putInt(0).array())

    private fun trak(trackId: Int, timescale: Long): ByteArray =
        box("trak", tkhd(trackId), box("mdia", mdhd(timescale)))

    fun header(): ByteArray =
        box("ftyp", "isomiso6".toByteArray()) +
            box("moov", trak(VIDEO_TRACK, VIDEO_TIMESCALE), trak(AUDIO_TRACK, AUDIO_TIMESCALE))

    private fun mvhd(timescale: Long): ByteArray =
        fullBox("mvhd", 0, ByteBuffer.allocate(96).putInt(0).putInt(0).putInt(timescale.toInt()).putInt(0).array())

    /** The shape ffmpeg's live fMP4 header has: zero durations, mvex without mehd, then udta. */
    fun ffmpegLikeHeader(): ByteArray =
        box("ftyp", "isomiso6".toByteArray()) +
            box(
                "moov",
                mvhd(1000),
                trak(VIDEO_TRACK, VIDEO_TIMESCALE),
                trak(AUDIO_TRACK, AUDIO_TIMESCALE),
                box("mvex", fullBox("trex", 0, ByteArray(20)), fullBox("trex", 0, ByteArray(20))),
                box("udta", ByteArray(89)),
            )

    private fun traf(trackId: Int, decodeTime: Long): ByteArray =
        box(
            "traf",
            fullBox("tfhd", 0, ByteBuffer.allocate(4).putInt(trackId).array()),
            fullBox("tfdt", 1, ByteBuffer.allocate(8).putLong(decodeTime).array()),
        )

    fun fragment(index: Int, videoTime: Long, audioTime: Long, mdatBytes: Int = 4096): ByteArray =
        box("moof", fullBox("mfhd", 0, ByteBuffer.allocate(4).putInt(index).array()), traf(VIDEO_TRACK, videoTime), traf(AUDIO_TRACK, audioTime)) +
            box("mdat", ByteArray(mdatBytes) { (it + index).toByte() })

    /** Header plus [count] fragments, each covering two seconds. */
    fun stream(count: Int, mdatBytes: Int = 4096, header: ByteArray = header()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        repeat(count) { i -> out.write(fragment(i + 1, i * 2 * VIDEO_TIMESCALE, i * 2 * AUDIO_TIMESCALE, mdatBytes)) }
        return out.toByteArray()
    }

    /** mfhd sequence number of every moof, in order. */
    fun sequenceNumbers(data: ByteArray): List<Long> =
        Fmp4.boxes(data).filter { it.type == "moof" }.map { moof ->
            val mfhd = Fmp4.boxes(data, moof.offset + moof.headerSize, moof.offset + moof.size).first { it.type == "mfhd" }
            Fmp4.readU32(data, mfhd.offset + mfhd.headerSize + 4)
        }.toList()

    /** (track_ID, tfdt) for every traf, in order. */
    fun decodeTimes(data: ByteArray): List<Pair<Int, Long>> =
        Fmp4.boxes(data).filter { it.type == "moof" }.flatMap { moof ->
            Fmp4.boxes(data, moof.offset + moof.headerSize, moof.offset + moof.size).filter { it.type == "traf" }.map { traf ->
                var track = -1
                var time = -1L
                Fmp4.boxes(data, traf.offset + traf.headerSize, traf.offset + traf.size).forEach { b ->
                    if (b.type == "tfhd") track = Fmp4.readU32(data, b.offset + b.headerSize + 4).toInt()
                    if (b.type == "tfdt") time = ByteBuffer.wrap(data).getLong(b.offset + b.headerSize + 4)
                }
                track to time
            }
        }.toList()
}
