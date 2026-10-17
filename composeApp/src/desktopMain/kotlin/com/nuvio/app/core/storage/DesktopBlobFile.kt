package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One large text value in its own per-account file, read when asked for and never held in memory.
 *
 * For caches that keep a single serialised blob: a [DesktopStorage.Store] keeps every value in
 * memory for the whole session so it can rewrite its file, which for a blob that the caller parses
 * once at startup meant carrying the raw JSON next to the parsed objects. Measured 2026-10-16: the
 * local calendar cache held 19 MB and the lightweight meta cache 17 MB of strings that were never
 * read again (9.8 MB and 8.6 MB files; non-Latin text costs two bytes a character in memory).
 *
 * Writes go to a sibling `.tmp` and are moved over the file, so an interrupted write leaves the old
 * file intact. Callers write from background threads. A value still in the old store (named
 * [legacyStoreName], under [legacyKey]) is moved here on first read, and the old store's files are
 * deleted.
 */
internal class DesktopBlobFile(
    private val name: String,
    private val legacyStoreName: String? = null,
    private val legacyKey: String? = null,
    private val directory: () -> Path = { DesktopStorage.accountDir },
) {
    private val lock = Any()
    private val file: Path get() = directory().resolve("$name.json")

    fun read(): String? = synchronized(lock) {
        val current = file
        if (Files.exists(current)) return Files.readString(current)
        migrateLegacy()
    }

    /** Null deletes the file. */
    fun write(text: String?): Unit = synchronized(lock) {
        val target = file
        if (text == null) {
            Files.deleteIfExists(target)
            return
        }
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temp, text)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(): Unit = synchronized(lock) {
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}.tmp"))
    }

    private fun migrateLegacy(): String? {
        val storeName = legacyStoreName ?: return null
        val key = legacyKey ?: return null
        // Only open the old store when its file exists; opening one creates its in-memory copy.
        if (!Files.exists(DesktopStorage.storeFile(storeName))) return null
        val store = DesktopStorage.store(storeName)
        val value = store.getString(key)
        runCatching { if (value != null) write(value) }
            .onFailure { error -> log.w(error) { "Could not move $storeName into $name.json" } }
            .onSuccess { store.deleteAll() }
        return value
    }

    private companion object {
        val log = Logger.withTag("DesktopBlobFile")
    }
}
