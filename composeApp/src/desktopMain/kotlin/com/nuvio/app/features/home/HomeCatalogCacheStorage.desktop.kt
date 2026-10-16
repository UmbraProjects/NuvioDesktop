package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `accounts/<slot>/home-catalog-cache/p<profile>/<key>.json`: in the account's own folder, so it
 * leaves with the account, and one file per row so a refresh rewrites rows, not one large blob.
 */
internal actual object HomeCatalogCacheStorage {
    private val root: Path get() = DesktopStorage.accountDir.resolve("home-catalog-cache")

    private fun file(profileId: Int, fileKey: String): Path = root.resolve("p$profileId").resolve("$fileKey.json")

    actual fun read(profileId: Int, fileKey: String): String? =
        file(profileId, fileKey).takeIf { it.exists() }?.readText()

    actual fun write(profileId: Int, fileKey: String, contents: String) {
        val target = file(profileId, fileKey)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeText(contents)
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .recoverCatching { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    actual fun clearAll() {
        if (root.exists()) root.deleteRecursively()
    }
}
