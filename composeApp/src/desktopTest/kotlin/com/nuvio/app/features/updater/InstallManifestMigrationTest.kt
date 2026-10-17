package com.nuvio.app.features.updater

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-time JVM -> native move (native-image plan step 6). The native zip replaces `Nuvio.zip`,
 * so an ordinary update swaps a JVM install for the native layout. The jars and the JVM runtime
 * must go as stale files (the previous manifest listed them and the new build does not ship them),
 * everything both layouts ship must stay, and files the user put in the install folder must never
 * be touched. File names are taken from the 2026-10-17 JVM and native build outputs.
 */
class InstallManifestMigrationTest {
    private val tempRoots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    private val shared = listOf(
        "Nuvio.exe",
        "app\\Nuvio.cfg",
        "avcodec-62.dll",
        "libmpv-2.dll",
        "player_bridge.dll",
        "nuvio_engine.dll",
        "resources\\torrserver\\TorrServer.exe",
        "resources\\backdrop-framing\\u2netp.onnx",
        "app\\resources\\some-compose-resource.xml",
    )

    private val jvmOnly = listOf(
        "app\\composeApp-desktop-1.15.0.jar",
        "app\\skiko-awt-runtime-windows-x64.jar",
        "app\\Nuvio.jsa",
        "runtime\\bin\\server\\jvm.dll",
        "runtime\\bin\\java.dll",
        "runtime\\lib\\modules",
        "runtime\\lib\\security\\cacerts",
        "runtime\\conf\\security\\java.security",
        "runtime\\legal\\java.base\\LICENSE",
        "runtime\\release",
    )

    private val nativeOnly = listOf(
        "java.dll",
        "awt.dll",
        "jvm.dll",
        "fontmanager.dll",
        "runtime\\bin\\jawt.dll",
        "runtime\\lib\\fontconfig.bfc",
    )

    @Test
    fun aNativeUpdateOverAJvmInstallRemovesExactlyTheJvmOnlyFiles() {
        val install = tempDir()
        val jvmFiles = shared + jvmOnly
        jvmFiles.forEach { install.touch(it) }
        File(install, installManifestName).writeText(jvmFiles.joinToString("\r\n"), Charsets.UTF_8)
        install.touch("my-notes.txt") // the user's own file, never in a manifest
        val staged = tempDir()
        val nativeFiles = shared + nativeOnly

        val stale = writeInstallManifest(sourceRoot = staged, installRoot = install, newFiles = nativeFiles)

        assertEquals(jvmOnly.toSet(), stale.toSet())
        assertTrue(stale.none { it in shared || it in nativeOnly })
        assertTrue("my-notes.txt" !in stale)
        // The new manifest lists the native build, so the next update reasons from it.
        val written = File(staged, installManifestName).readLines(Charsets.UTF_8).toSet()
        assertEquals(nativeFiles.toSet(), written)
    }

    @Test
    fun aJvmInstallWithoutAManifestStillLosesItsJarsAndRuntime() {
        // app\*.jar, app\*.jsa and runtime\ only ever hold the build's own files, so they are
        // cleaned without a manifest. Anything else unlisted (an old DLL at the root, the user's
        // own files) stays, because nothing proves it was ours.
        val install = tempDir()
        (shared + jvmOnly).forEach { install.touch(it) }
        install.touch("old-unlisted.dll")
        install.touch("my-notes.txt")
        val stale = writeInstallManifest(sourceRoot = tempDir(), installRoot = install, newFiles = shared + nativeOnly)
        assertEquals(jvmOnly.toSet(), stale.toSet())
    }

    @Test
    fun jarsLeftByHandUnzippedUpdatesAreRemoved() {
        // Every build's jar names carry a content hash; unzipping updates over the folder kept the
        // old ones (one user's app folder: 179 files, 1.4 GB). A manifest written after that never
        // names them, but the next update still clears them.
        val install = tempDir()
        val current = listOf("Nuvio.exe", "app\\Nuvio.cfg", "app\\composeApp-desktop-1111.jar", "app\\nuvio-app.jsa")
        current.forEach { install.touch(it) }
        File(install, installManifestName).writeText(current.joinToString("\r\n"), Charsets.UTF_8)
        val leftovers = (1..25).map { "app\\composeApp-desktop-old$it.jar" }
        leftovers.forEach { install.touch(it) }
        val next = listOf("Nuvio.exe", "app\\Nuvio.cfg", "app\\composeApp-desktop-2222.jar", "app\\nuvio-app.jsa")

        val stale = writeInstallManifest(sourceRoot = tempDir(), installRoot = install, newFiles = next)

        assertEquals((leftovers + "app\\composeApp-desktop-1111.jar").toSet(), stale.toSet())
        assertTrue("app\\nuvio-app.jsa" !in stale && "app\\Nuvio.cfg" !in stale)
    }

    @Test
    fun manifestEntriesThatPointOutsideTheInstallAreNeverStale() {
        val install = tempDir()
        File(install, installManifestName).writeText(
            listOf("Nuvio.exe", "..\\outside.dll", "C:\\Windows\\evil.dll", "\\rooted.dll", "app\\old.jar").joinToString("\r\n"),
            Charsets.UTF_8,
        )
        val stale = writeInstallManifest(sourceRoot = tempDir(), installRoot = install, newFiles = listOf("Nuvio.exe"))
        assertEquals(listOf("app\\old.jar"), stale)
    }

    private fun tempDir(): File = Files.createTempDirectory("install-manifest-test").toFile().also { tempRoots += it }

    private fun File.touch(relative: String) {
        File(this, relative).apply { parentFile.mkdirs() }.writeText("x")
    }
}
