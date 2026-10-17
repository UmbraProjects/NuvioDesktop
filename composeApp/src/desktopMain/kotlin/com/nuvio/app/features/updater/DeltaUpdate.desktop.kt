package com.nuvio.app.features.updater

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.Inflater

/*
 * Delta updates: download only the files of the release zip that differ from the installed build.
 *
 * Nothing extra is published for this. The release zip's own central directory (the index at the
 * end of every zip) lists each file's size and CRC32, and GitHub serves release assets with HTTP
 * range support, so the updater reads that index with two small requests, compares it against the
 * install folder, and then fetches just the changed entries' bytes out of the same zip. Every zip
 * the user uploads works, from any installed version.
 *
 * The staged tree holds only the changed files; the robocopy swap copies them over the install
 * and leaves everything else alone. Anything this code can't handle (zip64, an unknown compression
 * method, a server that ignores Range, a mostly-changed release) throws or returns null, and the
 * caller falls back to downloading the whole zip.
 */

/** One file in a zip's central directory. [name] is normalised to `/` separators. */
internal data class ZipCentralEntry(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc32: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

internal class ZipIndex(
    val entries: List<ZipCentralEntry>,
    val centralDirectoryOffset: Long,
)

/** Random access to a remote (or, in tests, local) zip. */
internal interface ZipRangeSource {
    val length: Long

    /** Bytes `[start, endExclusive)`. */
    fun open(start: Long, endExclusive: Long): InputStream
}

internal class DeltaPlan(
    /** Directory inside the zip that holds Nuvio.exe: `""` or e.g. `"Nuvio/"`. */
    val imagePrefix: String,
    /** Every file of the new build, relative to the app-image root, `\`-separated. */
    val newFiles: List<String>,
    /** Entries that differ from (or are missing in) the install folder. */
    val changed: List<ZipCentralEntry>,
) {
    val changedCompressedBytes: Long get() = changed.sumOf { it.compressedSize }
}

internal object ZipDelta {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    /** Neighbouring changed entries closer than this are fetched with one request. */
    private const val COALESCE_GAP_BYTES = 256L * 1024

    fun readIndex(source: ZipRangeSource): ZipIndex {
        val tailLength = minOf(source.length, (EOCD_MIN_SIZE + MAX_COMMENT).toLong())
        val tailStart = source.length - tailLength
        val tail = source.open(tailStart, source.length).use { it.readExactly(tailLength.toInt()) }
        val (centralOffset, centralSize) = locateCentralDirectory(tail)
        require(centralOffset + centralSize <= tailStart + tail.size) { "Central directory lies outside the archive." }
        val central = if (centralOffset >= tailStart) {
            val from = (centralOffset - tailStart).toInt()
            tail.copyOfRange(from, from + centralSize.toInt())
        } else {
            source.open(centralOffset, centralOffset + centralSize).use { it.readExactly(centralSize.toInt()) }
        }
        return ZipIndex(parseCentralDirectory(central), centralOffset)
    }

    /** Returns (offset, size) of the central directory from the archive's tail bytes. */
    fun locateCentralDirectory(tail: ByteArray): Pair<Long, Long> {
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (position in tail.size - EOCD_MIN_SIZE downTo 0) {
            if (buffer.getInt(position) != EOCD_SIGNATURE) continue
            val commentLength = buffer.u16(position + 20)
            if (position + EOCD_MIN_SIZE + commentLength != tail.size) continue
            val entryCount = buffer.u16(position + 10)
            val size = buffer.u32(position + 12)
            val offset = buffer.u32(position + 16)
            // The 0xFFFF / 0xFFFFFFFF sentinels mean the real values live in a zip64 record. A
            // release zip is far below those limits, so zip64 only shows up if a tool forces it.
            require(entryCount != 0xFFFF && size != 0xFFFFFFFFL && offset != 0xFFFFFFFFL) {
                "zip64 archives are not supported for delta updates."
            }
            return offset to size
        }
        throw IllegalArgumentException("No zip end-of-central-directory record found.")
    }

    fun parseCentralDirectory(bytes: ByteArray): List<ZipCentralEntry> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val entries = mutableListOf<ZipCentralEntry>()
        var position = 0
        while (position + 46 <= bytes.size && buffer.getInt(position) == CENTRAL_SIGNATURE) {
            val flags = buffer.u16(position + 8)
            val nameLength = buffer.u16(position + 28)
            val extraLength = buffer.u16(position + 30)
            val commentLength = buffer.u16(position + 32)
            require(position + 46 + nameLength <= bytes.size) { "Truncated central directory." }
            val nameBytes = bytes.copyOfRange(position + 46, position + 46 + nameLength)
            // Bit 11 = UTF-8 name; otherwise CP437, which is identical for the ASCII names we ship.
            val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            entries += ZipCentralEntry(
                name = name.replace('\\', '/'),
                method = buffer.u16(position + 10),
                flags = flags,
                crc32 = buffer.u32(position + 16),
                compressedSize = buffer.u32(position + 20),
                size = buffer.u32(position + 24),
                localHeaderOffset = buffer.u32(position + 42),
            )
            position += 46 + nameLength + extraLength + commentLength
        }
        require(entries.isNotEmpty()) { "Empty or unreadable central directory." }
        return entries
    }

    /**
     * Compares the archive's app image against [installRoot]. Returns null when the archive has no
     * recognisable app image (no Nuvio.exe at its root or one folder down).
     */
    fun plan(entries: List<ZipCentralEntry>, installRoot: File, launcherName: String): DeltaPlan? {
        val prefix = findImagePrefix(entries, launcherName) ?: return null
        val files = entries.filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.length > prefix.length }
        files.forEach { require(isSafeRelativePath(it.name.removePrefix(prefix))) { "Unsafe entry name: ${it.name}" } }
        val changed = files.filterNot { entry ->
            val local = File(installRoot, entry.name.removePrefix(prefix))
            local.isFile && local.length() == entry.size && crc32Of(local) == entry.crc32
        }
        return DeltaPlan(
            imagePrefix = prefix,
            newFiles = files.map { it.name.removePrefix(prefix).replace('/', '\\') },
            changed = changed,
        )
    }

    /**
     * Downloads every entry of [plan]'s changed list into [targetRoot] (mirroring the app-image
     * layout) and verifies each one's size and CRC32 against the central directory.
     */
    fun fetchChanged(
        source: ZipRangeSource,
        index: ZipIndex,
        plan: DeltaPlan,
        targetRoot: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val total = plan.changedCompressedBytes
        if (plan.changed.isEmpty()) {
            onProgress(0L, 0L)
            return
        }
        // Each entry's bytes run up to the next entry's local header (or the central directory).
        val sortedOffsets = index.entries.map { it.localHeaderOffset }.distinct().sorted()
        fun spanEnd(entry: ZipCentralEntry): Long {
            val next = sortedOffsets.binarySearch(entry.localHeaderOffset) + 1
            return if (next < sortedOffsets.size) sortedOffsets[next] else index.centralDirectoryOffset
        }

        val canonicalTarget = targetRoot.canonicalFile.toPath()
        var downloaded = 0L
        coalesce(plan.changed.sortedBy { it.localHeaderOffset }, ::spanEnd).forEach { group ->
            val groupStart = group.first().localHeaderOffset
            val groupEnd = spanEnd(group.last())
            ResumingRangeStream(source, groupStart, groupEnd).use { input ->
                var position = groupStart
                group.forEach { entry ->
                    input.skipExactly(entry.localHeaderOffset - position)
                    position = entry.localHeaderOffset
                    val outFile = File(targetRoot, entry.name.removePrefix(plan.imagePrefix)).canonicalFile
                    check(outFile.toPath().startsWith(canonicalTarget)) { "Refusing to stage outside the target: ${entry.name}" }
                    outFile.parentFile?.mkdirs()
                    val consumed = extractEntry(input, entry, outFile) { bytes ->
                        downloaded += bytes
                        onProgress(downloaded, total)
                    }
                    position += consumed
                    check(position <= spanEnd(entry)) { "Entry ${entry.name} overran its span." }
                }
            }
        }
    }

    private fun coalesce(
        sorted: List<ZipCentralEntry>,
        spanEnd: (ZipCentralEntry) -> Long,
    ): List<List<ZipCentralEntry>> {
        val groups = mutableListOf<MutableList<ZipCentralEntry>>()
        sorted.forEach { entry ->
            val current = groups.lastOrNull()
            if (current != null && entry.localHeaderOffset - spanEnd(current.last()) <= COALESCE_GAP_BYTES) {
                current += entry
            } else {
                groups += mutableListOf(entry)
            }
        }
        return groups
    }

    /** Reads one local entry from [input] into [outFile]; returns the number of bytes consumed. */
    private fun extractEntry(
        input: InputStream,
        entry: ZipCentralEntry,
        outFile: File,
        onCompressedBytes: (Int) -> Unit,
    ): Long {
        require(entry.flags and 0x1 == 0) { "Encrypted entry: ${entry.name}" }
        require(entry.method == METHOD_STORED || entry.method == METHOD_DEFLATED) {
            "Unsupported compression method ${entry.method} for ${entry.name}"
        }
        val header = ByteBuffer.wrap(input.readExactly(30)).order(ByteOrder.LITTLE_ENDIAN)
        check(header.getInt(0) == LOCAL_SIGNATURE) { "Bad local header for ${entry.name}" }
        val headerExtra = header.u16(26).toLong() + header.u16(28)
        input.skipExactly(headerExtra)

        val crc = CRC32()
        var written = 0L
        var remaining = entry.compressedSize
        val readBuffer = ByteArray(256 * 1024)
        FileOutputStream(outFile).use { output ->
            fun emit(bytes: ByteArray, count: Int) {
                output.write(bytes, 0, count)
                crc.update(bytes, 0, count)
                written += count
            }
            if (entry.method == METHOD_STORED) {
                while (remaining > 0) {
                    val read = input.read(readBuffer, 0, minOf(readBuffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw EOFException("Truncated entry ${entry.name}")
                    emit(readBuffer, read)
                    remaining -= read
                    onCompressedBytes(read)
                }
            } else {
                val inflater = Inflater(true)
                val outBuffer = ByteArray(256 * 1024)
                try {
                    while (!inflater.finished()) {
                        if (inflater.needsInput()) {
                            check(remaining > 0) { "Truncated deflate stream for ${entry.name}" }
                            val read = input.read(readBuffer, 0, minOf(readBuffer.size.toLong(), remaining).toInt())
                            if (read < 0) throw EOFException("Truncated entry ${entry.name}")
                            inflater.setInput(readBuffer, 0, read)
                            remaining -= read
                            onCompressedBytes(read)
                        }
                        val produced = inflater.inflate(outBuffer)
                        if (produced > 0) emit(outBuffer, produced)
                        check(!inflater.needsDictionary()) { "Deflate stream for ${entry.name} needs a dictionary" }
                    }
                    // The stream ended inside the last chunk; anything left of the entry is unread.
                    if (remaining > 0) {
                        input.skipExactly(remaining)
                        onCompressedBytes(remaining.toInt())
                    }
                } finally {
                    inflater.end()
                }
            }
        }
        check(written == entry.size && crc.value == entry.crc32) {
            "Downloaded ${entry.name} failed verification (size $written/${entry.size}, CRC mismatch=${crc.value != entry.crc32})."
        }
        return 30 + headerExtra + entry.compressedSize
    }

    private fun findImagePrefix(entries: List<ZipCentralEntry>, launcherName: String): String? {
        val names = entries.mapTo(HashSet()) { it.name.lowercase() }
        if (launcherName.lowercase() in names) return ""
        return entries.asSequence()
            .map { it.name }
            .filter { name ->
                val slash = name.indexOf('/')
                slash > 0 && name.substring(slash + 1).equals(launcherName, ignoreCase = true)
            }
            .map { it.substring(0, it.indexOf('/') + 1) }
            .firstOrNull()
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("/") &&
            ':' !in path &&
            path.split('/').none { it == ".." || it == "." }

    fun crc32Of(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(1024 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
    private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL
}

/**
 * Bytes `[start, endExclusive)` of [source] that survive a dropped connection: when a read fails
 * part-way, the remaining range is requested again (up to [maxResumes] times) and reading carries on
 * where it stopped. On the native build every update fetches the whole ~86 MB compressed exe as one
 * entry, and without this one dropped connection threw the delta away for the even bigger full zip.
 * The entry's CRC32 is still checked against the central directory after a resume.
 */
internal class ResumingRangeStream(
    private val source: ZipRangeSource,
    start: Long,
    private val endExclusive: Long,
    private val maxResumes: Int = 3,
) : InputStream() {
    private var position = start
    private var resumes = 0
    private var current: InputStream? = source.open(start, endExclusive)

    override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= endExclusive) return -1
        while (true) {
            val stream = current ?: source.open(position, endExclusive).also { current = it }
            val read = try {
                stream.read(buffer, offset, minOf(length.toLong(), endExclusive - position).toInt())
            } catch (error: java.io.IOException) {
                if (resumes >= maxResumes) throw error
                resumes++
                runCatching { stream.close() }
                current = null
                continue
            }
            if (read < 0) {
                // The connection ended before the range did: same as a dropped read.
                if (resumes >= maxResumes) throw EOFException("Range ended at $position of $endExclusive")
                resumes++
                runCatching { stream.close() }
                current = null
                continue
            }
            position += read
            return read
        }
    }

    override fun skip(count: Long): Long {
        // Skipping by reading keeps [position] right for a resume; skipped spans are small (the
        // gaps between coalesced entries and their local headers).
        val scratch = ByteArray(minOf(count, 64L * 1024).toInt().coerceAtLeast(1))
        var skipped = 0L
        while (skipped < count) {
            val read = read(scratch, 0, minOf(scratch.size.toLong(), count - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    override fun close() {
        current?.close()
        current = null
    }
}

/** A zip served over HTTPS with byte-range support (GitHub release assets are). */
internal class HttpZipRangeSource(
    private val client: HttpClient,
    private val url: String,
) : ZipRangeSource {
    override val length: Long by lazy {
        val response = send(0, 1)
        response.body().close()
        contentRangeTotal(response) ?: error("Server did not report the archive length.")
    }

    override fun open(start: Long, endExclusive: Long): InputStream {
        require(endExclusive > start) { "Empty range" }
        val response = send(start, endExclusive)
        val range = response.headers().firstValue("Content-Range").orElse("")
        if (response.statusCode() != 206 || !range.startsWith("bytes $start-")) {
            response.body().close()
            error("Server ignored the byte range (HTTP ${response.statusCode()}).")
        }
        return response.body()
    }

    private fun send(start: Long, endExclusive: Long): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .header("Accept", "application/octet-stream")
            .header("Range", "bytes=$start-${endExclusive - 1}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Range request failed with HTTP ${response.statusCode()}.")
        }
        return response
    }

    private fun contentRangeTotal(response: HttpResponse<*>): Long? =
        response.headers().firstValue("Content-Range").orElse(null)
            ?.substringAfterLast('/')
            ?.toLongOrNull()
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw EOFException("Expected $count bytes, got $offset")
        offset += read
    }
    return bytes
}

private fun InputStream.skipExactly(count: Long) {
    require(count >= 0) { "Negative skip" }
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) throw EOFException("Expected to skip $count bytes")
            remaining--
        }
    }
}
