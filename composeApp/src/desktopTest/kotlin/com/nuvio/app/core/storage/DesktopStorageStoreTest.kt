package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStorageStoreTest {
    private fun tempStoreFile(): Path = Files.createTempDirectory("nuvio-store").resolve("s.properties")

    private fun readFile(file: Path): Properties =
        Properties().also { props -> Files.newInputStream(file).use(props::load) }

    @Test
    fun `writes are coalesced until flushed`() {
        val file = tempStoreFile()
        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        store.putString("a", "1")
        store.putString("b", "2")
        assertFalse(Files.exists(file))
        assertEquals("1", store.getString("a"))

        store.flush()
        assertEquals("2", readFile(file).getProperty("b"))
    }

    @Test
    fun `rewriting the stored value does not rewrite the file`() {
        val file = tempStoreFile()
        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        store.putString("k", "v1")
        store.flush()
        Files.delete(file)

        store.putString("k", "v1")
        store.remove("absent")
        store.flush()
        assertFalse(Files.exists(file))

        store.putString("k", "v2")
        store.flush()
        assertEquals("v2", readFile(file).getProperty("k"))
    }

    @Test
    fun `a store that cannot be parsed is never overwritten`() {
        val file = tempStoreFile()
        Files.writeString(file, "good=1\nbad=\\uZZZZ\n")
        val original = Files.readAllBytes(file)

        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        assertNull(store.getString("good"))
        store.putString("new", "x")
        store.flush()

        assertTrue(original.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun `an unparseable store falls back to the previous version`() {
        val file = tempStoreFile()
        val first = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        first.putString("k", "v1")
        first.flush()
        first.putString("k", "v2")
        first.flush()
        assertEquals("v1", readFile(file.resolveSibling("s.properties.bak")).getProperty("k"))

        Files.writeString(file, "k=\\uZZZZ\n")
        val reopened = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        assertEquals("v1", reopened.getString("k"))
    }

    @Test
    fun `a zero-length store falls back to the previous version`() {
        val file = tempStoreFile()
        val first = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        first.putString("k", "v1")
        first.flush()
        first.putString("k", "v2")
        first.flush()

        // A write cut short by a power loss: the swap landed, the data did not.
        Files.write(file, ByteArray(0))
        val reopened = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        assertEquals("v1", reopened.getString("k"))

        reopened.putString("other", "x")
        reopened.flush()
        assertEquals("v1", readFile(file).getProperty("k"))
        assertEquals("x", readFile(file).getProperty("other"))
    }

    @Test
    fun `a store saved empty is not mistaken for a damaged one`() {
        val file = tempStoreFile()
        val first = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        first.putString("k", "v1")
        first.flush()
        first.remove("k")
        first.flush()

        val reopened = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        assertNull(reopened.getString("k"))
    }

    @Test
    fun `deleteAll removes the file and its backup and leaves a usable empty store`() {
        val file = tempStoreFile()
        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        store.putString("k", "v1")
        store.flush()
        store.putString("k", "v2")
        store.flush()

        store.deleteAll()

        assertFalse(Files.exists(file))
        assertFalse(Files.exists(file.resolveSibling("s.properties.bak")))
        assertNull(store.getString("k"))
        store.putString("k", "v3")
        store.flush()
        assertEquals("v3", readFile(file).getProperty("k"))
    }

    @Test
    fun `sign-out leaves machine-local stores alone`() {
        val wiped = PlatformLocalAccountDataCleaner.accountStoreNames
        listOf(
            "nuvio_first_run_wizard",
            "nuvio_games_settings",
            "nuvio_local_library",
            "nuvio_updater",
            "nuvio_screensaver",
            "nuvio_window_state",
            "nuvio_player_settings",
            "nuvio_synchronization_preferences",
        ).forEach { name -> assertFalse(name in wiped, name) }
        listOf("nuvio_auth", "nuvio_addons", "nuvio_watch_progress", "nuvio_watched", "nuvio_profiles")
            .forEach { name -> assertTrue(name in wiped, name) }
    }

    @Test
    fun `idle eviction drops clean large store, short-circuits unchanged put, and reloads on read or change`() {
        val file = tempStoreFile()
        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        val payload = "x".repeat(2_000)
        store.putString("big", payload)
        // Dirty store must NOT be evicted.
        assertEquals(0L, store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 1_000L))
        assertTrue(store.isLoadedInMemory)

        store.flush()
        // Small threshold not met -> kept.
        assertEquals(0L, store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 5_000L))
        assertTrue(store.isLoadedInMemory)

        // Clean, idle, large -> evicted.
        val evicted = store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 1_000L)
        assertTrue(evicted >= 2_000L)
        assertFalse(store.isLoadedInMemory)

        // contains / keys / unchanged putString must not reload from disk.
        assertTrue(store.contains("big"))
        assertEquals(setOf("big"), store.keys())
        store.putString("big", payload)
        assertFalse(store.isLoadedInMemory)

        // getString transparently reloads from disk.
        assertEquals(payload, store.getString("big"))
        assertTrue(store.isLoadedInMemory)

        // Evict again, then write a changed value -> no reload on the writer's thread; the flush
        // reads the file back and writes the change merged into it.
        assertTrue(store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 1_000L) >= 2_000L)
        assertFalse(store.isLoadedInMemory)
        store.putString("big", "updated")
        assertFalse(store.isLoadedInMemory)
        assertEquals("updated", store.getString("big"))
        assertFalse(store.isLoadedInMemory)
        assertTrue(store.contains("big"))
        store.flush()
        assertEquals("updated", readFile(file).getProperty("big"))
    }

    @Test
    fun `writes to an evicted store merge with the file on flush, removals included`() {
        val file = tempStoreFile()
        val store = DesktopStorage.Store(file, flushDelayMs = 60_000L)
        val payload = "y".repeat(2_000)
        store.putString("keep", payload)
        store.putString("drop", payload)
        store.flush()
        assertTrue(store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 1_000L) > 0L)

        store.putString("added", "new")
        store.remove("drop")
        assertFalse(store.isLoadedInMemory)
        assertNull(store.getString("drop"))
        assertFalse(store.contains("drop"))
        // An evicted store with a pending override is not evicted again before it is written.
        assertEquals(0L, store.evictIfIdleAndClean(nowMs = System.currentTimeMillis() + 300_000L, idleMs = 1_000L, minChars = 1L))

        store.flush()
        val written = readFile(file)
        assertEquals(payload, written.getProperty("keep"))
        assertEquals("new", written.getProperty("added"))
        assertNull(written.getProperty("drop"))
        // And the in-memory view after the flush's reload agrees.
        assertEquals(payload, store.getString("keep"))
        assertNull(store.getString("drop"))
    }
}
