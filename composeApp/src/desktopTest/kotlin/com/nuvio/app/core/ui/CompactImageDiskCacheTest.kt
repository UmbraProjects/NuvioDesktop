package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import coil3.disk.directory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.use

class CompactImageDiskCacheTest {

    private val root: Path = Files.createTempDirectory("nuvio-compact-cache-test")
    private val fs = FileSystem.SYSTEM

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun DiskCache.put(key: String, meta: String, body: String) {
        val editor = checkNotNull(openEditor(key))
        fs.write(editor.metadata) { writeUtf8(meta) }
        fs.write(editor.data) { writeUtf8(body) }
        editor.commit()
    }

    private fun DiskCache.read(key: String): Pair<String, String>? = openSnapshot(key)?.use { snap ->
        val meta = fs.source(snap.metadata).buffer().use { it.readUtf8() }
        val body = fs.source(snap.data).buffer().use { it.readUtf8() }
        meta to body
    }

    @Test
    fun putReadReopenAndCoilJournalInterop() {
        val dir = root.resolve("interop")
        // Write with Coil's built-in DiskCache, read back with CompactImageDiskCache.
        val coil = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(10_000_000).build()
        repeat(50) { i -> coil.put("key-$i", "m-$i", "body-$i") }
        coil.remove("key-3")
        coil.shutdown()

        val compact = CompactImageDiskCache(dir, dir, 10_000_000)
        assertEquals(49, compact.entryCount)
        assertNull(compact.read("key-3"))
        repeat(50) { i ->
            if (i != 3) assertEquals("m-$i" to "body-$i", compact.read("key-$i"))
        }
        // Write a new entry with CompactImageDiskCache, then verify Coil itself can still replay it.
        compact.put("key-new", "m-new", "body-new")
        compact.shutdown()

        val coilReopen = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(10_000_000).build()
        assertEquals("m-new" to "body-new", coilReopen.read("key-new"))
        assertEquals("m-0" to "body-0", coilReopen.read("key-0"))
        assertNull(coilReopen.read("key-3"))
        coilReopen.shutdown()
    }

    @Test
    fun lruEvictionDropsOldestUntouchedEntriesFirst() {
        val dir = root.resolve("lru")
        // Each entry is 4 ("meta") + 100 ("x"*100) = 104 bytes. Cap at 350 -> fits 3 entries.
        val cache = CompactImageDiskCache(dir, dir, maxSize = 350L)
        val payload = "x".repeat(100)
        cache.put("a", "meta", payload)
        cache.put("b", "meta", payload)
        cache.put("c", "meta", payload)
        // Touch "a" so "b" becomes the oldest entry.
        assertNotNull(cache.read("a"))
        cache.put("d", "meta", payload)

        assertEquals(3, cache.entryCount)
        assertNull(cache.read("b"), "least recently used entry should be evicted")
        assertNotNull(cache.read("a"))
        assertNotNull(cache.read("c"))
        assertNotNull(cache.read("d"))
        cache.shutdown()
    }

    @Test
    fun metadataOnlyEditPreservesExistingBody() {
        val dir = root.resolve("reval")
        val cache = CompactImageDiskCache(dir, dir, maxSize = 1_000_000L)
        cache.put("poster", "etag-1", "image-bytes")

        val editor = assertNotNull(assertNotNull(cache.openSnapshot("poster")).closeAndOpenEditor())
        fs.write(editor.metadata) { writeUtf8("etag-2-revalidated") }
        editor.commit()

        assertEquals("etag-2-revalidated" to "image-bytes", cache.read("poster"))
        assertEquals("etag-2-revalidated".length.toLong() + "image-bytes".length.toLong(), cache.size)
        cache.shutdown()
    }

    @Test
    fun removingEntryWithOpenSnapshotDefersFileDeletionUntilClose() {
        val dir = root.resolve("zombie")
        val cache = CompactImageDiskCache(dir, dir, maxSize = 1_000_000L)
        cache.put("art", "m", "payload")

        val snapshot = assertNotNull(cache.openSnapshot("art"))
        val dataFile = dir.resolve(snapshot.data.name)
        assertTrue(cache.remove("art"))
        assertNull(cache.openSnapshot("art"))
        assertTrue(dataFile.exists(), "open snapshot must still be able to read its file")
        assertEquals("payload", fs.source(snapshot.data).buffer().use { it.readUtf8() })

        snapshot.close()
        assertFalse(dataFile.exists(), "zombie clean file should be deleted once last snapshot closes")
        cache.shutdown()
    }

    @Test
    fun concurrentEditorRejectedAndAbortCleansUpBrandNewEntry() {
        val dir = root.resolve("abort")
        val cache = CompactImageDiskCache(dir, dir, maxSize = 1_000_000L)
        val editor = assertNotNull(cache.openEditor("fresh"))
        assertNull(cache.openEditor("fresh"), "second concurrent editor must be rejected")
        fs.write(editor.metadata) { writeUtf8("m") }
        editor.abort()

        assertEquals(0, cache.entryCount)
        assertNull(cache.openSnapshot("fresh"))
        cache.shutdown()
    }

    @Test
    fun journalCompactsAfterManyRedundantOpsAndSurvivesReopen() {
        val dir = root.resolve("compact")
        val cache = CompactImageDiskCache(dir, dir, maxSize = 10_000_000L)
        cache.put("hot", "m", "body")
        repeat(2_100) {
            cache.openSnapshot("hot")?.close()
        }
        val journalLines = Files.readAllLines(dir.resolve("journal"))
        assertTrue(journalLines.size < 20, "journal should have compacted, got ${journalLines.size} lines")
        cache.shutdown()

        val reopened = CompactImageDiskCache(dir, dir, maxSize = 10_000_000L)
        assertEquals("m" to "body", reopened.read("hot"))
        reopened.shutdown()
    }
}
