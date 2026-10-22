package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToLong

private val remuxLog = Logger.withTag("CastRemux")

/** Just enough ISO-BMFF to splice fragmented MP4: box walking, timescales, tfdt shifting. */
internal object Fmp4 {
    data class Box(val offset: Int, val size: Int, val headerSize: Int, val type: String)

    fun boxes(data: ByteArray, start: Int = 0, end: Int = data.size): Sequence<Box> = sequence {
        var pos = start
        while (pos + 8 <= end) {
            var size = readU32(data, pos)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > end) break
                size = ByteBuffer.wrap(data, pos + 8, 8).long
                header = 16
            }
            if (size < header || pos + size > end) break
            yield(Box(pos, size.toInt(), header, String(data, pos + 4, 4, Charsets.ISO_8859_1)))
            pos += size.toInt()
        }
    }

    /** Offset of the first top-level `moof`, i.e. the length of the ftyp+moov header; null if not yet seen. */
    fun firstMoofOffset(data: ByteArray, length: Int = data.size): Int? =
        boxes(data, 0, length).firstOrNull { it.type == "moof" }?.offset

    /** track_ID -> media timescale, read from moov/trak/(tkhd, mdia/mdhd). */
    fun timescales(data: ByteArray): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        val moov = boxes(data).firstOrNull { it.type == "moov" } ?: return result
        boxes(data, moov.offset + moov.headerSize, moov.offset + moov.size).filter { it.type == "trak" }.forEach { trak ->
            var trackId: Int? = null
            var timescale: Long? = null
            boxes(data, trak.offset + trak.headerSize, trak.offset + trak.size).forEach { box ->
                if (box.type == "tkhd") {
                    val version = data[box.offset + box.headerSize].toInt()
                    trackId = readU32(data, box.offset + box.headerSize + if (version == 1) 20 else 12).toInt()
                }
                if (box.type == "mdia") {
                    boxes(data, box.offset + box.headerSize, box.offset + box.size).firstOrNull { it.type == "mdhd" }?.let { mdhd ->
                        val version = data[mdhd.offset + mdhd.headerSize].toInt()
                        timescale = readU32(data, mdhd.offset + mdhd.headerSize + if (version == 1) 20 else 12)
                    }
                }
            }
            val id = trackId
            val scale = timescale
            if (id != null && scale != null) result[id] = scale
        }
        return result
    }

    /**
     * A copy of one `moof` with each traf's baseMediaDecodeTime raised by offsets[track_ID] and
     * its mfhd sequence number raised by [sequenceOffset]. A seek session's own numbering restarts
     * at 1, which a renderer that already saw the start session's fragments reads as going back.
     */
    fun shiftMoof(moof: ByteArray, offsets: Map<Int, Long>, sequenceOffset: Long = 0): ByteArray {
        val out = moof.copyOf()
        val top = boxes(out).firstOrNull() ?: return out
        if (sequenceOffset != 0L) {
            boxes(out, top.offset + top.headerSize, top.offset + top.size).firstOrNull { it.type == "mfhd" }?.let { mfhd ->
                val at = mfhd.offset + mfhd.headerSize + 4
                writeU32(out, at, readU32(out, at) + sequenceOffset)
            }
        }
        boxes(out, top.offset + top.headerSize, top.offset + top.size).filter { it.type == "traf" }.forEach { traf ->
            var trackId: Int? = null
            boxes(out, traf.offset + traf.headerSize, traf.offset + traf.size).forEach { box ->
                if (box.type == "tfhd") trackId = readU32(out, box.offset + box.headerSize + 4).toInt()
                if (box.type == "tfdt") {
                    val offset = offsets[trackId] ?: return@forEach
                    val at = box.offset + box.headerSize + 4
                    if (out[box.offset + box.headerSize].toInt() == 1) {
                        val buffer = ByteBuffer.wrap(out, at, 8)
                        val value = buffer.getLong(at)
                        buffer.putLong(at, value + offset)
                    } else {
                        writeU32(out, at, readU32(out, at) + offset)
                    }
                }
            }
        }
        return out
    }

    /**
     * The header (ftyp+moov) with the real [durationS] written in, at the same byte length.
     *
     * A live fragmented remux cannot know its length, so ffmpeg leaves every duration at 0, and
     * players that read it from the file (Kodi) show the first fragment's few seconds as the whole
     * film. mvhd/tkhd/mdhd are patched in place; an `mehd` (the fragmented-file total) is added to
     * mvex by taking 20 bytes from the box after it (ffmpeg's `udta` encoder tag), whose remainder
     * becomes a `free` box. Keeping the length is what lets every byte offset a renderer has seen
     * stay valid. Without such a box to borrow from, only the in-place fields are written.
     */
    fun withDuration(header: ByteArray, durationS: Double): ByteArray {
        val out = header.copyOf()
        val moov = boxes(out).firstOrNull { it.type == "moov" } ?: return out
        val inMoov = boxes(out, moov.offset + moov.headerSize, moov.offset + moov.size).toList()
        val movieScale = inMoov.firstOrNull { it.type == "mvhd" }?.let { mvhd ->
            val body = mvhd.offset + mvhd.headerSize
            val v1 = out[body].toInt() == 1
            val scale = readU32(out, body + if (v1) 20 else 12)
            writeDuration(out, body + if (v1) 24 else 16, v1, (durationS * scale).roundToLong())
            scale
        } ?: return out
        inMoov.filter { it.type == "trak" }.forEach { trak ->
            boxes(out, trak.offset + trak.headerSize, trak.offset + trak.size).forEach { box ->
                val body = box.offset + box.headerSize
                if (box.type == "tkhd") {
                    val v1 = out[body].toInt() == 1
                    writeDuration(out, body + if (v1) 28 else 20, v1, (durationS * movieScale).roundToLong())
                }
                if (box.type == "mdia") {
                    boxes(out, body, box.offset + box.size).firstOrNull { it.type == "mdhd" }?.let { mdhd ->
                        val mdBody = mdhd.offset + mdhd.headerSize
                        val v1 = out[mdBody].toInt() == 1
                        val scale = readU32(out, mdBody + if (v1) 20 else 12)
                        writeDuration(out, mdBody + if (v1) 24 else 16, v1, (durationS * scale).roundToLong())
                    }
                }
            }
        }
        val mvexIndex = inMoov.indexOfFirst { it.type == "mvex" }
        val mvex = inMoov.getOrNull(mvexIndex) ?: return out
        val hasMehd = boxes(out, mvex.offset + mvex.headerSize, mvex.offset + mvex.size).any { it.type == "mehd" }
        val donor = inMoov.getOrNull(mvexIndex + 1)?.takeIf { (it.type == "udta" || it.type == "free") && it.size >= MEHD_SIZE + 8 }
        if (hasMehd || donor == null || mvex.headerSize != 8) return out
        val mehd = ByteBuffer.allocate(MEHD_SIZE).putInt(MEHD_SIZE).put("mehd".toByteArray(Charsets.ISO_8859_1))
            .putInt(0x01000000).putLong((durationS * movieScale).roundToLong()).array()
        val mvexBody = out.copyOfRange(mvex.offset + 8, mvex.offset + mvex.size)
        val rebuilt = ByteBuffer.allocate(mvex.size + donor.size)
            .putInt(mvex.size + MEHD_SIZE).put("mvex".toByteArray(Charsets.ISO_8859_1)).put(mehd).put(mvexBody)
            .putInt(donor.size - MEHD_SIZE).put("free".toByteArray(Charsets.ISO_8859_1))
            .array()
        rebuilt.copyInto(out, mvex.offset)
        return out
    }

    private const val MEHD_SIZE = 20 // version 1: 8 header + 4 version/flags + 8 duration

    private fun writeDuration(data: ByteArray, at: Int, v1: Boolean, value: Long) {
        if (v1) ByteBuffer.wrap(data).putLong(at, value) else writeU32(data, at, value.coerceAtMost(0xFFFFFFFFL))
    }

    fun readU32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun writeU32(data: ByteArray, at: Int, value: Long) {
        data[at] = (value shr 24).toByte()
        data[at + 1] = (value shr 16).toByte()
        data[at + 2] = (value shr 8).toByte()
        data[at + 3] = value.toByte()
    }
}

/**
 * One remux of the source starting at [keyS] (0 = the whole file from the top).
 *
 * The renderer sees a session at [tvBase] in its own byte numbering. A seek session is sent as
 * a complete MP4: [prefix] (the start session's header) followed by its shifted fragments.
 */
internal interface RemuxSession : Closeable {
    val requestedS: Double
    val keyS: Double
    var tvBase: Long
    var prefix: ByteArray
    val written: Long

    /** ftyp+moov of this session's output; blocks until ffmpeg has written it. */
    fun initSegment(): ByteArray

    /**
     * Writes the renderer-facing bytes from [outOffset]: the raw output for the start session,
     * fragments only (tfdt shifted to [keyS]) for a seek session. Returns the bytes sent.
     */
    fun stream(out: OutputStream, outOffset: Long): Long
}

/**
 * Spools a remux to disk so the renderer's reconnects are served from where it left off.
 * Production pauses once it is [readAhead] bytes past the furthest point anyone has read, so a
 * paused TV does not pull the whole file.
 */
internal class SpooledRemuxSession(
    override val requestedS: Double,
    override val keyS: Double,
    source: InputStream,
    spoolDir: File,
    private val onClose: () -> Unit = {},
    private val readAhead: Long = DEFAULT_READ_AHEAD,
    /** Written into the start session's header; see [Fmp4.withDuration]. */
    private val durationS: Double? = null,
    /** Added to a seek session's mfhd numbers so they continue past everything sent before. */
    private val sequenceOffset: Long = 0,
) : RemuxSession {
    private val spool = File.createTempFile("remux-", ".mp4", spoolDir)
    private val lock = Object()
    @Volatile override var tvBase: Long = 0
    @Volatile override var prefix: ByteArray = ByteArray(0)
    @Volatile override var written: Long = 0
        private set
    private var done = false
    private var closed = false
    private var readerPos = 0L
    private var firstMoof: Int? = null
    private var offsets: Map<Int, Long> = emptyMap()

    private val isSeek get() = keyS > 0

    init {
        thread(isDaemon = true, name = "cast-remux-${keyS.roundToLong()}") { produce(source) }
    }

    private fun produce(source: InputStream) {
        val buffer = ByteArray(512 * 1024)
        try {
            spool.outputStream().use { out ->
                while (true) {
                    synchronized(lock) {
                        while (!closed && written - readerPos > readAhead) lock.wait(1_000)
                    }
                    if (synchronized(lock) { closed }) break
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    out.flush()
                    synchronized(lock) {
                        written += read
                        if (firstMoof == null) findHeader()
                        lock.notifyAll()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed) remuxLog.w(error) { "Remux producer stopped at ${written / 1_048_576} MiB" }
        } finally {
            runCatching { source.close() }
            synchronized(lock) {
                done = true
                lock.notifyAll()
            }
        }
    }

    /** Called with the lock held, whenever the header might now be complete. */
    private fun findHeader() {
        val headLength = written.coerceAtMost(HEADER_SCAN_LIMIT).toInt()
        val head = ByteArray(headLength)
        RandomAccessFile(spool, "r").use { it.readFully(head) }
        val moof = Fmp4.firstMoofOffset(head) ?: return
        val scales = Fmp4.timescales(head.copyOf(moof))
        offsets = scales.mapValues { (_, scale) -> (keyS * scale).roundToLong() }
        if (!isSeek && durationS != null) {
            // Same length by construction, so this rewrites the header without moving a byte after it.
            val patched = Fmp4.withDuration(head.copyOf(moof), durationS)
            RandomAccessFile(spool, "rw").use { it.write(patched) }
        }
        firstMoof = moof
    }

    private fun waitFor(end: Long): Boolean = synchronized(lock) {
        while (!closed && !done && written < end) lock.wait(1_000)
        !closed
    }

    private fun noteRead(pos: Long) = synchronized(lock) {
        if (pos > readerPos) readerPos = pos
        lock.notifyAll()
    }

    override fun initSegment(): ByteArray {
        synchronized(lock) {
            while (!closed && !done && firstMoof == null) lock.wait(1_000)
        }
        val length = firstMoof ?: return ByteArray(0)
        return ByteArray(length).also { bytes -> RandomAccessFile(spool, "r").use { it.readFully(bytes) } }
    }

    override fun stream(out: OutputStream, outOffset: Long): Long =
        if (isSeek) streamShifted(out, outOffset) else streamRaw(out, outOffset)

    private fun streamRaw(out: OutputStream, offset: Long): Long {
        // Nothing goes out before the header has been found and given its duration.
        initSegment()
        var pos = offset
        var sent = 0L
        val buffer = ByteArray(512 * 1024)
        RandomAccessFile(spool, "r").use { file ->
            while (true) {
                noteRead(pos)
                if (!waitFor(pos + 1)) break
                val available = synchronized(lock) { written } - pos
                if (available <= 0) {
                    if (synchronized(lock) { done }) break
                    continue
                }
                file.seek(pos)
                val read = file.read(buffer, 0, minOf(available, buffer.size.toLong()).toInt())
                if (read <= 0) continue
                out.write(buffer, 0, read)
                pos += read
                sent += read
            }
        }
        return sent
    }

    /** Fragments only, from the first moof, with tfdt shifted; the first [skip] bytes are not sent. */
    private fun streamShifted(out: OutputStream, skip: Long): Long {
        initSegment()
        var box = (firstMoof ?: return 0).toLong()
        var outPos = 0L
        var sent = 0L
        val buffer = ByteArray(512 * 1024)
        RandomAccessFile(spool, "r").use { file ->
            while (true) {
                if (!waitFor(box + 8) || synchronized(lock) { written } < box + 8) break
                val header = ByteArray(8)
                file.seek(box)
                file.readFully(header)
                val size = Fmp4.readU32(header, 0)
                if (size < 8) break
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                if (type == "moof") {
                    if (!waitFor(box + size)) break
                    val moof = ByteArray(size.toInt())
                    file.seek(box)
                    file.readFully(moof)
                    val shifted = Fmp4.shiftMoof(moof, offsets, sequenceOffset)
                    if (outPos + size > skip) {
                        val from = (skip - outPos).coerceAtLeast(0).toInt()
                        out.write(shifted, from, shifted.size - from)
                        sent += shifted.size - from
                    }
                    outPos += size
                    box += size
                    noteRead(box)
                    continue
                }
                // mdat (or anything else) passes through as it is produced.
                val boxEnd = box + size
                var pos = box
                while (pos < boxEnd) {
                    noteRead(pos)
                    if (!waitFor(pos + 1)) return sent
                    val available = minOf(synchronized(lock) { written }, boxEnd) - pos
                    if (available <= 0) return sent
                    file.seek(pos)
                    val read = file.read(buffer, 0, minOf(available, buffer.size.toLong()).toInt())
                    if (read <= 0) return sent
                    val chunkOut = outPos + (pos - box)
                    if (chunkOut + read > skip) {
                        val from = (skip - chunkOut).coerceAtLeast(0).toInt()
                        out.write(buffer, from, read - from)
                        sent += read - from
                    }
                    pos += read
                }
                outPos += size
                box = boxEnd
            }
        }
        return sent
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching(onClose)
        spool.delete()
    }

    companion object {
        const val DEFAULT_READ_AHEAD = 256L * 1024 * 1024
        private const val HEADER_SCAN_LIMIT = 8L * 1024 * 1024
    }
}

/**
 * Decides which remux session answers a renderer request, from the two things a DLNA renderer
 * sends: a byte offset (Range) and a time (TimeSeekRange.dlna.org). Behaviour recorded from an
 * LG CX:
 *
 * - it reconnects mid-play with the byte offset it reached and a matching time: a **resume**,
 *   served from the spool of the session that offset belongs to;
 * - for a seek it sends the target time with whatever byte offset it had reached: a **seek**,
 *   served by a session started there.
 *
 * The two are told apart by whether the time agrees with where the offset falls in a session.
 *
 * A seek is answered with a complete MP4 (header + fragments shifted to the real time), because
 * the reply is a 200: that says "the resource from its start", and the CX rejected a 200 whose
 * body began mid-stream with bare fragments ("file cannot be recognized").
 */
internal class DvRemuxRouter(
    private val byteRate: Double,
    private val sessionFactory: (Double) -> RemuxSession,
    private val maxSessions: Int = 2,
    private val readAhead: Long = SpooledRemuxSession.DEFAULT_READ_AHEAD,
) : Closeable {
    class Route(val session: RemuxSession, val outOffset: Long, val prefix: ByteArray, val replyNptS: Double?)

    private val sessions = ArrayDeque<RemuxSession>() // most recently used last

    /**
     * The session the renderer was last sent. A byte offset only means something in the stream it
     * came from, and every session's bytes now count from 0, so a resume is judged against this one.
     */
    private var lastServed: RemuxSession? = null

    @Synchronized
    fun route(rangeStart: Long?, nptS: Double?): Route {
        val offset = rangeStart ?: 0L
        if (nptS == null || nptS < 1.0) {
            val session = (if (offset > 0) resumable(offset, null) else null) ?: sessionAt(0.0)
            return slice(session, offset - session.tvBase, nptS?.let { session.keyS })
        }
        resumable(offset, nptS)?.takeIf { offset > 0 }?.let { session ->
            return slice(session, offset - session.tvBase, session.keyS)
        }
        val head = sessionAt(0.0)
        val session = sessionAt(nptS)
        session.prefix = head.initSegment()
        session.tvBase = 0
        return slice(session, 0, session.keyS)
    }

    /** The bytes from [relative] of the session's renderer-facing stream: prefix, then its own output. */
    private fun slice(session: RemuxSession, relative: Long, replyNptS: Double?): Route {
        lastServed = session
        val header = session.prefix
        return if (relative < header.size) {
            Route(session, 0, header.copyOfRange(relative.toInt(), header.size), replyNptS)
        } else {
            Route(session, relative - header.size, ByteArray(0), replyNptS)
        }
    }

    private fun resumable(offset: Long, nptS: Double?): RemuxSession? =
        lastServed?.takeIf { session ->
            val relative = offset - session.tvBase
            session in sessions && relative >= 0 && relative <= session.written + readAhead &&
                (nptS == null || abs(session.keyS + relative / byteRate - nptS) <= RESUME_TOLERANCE_S)
        }

    private fun sessionAt(requestedS: Double): RemuxSession {
        sessions.firstOrNull { abs(it.requestedS - requestedS) <= 0.5 }?.let { existing ->
            sessions.remove(existing)
            sessions.addLast(existing)
            return existing
        }
        val created = sessionFactory(requestedS)
        sessions.addLast(created)
        // The start session is never evicted: every seek reuses its header.
        while (sessions.size > maxSessions) {
            val victim = sessions.firstOrNull { it.requestedS > 0 && it !== created } ?: break
            sessions.remove(victim)
            victim.close()
        }
        return created
    }

    @Synchronized
    override fun close() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    companion object {
        const val RESUME_TOLERANCE_S = 90.0
    }
}

/** `npt=1860.5-`, `npt=0:31:00.5-` -> seconds. */
internal fun parseNptStart(header: String): Double? {
    val value = header.substringAfter('=').substringBefore('-').trim()
    if (value.isEmpty()) return 0.0
    if (':' in value) {
        val parts = value.split(':').map { it.toDoubleOrNull() ?: return null }.toMutableList()
        while (parts.size < 3) parts.add(0, 0.0)
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }
    return value.toDoubleOrNull()
}

/** `bytes=1234-` -> 1234. */
internal fun parseRangeStart(header: String): Long? =
    header.substringAfter('=', "").substringBefore('-').trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
