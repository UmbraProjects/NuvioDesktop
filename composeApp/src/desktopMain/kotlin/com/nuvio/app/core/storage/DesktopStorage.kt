package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

internal object DesktopStorage {
    private val log = Logger.withTag("DesktopStorage")
    private const val SLOW_LOAD_LOG_THRESHOLD_MS = 250L
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir()
    }

    @Volatile
    private var freshInstall = false

    /**
     * True when neither a Nuvio HTPC data directory nor a legacy Nuvio directory existed when
     * storage was first resolved — i.e. a genuinely new user, not an upgrade and not someone who
     * simply never changed a given setting. Touching this resolves [rootDir], so the answer is
     * captured before anything has had a chance to create the directory.
     */
    /**
     * True when this session's account folder has never been used: a fresh install, or a new
     * account signing in on this PC. What decides new-user defaults and the first-run wizard now
     * that settings live per account; [isFreshInstall] only says the data directory is new.
     */
    val isFreshAccount: Boolean
        get() {
            accountDir
            return DesktopAccountSlots.isFreshSlot
        }

    val isFreshInstall: Boolean
        get() {
            rootDir
            return freshInstall
        }

    /**
     * The signed-in account's own folder, `accounts/<slot>/`, holding every store not in
     * [MACHINE_STORE_NAMES]. Fixed for the life of the process: switching account restarts the app
     * (see DesktopAccountSlots), because nearly every repository caches its store in memory.
     */
    val accountDir: Path by lazy { DesktopAccountSlots.activeSlotDir() }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(storeFile(name)) }
    }

    /** Where [name]'s file lives: the data directory for machine stores, else [accountDir]. */
    fun storeFile(name: String): Path =
        if (name in MACHINE_STORE_NAMES) rootDir.resolve("$name.properties") else accountDir.resolve("$name.properties")

    /**
     * Deletes the named stores (memory, file, and `.bak`/`.tmp` siblings) and nothing else.
     *
     * Deliberately a list of what to delete rather than what to keep: the data directory also holds
     * user-authored files (the game library, hero badges), machine settings, the open log file and
     * the single-instance lock, and a "delete everything except" walk took all of them. The [Store]
     * objects stay registered, because storage shims cache them at class init; replacing them would
     * leave two objects writing the same file.
     */
    fun wipe(storeNames: Set<String>) {
        storeNames.forEach { name -> store(name).deleteAll() }
    }

    private fun resolveAppDataDir(): Path {
        // Tests point this at a throwaway directory so they never touch the real install.
        System.getProperty("nuvio.desktop.dataDir")?.takeIf(String::isNotBlank)?.let { override ->
            return Paths.get(override).also { Files.createDirectories(it) }
        }
        resolvePortableDataDir()?.let { return it }
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        if (!osName.contains("win")) {
            val parent = if (osName.contains("mac")) {
                userHome.resolve("Library/Application Support")
            } else {
                System.getenv("XDG_CONFIG_HOME")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Paths::get)
                    ?: userHome.resolve(".config")
            }
            val destination = parent.resolve(if (osName.contains("mac")) "NuvioHTPC" else "nuviohtpc")
            val legacy = parent.resolve(if (osName.contains("mac")) "Nuvio" else "nuvio")
            freshInstall = !destination.exists() && !legacy.exists()
            migrateLegacyDirectories(destination, legacy)
            return destination
        }

        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?: userHome.resolve("AppData/Local")
        val destination = localAppData.resolve("NuvioHTPC")
        if (destination.exists()) return destination

        // Nuvio Desktop stored preferences in roaming AppData while its logs, custom badges,
        // and updater files lived in Local AppData. Nuvio HTPC uses a single Local AppData
        // directory, so bring both old locations forward once before creating any new files.
        val roamingNuvio = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?.resolve("Nuvio")
            ?: userHome.resolve("AppData/Roaming/Nuvio")
        val localNuvio = localAppData.resolve("Nuvio")
        // These are also the *official* Nuvio Desktop's directories (settings in roaming, cache in
        // local), so their existence alone only proves the user has tried upstream. That user is
        // new to this fork and gets the wizard and new-install defaults; only a directory holding a
        // store this fork alone ever wrote marks an upgrade from a pre-1.10 HTPC build.
        freshInstall = listOf(roamingNuvio, localNuvio).none(::holdsHtpcOnlyStore)
        migrateLegacyDirectories(destination, roamingNuvio, localNuvio)
        return destination
    }

    /**
     * Portable mode (Settings > Account > Updates): a `portable` marker next to Nuvio.exe keeps all
     * data in a `data` folder beside it instead of %LOCALAPPDATA%\NuvioHTPC. Safe across updates:
     * the updater only deletes files the previous release's manifest listed, never `data\`.
     *
     * Both directions move the data at startup, after the relaunch helper has waited out the old
     * process, so nothing is mid-write:
     * - On: the first portable launch copies the AppData folder across, minus caches, logs, locks
     *   and staged updates, then *moves* the caches in [PORTABLE_MOVABLE_CACHES] when both folders
     *   are on one drive (a rename, instant at any size; the image cache is ~60k files). On
     *   different drives they stay behind and rebuild. The settings in AppData are left as they
     *   were. If the copy fails, this run stays on AppData and the next launch tries again, rather
     *   than starting from an empty folder.
     * - Off: the setting leaves [PORTABLE_RETURN_FLAG] in `data`; the next launch copies `data`
     *   back over AppData (same exclusions), moves the caches back the same way, and renames `data`
     *   to `data.old`, so switching on again later copies fresh rather than resurrecting stale
     *   settings.
     */
    private fun resolvePortableDataDir(): Path? =
        resolvePortableDataDirAndSwitch().also { root -> if (root != null) flushSwitchLog(root) }

    private fun resolvePortableDataDirAndSwitch(): Path? {
        val installRoot = portableInstallRoot() ?: return null
        val portableDir = installRoot.resolve(PORTABLE_DATA_DIR_NAME)
        val installed = appDataInstallDir()
        val marked = PORTABLE_MARKER_NAMES.any { Files.isRegularFile(installRoot.resolve(it)) }
        if (!marked) {
            return if (Files.isRegularFile(portableDir.resolve(PORTABLE_RETURN_FLAG))) {
                returnPortableDataToAppData(portableDir, installed)
            } else {
                null
            }
        }
        if (portableDir.exists()) {
            // Switched off and straight back on before a launch ran the return: just stay portable.
            runCatching { Files.deleteIfExists(portableDir.resolve(PORTABLE_RETURN_FLAG)) }
            return portableDir
        }
        if (!installed.exists()) {
            freshInstall = true
            Files.createDirectories(portableDir)
            return portableDir
        }

        return copyAppDataIntoPortable(installed, portableDir)
    }

    /** Switch on: [installed] stays as it was; returns the folder this run should use. */
    internal fun copyAppDataIntoPortable(installed: Path, portableDir: Path): Path {
        val staging = portableDir.resolveSibling("${portableDir.fileName}.migrating-${UUID.randomUUID()}")
        switchLog("switching on: copying $installed -> $portableDir")
        return try {
            copyDataTree(source = installed, target = staging)
            withLockRetry("rename staging to $portableDir") {
                runCatching { Files.move(staging, portableDir, StandardCopyOption.ATOMIC_MOVE) }
                    .getOrElse { Files.move(staging, portableDir) }
            }
            moveCachesIfSameDrive(from = installed, to = portableDir)
            switchLog("switched on")
            portableDir
        } catch (error: Exception) {
            switchLog("switch on FAILED, staying on AppData this run: ${error.describe()}")
            deleteTreeQuietly(staging)
            installed
        }
    }

    /** Switch off: returns the folder this run should use (still [portableDir] on failure). */
    internal fun returnPortableDataToAppData(portableDir: Path, installed: Path): Path =
        try {
            switchLog("switching off: copying $portableDir -> $installed")
            copyDataTree(source = portableDir, target = installed)
            moveCachesIfSameDrive(from = portableDir, to = installed)
            val old = portableDir.resolveSibling("${portableDir.fileName}.old")
            if (old.exists()) {
                // A previous data.old still holding its multi-GB image cache: rename it out of the
                // way now and delete it off the startup path.
                val doomed = portableDir.resolveSibling("${portableDir.fileName}.old-${UUID.randomUUID()}")
                Files.move(old, doomed)
                Thread { deleteTreeQuietly(doomed) }
                    .apply { isDaemon = true; name = "nuvio-portable-cleanup" }
                    .start()
            }
            withLockRetry("rename $portableDir to ${old.fileName}") { Files.move(portableDir, old) }
            switchLog("switched off")
            installed
        } catch (error: Exception) {
            // Keep the portable data for this run; the flag stays, so the next launch retries.
            switchLog("switch off FAILED, staying portable this run: ${error.describe()}")
            portableDir
        }

    /**
     * ATOMIC_MOVE makes this a pure rename: across drives it throws instead of degrading into a
     * multi-GB copy on the startup path, and the cache simply stays behind. A destination that
     * already has its own copy is left alone.
     */
    private fun moveCachesIfSameDrive(from: Path, to: Path) {
        PORTABLE_MOVABLE_CACHES.forEach { name ->
            val source = from.resolve(name)
            val target = to.resolve(name)
            if (!Files.isDirectory(source)) return@forEach
            if (target.exists()) {
                switchLog("cache $name: destination already has one, left in place")
                return@forEach
            }
            runCatching {
                withLockRetry("move cache $name") { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
            }
                .onSuccess { switchLog("cache $name: moved") }
                .onFailure { switchLog("cache $name: left behind, ${it.describe()}") }
        }
    }

    /** Copies [source] into [target], skipping [PORTABLE_MIGRATION_SKIP] at the top level. */
    private fun copyDataTree(source: Path, target: Path) {
        Files.createDirectories(target)
        Files.walk(source).use { paths ->
            paths.forEach { current ->
                val relative = source.relativize(current)
                if (relative.nameCount == 0) return@forEach
                val top = relative.getName(0).toString().lowercase(Locale.ROOT)
                if (top in PORTABLE_MIGRATION_SKIP) return@forEach
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    // REPLACE_EXISTING in both directions: a retried attempt may have left a
                    // partial file, and the copy back overwrites AppData's older settings.
                    withLockRetry("copy $relative") {
                        Files.copy(
                            current,
                            destination,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
            }
        }
    }

    /**
     * A file another process still has open (a helper that outlived the restart, an antivirus or
     * indexer scan) answers with an IOException that clears within moments, so each step gets a
     * few seconds of retries before the whole switch is abandoned.
     */
    private fun <T> withLockRetry(what: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: java.io.IOException) {
                // Only an in-use refusal can clear by waiting; a cross-drive move or a missing or
                // existing path answers the same every time, so it fails straight away.
                val permanent = error is java.nio.file.AtomicMoveNotSupportedException ||
                    error is java.nio.file.FileAlreadyExistsException ||
                    error is java.nio.file.NoSuchFileException ||
                    error is java.nio.file.DirectoryNotEmptyException
                attempt += 1
                if (permanent || attempt >= LOCK_RETRY_ATTEMPTS) throw error
                if (attempt == 1) switchLog("$what: ${error.describe()}, retrying")
                Thread.sleep(LOCK_RETRY_DELAY_MS)
            }
        }
    }

    private const val LOCK_RETRY_ATTEMPTS = 10
    private const val LOCK_RETRY_DELAY_MS = 500L

    /**
     * The switch runs before logging exists (the log lives in the folder being chosen), so its
     * story is kept here and written to `logs/portable-switch.log` in whichever folder won.
     */
    private val switchLogLines = mutableListOf<String>()

    private fun switchLog(message: String) {
        val line = "${java.time.LocalDateTime.now().withNano(0)} $message"
        switchLogLines += line
        System.err.println("Portable: $message")
    }

    private fun flushSwitchLog(root: Path) {
        if (switchLogLines.isEmpty()) return
        runCatching {
            val logDir = root.resolve("logs").also { Files.createDirectories(it) }
            Files.writeString(
                logDir.resolve("portable-switch.log"),
                switchLogLines.joinToString(separator = "\r\n", postfix = "\r\n"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
        switchLogLines.clear()
    }

    private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message.orEmpty()}"

    private fun deleteTreeQuietly(root: Path) {
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun appDataInstallDir(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home").orEmpty(), "AppData", "Local")
        return localAppData.resolve("NuvioHTPC")
    }

    /** The folder holding Nuvio.exe; null in a dev run, which has no install to be portable in. */
    fun portableInstallRoot(): Path? {
        val appPath = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank) ?: return null
        return Paths.get(appPath).parent
    }

    /** Whether this session's data lives beside the exe (see [resolvePortableDataDir]). */
    val isPortable: Boolean
        get() = portableInstallRoot()?.resolve(PORTABLE_DATA_DIR_NAME) == rootDir

    /**
     * Records the portable choice for the next launch, which does the actual move. False when the
     * install folder can't be written (e.g. under Program Files), so the caller must not restart.
     */
    fun setPortable(enabled: Boolean): Boolean {
        val installRoot = portableInstallRoot() ?: return false
        return runCatching {
            if (enabled) {
                Files.writeString(
                    installRoot.resolve(PORTABLE_MARKER_NAMES.first()),
                    "Nuvio HTPC keeps its data in the data folder beside this file. " +
                        "Turn portable mode off in Settings > Account rather than deleting this.\r\n",
                )
            } else {
                PORTABLE_MARKER_NAMES.forEach { Files.deleteIfExists(installRoot.resolve(it)) }
                if (isPortable) Files.writeString(rootDir.resolve(PORTABLE_RETURN_FLAG), "")
            }
            true
        }.getOrElse { error ->
            log.w(error) { "Could not switch portable mode to $enabled" }
            false
        }
    }

    private val PORTABLE_MARKER_NAMES = listOf("portable", "portable.txt")
    private const val PORTABLE_DATA_DIR_NAME = "data"
    private const val PORTABLE_RETURN_FLAG = "return-to-appdata"

    /** Caches worth keeping across a switch; moved only when that is a rename (see above). */
    private val PORTABLE_MOVABLE_CACHES = listOf("image_cache", "image_cache_animated", "shader-cache", "cache")

    /**
     * Top-level entries the settings copy skips: caches (moved separately, or rebuilt), the torrent
     * cache (only part-streamed data), logs, locks and staged updates.
     */
    private val PORTABLE_MIGRATION_SKIP = setOf(
        "image_cache",
        "image_cache_animated",
        "cache",
        "shader-cache",
        "nuvio-engine",
        "logs",
        "updates",
        "instance.lock",
        "instance.port",
        "single-instance-port.txt",
        PORTABLE_RETURN_FLAG,
    )

    /**
     * Stores HTPC wrote into the shared `Nuvio` directory up to 1.9 (1.10 moved to `NuvioHTPC`)
     * that upstream Nuvio Desktop has never had. Checked against upstream/Dev on 2026-09-25; if
     * upstream later adopts one of these names, drop it from the list.
     */
    private val HTPC_ONLY_LEGACY_STORES = listOf(
        "nuvio_api_keys_onboarding",
        "nuvio_discord_presence",
        "nuvio_mdblist_ratings_cache",
        "nuvio_player_shortcuts",
        "nuvio_settings_category_order",
        "nuvio_settings_favorites",
        "nuvio_simkl_settings",
        "nuvio_tvdb_settings",
    )

    internal fun holdsHtpcOnlyStore(directory: Path): Boolean =
        directory.exists() && HTPC_ONLY_LEGACY_STORES.any { name -> directory.resolve("$name.properties").exists() }

    private fun migrateLegacyDirectories(destination: Path, vararg sources: Path) {
        synchronized(stores) {
            if (destination.exists()) return
            val staging = destination.resolveSibling("${destination.fileName}.migrating-${UUID.randomUUID()}")
            try {
                Files.createDirectories(staging)
                sources
                    .filter { it.exists() && it != destination }
                    .forEach { source ->
                        Files.walk(source).use { paths ->
                            paths.forEach { current ->
                                val target = staging.resolve(source.relativize(current).toString())
                                if (Files.isDirectory(current)) {
                                    Files.createDirectories(target)
                                } else if (!target.exists()) {
                                    Files.createDirectories(target.parent)
                                    Files.copy(current, target)
                                }
                            }
                        }
                    }
                runCatching { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) }
                    .getOrElse { Files.move(staging, destination) }
            } catch (error: Exception) {
                // Migration problems must not block startup; the original Nuvio folders remain
                // untouched and the app starts with a fresh destination instead.
                System.err.println("Unable to migrate Nuvio data to $destination: ${error.message}")
                Files.createDirectories(destination)
            }
        }
    }

    /**
     * Writes are coalesced: [Store.putString] and friends update memory and schedule a flush on this
     * one background thread [FLUSH_DELAY_MS] later, instead of rewriting the whole file (up to
     * 3.6 MB) synchronously on the caller's thread — which was often the EDT. [flushAll] runs at
     * exit (and from a shutdown hook, for paths that call `exitProcess` directly) and before
     * anything that reads the files off disk, such as the settings backup.
     */
    private val flushExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-storage-flush").apply { isDaemon = true }
        }.also {
            Runtime.getRuntime().addShutdownHook(Thread({ flushAll() }, "nuvio-storage-exit-flush"))
        }
    }

    private const val FLUSH_DELAY_MS = 1_500L
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private val LOAD_RETRY_DELAYS_MS = longArrayOf(50L, 250L, 1_000L)

    /** Writes every store with pending changes, on the calling thread. Safe to call repeatedly. */
    fun flushAll() {
        val snapshot = synchronized(stores) { stores.values.toList() }
        snapshot.forEach { store -> store.flush() }
    }

    internal class Store(
        private val file: Path,
        private val flushDelayMs: Long = FLUSH_DELAY_MS,
    ) {
        /** Guards [properties] and the flags; held across disk I/O only for the initial load. */
        private val lock = Any()

        /** Serialises disk writes and deletes of this store's files. Always taken before [lock]. */
        private val ioLock = Any()
        private val properties = Properties()
        private var loaded = false
        private var dirty = false
        private var flushScheduled = false
        private var consecutiveFailures = 0

        /**
         * Set when the file exists but could not be read. Every later write would replace the real
         * contents with whatever little got loaded, so the store keeps working in memory for this
         * session but never touches the file again.
         */
        private var readOnly = false

        private val backupFile: Path get() = file.resolveSibling("${file.fileName}.bak")

        /** The store as `(PerfStore)` names it: the file name without its extension. */
        private val perfName: String = file.fileName.toString().substringBeforeLast('.')
        private val tempFile: Path get() = file.resolveSibling("${file.fileName}.tmp")

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val previous = if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            val changed = previous != value
            PerfTelemetry.recordStoreWrite(perfName, key, changed)
            // Callers routinely re-persist whole state objects that did not change (Home's catalog
            // settings on every return to Home, the avatar catalogue on every profile-bar mount);
            // each one used to rewrite the entire file - 197 KB for the catalog store.
            if (changed) markDirty()
        }

        fun keys(): Set<String> = synchronized(lock) {
            ensureLoaded()
            properties.stringPropertyNames()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            val previous = properties.remove(key)
            PerfTelemetry.recordStoreWrite(perfName, key, changed = previous != null)
            if (previous != null) markDirty()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            keys.forEach(properties::remove)
            markDirty()
        }

        /**
         * Drops every key in memory and deletes the file and its `.bak`/`.tmp` siblings. The
         * [Store] object stays valid (callers cache it at class init), and the next access reloads
         * from the now-missing file as an empty store.
         */
        fun deleteAll() = synchronized(ioLock) {
            synchronized(lock) {
                properties.clear()
                loaded = false
                dirty = false
                readOnly = false
                consecutiveFailures = 0
            }
            listOf(file, backupFile, tempFile).forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }

        /** Writes pending changes now, on the calling thread. */
        fun flush() = synchronized(ioLock) {
            val snapshot = synchronized(lock) {
                flushScheduled = false
                if (!dirty || readOnly) {
                    null
                } else {
                    dirty = false
                    // Serialised outside [lock] so readers never wait on the disk.
                    Properties().also { copy -> copy.putAll(properties) }
                }
            } ?: return@synchronized
            val writeStartedAt = System.nanoTime()
            val failure = runCatching { writeToDisk(snapshot) }.exceptionOrNull()
            if (failure == null && PerfTelemetry.enabled) {
                PerfTelemetry.recordStoreFlush(
                    perfName,
                    bytes = runCatching { Files.size(file) }.getOrDefault(-1L),
                    durationMs = (System.nanoTime() - writeStartedAt) / 1_000_000,
                )
            }
            synchronized(lock) {
                if (failure == null) {
                    if (consecutiveFailures > 0) {
                        log.i { "store ${file.fileName} written after $consecutiveFailures failed attempt(s)" }
                    }
                    consecutiveFailures = 0
                    return@synchronized
                }
                // Typically AccessDenied from MoveFileEx while a scanner, indexer or backup tool has
                // the target open without FILE_SHARE_DELETE. Throwing would surface in whichever UI
                // handler happened to call putString (Compose closes the window on an EDT
                // exception), so keep the change in memory and try again shortly.
                consecutiveFailures += 1
                dirty = true
                val retryMs = (flushDelayMs shl (consecutiveFailures - 1).coerceAtMost(5))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
                log.w(failure) {
                    "store ${file.fileName} write failed (attempt $consecutiveFailures); retrying in ${retryMs}ms"
                }
                if (!flushScheduled) schedule(retryMs)
            }
        }

        private fun markDirty() {
            dirty = true
            if (readOnly) return
            if (!flushScheduled) schedule(flushDelayMs)
        }

        private fun schedule(delayMs: Long) {
            flushScheduled = true
            runCatching {
                flushExecutor.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
            }.onFailure {
                // The executor is gone (JVM shutting down); the exit flush picks this up.
                flushScheduled = false
            }
        }

        private fun ensureLoaded() {
            if (loaded) return
            properties.clear()
            // notExists, not !exists: `exists()` is also false when existence "cannot be
            // determined", which is exactly the locked-by-a-scanner case where treating the store
            // as new would let the next write wipe it.
            val source = when {
                !Files.notExists(file) -> file
                // A crash between writes can only leave .tmp, but a .bak with no main file means
                // the main file was lost some other way; the previous version beats an empty store.
                Files.exists(backupFile) -> backupFile.also {
                    log.w { "store ${file.fileName} missing; recovering from ${backupFile.fileName}" }
                }
                else -> {
                    loaded = true
                    return
                }
            }
            val result = loadWithRetry(source)
            var recovered = result.getOrNull()
            if (recovered == null && result.exceptionOrNull() is IllegalArgumentException && source == file) {
                // Malformed content (e.g. a bad \u escape): the previous good version is next door.
                recovered = loadWithRetry(backupFile).getOrNull()?.also {
                    log.e(result.exceptionOrNull()) {
                        "store ${file.fileName} is unreadable; loaded ${backupFile.fileName} instead"
                    }
                }
            }
            loaded = true
            if (recovered == null) {
                readOnly = true
                log.e(result.exceptionOrNull()) {
                    "store ${file.fileName} could not be read; keeping it read-only for this session " +
                        "so the file on disk is not overwritten"
                }
                return
            }
            properties.putAll(recovered)
        }

        private fun loadWithRetry(source: Path): Result<Properties> {
            var result = loadOnce(source)
            for (delayMs in LOAD_RETRY_DELAYS_MS) {
                val error = result.exceptionOrNull() ?: return result
                // Parse errors and a file that really is not there will not change on retry.
                if (error is IllegalArgumentException || error is java.nio.file.NoSuchFileException) return result
                Thread.sleep(delayMs)
                result = loadOnce(source)
            }
            return result
        }

        private fun loadOnce(source: Path): Result<Properties> {
            // Timed separately because the open alone has stalled the UI thread for seconds
            // (2026-09-14 launch sampler: 5.75s in `CreateFile0` under this method). These files
            // are rewritten on every save, so an on-access scanner treats each open as a fresh
            // file; knowing which store and whether it was the open or the parse is what the next
            // slow launch needs to say.
            val startedAt = System.nanoTime()
            var openedAt = startedAt
            // Loaded into a fresh object so a parse that fails halfway never leaves a partial set.
            val result = runCatching {
                Properties().also { fresh ->
                    Files.newInputStream(source).use { input ->
                        openedAt = System.nanoTime()
                        fresh.load(input)
                    }
                }
            }
            val totalMs = (System.nanoTime() - startedAt) / 1_000_000
            if (totalMs >= SLOW_LOAD_LOG_THRESHOLD_MS) {
                val openMs = (openedAt - startedAt) / 1_000_000
                log.w {
                    "slow store load: ${source.fileName} took ${totalMs}ms " +
                        "(open ${openMs}ms, parse ${totalMs - openMs}ms) on ${Thread.currentThread().name}"
                }
            }
            return result
        }

        private fun writeToDisk(snapshot: Properties) {
            Files.createDirectories(file.parent)
            // Write to a sibling temp file and atomically swap it in, rather than truncating the
            // real file and writing in place. The old in-place write left a window where a crash
            // (the app has intermittent silent CTDs) or kill mid-write would leave a half-written,
            // unparseable file — on next launch that store would silently reset to empty, which for
            // the large MDBList ratings/cast cache meant losing the whole cache and refetching
            // everything. With the swap, an interrupted write only ever leaves a stale .tmp; the
            // real file stays intact and complete.
            val tmp = tempFile
            try {
                Files.newOutputStream(tmp).use { output ->
                    snapshot.store(output, "Nuvio desktop preferences")
                }
                // Keep the outgoing version as .bak: a hard link costs no copy, and the swap below
                // replaces only the directory entry, so the link keeps the previous contents.
                runCatching {
                    Files.deleteIfExists(backupFile)
                    if (Files.exists(file)) Files.createLink(backupFile, file)
                }
                runCatching {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
                }.recoverCatching {
                    // Rare: some filesystems reject ATOMIC_MOVE onto an existing target. A plain
                    // replace still swaps in a fully-written temp file, so the destination is never
                    // left half-written the way the old truncate-in-place write could.
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw error
            }
        }
    }
}
