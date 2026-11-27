package com.nuvio.app.features.watched

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchedStorageDesktopTest {

    @Test
    fun `legacy properties store migrates all profiles to raw json blobs and deletes itself`() {
        WatchedStorage.clearAll()
        DesktopStorage.store("nuvio_watched").apply {
            putString("watched_1", """{"items":[{"id":"tt1","type":"movie"}]}""")
            putString("watched_2", """{"items":[{"id":"tt2","type":"series"}]}""")
            flush()
        }

        // Reading profile 2 first must still migrate profile 1 before deleting the legacy store.
        assertEquals("""{"items":[{"id":"tt2","type":"series"}]}""", WatchedStorage.loadPayload(2))
        assertEquals("""{"items":[{"id":"tt1","type":"movie"}]}""", WatchedStorage.loadPayload(1))
        assertFalse(Files.exists(DesktopStorage.storeFile("nuvio_watched")))
        assertEquals(
            """{"items":[{"id":"tt1","type":"movie"}]}""",
            Files.readString(DesktopStorage.accountDir.resolve("nuvio_watched_1.json")),
        )
        assertEquals(
            """{"items":[{"id":"tt2","type":"series"}]}""",
            Files.readString(DesktopStorage.accountDir.resolve("nuvio_watched_2.json")),
        )
    }

    @Test
    fun `savePayload is readable immediately, flushes on DesktopStorage flushAll, and clears cleanly`() {
        WatchedStorage.clearAll()
        val payload = """{"items":[{"id":"tt99","type":"movie"}]}"""
        WatchedStorage.savePayload(1, payload)

        // Visible immediately before the coalesced timer fires (both string and stream reads).
        assertEquals(payload, WatchedStorage.loadPayload(1))
        assertEquals(
            payload,
            WatchedStorage.loadPayloadStream(1) { it.readBytes().toString(Charsets.UTF_8) },
        )

        DesktopStorage.flushAll()
        val blobFile = DesktopStorage.accountDir.resolve("nuvio_watched_1.json")
        assertTrue(Files.exists(blobFile))
        assertEquals(payload, Files.readString(blobFile))
        assertEquals(
            payload,
            WatchedStorage.loadPayloadStream(1) { it.readBytes().toString(Charsets.UTF_8) },
        )

        WatchedStorage.clearAll()
        assertFalse(Files.exists(blobFile))
        assertNull(WatchedStorage.loadPayload(1))
        assertNull(WatchedStorage.loadPayloadStream(1) { it.readBytes() })
    }

    @Test
    fun `a restored legacy store replaces the history already on disk`() {
        WatchedStorage.clearAll()
        WatchedStorage.savePayload(1, """{"items":[{"id":"ttCurrent","type":"movie"}]}""")
        DesktopStorage.flushAll()

        // What a settings restore writes before the app opens any store.
        val restored = """{"items":[{"id":"ttRestored","type":"movie"}]}"""
        DesktopStorage.store("nuvio_watched").apply {
            putString("watched_1", restored)
            flush()
        }

        assertEquals(restored, WatchedStorage.loadPayload(1))
        assertEquals(restored, Files.readString(DesktopStorage.accountDir.resolve("nuvio_watched_1.json")))
        assertFalse(Files.exists(DesktopStorage.storeFile("nuvio_watched")))
        WatchedStorage.clearAll()
    }

    @Test
    fun `backup payloads cover every profile on disk and unflushed saves`() {
        WatchedStorage.clearAll()
        WatchedStorage.savePayload(1, """{"items":[{"id":"tt1","type":"movie"}]}""")
        DesktopStorage.flushAll()
        WatchedStorage.savePayload(2, """{"items":[{"id":"tt2","type":"movie"}]}""") // not flushed yet

        assertEquals(
            mapOf(
                "watched_1" to """{"items":[{"id":"tt1","type":"movie"}]}""",
                "watched_2" to """{"items":[{"id":"tt2","type":"movie"}]}""",
            ),
            WatchedStorage.payloadsForBackup(),
        )
        WatchedStorage.clearAll()
    }
}
