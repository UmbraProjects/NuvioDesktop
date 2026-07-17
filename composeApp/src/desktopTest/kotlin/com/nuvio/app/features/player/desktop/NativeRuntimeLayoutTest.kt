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
