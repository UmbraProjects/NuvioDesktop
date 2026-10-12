package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import coil3.disk.directory
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartitionedImageDiskCacheTest {

    private lateinit var root: java.nio.file.Path
    private lateinit var stills: DiskCache
    private lateinit var animated: DiskCache
    private lateinit var cache: PartitionedImageDiskCache
    private val fs = FileSystem.SYSTEM

    @BeforeTest
    fun open() {
        root = Files.createTempDirectory("nuvio-partitioned-cache")
        stills = DiskCache.Builder().directory(root.resolve("stills").toOkioPath()).maxSizeBytes(1L shl 20).build()
        animated = DiskCache.Builder().directory(root.resolve("animated").toOkioPath()).maxSizeBytes(1L shl 20).build()
        cache = PartitionedImageDiskCache(stills, animated)
    }

    @AfterTest
    fun close() {
        cache.shutdown()
        runCatching {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a still is committed to the stills partition`() {
        write("poster", PNG)
        assertNotNull(stills.openSnapshot("poster")).close()
        assertNull(animated.openSnapshot("poster"))
        assertContentEquals(PNG, read("poster"))
    }

    @Test
    fun `a gif is committed to the animated partition and nowhere else`() {
        write("collection", GIF)
        assertNotNull(animated.openSnapshot("collection")).close()
        assertNull(stills.openSnapshot("collection"))
        assertContentEquals(GIF, read("collection"))
        assertEquals("meta", assertNotNull(cache.openSnapshot("collection")).use { fs.read(it.metadata) { readUtf8() } })
    }

    @Test
    fun `an animated webp is routed, a still webp is not`() {
        write("animated-webp", webp(animatedFlag = true))
        write("still-webp", webp(animatedFlag = false))
        assertNotNull(animated.openSnapshot("animated-webp")).close()
        assertNotNull(stills.openSnapshot("still-webp")).close()
    }

    @Test
    fun `a still replaced by a gif moves partitions`() {
        write("art", PNG)
        // NetworkFetcher replacing a stale copy: through the snapshot it read, not openEditor.
        val editor = assertNotNull(assertNotNull(cache.openSnapshot("art")).closeAndOpenEditor())
        fs.write(editor.metadata) { writeUtf8("meta") }
        fs.write(editor.data) { write(GIF) }
        editor.commitAndOpenSnapshot()?.close()

        assertNull(stills.openSnapshot("art"))
        assertContentEquals(GIF, read("art"))
    }

    @Test
    fun `a metadata-only edit of an animated entry stays put and keeps its body`() {
        write("collection", GIF)
        val editor = assertNotNull(assertNotNull(cache.openSnapshot("collection")).closeAndOpenEditor())
        fs.write(editor.metadata) { writeUtf8("revalidated") }
        editor.commit()

        assertNull(stills.openSnapshot("collection"))
        assertContentEquals(GIF, read("collection"))
    }

    @Test
    fun `a metadata-only edit of a still keeps its body`() {
        // A 304 revalidation: NetworkFetcher rewrites the headers and never touches the body.
        write("poster", PNG)
        val editor = assertNotNull(assertNotNull(cache.openSnapshot("poster")).closeAndOpenEditor())
        fs.write(editor.metadata) { writeUtf8("revalidated") }
        editor.commit()

        assertContentEquals(PNG, read("poster"))
        assertEquals("revalidated", assertNotNull(cache.openSnapshot("poster")).use { fs.read(it.metadata) { readUtf8() } })
    }

    @Test
    fun `an empty still body is a miss and is dropped`() {
        val editor = assertNotNull(stills.openEditor("broken"))
        fs.write(editor.metadata) { writeUtf8("meta") }
        fs.write(editor.data) { }
        editor.commit()

        assertNull(cache.openSnapshot("broken"))
        assertNull(stills.openSnapshot("broken"))
    }

    @Test
    fun `remove and size cover both partitions`() {
        write("poster", PNG)
        write("collection", GIF)
        assertEquals(stills.size + animated.size, cache.size)
        assertTrue(cache.remove("collection"))
        assertTrue(cache.remove("poster"))
        assertFalse(cache.remove("poster"))
        assertNull(cache.openSnapshot("collection"))
    }

    private fun write(key: String, body: ByteArray) {
        val editor = assertNotNull(cache.openEditor(key))
        fs.write(editor.metadata) { writeUtf8("meta") }
        fs.write(editor.data) { write(body) }
        editor.commitAndOpenSnapshot()?.close()
    }

    private fun read(key: String): ByteArray {
        val snapshot = assertNotNull(cache.openSnapshot(key))
        return snapshot.use { fs.read(it.data) { readByteArray() } }
    }

    private fun webp(animatedFlag: Boolean): ByteArray {
        val bytes = ByteArray(40)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8X".toByteArray().copyInto(bytes, 12)
        bytes[20] = if (animatedFlag) 0x02 else 0x00
        return bytes
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
        val GIF = "GIF89a".toByteArray() + ByteArray(16) { it.toByte() }
    }
}
