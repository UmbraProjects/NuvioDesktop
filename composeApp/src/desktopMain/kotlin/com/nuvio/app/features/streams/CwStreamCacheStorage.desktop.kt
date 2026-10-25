package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopStorage
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal actual object CwStreamCacheStorage {
    private val lock = Any()
    private val root: Path get() = DesktopStorage.accountDir.resolve("cw-stream-cache")

    private fun file(profileId: Int): Path = root.resolve("profile-$profileId.json.gz")

    actual fun <T> read(profileId: Int, decode: (InputStream) -> T): T? = synchronized(lock) {
        val target = file(profileId)
        if (!target.exists()) return null
        GZIPInputStream(target.inputStream().buffered()).use(decode)
    }

    actual fun write(profileId: Int, encode: (OutputStream) -> Unit): Unit = synchronized(lock) {
        val target = file(profileId)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            GZIPOutputStream(tmp.outputStream().buffered()).use(encode)
            runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .recoverCatching { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
                .getOrThrow()
        } finally {
            tmp.deleteIfExists()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    actual fun clearAll(): Unit = synchronized(lock) {
        if (root.exists()) root.deleteRecursively()
    }
}
