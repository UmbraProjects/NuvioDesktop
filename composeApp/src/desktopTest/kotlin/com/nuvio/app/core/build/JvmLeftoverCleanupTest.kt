package com.nuvio.app.core.build

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmLeftoverCleanupTest {
    private val tempRoots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    @Test
    fun removesJvmLeftoversAndKeepsEverythingTheNativeBuildShips() {
        val install = Files.createTempDirectory("jvm-leftover-test").toFile().also { tempRoots += it }
        val leftovers = listOf(
            "app/composeApp-desktop-old1.jar",
            "app/composeApp-desktop-old2.jar",
            "app/nuvio-app.jsa",
            "runtime/bin/server/jvm.dll",
            "runtime/lib/modules",
            "runtime/legal/java.base/LICENSE",
            "runtime/conf/security/java.security",
            "runtime/release",
        )
        // Taken from the 2026-10-17 native layout, plus a user file and portable data.
        val kept = listOf(
            "Nuvio.exe",
            "app/Nuvio.cfg",
            "app/build-info.properties",
            "app/resources/x.xml",
            "runtime/bin/jawt.dll",
            "runtime/bin/vcruntime140.dll",
            "runtime/lib/fontconfig.bfc",
            "data/nuvio_updater.properties",
            "my-notes.txt",
        )
        (leftovers + kept).forEach { File(install, it).apply { parentFile.mkdirs() }.writeText("12345") }

        val freed = JvmLeftoverCleanup.clean(install)

        assertEquals(leftovers.size * 5L, freed)
        leftovers.forEach { assertFalse(File(install, it).exists(), "$it should be gone") }
        kept.forEach { assertTrue(File(install, it).isFile, "$it should stay") }
        assertFalse(File(install, "runtime/bin/server").exists())
        assertEquals(0L, JvmLeftoverCleanup.clean(install), "a second launch has nothing left to do")
    }
}
