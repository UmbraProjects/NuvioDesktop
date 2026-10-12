package com.nuvio.app.features.player.autosync

/**
 * Reads embedded subtitle timing from an MP4/MOV `moov` box without touching `mdat`.
 *
 * Desktop replacement for the Media3 `BoxParser.parseTraks` step NuvioTV used: AutoSync needs only
 * when each subtitle sample starts and how long it lasts, which the sample tables give directly —
 * `mdhd` (timescale, language), `stts` (durations), `ctts` (rare composition offsets), `stsz`/`stz2`
 * (sizes, to drop the empty gap samples tx3g/WebVTT tracks use between lines) and the first `elst`
 * edit. Only `tx3g` (mov_text) and `wvtt` tracks are read; bitmap/CEA tracks are left alone.
 */
internal object Mp4SubtitleSampleTables {
    const val TYPE_MOOV = 0x6d6f6f76 // moov
    private const val TYPE_MVHD = 0x6d766864
    private const val TYPE_MVEX = 0x6d766578
    private const val TYPE_TRAK = 0x7472616b
    private const val TYPE_TKHD = 0x746b6864
    private const val TYPE_EDTS = 0x65647473
    private const val TYPE_ELST = 0x656c7374
    private const val TYPE_MDIA = 0x6d646961
    private const val TYPE_MDHD = 0x6d646864
    private const val TYPE_HDLR = 0x68646c72
    private const val TYPE_MINF = 0x6d696e66
    private const val TYPE_STBL = 0x7374626c
    private const val TYPE_STSD = 0x73747364
    private const val TYPE_STTS = 0x73747473
    private const val TYPE_CTTS = 0x63747473
    private const val TYPE_STSZ = 0x7374737a
    private const val TYPE_STZ2 = 0x73747a32
    private const val FORMAT_TX3G = 0x74783367
    private const val FORMAT_WVTT = 0x77767474

    private const val MIN_CUES = 8
    private const val MIN_SPAN_MS = 30_000L
    private const val MAX_SAMPLES = 200_000

    sealed interface Result {
        /** A fragmented (moof) or malformed file: timing isn't all in moov. */
        data object Unreadable : Result
        data class Tracks(val tracks: List<ReferenceTrack>, val sawTextTrack: Boolean) : Result
    }

    fun parse(moov: ByteArray): Result {
        val root = readHeader(moov, 0, moov.size) ?: return Result.Unreadable
        if (root.type != TYPE_MOOV || root.size != moov.size.toLong()) return Result.Unreadable
        val children = children(moov, root.headerSize, moov.size) ?: return Result.Unreadable
        if (children.any { it.type == TYPE_MVEX }) return Result.Unreadable
        val movieTimescale = children.firstOrNull { it.type == TYPE_MVHD }
            ?.let { mvhd -> fullBoxTimescale(moov, mvhd) }
            ?: 0L

        var sawTextTrack = false
        val tracks = children.filter { it.type == TYPE_TRAK }.mapNotNull { trak ->
            val parsed = runCatching { parseTrack(moov, trak, movieTimescale) }.getOrNull()
            if (parsed != null) sawTextTrack = true
            parsed?.takeIf { it.cues.size >= MIN_CUES && it.cues.last().startTimeMs - it.cues.first().startTimeMs >= MIN_SPAN_MS }
        }
        return Result.Tracks(tracks, sawTextTrack)
    }

    /** Null when [trak] isn't a tx3g/WebVTT subtitle track. */
    private fun parseTrack(bytes: ByteArray, trak: Box, movieTimescale: Long): ReferenceTrack? {
        val trakChildren = children(bytes, trak.dataStart, trak.end) ?: return null
        val mdia = trakChildren.firstOrNull { it.type == TYPE_MDIA } ?: return null
        val mdiaChildren = children(bytes, mdia.dataStart, mdia.end) ?: return null
        val stbl = mdiaChildren.firstOrNull { it.type == TYPE_MINF }
            ?.let { minf -> children(bytes, minf.dataStart, minf.end) }
            ?.firstOrNull { it.type == TYPE_STBL }
            ?: return null
        val stblChildren = children(bytes, stbl.dataStart, stbl.end) ?: return null

        val stsd = stblChildren.firstOrNull { it.type == TYPE_STSD } ?: return null
        // stsd: full box header (4) + entry count (4), then the first sample entry's size + format.
        val format = readInt(bytes, stsd.dataStart + 12)
        if (format != FORMAT_TX3G && format != FORMAT_WVTT) return null

        val mdhd = mdiaChildren.firstOrNull { it.type == TYPE_MDHD } ?: return null
        val timescale = fullBoxTimescale(bytes, mdhd)?.takeIf { it > 0 } ?: return null
        val language = mdhdLanguage(bytes, mdhd)

        val durations = readStts(bytes, stblChildren.firstOrNull { it.type == TYPE_STTS } ?: return null) ?: return null
        val sizes = stblChildren.firstOrNull { it.type == TYPE_STSZ || it.type == TYPE_STZ2 }
            ?.let { readSampleSizes(bytes, it) }
        val compositionOffsets = stblChildren.firstOrNull { it.type == TYPE_CTTS }?.let { readCtts(bytes, it) }
        val editShift = trakChildren.firstOrNull { it.type == TYPE_EDTS }
            ?.let { edts -> children(bytes, edts.dataStart, edts.end)?.firstOrNull { it.type == TYPE_ELST } }
            ?.let { elst -> editListShift(bytes, elst, timescale, movieTimescale) }
            ?: 0L

        val emptyThreshold = if (format == FORMAT_TX3G) 2 else 8
        val cues = ArrayList<AutoSyncCue>(durations.size)
        var decodeTime = 0L
        for (index in durations.indices) {
            val duration = durations[index]
            val size = sizes?.getOrNull(index)
            val start = decodeTime + (compositionOffsets?.getOrNull(index) ?: 0L) + editShift
            decodeTime += duration
            // Gap samples only mark where the previous line ended; they aren't lines themselves.
            if (size != null && size <= emptyThreshold) continue
            if (start < 0 || duration <= 0) continue
            val startMs = start * 1000 / timescale
            val endMs = ((start + duration) * 1000 / timescale).coerceAtLeast(startMs + 1)
            cues += AutoSyncCue(startTimeMs = startMs, endTimeMs = endMs, text = "")
        }

        val trackId = trakChildren.firstOrNull { it.type == TYPE_TKHD }?.let { tkhd ->
            val version = bytes[tkhd.dataStart].toInt() and 0xFF
            readInt(bytes, tkhd.dataStart + if (version == 1) 20 else 12)
        } ?: 0
        return ReferenceTrack(
            key = "mp4-samples:$trackId",
            language = language,
            cues = cues.sortedBy { it.startTimeMs }.distinctBy { it.startTimeMs },
            label = (language ?: "Subtitle") + " [Full]",
        )
    }

    /** mvhd and mdhd share the layout: version/flags, creation, modification, then timescale. */
    private fun fullBoxTimescale(bytes: ByteArray, box: Box): Long? {
        val version = bytes[box.dataStart].toInt() and 0xFF
        val offset = box.dataStart + if (version == 1) 20 else 12
        if (offset + 4 > box.end) return null
        return readUInt(bytes, offset)
    }

    private fun mdhdLanguage(bytes: ByteArray, mdhd: Box): String? {
        val version = bytes[mdhd.dataStart].toInt() and 0xFF
        val offset = mdhd.dataStart + if (version == 1) 32 else 20
        if (offset + 2 > mdhd.end) return null
        val packed = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        // ISO-639-2/T, three 5-bit letters offset from 0x60; 0 or "und" means unknown.
        val code = String(CharArray(3) { i -> (((packed shr (10 - 5 * i)) and 0x1F) + 0x60).toChar() })
        return code.takeIf { it.all { c -> c in 'a'..'z' } && it != "und" }
    }

    private fun readStts(bytes: ByteArray, box: Box): LongArray? {
        val count = readUInt(bytes, box.dataStart + 4)
        if (box.dataStart + 8 + count * 8 > box.end) return null
        var total = 0L
        for (entry in 0 until count.toInt()) total += readUInt(bytes, box.dataStart + 8 + entry * 8)
        if (total > MAX_SAMPLES) return null
        val out = LongArray(total.toInt())
        var index = 0
        for (entry in 0 until count.toInt()) {
            val samples = readUInt(bytes, box.dataStart + 8 + entry * 8).toInt()
            val delta = readUInt(bytes, box.dataStart + 12 + entry * 8)
            repeat(samples) { out[index++] = delta }
        }
        return out
    }

    private fun readCtts(bytes: ByteArray, box: Box): LongArray? {
        val version = bytes[box.dataStart].toInt() and 0xFF
        val count = readUInt(bytes, box.dataStart + 4)
        if (box.dataStart + 8 + count * 8 > box.end) return null
        val out = ArrayList<Long>()
        for (entry in 0 until count.toInt()) {
            val samples = readUInt(bytes, box.dataStart + 8 + entry * 8)
            val offset = if (version == 1) {
                readInt(bytes, box.dataStart + 12 + entry * 8).toLong()
            } else {
                readUInt(bytes, box.dataStart + 12 + entry * 8)
            }
            if (out.size + samples > MAX_SAMPLES) return null
            repeat(samples.toInt()) { out += offset }
        }
        return out.toLongArray()
    }

    private fun readSampleSizes(bytes: ByteArray, box: Box): IntArray? {
        val data = box.dataStart
        if (box.type == TYPE_STSZ) {
            val fixed = readUInt(bytes, data + 4)
            val count = readUInt(bytes, data + 8)
            if (count > MAX_SAMPLES) return null
            if (fixed != 0L) return IntArray(count.toInt()) { fixed.toInt() }
            if (data + 12 + count * 4 > box.end) return null
            return IntArray(count.toInt()) { readUInt(bytes, data + 12 + it * 4).toInt() }
        }
        val fieldBits = bytes[data + 7].toInt() and 0xFF
        val count = readUInt(bytes, data + 8)
        if (count > MAX_SAMPLES) return null
        return IntArray(count.toInt()) { index ->
            val base = data + 12
            when (fieldBits) {
                4 -> (bytes[base + index / 2].toInt() shr (if (index % 2 == 0) 4 else 0)) and 0x0F
                8 -> bytes[base + index].toInt() and 0xFF
                16 -> ((bytes[base + index * 2].toInt() and 0xFF) shl 8) or (bytes[base + index * 2 + 1].toInt() and 0xFF)
                else -> return null
            }
        }
    }

    /**
     * Shift from the edit list, in track timescale. An empty first edit delays the track by its
     * duration (movie timescale); a media edit starting at media_time T makes T the presentation zero.
     */
    private fun editListShift(bytes: ByteArray, elst: Box, timescale: Long, movieTimescale: Long): Long {
        val version = bytes[elst.dataStart].toInt() and 0xFF
        val count = readUInt(bytes, elst.dataStart + 4).toInt()
        val entrySize = if (version == 1) 20 else 12
        var shift = 0L
        for (entry in 0 until count) {
            val offset = elst.dataStart + 8 + entry * entrySize
            if (offset + entrySize > elst.end) break
            val segmentDuration = if (version == 1) readLong(bytes, offset) else readUInt(bytes, offset)
            val mediaTime = if (version == 1) readLong(bytes, offset + 8) else readInt(bytes, offset + 4).toLong()
            if (mediaTime == -1L) {
                if (movieTimescale > 0) shift += segmentDuration * timescale / movieTimescale
            } else {
                shift -= mediaTime
                break
            }
        }
        return shift
    }

    data class Box(val type: Int, val start: Int, val dataStart: Int, val end: Int, val size: Long, val headerSize: Int)

    /** Header of the box at [offset]; size 0 means "to [limit]". Null when malformed or out of range. */
    fun readHeader(bytes: ByteArray, offset: Int, limit: Int, extendsToEndSize: Long = (limit - offset).toLong()): Box? {
        if (offset < 0 || limit > bytes.size || offset + 8 > limit) return null
        val size32 = readUInt(bytes, offset)
        val type = readInt(bytes, offset + 4)
        var headerSize = 8
        val size = when (size32) {
            0L -> extendsToEndSize
            1L -> {
                if (offset + 16 > limit) return null
                headerSize = 16
                readLong(bytes, offset + 8).takeIf { it >= 0 } ?: return null
            }
            else -> size32
        }
        if (size < headerSize) return null
        val end = (offset.toLong() + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Box(type, offset, offset + headerSize, end, size, headerSize)
    }

    private fun children(bytes: ByteArray, start: Int, end: Int): List<Box>? {
        val out = ArrayList<Box>()
        var position = start
        while (position + 8 <= end) {
            val box = readHeader(bytes, position, end) ?: return null
            if (box.end > end || box.end <= position) return null
            out += box
            position = box.end
        }
        return out
    }

    private fun readUInt(bytes: ByteArray, offset: Int): Long = readInt(bytes, offset).toLong() and 0xFFFFFFFFL

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        (readUInt(bytes, offset) shl 32) or readUInt(bytes, offset + 4)
}
