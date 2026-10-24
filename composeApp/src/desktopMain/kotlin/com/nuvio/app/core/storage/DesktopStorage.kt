package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
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
    val isFreshInstall: Boolean
        get() {
            rootDir
            return freshInstall
        }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

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
            if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            markDirty()
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
            properties.remove(key)
            markDirty()
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
            val failure = runCatching { writeToDisk(snapshot) }.exceptionOrNull()
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
