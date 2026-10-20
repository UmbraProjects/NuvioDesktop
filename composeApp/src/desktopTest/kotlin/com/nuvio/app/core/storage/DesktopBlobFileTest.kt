package com.nuvio.app.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBlobFileTest {
    private val dir = Files.createTempDirectory("nuvio-blob-test")

    @Test
    fun `writes, reads back and deletes`() {
        val blob = DesktopBlobFile("cache", directory = { dir })
        assertNull(blob.read())
        blob.write("{\"a\":\"日本語\"}")
        assertEquals("{\"a\":\"日本語\"}", blob.read())
        assertFalse(Files.exists(dir.resolve("cache.json.tmp")))
        blob.write(null)
        assertNull(blob.read())
        blob.write("x")
        blob.delete()
        assertFalse(Files.exists(dir.resolve("cache.json")))
    }

    @Test
    fun `a value still in the old store moves to the blob file and the store is deleted`() {
        val storeName = "nuvio_blob_migration_test"
        DesktopStorage.store(storeName).apply {
            putString("entries", "legacy payload")
            flush()
        }
        assertTrue(Files.exists(DesktopStorage.storeFile(storeName)))

        val blob = DesktopBlobFile("migrated", legacyStoreName = storeName, legacyKey = "entries", directory = { dir })
        assertEquals("legacy payload", blob.read())
        assertEquals("legacy payload", Files.readString(dir.resolve("migrated.json")))
        assertFalse(Files.exists(DesktopStorage.storeFile(storeName)))
        // Read again from the file, not the store.
        assertEquals("legacy payload", blob.read())
    }

    @Test
    fun `stream write and read round-trip, and a failed write keeps the old file`() {
        val blob = DesktopBlobFile("streamed", directory = { dir })
        assertNull(blob.readStream { it.readBytes() })
        blob.writeStream { it.write("first 日本語".toByteArray()) }
        assertEquals("first 日本語", blob.readStream { String(it.readBytes()) })

        assertFailsWith<IllegalStateException> {
            blob.writeStream { output ->
                output.write("half a ".toByteArray())
                error("encode failed")
            }
        }
        assertEquals("first 日本語", blob.read())
        assertFalse(Files.exists(dir.resolve("streamed.json.tmp")))
    }

    @Test
    fun `streamed json map matches encodeToString and reads back`() {
        val json = Json
        val entries = linkedMapOf("v1:tt1" to 1, "v1:\"quoted\" 日本" to 2, "empty" to 0)
        val out = ByteArrayOutputStream()
        json.encodeJsonMap(out, entries, Wrapped.serializer()) { Wrapped(it, "n$it") }
        val expected = json.encodeToString(entries.mapValues { (_, v) -> Wrapped(v, "n$v") })
        assertEquals(expected, out.toString(Charsets.UTF_8))
        assertEquals(
            entries.mapValues { (_, v) -> Wrapped(v, "n$v") },
            json.decodeJsonMap(ByteArrayInputStream(out.toByteArray()), Wrapped.serializer()),
        )

        val empty = ByteArrayOutputStream()
        json.encodeJsonMap(empty, emptyMap<String, Int>(), Wrapped.serializer()) { Wrapped(it, "") }
        assertEquals("{}", empty.toString(Charsets.UTF_8))
    }

    @Serializable
    private data class Wrapped(val value: Int, val name: String? = null)

    @Test
    fun `no old store file means no store is opened`() {
        val blob = DesktopBlobFile("fresh", legacyStoreName = "nuvio_blob_never_existed", legacyKey = "k", directory = { dir })
        assertNull(blob.read())
        assertFalse(Files.exists(DesktopStorage.storeFile("nuvio_blob_never_existed")))
    }
}
