package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeRuntimeLayoutTest {
    @Test
    fun `complete packaged Windows runtime is used without requiring a writable install directory`() {
        val installDir = createTempDirectory("nuvio-packaged-runtime-").toFile()
        try {
            val javaHome = installDir.resolve("runtime").apply { mkdirs() }
            installDir.resolve("player_bridge.dll").writeBytes(byteArrayOf(1))
            installDir.resolve("libmpv-2.dll").writeBytes(byteArrayOf(2))

            val result = findPackagedNativeRuntime(
                platform = DesktopHostOs.WINDOWS,
                javaHome = javaHome,
                requiredFiles = listOf("player_bridge.dll", "libmpv-2.dll"),
            )

            assertEquals(installDir.canonicalFile, result?.canonicalFile)
        } finally {
            installDir.deleteRecursively()
        }
    }

    @Test
    fun `packaged macOS bridge is found beside app jars inside the app bundle`() {
        val appBundle = createTempDirectory("nuvio-macos-runtime-").toFile().resolve("Nuvio.app")
        try {
            val appContents = appBundle.resolve("Contents")
            val javaHome = appContents.resolve("runtime/Contents/Home").apply { mkdirs() }
            val appDir = appContents.resolve("app").apply { mkdirs() }
            appDir.resolve("libplayer_bridge.dylib").writeBytes(byteArrayOf(1))

            val result = findPackagedNativeRuntime(
                platform = DesktopHostOs.MACOS,
                javaHome = javaHome,
                requiredFiles = listOf("libplayer_bridge.dylib"),
            )

            assertEquals(appDir.canonicalFile, result?.canonicalFile)
        } finally {
            appBundle.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `missing packaged macOS bridge is rejected`() {
        val appBundle = createTempDirectory("nuvio-macos-incomplete-").toFile().resolve("Nuvio.app")
        try {
            val appContents = appBundle.resolve("Contents")
            val javaHome = appContents.resolve("runtime/Contents/Home").apply { mkdirs() }
            appContents.resolve("app").mkdirs()

            assertNull(
                findPackagedNativeRuntime(
                    platform = DesktopHostOs.MACOS,
                    javaHome = javaHome,
                    requiredFiles = listOf("libplayer_bridge.dylib"),
                ),
            )
        } finally {
            appBundle.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `incomplete packaged runtime is rejected`() {
        val installDir = createTempDirectory("nuvio-incomplete-runtime-").toFile()
        try {
            val javaHome = installDir.resolve("runtime").apply { mkdirs() }
            Files.createFile(installDir.resolve("player_bridge.dll").toPath())

            assertNull(
                findPackagedNativeRuntime(
                    platform = DesktopHostOs.WINDOWS,
                    javaHome = javaHome,
                    requiredFiles = listOf("player_bridge.dll", "libmpv-2.dll"),
                ),
            )
        } finally {
            installDir.deleteRecursively()
        }
    }
}
