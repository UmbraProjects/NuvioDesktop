package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Runs against the throwaway data directory the test task sets (nuvio.desktop.dataDir). Each
 * call to [DesktopAccountSlots.activeSlotDir] stands in for the next launch of the app.
 */
class DesktopAccountSlotsTest {
    private var restarts = 0
    private val registry get() = DesktopStorage.store(DesktopAccountSlots.REGISTRY_STORE)

    @BeforeTest
    fun setUp() {
        DesktopStorage.accountDir
        DesktopAccountSlots.restart = { restarts++ }
        // Every test starts as a first launch with nothing signed in.
        registry.keys().forEach(registry::remove)
        registry.flush()
    }

    @AfterTest
    fun tearDown() {
        registry.keys().forEach(registry::remove)
        // Hand the process's own folder back, so stores other tests opened keep a home.
        Files.createDirectories(DesktopStorage.accountDir)
        registry.putString("active", DesktopStorage.accountDir.fileName.toString())
        registry.flush()
    }

    private fun launch(): Path = DesktopAccountSlots.activeSlotDir()

    @Test
    fun firstLaunchMovesAccountStoresAndLeavesMachineStores() {
        val root = DesktopStorage.rootDir
        root.resolve("nuvio_slot_test_theme.properties").writeText("theme=dark\n")
        root.resolve("nuvio_slot_test_theme.properties.bak").writeText("theme=light\n")
        root.resolve("nuvio_window_state.properties").writeText("x=1\n")
        root.resolve("unrelated.properties").writeText("y=2\n")

        val slot = launch()

        assertEquals("theme=dark\n", slot.resolve("nuvio_slot_test_theme.properties").readText())
        assertTrue(slot.resolve("nuvio_slot_test_theme.properties.bak").exists())
        assertFalse(root.resolve("nuvio_slot_test_theme.properties").exists())
        assertTrue(root.resolve("nuvio_window_state.properties").exists(), "machine store moved")
        assertTrue(root.resolve("unrelated.properties").exists(), "non-store file moved")
        assertEquals(slot, launch(), "second launch should reuse the folder")
    }

    @Test
    fun accountsKeepTheirOwnFolders() {
        val first = launch()
        assertFalse(DesktopAccountSlots.onAuthenticated("alice"))
        assertEquals(0, restarts, "a new account claims the fresh folder without restarting")

        assertTrue(DesktopAccountSlots.onSignedOut(erase = false))
        assertEquals(1, restarts)
        val afterSignOut = launch()
        assertNotEquals(first, afterSignOut, "signed out onto a fresh folder")
        assertTrue(first.exists(), "alice's folder kept")

        assertFalse(DesktopAccountSlots.onAuthenticated("bob"))
        assertEquals(afterSignOut, launch(), "bob claimed the fresh folder")

        DesktopAccountSlots.onSignedOut(erase = false)
        val unclaimed = launch()
        assertTrue(DesktopAccountSlots.onAuthenticated("alice"), "alice should restart into her folder")
        assertEquals(first, launch(), "alice is back in her own folder")
        assertFalse(unclaimed.exists(), "the folder alice passed through is removed")
        assertTrue(afterSignOut.exists(), "bob's folder untouched")
    }

    @Test
    fun eraseRemovesTheAccountFolderOnTheNextLaunch() {
        val aliceDir = launch()
        DesktopAccountSlots.onAuthenticated("alice")
        Files.writeString(aliceDir.resolve("nuvio_slot_test_keys.properties"), "api_key=secret\n")

        DesktopAccountSlots.onSignedOut(erase = true)
        assertTrue(aliceDir.exists(), "deleted at the next launch, not under the running app")
        val next = launch()
        assertFalse(aliceDir.exists())

        assertFalse(DesktopAccountSlots.onAuthenticated("alice"), "alice starts fresh, no folder to switch to")
        assertEquals(next, launch())
    }

    @Test
    fun serverSwitchCarriesTheFolderToTheNewAccount() {
        val dir = launch()
        DesktopAccountSlots.onAuthenticated("alice-old-server")
        DesktopAccountSlots.onServerSwitch()

        assertFalse(DesktopAccountSlots.onAuthenticated("alice-new-server"))
        assertEquals(0, restarts)
        assertEquals(dir, launch())

        // The carried-over folder now belongs to the new id: signing out and back in returns to it.
        DesktopAccountSlots.onSignedOut(erase = false)
        launch()
        assertTrue(DesktopAccountSlots.onAuthenticated("alice-new-server"))
        assertEquals(dir, launch())
    }

    @Test
    fun anAccountNeverOpensAFolderOwnedBySomeoneElse() {
        val aliceDir = launch()
        DesktopAccountSlots.onAuthenticated("alice")
        // A session for bob restored without a sign-out in between.
        assertTrue(DesktopAccountSlots.onAuthenticated("bob"))
        val bobDir = launch()
        assertNotEquals(aliceDir, bobDir)
        assertTrue(aliceDir.exists())
    }

    @Test
    fun onlyAnUnusedFolderCountsAsANewAccount() {
        // A first launch that moved existing settings in is an upgrade, not a new account.
        DesktopStorage.rootDir.resolve("nuvio_slot_test_existing.properties").writeText("a=1\n")
        launch()
        assertFalse(DesktopAccountSlots.isFreshSlot, "migrated settings are not a new account")
        DesktopAccountSlots.onAuthenticated("alice")

        // The folder a sign-out leaves is new, and stays new for the session that claims it.
        DesktopAccountSlots.onSignedOut(erase = false)
        launch()
        assertTrue(DesktopAccountSlots.isFreshSlot)
        assertFalse(DesktopAccountSlots.onAuthenticated("bob"))
        assertTrue(DesktopAccountSlots.isFreshSlot, "captured at start, not cleared by the claim")

        // Bob coming back is not new.
        DesktopAccountSlots.onSignedOut(erase = false)
        launch()
        DesktopAccountSlots.onAuthenticated("bob")
        launch()
        assertFalse(DesktopAccountSlots.isFreshSlot)
    }

    @Test
    fun storesResolveToTheirScope() {
        val slot = launch()
        assertEquals(DesktopStorage.rootDir.resolve("nuvio_window_state.properties"), DesktopStorage.storeFile("nuvio_window_state"))
        assertEquals(
            DesktopStorage.accountDir.resolve("nuvio_theme_settings.properties"),
            DesktopStorage.storeFile("nuvio_theme_settings"),
        )
        assertTrue(slot.toString().contains("accounts"))
    }
}
