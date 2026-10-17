package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `no old store file means no store is opened`() {
        val blob = DesktopBlobFile("fresh", legacyStoreName = "nuvio_blob_never_existed", legacyKey = "k", directory = { dir })
        assertNull(blob.read())
        assertFalse(Files.exists(DesktopStorage.storeFile("nuvio_blob_never_existed")))
    }
}
