package com.nuvio.app.features.home

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
    val localAppData = System.getenv("LOCALAPPDATA")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    if (localAppData != null) {
        return File(localAppData, "Nuvio/Badges")
    }

    val appDataLocal = System.getenv("APPDATA")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.parentFile
        ?.resolve("Local/Nuvio/Badges")
    if (appDataLocal != null) return appDataLocal

    return File(System.getProperty("user.home"), "AppData/Local/Nuvio/Badges")
}
