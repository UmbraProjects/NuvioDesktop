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
}
