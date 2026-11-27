package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Compact [DiskCache] backed by primitive arrays, compatible on disk with Coil's
 * `libcore.io.DiskLruCache` journal and `<sha256>.0` / `<sha256>.1` entry files.
 *
 * Coil's `RealDiskCache` keeps a `LinkedHashMap<String, Entry>` where each entry retains a
 * 64-char hex key String plus four `okio.Path` objects (`cleanFiles` + `dirtyFiles`) holding the
 * full Windows path — ~1,040 bytes per entry, or 84 MB of live heap for an 80,649-entry stills
 * cache (2026-11-25 baseline heap dump).
 *
 * This implementation packs every entry into parallel primitive arrays: 4 `Long`s for the raw
 * 256-bit SHA-256 key, 2 `Long`s for metadata/data lengths, 2 `Int`s for an intrusive doubly-linked
 * LRU list, 1 `Byte` of flags, and an open-addressing `IntArray` hash index (~60–80 bytes/entry
 * including hash headroom). `okio.Path` instances are constructed only while an entry is actively
 * being read, edited, removed, or evicted.
 */
internal class CompactImageDiskCache(
    private val journalDir: Path,
    private val entryDir: Path,
    override val maxSize: Long,
    override val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DiskCache {

    init {
        require(maxSize > 0L) { "maxSize must be > 0" }
    }

    override val directory: okio.Path = journalDir.toOkioPath()
    private val entryRoot: okio.Path = entryDir.toOkioPath()
    private val journalFile: Path = journalDir.resolve(JournalName)
    private val journalTmpFile: Path = journalDir.resolve(JournalTmpName)
    private val journalBackupFile: Path = journalDir.resolve(JournalBackupName)

    private val lock = Any()
    private var initialized = false
    private var closed = false
    private var hasJournalErrors = false

    /** Set when the replayed journal's header was not Coil's own; rewritten so Coil can read it. */
    private var journalHeaderStale = false
    private var totalSize = 0L
    private var redundantOpCount = 0
    private var journalWriter: OutputStreamWriter? = null

    // Parallel slot arrays (slot 0..allocatedSlots-1).
    private var k0 = LongArray(InitialCapacity)
    private var k1 = LongArray(InitialCapacity)
    private var k2 = LongArray(InitialCapacity)
    private var k3 = LongArray(InitialCapacity)
    private var metaLen = LongArray(InitialCapacity)
    private var dataLen = LongArray(InitialCapacity)
    private var prev = IntArray(InitialCapacity) { None }
    private var next = IntArray(InitialCapacity) { None }
    private var flags = ByteArray(InitialCapacity)

    private var allocatedSlots = 0
    private var freeHead = None
    private var liveCount = 0
    private var lruHead = None // oldest
    private var lruTail = None // newest

    // Open-addressing hash table mapping (k0..k3) -> slot.
    private var buckets = IntArray(InitialBucketCount) { BucketEmpty }
    private var usedBuckets = 0

    // Only in-flight snapshots/editors live here (typically 0..10 entries).
    private val activeEditors = HashMap<Int, EditorImpl>()
    private val snapshotCounts = HashMap<Int, Int>()

    override val size: Long
        get() = synchronized(lock) {
            ensureInitialized()
            totalSize
        }

    internal val entryCount: Int
        get() = synchronized(lock) {
            ensureInitialized()
            liveCount
        }

    override fun openSnapshot(key: String): DiskCache.Snapshot? {
        val hash = sha256(key)
        val h0 = packLong(hash, 0)
        val h1 = packLong(hash, 8)
        val h2 = packLong(hash, 16)
        val h3 = packLong(hash, 24)
        return openSnapshotByHash(h0, h1, h2, h3)
    }

    private fun openSnapshotByHash(h0: Long, h1: Long, h2: Long, h3: Long): DiskCache.Snapshot? = synchronized(lock) {
        if (closed) return null
        ensureInitialized()
        val slot = findSlot(h0, h1, h2, h3)
        if (slot == None) return null
        if ((flags[slot].toInt() and FlagReadable) == 0) return null

        val hex = formatHex(h0, h1, h2, h3)
        val metaPath = entryDir.resolve("$hex.0")
        val dataPath = entryDir.resolve("$hex.1")
        if (!Files.exists(metaPath) || !Files.exists(dataPath)) {
            removeSlotLocked(slot, hex)
            return null
        }

        snapshotCounts[slot] = (snapshotCounts[slot] ?: 0) + 1
        moveToMru(slot)
        redundantOpCount++
        writeJournalLine("READ $hex\n", flush = false)
        if (needsJournalRebuild()) rebuildJournalLocked()

        SnapshotImpl(
            slot = slot,
            h0 = h0,
            h1 = h1,
            h2 = h2,
            h3 = h3,
            metadata = entryRoot / "$hex.0",
            data = entryRoot / "$hex.1",
        )
    }

    override fun openEditor(key: String): DiskCache.Editor? {
        val hash = sha256(key)
        val h0 = packLong(hash, 0)
        val h1 = packLong(hash, 8)
        val h2 = packLong(hash, 16)
        val h3 = packLong(hash, 24)
        return openEditorByHash(h0, h1, h2, h3)
    }

    private fun openEditorByHash(h0: Long, h1: Long, h2: Long, h3: Long): DiskCache.Editor? = synchronized(lock) {
        if (closed) return null
        ensureInitialized()
        var slot = findSlot(h0, h1, h2, h3)
        if (slot != None) {
            if (activeEditors.containsKey(slot)) return null
        } else {
            slot = allocateSlot(h0, h1, h2, h3)
            insertBucket(slot, h0)
            appendToMru(slot)
            liveCount++
        }

        val hex = formatHex(h0, h1, h2, h3)
        val editor = EditorImpl(slot = slot, hex = hex)
        activeEditors[slot] = editor
        writeJournalLine("DIRTY $hex\n", flush = true)
        if (hasJournalErrors) {
            rebuildJournalLocked()
        }
        editor
    }

    override fun remove(key: String): Boolean {
        val hash = sha256(key)
        val h0 = packLong(hash, 0)
        val h1 = packLong(hash, 8)
        val h2 = packLong(hash, 16)
        val h3 = packLong(hash, 24)
        return synchronized(lock) {
            if (closed) return false
            ensureInitialized()
            val slot = findSlot(h0, h1, h2, h3)
            if (slot == None) return false
            removeSlotLocked(slot, formatHex(h0, h1, h2, h3))
            true
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (closed) return
            ensureInitialized()
            var slot = lruHead
            while (slot != None) {
                val nextSlot = next[slot]
                val hex = formatHex(k0[slot], k1[slot], k2[slot], k3[slot])
                removeSlotLocked(slot, hex, writeRemoveLine = false)
                slot = nextSlot
            }
            rebuildJournalLocked()
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            if (closed) return
            if (initialized) {
                if (activeEditors.isNotEmpty()) {
                    activeEditors.values.toList().forEach { editor ->
                        runCatching { completeEditLocked(editor, success = false) }
                    }
                }
                trimToSizeLocked()
                closeJournalWriterLocked()
            }
            closed = true
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        Files.createDirectories(journalDir)
        Files.createDirectories(entryDir)

        if (!Files.exists(journalFile) && Files.exists(journalBackupFile)) {
            runCatching {
                Files.move(journalBackupFile, journalFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        if (Files.exists(journalFile)) {
            val replayed = runCatching { replayJournalLocked() }.isSuccess
            if (replayed) {
                // Writer first: the trim below records its REMOVEs, or the trimmed entries come back
                // from the journal on the next launch and are trimmed (and miscounted) all over again.
                openJournalWriterLocked(append = true)
                trimToSizeLocked()
                if (needsJournalRebuild() || journalHeaderStale || hasJournalErrors) rebuildJournalLocked()
                return
            }
            // Corrupt journal: reset in-memory index and start fresh without touching entry files.
            resetIndexLocked()
        }
        rebuildJournalLocked()
    }

    private fun replayJournalLocked() {
        val dirtySlots = HashSet<Int>()
        var lineCount = 0
        Files.newBufferedReader(journalFile, StandardCharsets.UTF_8).use { reader ->
            val magic = reader.readLine()
            val version = reader.readLine()
            val appVersion = reader.readLine()
            val valueCount = reader.readLine()
            val blank = reader.readLine()
            if (magic != JournalMagic ||
                version != JournalVersion ||
                appVersion.isNullOrEmpty() ||
                valueCount != ValueCountText ||
                blank != ""
            ) {
                throw IOException("Unexpected journal header: [$magic, $version, $appVersion, $valueCount, $blank]")
            }
            journalHeaderStale = appVersion != AppVersionText

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                lineCount++
                parseJournalLineLocked(line, dirtySlots)
            }
        }

        // Clean up any entry left DIRTY by a previous crash.
        if (dirtySlots.isNotEmpty()) {
            for (slot in dirtySlots) {
                if ((flags[slot].toInt() and FlagOccupied) == 0) continue
                val hex = formatHex(k0[slot], k1[slot], k2[slot], k3[slot])
                deleteQuietly(entryDir.resolve("$hex.0.tmp"))
                deleteQuietly(entryDir.resolve("$hex.1.tmp"))
                if ((flags[slot].toInt() and FlagReadable) == 0) {
                    removeFromBucket(slot, k0[slot])
                    unlinkFromLru(slot)
                    liveCount--
                    freeSlot(slot)
                }
            }
        }

        redundantOpCount = (lineCount - liveCount).coerceAtLeast(0)
    }

    private fun parseJournalLineLocked(line: String, dirtySlots: MutableSet<Int>) {
        val firstSpace = line.indexOf(' ')
        if (firstSpace <= 0) throw IOException("Malformed journal line: $line")

        val keyStart = firstSpace + 1
        val secondSpace = line.indexOf(' ', keyStart)
        val keyEnd = if (secondSpace == -1) line.length else secondSpace
        if (keyEnd - keyStart != 64) throw IOException("Invalid key length in journal line: $line")

        val h0 = parseHexLong(line, keyStart)
        val h1 = parseHexLong(line, keyStart + 16)
        val h2 = parseHexLong(line, keyStart + 32)
        val h3 = parseHexLong(line, keyStart + 48)

        when {
            line.startsWith("CLEAN ") && secondSpace != -1 -> {
                val thirdSpace = line.indexOf(' ', secondSpace + 1)
                if (thirdSpace == -1) throw IOException("Missing lengths in CLEAN line: $line")
                val len0 = parseUnsignedLong(line, secondSpace + 1, thirdSpace)
                val len1 = parseUnsignedLong(line, thirdSpace + 1, line.length)
                var slot = findSlot(h0, h1, h2, h3)
                if (slot == None) {
                    slot = allocateSlot(h0, h1, h2, h3)
                    insertBucket(slot, h0)
                    appendToMru(slot)
                    liveCount++
                } else {
                    if ((flags[slot].toInt() and FlagReadable) != 0) {
                        totalSize -= metaLen[slot] + dataLen[slot]
                    }
                    moveToMru(slot)
                }
                metaLen[slot] = len0
                dataLen[slot] = len1
                totalSize += len0 + len1
                flags[slot] = (FlagOccupied or FlagReadable).toByte()
                dirtySlots.remove(slot)
            }
            line.startsWith("READ ") && secondSpace == -1 -> {
                val slot = findSlot(h0, h1, h2, h3)
                if (slot != None) moveToMru(slot)
            }
            line.startsWith("DIRTY ") && secondSpace == -1 -> {
                var slot = findSlot(h0, h1, h2, h3)
                if (slot == None) {
                    slot = allocateSlot(h0, h1, h2, h3)
                    insertBucket(slot, h0)
                    appendToMru(slot)
                    liveCount++
                }
                dirtySlots.add(slot)
            }
            line.startsWith("REMOVE ") && secondSpace == -1 -> {
                val slot = findSlot(h0, h1, h2, h3)
                if (slot != None) {
                    if ((flags[slot].toInt() and FlagReadable) != 0) {
                        totalSize -= metaLen[slot] + dataLen[slot]
                    }
                    removeFromBucket(slot, h0)
                    unlinkFromLru(slot)
                    liveCount--
                    dirtySlots.remove(slot)
                    freeSlot(slot)
                }
            }
            else -> throw IOException("Unexpected journal verb: $line")
        }
    }

    private fun rebuildJournalLocked() {
        closeJournalWriterLocked()
        Files.createDirectories(journalDir)
        OutputStreamWriter(
            BufferedOutputStream(
                Files.newOutputStream(
                    journalTmpFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ),
                32 * 1024,
            ),
            StandardCharsets.UTF_8,
        ).use { writer ->
            writer.write(JournalMagic)
            writer.write('\n'.code)
            writer.write(JournalVersion)
            writer.write('\n'.code)
            writer.write(AppVersionText)
            writer.write('\n'.code)
            writer.write(ValueCountText)
            writer.write('\n'.code)
            writer.write('\n'.code)

            var slot = lruHead
            while (slot != None) {
                val hex = formatHex(k0[slot], k1[slot], k2[slot], k3[slot])
                if (activeEditors.containsKey(slot)) {
                    writer.write("DIRTY ")
                    writer.write(hex)
                    writer.write('\n'.code)
                } else if ((flags[slot].toInt() and FlagReadable) != 0) {
                    writer.write("CLEAN ")
                    writer.write(hex)
                    writer.write(' '.code)
                    writer.write(metaLen[slot].toString())
                    writer.write(' '.code)
                    writer.write(dataLen[slot].toString())
                    writer.write('\n'.code)
                }
                slot = next[slot]
            }
            writer.flush()
        }

        if (Files.exists(journalFile)) {
            runCatching {
                Files.move(journalFile, journalBackupFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        runCatching {
            Files.move(journalTmpFile, journalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(journalTmpFile, journalFile, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
        deleteQuietly(journalBackupFile)

        redundantOpCount = 0
        hasJournalErrors = false
        journalHeaderStale = false
        openJournalWriterLocked(append = true)
    }

    private fun openJournalWriterLocked(append: Boolean) {
        closeJournalWriterLocked()
        val options = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
        journalWriter = OutputStreamWriter(
            BufferedOutputStream(Files.newOutputStream(journalFile, *options), 8 * 1024),
            StandardCharsets.UTF_8,
        )
    }

    private fun closeJournalWriterLocked() {
        journalWriter?.let { writer ->
            runCatching {
                writer.flush()
                writer.close()
            }
        }
        journalWriter = null
    }

    private fun writeJournalLine(line: String, flush: Boolean) {
        val writer = journalWriter ?: return
        try {
            writer.write(line)
            if (flush) writer.flush()
        } catch (_: IOException) {
            hasJournalErrors = true
        }
    }

    private fun needsJournalRebuild(): Boolean =
        redundantOpCount >= JournalCompactThreshold && redundantOpCount >= liveCount

    private fun completeEditLocked(editor: EditorImpl, success: Boolean) {
        val slot = editor.slot
        if (activeEditors[slot] !== editor) return
        activeEditors.remove(slot)

        val hex = editor.hex
        val dirtyMeta = entryDir.resolve("$hex.0.tmp")
        val dirtyData = entryDir.resolve("$hex.1.tmp")
        val cleanMeta = entryDir.resolve("$hex.0")
        val cleanData = entryDir.resolve("$hex.1")

        if (editor.detached) {
            val replacement = findSlot(k0[slot], k1[slot], k2[slot], k3[slot])
            if (replacement == None || !activeEditors.containsKey(replacement)) {
                deleteQuietly(dirtyMeta)
                deleteQuietly(dirtyData)
            }
            finalizeZombieSlotLocked(slot, hex)
            return
        }

        val wasReadable = (flags[slot].toInt() and FlagReadable) != 0
        if (success && !wasReadable) {
            // Brand-new entry: both metadata and data must have been created.
            if (!editor.writtenMeta || !editor.writtenData || !Files.exists(dirtyMeta) || !Files.exists(dirtyData)) {
                completeAbortLocked(slot, hex, wasReadable = false, dirtyMeta, dirtyData)
                return
            }
        }

        if (success) {
            var movedMeta = false
            val previousMetaLen = metaLen[slot]
            try {
                if (editor.writtenMeta && Files.exists(dirtyMeta)) {
                    moveReplace(dirtyMeta, cleanMeta)
                    val newLen = Files.size(cleanMeta)
                    if (wasReadable) totalSize -= previousMetaLen
                    metaLen[slot] = newLen
                    totalSize += newLen
                    movedMeta = true
                } else {
                    deleteQuietly(dirtyMeta)
                }
                if (editor.writtenData && Files.exists(dirtyData)) {
                    moveReplace(dirtyData, cleanData)
                    val newLen = Files.size(cleanData)
                    if (wasReadable) totalSize -= dataLen[slot]
                    dataLen[slot] = newLen
                    totalSize += newLen
                } else {
                    deleteQuietly(dirtyData)
                }
            } catch (_: IOException) {
                if (movedMeta && !wasReadable) {
                    totalSize -= metaLen[slot]
                    metaLen[slot] = previousMetaLen
                    deleteQuietly(cleanMeta)
                }
                completeAbortLocked(slot, hex, wasReadable, dirtyMeta, dirtyData)
                return
            }

            flags[slot] = (FlagOccupied or FlagReadable).toByte()
            moveToMru(slot)
            redundantOpCount++
            writeJournalLine("CLEAN $hex ${metaLen[slot]} ${dataLen[slot]}\n", flush = true)
            trimToSizeLocked()
            if (needsJournalRebuild() || hasJournalErrors) rebuildJournalLocked()
        } else {
            completeAbortLocked(slot, hex, wasReadable, dirtyMeta, dirtyData)
        }
    }

    private fun completeAbortLocked(
        slot: Int,
        hex: String,
        wasReadable: Boolean,
        dirtyMeta: Path,
        dirtyData: Path,
    ) {
        deleteQuietly(dirtyMeta)
        deleteQuietly(dirtyData)
        if (!wasReadable) {
            removeFromBucket(slot, k0[slot])
            unlinkFromLru(slot)
            liveCount--
            redundantOpCount++
            writeJournalLine("REMOVE $hex\n", flush = true)
            if ((snapshotCounts[slot] ?: 0) == 0) {
                freeSlot(slot)
            } else {
                flags[slot] = FlagZombie.toByte()
            }
            if (needsJournalRebuild() || hasJournalErrors) rebuildJournalLocked()
        }
    }

    private fun removeSlotLocked(slot: Int, hex: String, writeRemoveLine: Boolean = true) {
        if ((flags[slot].toInt() and FlagReadable) != 0) {
            totalSize -= metaLen[slot] + dataLen[slot]
        }
        removeFromBucket(slot, k0[slot])
        unlinkFromLru(slot)
        liveCount--

        val editor = activeEditors[slot]
        if (editor != null) {
            editor.detached = true
        }

        if (writeRemoveLine) {
            redundantOpCount++
            writeJournalLine("REMOVE $hex\n", flush = true)
        }

        val openSnapshots = snapshotCounts[slot] ?: 0
        if (openSnapshots == 0 && editor == null) {
            deleteQuietly(entryDir.resolve("$hex.0"))
            deleteQuietly(entryDir.resolve("$hex.1"))
            freeSlot(slot)
        } else {
            flags[slot] = FlagZombie.toByte()
        }

        if (writeRemoveLine && (needsJournalRebuild() || hasJournalErrors)) {
            rebuildJournalLocked()
        }
    }

    private fun finalizeZombieSlotLocked(slot: Int, hex: String) {
        if ((flags[slot].toInt() and FlagZombie) == 0) return
        if ((snapshotCounts[slot] ?: 0) > 0 || activeEditors.containsKey(slot)) return
        if (findSlot(k0[slot], k1[slot], k2[slot], k3[slot]) == None) {
            deleteQuietly(entryDir.resolve("$hex.0"))
            deleteQuietly(entryDir.resolve("$hex.1"))
        }
        freeSlot(slot)
    }

    private fun trimToSizeLocked() {
        while (totalSize > maxSize && lruHead != None) {
            var candidate = lruHead
            while (candidate != None && (activeEditors.containsKey(candidate) || (snapshotCounts[candidate] ?: 0) > 0)) {
                candidate = next[candidate]
            }
            if (candidate == None) break
            val hex = formatHex(k0[candidate], k1[candidate], k2[candidate], k3[candidate])
            removeSlotLocked(candidate, hex)
        }
    }

    private fun resetIndexLocked() {
        k0 = LongArray(InitialCapacity)
        k1 = LongArray(InitialCapacity)
        k2 = LongArray(InitialCapacity)
        k3 = LongArray(InitialCapacity)
        metaLen = LongArray(InitialCapacity)
        dataLen = LongArray(InitialCapacity)
        prev = IntArray(InitialCapacity) { None }
        next = IntArray(InitialCapacity) { None }
        flags = ByteArray(InitialCapacity)
        buckets = IntArray(InitialBucketCount) { BucketEmpty }
        allocatedSlots = 0
        freeHead = None
        liveCount = 0
        lruHead = None
        lruTail = None
        usedBuckets = 0
        totalSize = 0L
        redundantOpCount = 0
        activeEditors.clear()
        snapshotCounts.clear()
    }

    // region Slot & LRU management

    private fun allocateSlot(h0: Long, h1: Long, h2: Long, h3: Long): Int {
        val slot = if (freeHead != None) {
            val s = freeHead
            freeHead = next[s]
            s
        } else {
            if (allocatedSlots == k0.size) growSlots()
            allocatedSlots++
        }
        k0[slot] = h0
        k1[slot] = h1
        k2[slot] = h2
        k3[slot] = h3
        metaLen[slot] = 0L
        dataLen[slot] = 0L
        prev[slot] = None
        next[slot] = None
        flags[slot] = FlagOccupied.toByte()
        return slot
    }

    private fun freeSlot(slot: Int) {
        flags[slot] = 0
        prev[slot] = None
        next[slot] = freeHead
        freeHead = slot
    }

    private fun growSlots() {
        val oldCap = k0.size
        val newCap = maxOf(oldCap + (oldCap ushr 1), oldCap + 64)
        k0 = k0.copyOf(newCap)
        k1 = k1.copyOf(newCap)
        k2 = k2.copyOf(newCap)
        k3 = k3.copyOf(newCap)
        metaLen = metaLen.copyOf(newCap)
        dataLen = dataLen.copyOf(newCap)
        prev = prev.copyOf(newCap).also { it.fill(None, oldCap, newCap) }
        next = next.copyOf(newCap).also { it.fill(None, oldCap, newCap) }
        flags = flags.copyOf(newCap)
    }

    private fun appendToMru(slot: Int) {
        prev[slot] = lruTail
        next[slot] = None
        if (lruTail != None) {
            next[lruTail] = slot
        } else {
            lruHead = slot
        }
        lruTail = slot
    }

    private fun unlinkFromLru(slot: Int) {
        val p = prev[slot]
        val n = next[slot]
        if (p != None) next[p] = n else lruHead = n
        if (n != None) prev[n] = p else lruTail = p
        prev[slot] = None
        next[slot] = None
    }

    private fun moveToMru(slot: Int) {
        if (slot == lruTail) return
        unlinkFromLru(slot)
        appendToMru(slot)
    }

    // endregion

    // region Open-addressing hash table

    private fun findSlot(h0: Long, h1: Long, h2: Long, h3: Long): Int {
        val mask = buckets.size - 1
        var idx = hashIndex(h0, mask)
        while (true) {
            val s = buckets[idx]
            if (s == BucketEmpty) return None
            if (s >= 0 && k0[s] == h0 && k1[s] == h1 && k2[s] == h2 && k3[s] == h3) return s
            idx = (idx + 1) and mask
        }
    }

    private fun insertBucket(slot: Int, h0: Long) {
        if ((usedBuckets + 1) * 10 >= buckets.size * 7) {
            val newCap = if (liveCount * 2 >= buckets.size) buckets.size shl 1 else buckets.size
            rehash(newCap)
        }
        val mask = buckets.size - 1
        var idx = hashIndex(h0, mask)
        while (true) {
            val existing = buckets[idx]
            if (existing == BucketEmpty) {
                buckets[idx] = slot
                usedBuckets++
                return
            }
            if (existing == BucketTombstone) {
                buckets[idx] = slot
                return
            }
            idx = (idx + 1) and mask
        }
    }

    private fun removeFromBucket(slot: Int, h0: Long) {
        val mask = buckets.size - 1
        var idx = hashIndex(h0, mask)
        while (true) {
            val existing = buckets[idx]
            if (existing == BucketEmpty) return
            if (existing == slot) {
                buckets[idx] = BucketTombstone
                return
            }
            idx = (idx + 1) and mask
        }
    }

    private fun rehash(newCapacity: Int) {
        val newBuckets = IntArray(newCapacity) { BucketEmpty }
        val mask = newCapacity - 1
        var count = 0
        var slot = lruHead
        while (slot != None) {
            var idx = hashIndex(k0[slot], mask)
            while (newBuckets[idx] != BucketEmpty) {
                idx = (idx + 1) and mask
            }
            newBuckets[idx] = slot
            count++
            slot = next[slot]
        }
        buckets = newBuckets
        usedBuckets = count
    }

    // endregion

    private inner class SnapshotImpl(
        private val slot: Int,
        private val h0: Long,
        private val h1: Long,
        private val h2: Long,
        private val h3: Long,
        override val metadata: okio.Path,
        override val data: okio.Path,
    ) : DiskCache.Snapshot {
        private var isClosed = false

        override fun close() {
            synchronized(lock) {
                if (isClosed) return
                isClosed = true
                val remaining = (snapshotCounts[slot] ?: 1) - 1
                if (remaining <= 0) {
                    snapshotCounts.remove(slot)
                    if ((flags[slot].toInt() and FlagZombie) != 0) {
                        finalizeZombieSlotLocked(slot, formatHex(h0, h1, h2, h3))
                    }
                } else {
                    snapshotCounts[slot] = remaining
                }
            }
        }

        override fun closeAndOpenEditor(): DiskCache.Editor? = synchronized(lock) {
            close()
            openEditorByHash(h0, h1, h2, h3)
        }
    }

    private inner class EditorImpl(
        val slot: Int,
        val hex: String,
    ) : DiskCache.Editor {
        var writtenMeta = false
        var writtenData = false
        var detached = false
        private var finished = false

        override val metadata: okio.Path
            get() = synchronized(lock) {
                check(!finished) { "editor is closed" }
                writtenMeta = true
                val path = entryDir.resolve("$hex.0.tmp")
                ensureEmptyFile(path)
                entryRoot / "$hex.0.tmp"
            }

        override val data: okio.Path
            get() = synchronized(lock) {
                check(!finished) { "editor is closed" }
                writtenData = true
                val path = entryDir.resolve("$hex.1.tmp")
                ensureEmptyFile(path)
                entryRoot / "$hex.1.tmp"
            }

        override fun commit() {
            synchronized(lock) {
                if (finished) return
                finished = true
                completeEditLocked(this, success = true)
            }
        }

        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = synchronized(lock) {
            if (finished) return null
            finished = true
            val wasDetached = detached
            val h0 = k0[slot]
            val h1 = k1[slot]
            val h2 = k2[slot]
            val h3 = k3[slot]
            completeEditLocked(this, success = true)
            if (wasDetached) null else openSnapshotByHash(h0, h1, h2, h3)
        }

        override fun abort() {
            synchronized(lock) {
                if (finished) return
                finished = true
                completeEditLocked(this, success = false)
            }
        }
    }

    companion object {
        private const val JournalName = "journal"
        private const val JournalTmpName = "journal.tmp"
        private const val JournalBackupName = "journal.bkp"
        private const val JournalMagic = "libcore.io.DiskLruCache"
        private const val JournalVersion = "1"
        // Coil's RealDiskCache opens its DiskLruCache with appVersion 3 and rejects (and wipes) a
        // journal with any other, so write the same: an older build, or Nick's fork, sharing this
        // data folder then keeps the cache instead of clearing it.
        private const val AppVersionText = "3"
        private const val ValueCountText = "2"
        private const val JournalCompactThreshold = 2_000

        private const val InitialCapacity = 64
        private const val InitialBucketCount = 128
        private const val None = -1
        private const val BucketEmpty = -1
        private const val BucketTombstone = -2

        private const val FlagOccupied = 1
        private const val FlagReadable = 2
        private const val FlagZombie = 4

        private val HexChars = "0123456789abcdef".toCharArray()
        private val Sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        private fun sha256(key: String): ByteArray {
            val digest = Sha256ThreadLocal.get()
            digest.reset()
            return digest.digest(key.toByteArray(StandardCharsets.UTF_8))
        }

        private fun packLong(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFFL) shl 56) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 48) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 40) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 32) or
                ((bytes[offset + 4].toLong() and 0xFFL) shl 24) or
                ((bytes[offset + 5].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 6].toLong() and 0xFFL) shl 8) or
                (bytes[offset + 7].toLong() and 0xFFL)

        private fun hashIndex(h0: Long, mask: Int): Int {
            val mixed = (h0 xor (h0 ushr 32)).toInt()
            return mixed and mask
        }

        internal fun formatHex(h0: Long, h1: Long, h2: Long, h3: Long): String {
            val chars = CharArray(64)
            writeLongHex(h0, chars, 0)
            writeLongHex(h1, chars, 16)
            writeLongHex(h2, chars, 32)
            writeLongHex(h3, chars, 48)
            return String(chars)
        }

        private fun writeLongHex(value: Long, out: CharArray, offset: Int) {
            var shift = 60
            for (i in 0 until 16) {
                out[offset + i] = HexChars[((value ushr shift) and 0xFL).toInt()]
                shift -= 4
            }
        }

        private fun parseHexLong(s: String, start: Int): Long {
            var result = 0L
            for (i in start until start + 16) {
                val c = s[i]
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw IOException("Invalid hex char '$c' in journal key")
                }
                result = (result shl 4) or digit.toLong()
            }
            return result
        }

        private fun parseUnsignedLong(s: String, start: Int, end: Int): Long {
            if (start >= end) throw IOException("Empty integer in journal line")
            var result = 0L
            for (i in start until end) {
                val c = s[i]
                if (c !in '0'..'9') throw IOException("Invalid digit '$c' in journal length")
                result = result * 10L + (c - '0')
            }
            return result
        }

        private fun ensureEmptyFile(path: Path) {
            if (!Files.exists(path)) {
                runCatching { Files.createFile(path) }
            }
        }

        private fun moveReplace(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun deleteQuietly(path: Path) {
            runCatching { Files.deleteIfExists(path) }
        }
    }
}
