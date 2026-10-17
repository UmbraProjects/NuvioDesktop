package com.nuvio.app.features.updater

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipDeltaTest {
    private val tempRoots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    @Test
    fun onlyChangedAndNewFilesAreFetchedAndTheyMatchTheArchive() {
        val unchanged = Random(1).nextBytes(300_000)
        val changedOld = "old jar".toByteArray()
        val changedNew = Random(2).nextBytes(500_000)
        val stored = "stored entry".repeat(1000).toByteArray()
        val zip = zipOf(
            "Nuvio/" to null,
            "Nuvio/Nuvio.exe" to unchanged,
            "Nuvio/app/composeApp.jar" to changedNew,
            "Nuvio/resources/new.json" to "{}".toByteArray(),
            "Nuvio/stored.txt" to stored,
            stored = setOf("Nuvio/stored.txt"),
        )
        val install = tempDir().apply {
            write("Nuvio.exe", unchanged)
            write("app/composeApp.jar", changedOld)
            write("stored.txt", stored)
            write("user-file.txt", "kept".toByteArray())
        }

        val source = CountingSource(zip)
        val index = ZipDelta.readIndex(source)
        val plan = assertNotNull(ZipDelta.plan(index.entries, install, "Nuvio.exe"))

        assertEquals("Nuvio/", plan.imagePrefix)
        assertEquals(
            setOf("Nuvio.exe", "app\\composeApp.jar", "resources\\new.json", "stored.txt"),
            plan.newFiles.toSet(),
        )
        assertEquals(
            setOf("Nuvio/app/composeApp.jar", "Nuvio/resources/new.json"),
            plan.changed.map { it.name }.toSet(),
        )

        val staging = tempDir()
        var lastProgress = 0L
        source.bytesServed = 0L
        ZipDelta.fetchChanged(source, index, plan, staging) { downloaded, total ->
            assertTrue(downloaded in lastProgress..total)
            lastProgress = downloaded
        }
        assertEquals(plan.changedCompressedBytes, lastProgress)
        assertContentEquals(changedNew, File(staging, "app/composeApp.jar").readBytes())
        assertEquals("{}", File(staging, "resources/new.json").readText())
        assertFalse(File(staging, "Nuvio.exe").exists())
        assertFalse(File(staging, "stored.txt").exists())
        // Only the changed entries (plus their local headers) were transferred, not the 300KB launcher.
        assertTrue(source.bytesServed < plan.changedCompressedBytes + 1_000, "served ${source.bytesServed}")
    }

    @Test
    fun storedEntriesAndRootLevelImagesAreHandled() {
        val stored = Random(3).nextBytes(70_000)
        val zip = zipOf(
            "Nuvio.exe" to "exe".toByteArray(),
            "runtime/lib.dll" to stored,
            stored = setOf("runtime/lib.dll"),
        )
        val install = tempDir().apply { write("Nuvio.exe", "exe".toByteArray()) }
        val source = CountingSource(zip)
        val index = ZipDelta.readIndex(source)
        val plan = assertNotNull(ZipDelta.plan(index.entries, install, "Nuvio.exe"))

        assertEquals("", plan.imagePrefix)
        assertEquals(listOf("runtime/lib.dll"), plan.changed.map { it.name })
        val staging = tempDir()
        ZipDelta.fetchChanged(source, index, plan, staging) { _, _ -> }
        assertContentEquals(stored, File(staging, "runtime/lib.dll").readBytes())
    }

    @Test
    fun identicalInstallNeedsNothing() {
        val exe = Random(4).nextBytes(10_000)
        val zip = zipOf("Nuvio/Nuvio.exe" to exe)
        val install = tempDir().apply { write("Nuvio.exe", exe) }
        val plan = assertNotNull(ZipDelta.plan(ZipDelta.readIndex(CountingSource(zip)).entries, install, "Nuvio.exe"))
        assertTrue(plan.changed.isEmpty())
        assertEquals(listOf("Nuvio.exe"), plan.newFiles)
    }

    @Test
    fun archiveWithoutLauncherHasNoPlan() {
        val zip = zipOf("Other/readme.txt" to "x".toByteArray())
        assertNull(ZipDelta.plan(ZipDelta.readIndex(CountingSource(zip)).entries, tempDir(), "Nuvio.exe"))
    }

    @Test
    fun traversalEntriesAreRejected() {
        val zip = zipOf("Nuvio.exe" to "x".toByteArray(), "../evil.dll" to "x".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            ZipDelta.plan(ZipDelta.readIndex(CountingSource(zip)).entries, tempDir(), "Nuvio.exe")
        }
    }

    @Test
    fun corruptedEntryFailsVerification() {
        val payload = Random(5).nextBytes(50_000)
        val zip = zipOf("Nuvio.exe" to "x".toByteArray(), "app.jar" to payload, stored = setOf("app.jar"))
        val index = ZipDelta.readIndex(CountingSource(zip))
        val entry = index.entries.single { it.name == "app.jar" }
        // Flip a byte inside the stored data, after its local header.
        zip[(entry.localHeaderOffset + 30 + "app.jar".length + 100).toInt()] = 0x55
        val install = tempDir().apply { write("Nuvio.exe", "x".toByteArray()) }
        val plan = assertNotNull(ZipDelta.plan(index.entries, install, "Nuvio.exe"))
        assertFailsWith<IllegalStateException> {
            ZipDelta.fetchChanged(CountingSource(zip), index, plan, tempDir()) { _, _ -> }
        }
    }

    @Test
    fun archiveCommentDoesNotHideTheEndRecord() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setComment("release notes ".repeat(50))
            zip.putNextEntry(ZipEntry("Nuvio.exe"))
            zip.write(1)
            zip.closeEntry()
        }
        val index = ZipDelta.readIndex(CountingSource(output.toByteArray()))
        assertEquals(listOf("Nuvio.exe"), index.entries.map { it.name })
    }

    @Test
    fun aDroppedConnectionResumesTheEntryInsteadOfFailingTheDelta() {
        // Incompressible, so the big entry is one long span like the native exe.
        val exe = Random(6).nextBytes(2_000_000)
        val zip = zipOf("Nuvio/Nuvio.exe" to exe, "Nuvio/app/small.txt" to "small".toByteArray())
        val install = tempDir()
        val index = ZipDelta.readIndex(CountingSource(zip))
        val plan = assertNotNull(ZipDelta.plan(index.entries, install, "Nuvio.exe"))
        val source = DroppingSource(zip, dropAfterBytes = 700_000, drops = 2)
        val staging = tempDir()

        ZipDelta.fetchChanged(source, index, plan, staging) { _, _ -> }

        assertContentEquals(exe, File(staging, "Nuvio.exe").readBytes())
        assertEquals(2, source.dropped)
        // Each resume asks only for what was left, not the whole range again.
        assertTrue(source.reopenStarts.all { it > 0 }, "reopened at ${source.reopenStarts}")
    }

    @Test
    fun aConnectionThatKeepsDroppingStillFails() {
        val exe = Random(7).nextBytes(500_000)
        val zip = zipOf("Nuvio.exe" to exe)
        val index = ZipDelta.readIndex(CountingSource(zip))
        val plan = assertNotNull(ZipDelta.plan(index.entries, tempDir(), "Nuvio.exe"))
        assertFailsWith<java.io.IOException> {
            ZipDelta.fetchChanged(DroppingSource(zip, dropAfterBytes = 50_000, drops = 100), index, plan, tempDir()) { _, _ -> }
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray?>, stored: Set<String> = emptySet()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (name in stored && bytes != null) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                bytes?.let(zip::write)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("zip-delta-test").toFile().also { tempRoots += it }

    private fun File.write(relative: String, bytes: ByteArray) {
        File(this, relative).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    /** Each of the first [drops] opened ranges throws after serving [dropAfterBytes]. */
    private class DroppingSource(
        private val bytes: ByteArray,
        private val dropAfterBytes: Int,
        private val drops: Int,
    ) : ZipRangeSource {
        var dropped = 0
        val reopenStarts = mutableListOf<Long>()
        private var opened = 0
        override val length: Long get() = bytes.size.toLong()
        override fun open(start: Long, endExclusive: Long): InputStream {
            if (opened++ > 0) reopenStarts += start
            val body = ByteArrayInputStream(bytes, start.toInt(), (endExclusive - start).toInt())
            if (dropped >= drops) return body
            return object : InputStream() {
                private var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (served >= dropAfterBytes) {
                        dropped++
                        throw java.io.IOException("connection reset (test)")
                    }
                    val read = body.read(buffer, offset, minOf(length, dropAfterBytes - served))
                    if (read > 0) served += read
                    return read
                }
            }
        }
    }

    private class CountingSource(private val bytes: ByteArray) : ZipRangeSource {
        var bytesServed = 0L
        override val length: Long get() = bytes.size.toLong()
        override fun open(start: Long, endExclusive: Long): InputStream {
            bytesServed += endExclusive - start
            return ByteArrayInputStream(bytes, start.toInt(), (endExclusive - start).toInt())
        }
    }
}
