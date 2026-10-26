package com.nuvio.app.features.iptv

/*
 * One continuous clock for a stitched live channel ([IptvHlsSubtitleServer] serves its segments
 * rewritten with it).
 *
 * Pluto TV builds a channel by joining separate files (programme, ad, programme), each keeping the
 * timestamps its own encoder gave it, so the clock jumps at every join: by seconds, or by 26 hours
 * across the 33-bit MPEG-TS wrap. The playlist marks each join with `#EXT-X-DISCONTINUITY`, which
 * browser players use to re-anchor; FFmpeg's HLS reader ignores it and hands mpv the raw jumps,
 * which mpv sometimes rides out and sometimes freezes on.
 *
 * The joins do not fall on segment boundaries: a segment can carry the last packets of one ad and
 * the first of the next, and its video and audio cross at different points. So each stream (each
 * PID) is kept continuous on its own: where its timestamps jump, it is re-anchored just after its
 * last output, or onto another stream's new offset where that one already crossed the same join,
 * which keeps the file's own audio/video alignment.
 */

/** MPEG-TS timestamps are 33-bit counts of a 90 kHz clock. */
internal const val MPEGTS_WRAP = 1L shl 33
private const val MPEGTS_MASK = MPEGTS_WRAP - 1
internal const val MPEGTS_PACKET = 188

/** The value [x] stands for on the 33-bit clock, as a signed distance: within ±2^32. */
internal fun signedWrap(x: Long): Long {
    val m = x and MPEGTS_MASK
    return if (m >= MPEGTS_WRAP / 2) m - MPEGTS_WRAP else m
}

/** Reading and shifting the timestamps of single 188-byte MPEG-TS packets, in place. */
internal object MpegTsTimestamps {
    /** PES stream ids with no optional header, hence no PTS/DTS (ISO 13818-1 2.4.3.7). */
    private val NoPesHeader = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xFF, 0xF2, 0xF8)

    private fun u(buf: ByteArray, i: Int) = buf[i].toInt() and 0xFF

    private fun readTimestamp(buf: ByteArray, i: Int): Long =
        ((u(buf, i).toLong() shr 1) and 0x07 shl 30) or
            (u(buf, i + 1).toLong() shl 22) or
            ((u(buf, i + 2).toLong() shr 1) shl 15) or
            (u(buf, i + 3).toLong() shl 7) or
            (u(buf, i + 4).toLong() shr 1)

    private fun writeTimestamp(buf: ByteArray, i: Int, value: Long) {
        val v = value and MPEGTS_MASK
        buf[i] = ((u(buf, i) and 0xF1) or ((v shr 30).toInt() and 0x07 shl 1)).toByte()
        buf[i + 1] = (v shr 22).toInt().toByte()
        buf[i + 2] = (((v shr 15).toInt() and 0x7F shl 1) or (u(buf, i + 2) and 0x01)).toByte()
        buf[i + 3] = (v shr 7).toInt().toByte()
        buf[i + 4] = (((v.toInt() and 0x7F) shl 1) or (u(buf, i + 4) and 0x01)).toByte()
    }

    /** The packet's PID. */
    fun pid(buf: ByteArray, off: Int): Int = ((u(buf, off + 1) and 0x1F) shl 8) or u(buf, off + 2)

    /** Where this packet's PES header starts, or -1 (no PES start, scrambled, or no room). */
    private fun pesStart(buf: ByteArray, off: Int): Int {
        if (u(buf, off) != 0x47) return -1
        val payloadStart = u(buf, off + 1) and 0x40 != 0
        val scrambled = u(buf, off + 3) and 0xC0 != 0
        val control = (u(buf, off + 3) shr 4) and 0x03
        if (!payloadStart || scrambled || control and 0x01 == 0) return -1
        val payload = if (control and 0x02 != 0) off + 5 + u(buf, off + 4) else off + 4
        if (payload + 9 > off + MPEGTS_PACKET) return -1
        if (u(buf, payload) != 0 || u(buf, payload + 1) != 0 || u(buf, payload + 2) != 1) return -1
        if (u(buf, payload + 3) in NoPesHeader) return -1
        return payload
    }

    /** What a PES's stream id says it carries. */
    enum class Kind { Video, Audio, Other }

    /** The kind of PES starting in the packet at [off]; null when none starts there. */
    fun pesKind(buf: ByteArray, off: Int): Kind? {
        val pes = pesStart(buf, off).takeIf { it >= 0 } ?: return null
        return when (u(buf, pes + 3)) {
            in 0xE0..0xEF -> Kind.Video
            in 0xC0..0xDF -> Kind.Audio
            // 0xBD (private stream 1: AC-3 audio, but also HLS timed ID3), 0xFC and the rest.
            else -> Kind.Other
        }
    }

    /** The decode time (DTS, else PTS) of a PES starting in the packet at [off]; null when none starts there. */
    fun decodeTimestamp(buf: ByteArray, off: Int): Long? {
        val pes = pesStart(buf, off).takeIf { it >= 0 } ?: return null
        val flags = (u(buf, pes + 7) shr 6) and 0x03
        return when {
            flags == 3 && pes + 19 <= off + MPEGTS_PACKET -> readTimestamp(buf, pes + 14)
            flags >= 2 && pes + 14 <= off + MPEGTS_PACKET -> readTimestamp(buf, pes + 9)
            else -> null
        }
    }

    /** Adds [offset] (mod 2^33) to the PTS and DTS of a PES starting in the packet at [off]. */
    fun shiftPes(buf: ByteArray, off: Int, offset: Long) {
        val pes = pesStart(buf, off).takeIf { it >= 0 } ?: return
        val flags = (u(buf, pes + 7) shr 6) and 0x03
        if (flags >= 2 && pes + 14 <= off + MPEGTS_PACKET) writeTimestamp(buf, pes + 9, readTimestamp(buf, pes + 9) + offset)
        if (flags == 3 && pes + 19 <= off + MPEGTS_PACKET) writeTimestamp(buf, pes + 14, readTimestamp(buf, pes + 14) + offset)
    }

    /** Whether the packet at [off] carries a PCR. */
    fun hasPcr(buf: ByteArray, off: Int): Boolean {
        if (u(buf, off) != 0x47) return false
        val control = (u(buf, off + 3) shr 4) and 0x03
        return control and 0x02 != 0 && u(buf, off + 4) >= 7 && u(buf, off + 5) and 0x10 != 0
    }

    /** Adds [offset] (mod 2^33) to the packet's PCR base (90 kHz), keeping its extension. */
    fun shiftPcr(buf: ByteArray, off: Int, offset: Long) {
        if (!hasPcr(buf, off)) return
        val p = off + 6
        val base = (u(buf, p).toLong() shl 25) or (u(buf, p + 1).toLong() shl 17) or
            (u(buf, p + 2).toLong() shl 9) or (u(buf, p + 3).toLong() shl 1) or (u(buf, p + 4).toLong() shr 7)
        val shifted = (base + offset) and MPEGTS_MASK
        buf[p] = (shifted shr 25).toInt().toByte()
        buf[p + 1] = (shifted shr 17).toInt().toByte()
        buf[p + 2] = (shifted shr 9).toInt().toByte()
        buf[p + 3] = (shifted shr 1).toInt().toByte()
        buf[p + 4] = (((shifted.toInt() and 0x01) shl 7) or (u(buf, p + 4) and 0x7F)).toByte()
    }

    /** Adds [offset] to the packet's PCR and its PES's PTS and DTS. */
    fun shift(buf: ByteArray, off: Int, offset: Long) {
        shiftPcr(buf, off, offset)
        shiftPes(buf, off, offset)
    }
}

/**
 * Keeps every stream of one live channel on one continuous output clock, packet by packet, across
 * the segments fed to [retime] in order. Not thread-safe: one per channel, fed from one thread.
 *
 * Each stream has its own offset (90 kHz, applied mod 2^33) and remembers where its output clock
 * has reached. Video and audio are one stream each whatever their PID: Pluto carries an ad's video
 * on a different PID from the programme's, mpv (with `merge_pmt_versions`) plays both as one
 * stream, and tracked apart the two PIDs kept separate clocks that met 30 s apart. Other PES
 * streams (timed ID3) stay per PID. A timestamp that lands more than [BACK_TOLERANCE] behind or
 * [FORWARD_TOLERANCE] ahead of where its stream reached is a join: the stream takes another
 * audio/video stream's offset if that places it within the tolerances (the other already crossed
 * the same join, so the file's own alignment is kept), and otherwise starts again one step after
 * its last output. A gap left by a lost segment closes up the same way.
 */
internal class MpegTsContinuity {
    private class Track(var offset: Long, var lastOut: Long, var step: Long, val kind: MpegTsTimestamps.Kind)

    /**
     * What one call to [retime] did: the video's offset and output time (90 kHz, mod 2^33) at its
     * first video timestamp, and the joins it crossed.
     */
    class Result(
        val videoOffset: Long?,
        val videoStart: Long?,
        val joins: List<Double>,
        /** First and last output decode times (90 kHz, mod 2^33) per kind, for the log. */
        val ranges: Map<MpegTsTimestamps.Kind, LongRange> = emptyMap(),
    )

    /** By [trackKey]: "video", "audio", or "other:<pid>". */
    private val tracks = HashMap<String, Track>()
    /** The track each PID's PES last went to, for its PCR. */
    private val pidTracks = HashMap<Int, String>()

    private fun trackKey(kind: MpegTsTimestamps.Kind, pid: Int): String = when (kind) {
        MpegTsTimestamps.Kind.Video -> "video"
        MpegTsTimestamps.Kind.Audio -> "audio"
        MpegTsTimestamps.Kind.Other -> "other:$pid"
    }
    /** The furthest any stream's output has reached (unwrapped), for placing a stream first seen mid-way. */
    private var latestOut: Long? = null
    private var lastUsedOffset: Long? = null

    /** Shifts every timestamp in [body] (whole 188-byte packets from its start) in place. */
    fun retime(body: ByteArray): Result {
        var videoOffset: Long? = null
        var videoStart: Long? = null
        val joins = ArrayList<Double>()
        val first = HashMap<MpegTsTimestamps.Kind, Long>()
        val last = HashMap<MpegTsTimestamps.Kind, Long>()
        var off = 0
        while (off + MPEGTS_PACKET <= body.size && body[off] == 0x47.toByte()) {
            val pid = MpegTsTimestamps.pid(body, off)
            val raw = MpegTsTimestamps.decodeTimestamp(body, off)
            if (raw != null) {
                val kind = MpegTsTimestamps.pesKind(body, off) ?: MpegTsTimestamps.Kind.Other
                val key = trackKey(kind, pid)
                pidTracks[pid] = key
                val track = tracks[key]
                val offset = if (track == null) {
                    start(key, raw, kind)
                } else {
                    advance(track, raw)?.let { jumpedBy -> if (kind == MpegTsTimestamps.Kind.Video) joins += jumpedBy }
                    track.offset
                }
                val out = (raw + offset) and MPEGTS_MASK
                first.putIfAbsent(kind, out)
                last[kind] = out
                if (kind == MpegTsTimestamps.Kind.Video && videoOffset == null) {
                    videoOffset = offset
                    videoStart = out
                }
                MpegTsTimestamps.shiftPes(body, off, offset)
            }
            if (MpegTsTimestamps.hasPcr(body, off)) {
                // The PCR runs with its PID's stream (usually the video's); one on its own PID
                // follows whichever stream moved last.
                (pidTracks[pid]?.let { tracks[it] }?.offset ?: lastUsedOffset)?.let { MpegTsTimestamps.shiftPcr(body, off, it) }
            }
            off += MPEGTS_PACKET
        }
        return Result(videoOffset, videoStart, joins, first.mapValues { (kind, start) -> start..last.getValue(kind) })
    }

    /** A stream seen for the first time: on the clock the others are on, or at its start. */
    private fun start(key: String, raw: Long, kind: MpegTsTimestamps.Kind): Long {
        val latest = latestOut
        val offset = when {
            latest == null -> (START - raw) and MPEGTS_MASK
            // A new stream has no output of its own to step back from, so any offset that puts it
            // near the others will do: the file's own alignment is what matters.
            else -> fittingOffset(raw, latest, excluding = null, back = NEW_STREAM_WINDOW, forward = NEW_STREAM_WINDOW)
                ?: ((latest - raw) and MPEGTS_MASK)
        }
        val out = latest?.let { it + signedWrap(raw + offset - it) } ?: START
        tracks[key] = Track(offset, out, DEFAULT_STEP, kind)
        note(out, offset)
        return offset
    }

    /** Moves [track] on to [raw]; the size of the jump in seconds when it crossed a join, else null. */
    private fun advance(track: Track, raw: Long): Double? {
        val diff = signedWrap(raw + track.offset - track.lastOut)
        if (diff >= -BACK_TOLERANCE && diff <= FORWARD_TOLERANCE) {
            if (diff > 0) track.step = diff.coerceAtMost(MAX_STEP)
            track.lastOut += diff
            note(track.lastOut, track.offset)
            return null
        }
        val previous = track.offset
        val target = track.lastOut + track.step
        track.offset = fittingOffset(raw, track.lastOut, excluding = track) ?: ((target - raw) and MPEGTS_MASK)
        track.lastOut += signedWrap(raw + track.offset - track.lastOut)
        note(track.lastOut, track.offset)
        return signedWrap(previous - track.offset) / 90_000.0
    }

    /**
     * An audio or video stream's offset that puts [raw] from [back] before to [forward] after
     * [after]; null when none does. Never a metadata stream's: Pluto's timed ID3 runs on a clock of
     * its own (60 s off its picture at one join), so its offset says nothing about the file's.
     */
    private fun fittingOffset(
        raw: Long,
        after: Long,
        excluding: Track?,
        back: Long = BACK_TOLERANCE,
        forward: Long = FORWARD_TOLERANCE,
    ): Long? =
        tracks.values.asSequence()
            .filter { it !== excluding && it.kind != MpegTsTimestamps.Kind.Other }
            .sortedByDescending { it.lastOut }.map { it.offset }
            .firstOrNull { candidate ->
                val diff = signedWrap(raw + candidate - after)
                diff >= -back && diff <= forward
            }

    private fun note(out: Long, offset: Long) {
        latestOut = maxOf(latestOut ?: out, out)
        lastUsedOffset = offset
    }

    companion object {
        /** Where the output clock starts: 10 s, clear of 0 so nothing goes negative. */
        const val START = 10L * 90_000
        /**
         * How far a stream may step backwards and still be the same file: none. Decode timestamps
         * only ever rise within a stream (B-frames reorder PTS, not DTS), and a 0.4 s step back
         * let through as continuity made mpv reset playback.
         */
        const val BACK_TOLERANCE = 0L
        /** How near the others a stream first seen mid-way must land to keep their offset (10 s). */
        const val NEW_STREAM_WINDOW = 900_000L
        /** A step forwards beyond this (5 s) is a join, or a lost segment to close up. */
        const val FORWARD_TOLERANCE = 450_000L
        /** A stream's timestamps step by at most this much when it continues normally (1 s). */
        const val MAX_STEP = 90_000L
        /** The step assumed before one has been seen (40 ms, a frame at 25 fps). */
        const val DEFAULT_STEP = 3_600L
    }
}
