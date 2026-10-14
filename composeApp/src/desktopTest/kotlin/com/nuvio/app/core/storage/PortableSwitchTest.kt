package com.nuvio.app.core.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both directions of the portable switch, against throwaway folders standing in for
 * %LOCALAPPDATA%\NuvioHTPC and the `data` folder beside Nuvio.exe (same drive, so cache moves
 * are renames).
 */
class PortableSwitchTest {
    private lateinit var root: Path
    private lateinit var appData: Path
    private lateinit var portable: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("nuvio-portable-test")
        appData = root.resolve("NuvioHTPC")
        portable = root.resolve("install").resolve("data")
        Files.createDirectories(portable.parent)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun Path.file(relative: String, text: String): Path =
        resolve(relative).also { Files.createDirectories(it.parent); it.writeText(text) }

    @Test
    fun switchingOnCopiesSettingsMovesCachesAndSkipsTheRest() {
        appData.file("accounts/a-1/nuvio_player_settings.properties", "player")
        appData.file("nuvio_account_slots.properties", "slots")
        appData.file("image_cache/abc.0", "poster")
        appData.file("shader-cache/x.bin", "shader")
        appData.file("nuvio-engine/piece", "torrent")
        appData.file("logs/nuvio.log", "log")
        appData.file("instance.lock", "")

        val chosen = DesktopStorage.copyAppDataIntoPortable(appData, portable)

        assertEquals(portable, chosen)
        assertEquals("player", portable.resolve("accounts/a-1/nuvio_player_settings.properties").readText())
        assertEquals("slots", portable.resolve("nuvio_account_slots.properties").readText())
        // Settings stay behind in AppData as they were.
        assertEquals("player", appData.resolve("accounts/a-1/nuvio_player_settings.properties").readText())
        // Caches move rather than copy.
        assertEquals("poster", portable.resolve("image_cache/abc.0").readText())
        assertFalse(appData.resolve("image_cache").exists())
        assertEquals("shader", portable.resolve("shader-cache/x.bin").readText())
        // The torrent cache, logs and locks don't come across at all.
        assertFalse(portable.resolve("nuvio-engine").exists())
        assertFalse(portable.resolve("logs/nuvio.log").exists())
        assertFalse(portable.resolve("instance.lock").exists())
        assertTrue(appData.resolve("nuvio-engine/piece").exists())
    }

    @Test
    fun switchingOffCopiesBackMovesCachesAndRetiresTheFolder() {
        appData.file("accounts/a-1/nuvio_player_settings.properties", "old")
        portable.file("accounts/a-1/nuvio_player_settings.properties", "new")
        portable.file("image_cache/abc.0", "poster")
        portable.file("return-to-appdata", "")
        // A data.old from an earlier switch must not block this one.
        portable.resolveSibling("data.old").file("stale.txt", "stale")

        val chosen = DesktopStorage.returnPortableDataToAppData(portable, appData)

        assertEquals(appData, chosen)
        assertEquals("new", appData.resolve("accounts/a-1/nuvio_player_settings.properties").readText())
        assertEquals("poster", appData.resolve("image_cache/abc.0").readText())
        assertFalse(appData.resolve("return-to-appdata").exists())
        assertFalse(portable.exists())
        val old = portable.resolveSibling("data.old")
        assertEquals("new", old.resolve("accounts/a-1/nuvio_player_settings.properties").readText())
        assertFalse(old.resolve("stale.txt").exists())
    }

    @Test
    fun switchingOffKeepsAppDataCacheWhenItAlreadyHasOne() {
        appData.file("image_cache/mine.0", "appdata")
        portable.file("image_cache/theirs.0", "portable")
        portable.file("nuvio_account_slots.properties", "slots")

        DesktopStorage.returnPortableDataToAppData(portable, appData)

        assertTrue(appData.resolve("image_cache/mine.0").exists())
        assertFalse(appData.resolve("image_cache/theirs.0").exists())
    }

    /** The failure from the first real test: TorrServer still holding config.db as Nuvio restarts. */
    @Test
    fun switchingOffWaitsOutABrieflyLockedFile() {
        portable.file("nuvio_account_slots.properties", "slots")
        val db = portable.file("torrserver/config.db", "torrserver settings")
        val channel = FileChannel.open(db, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock = channel.lock()
        val releaser = Thread {
            Thread.sleep(1_200)
            lock.release()
            channel.close()
        }.apply { start() }

        val chosen = DesktopStorage.returnPortableDataToAppData(portable, appData)
        releaser.join()

        assertEquals(appData, chosen)
        assertEquals("torrserver settings", appData.resolve("torrserver/config.db").readText())
        assertFalse(portable.exists())
    }
}
