package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import com.nuvio.app.exitSoon
import com.nuvio.app.relaunchAfterExit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Stores shared by everyone who uses this PC. Every other store belongs to an account and lives in
 * that account's folder, so a new store is account-scoped unless it is named here.
 *
 * Shared: what describes the machine rather than the person (server choice, window, updater,
 * image cache, crash markers), the user's choice of PC-wide features (local
 * library, downloads and auto-download, games, screensaver, lights, external players, the P2P
 * engine), and content caches that hold nothing personal.
 */
internal val MACHINE_STORE_NAMES = setOf(
    DesktopAccountSlots.REGISTRY_STORE,
    "server_configuration",
    "nuvio_window_state",
    "nuvio_updater",
    "nuvio_image_cache_settings",
    "nuvio_hs_err_pid",
    "nuvio_safepoint_pid",
    "nuvio_local_library",
    "nuvio_downloads",
    "nuvio_library_pvr",
    "nuvio_addon_subtitle_download",
    "nuvio_games_settings",
    "nuvio_screensaver",
    "nuvio_lights",
    "nuvio_external_players",
    "nuvio_cast",
    "nuvio_engine",
    "nuvio_tmdb_external_ids",
    "nuvio_mdblist_ratings_cache",
    "nuvio_recap_cache",
    "nuvio_skipdb",
    "nuvio_qualicache_quality_cache",
    "nuvio_playback_redirect_verdicts_v2",
    "nuvio_library_title_facts",
    "nuvio_binge_group_cache",
)

/**
 * Which folder under `accounts/` holds which account's settings, and which one this session uses.
 *
 * A folder ("slot") is either owned by an account id or unowned. Signing out moves the PC onto a
 * fresh unowned slot; the next account to sign in claims it if it has never used this PC, or the
 * app restarts into the slot it already owns. An unowned slot that nobody claimed is deleted at the
 * next start. Erasing on sign-out deletes the account's slot at the next start, before any store is
 * opened, so nothing still running can write it back.
 *
 * The first start with this in place moves the existing account stores into a slot, which the
 * account restored at startup then claims — so an existing install keeps everything.
 */
internal object DesktopAccountSlots {
    const val REGISTRY_STORE = "nuvio_account_slots"

    private val log = Logger.withTag("AccountSlots")
    private const val ActiveKey = "active"
    private const val PendingDeleteKey = "pending_delete"
    private const val CarryOverKey = "carry_over_to_next_account"
    private fun ownerKey(slot: String) = "owner.$slot"
    /** A slot holding settings moved in from before account folders: not a new account. */
    private fun seededKey(slot: String) = "seeded.$slot"
    private fun slotKey(userId: String) = "slot_for.$userId"

    private val registry get() = DesktopStorage.store(REGISTRY_STORE)
    private val accountsRoot: Path get() = DesktopStorage.rootDir.resolve("accounts")

    /** The slot this process uses; set by [activeSlotDir] and fixed until the next start. */
    @Volatile private var activeSlot: String? = null

    /**
     * Whether this process started on a folder no account has used yet: a fresh install, or the
     * fresh folder a sign-out left for the next new account. Captured at start, because the
     * account that signs in claims the folder partway through the session.
     */
    @Volatile var isFreshSlot: Boolean = false
        private set

    /** Resolves (creating or migrating as needed) the slot this process will use. */
    @Synchronized
    fun activeSlotDir(): Path {
        deletePendingSlots()
        val slot = registry.getString(ActiveKey)?.takeIf { accountsRoot.resolve(it).isDirectory() }
            ?: newSlot().also { fresh ->
                val moved = migrateLegacyStores(accountsRoot.resolve(fresh))
                registry.putString(ActiveKey, fresh)
                if (moved > 0) {
                    registry.putBoolean(seededKey(fresh), true)
                    log.i { "Moved $moved settings files into the account folder $fresh" }
                }
            }
        deleteAbandonedSlots(keep = slot)
        registry.flush()
        activeSlot = slot
        isFreshSlot = registry.getString(ownerKey(slot)) == null && registry.getBoolean(seededKey(slot)) != true
        return accountsRoot.resolve(slot)
    }

    // The public entry points resolve DesktopStorage.accountDir *before* taking this object's lock.
    // That lazy runs activeSlotDir under DesktopStorage's store lock, while the code below takes
    // the store lock (registry reads) under this one; resolving it first keeps the order one-way.

    /** See [com.nuvio.app.core.auth.PlatformAccountScope.onAuthenticated]. */
    fun onAuthenticated(userId: String): Boolean {
        if (userId.isBlank()) return false
        DesktopStorage.accountDir
        return synchronized(this) { switchForSignIn(userId) }
    }

    private fun switchForSignIn(userId: String): Boolean {
        val active = activeSlot ?: return false
        val owner = registry.getString(ownerKey(active))
        if (owner == userId) return false

        if (registry.getBoolean(CarryOverKey) == true) {
            // Same person, another server: their settings come with them.
            registry.remove(CarryOverKey)
            owner?.let { registry.remove(slotKey(it)) }
            claim(active, userId)
            log.i { "Account folder $active carried over to the account on the new server" }
            return false
        }

        val existing = registry.getString(slotKey(userId))?.takeIf { accountsRoot.resolve(it).isDirectory() }
        if (existing != null) {
            // This account has used the PC before: restart into its own settings. An unowned slot
            // being left has just been written by this session's sign-in, so it goes too.
            if (owner == null) markForDelete(active)
            registry.putString(ActiveKey, existing)
            registry.flush()
            log.i { "Signing in to an account with its own settings folder; restarting into it" }
            restart()
            return true
        }
        if (owner == null) {
            claim(active, userId)
            log.i { "New account on this PC; it starts from the fresh settings folder $active" }
            return false
        }
        // The folder in use belongs to someone else (sessions out of step with the registry):
        // never let this account read or write it.
        val fresh = newSlot()
        claim(fresh, userId)
        registry.putString(ActiveKey, fresh)
        registry.flush()
        log.w { "Signed-in account does not own the active settings folder; restarting into a new one" }
        restart()
        return true
    }

    /** See [com.nuvio.app.core.auth.PlatformAccountScope.onSignedOut]. */
    fun onSignedOut(erase: Boolean): Boolean {
        DesktopStorage.accountDir
        return synchronized(this) { leaveForSignOut(erase) }
    }

    private fun leaveForSignOut(erase: Boolean): Boolean {
        val active = activeSlot ?: return false
        if (erase) {
            registry.getString(ownerKey(active))?.let { registry.remove(slotKey(it)) }
            registry.remove(ownerKey(active))
            markForDelete(active)
        }
        registry.putString(ActiveKey, newSlot())
        registry.flush()
        log.i { "Signed out (erase=$erase); restarting into a fresh settings folder" }
        restart()
        return true
    }

    fun onServerSwitch() {
        DesktopStorage.accountDir
        synchronized(this) {
            registry.putBoolean(CarryOverKey, true)
            registry.flush()
        }
    }

    private fun claim(slot: String, userId: String) {
        registry.putString(ownerKey(slot), userId)
        registry.putString(slotKey(userId), slot)
        registry.flush()
    }

    private fun newSlot(): String {
        val slot = "a-" + UUID.randomUUID().toString().substring(0, 8)
        Files.createDirectories(accountsRoot.resolve(slot))
        return slot
    }

    private fun markForDelete(slot: String) {
        val pending = registry.getString(PendingDeleteKey).orEmpty().split(',').filter(String::isNotBlank).toMutableSet()
        pending += slot
        registry.putString(PendingDeleteKey, pending.joinToString(","))
    }

    private fun deletePendingSlots() {
        val pending = registry.getString(PendingDeleteKey).orEmpty().split(',').filter(String::isNotBlank)
        if (pending.isEmpty()) return
        pending.forEach { slot ->
            deleteSlot(slot)
            registry.remove(ownerKey(slot))
            registry.remove(seededKey(slot))
        }
        registry.remove(PendingDeleteKey)
    }

    /** Unowned slots other than the active one: fresh folders nobody signed in to. */
    private fun deleteAbandonedSlots(keep: String) {
        val slots = runCatching { Files.list(accountsRoot).use { paths -> paths.filter { it.isDirectory() }.map { it.name }.toList() } }
            .getOrDefault(emptyList())
        slots.filter { it != keep && registry.getString(ownerKey(it)) == null }.forEach { slot ->
            deleteSlot(slot)
            registry.remove(seededKey(slot))
        }
    }

    private fun deleteSlot(slot: String) {
        val dir = accountsRoot.resolve(slot)
        if (!dir.exists()) return
        runCatching { dir.toFile().deleteRecursively() }
            .onFailure { log.w(it) { "Could not delete settings folder $slot" } }
    }

    /**
     * Moves account stores written before account folders existed (`nuvio_*.properties` and their
     * `.bak`, plus `torrent_settings`) from the data directory into [slotDir]. Machine stores and
     * anything else in the data directory stay put.
     */
    private fun migrateLegacyStores(slotDir: Path): Int {
        Files.createDirectories(slotDir)
        val files = runCatching {
            Files.list(DesktopStorage.rootDir).use { paths -> paths.filter(Files::isRegularFile).toList() }
        }.getOrDefault(emptyList())
        var moved = 0
        for (file in files) {
            val name = file.name
            val stem = when {
                name.endsWith(".properties") -> name.removeSuffix(".properties")
                name.endsWith(".properties.bak") -> name.removeSuffix(".properties.bak")
                else -> continue
            }
            if (stem in MACHINE_STORE_NAMES) continue
            if (!stem.startsWith("nuvio_") && stem != "torrent_settings") continue
            runCatching { Files.move(file, slotDir.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
                .onSuccess { moved++ }
                .onFailure { log.w(it) { "Could not move $name into the account folder" } }
        }
        return moved
    }

    /** Replaced in tests, which cannot let the JVM exit. */
    internal var restart: () -> Unit = ::restartIntoActiveSlot

    private fun restartIntoActiveSlot() {
        DesktopStorage.flushAll()
        if (!relaunchAfterExit("relaunch-after-account-switch.bat")) {
            // A dev run has no launcher; it still has to stop, since every store this process has
            // open belongs to the folder it is leaving.
            exitSoon()
        }
    }
}
