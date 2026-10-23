package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal actual object IptvStorage {
    private val store = DesktopStorage.store("nuvio_iptv")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("iptv"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("iptv"), payload)
    }
}

internal actual object IptvCacheStorage {
    private val root: Path get() = DesktopStorage.accountDir.resolve("iptv-cache")

    private fun file(name: String): Path = root.resolve(name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' })

    actual fun openRead(name: String): InputStream? =
        file(name).takeIf { it.exists() }?.inputStream()

    actual fun write(name: String, block: (OutputStream) -> Unit) {
        val target = file(name)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.outputStream().use(block)
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .recoverCatching { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    }

    actual fun delete(name: String) {
        file(name).deleteIfExists()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    actual fun clearAll() {
        if (root.exists()) root.deleteRecursively()
    }
}
