package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

internal actual object HeroDiscoveryConfigStorage {
    actual fun loadDefaultConfigText(): String? =
        Thread.currentThread()
            .contextClassLoader
            ?.getResourceAsStream(HeroDiscoveryConfigFileName)
            ?.bufferedReader()
            ?.use { it.readText() }

    actual fun loadUserConfigText(): String? {
        val file = File(resolveBadgeDirectory(), HeroDiscoveryConfigFileName)
        return file.takeIf(File::isFile)?.readText()
    }
}

private const val HeroDiscoveryConfigFileName = "hero_discovery.json"

private fun resolveBadgeDirectory(): File {
    return DesktopStorage.rootDir.resolve("Badges").toFile()
}
