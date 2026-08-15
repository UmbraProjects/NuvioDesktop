package com.nuvio.app.features.player

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddonSubtitleDownloadProviderTest {
    @Test
    fun `local media path resolves to the real file`() {
        val directory = createTempDirectory("nuvio-subtitle-target").toFile()
        val video = directory.resolve("Drama.S01E02.mkv").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(video.canonicalFile, resolveLocalMediaFile(video.absolutePath)?.canonicalFile)
        assertEquals(video.canonicalFile, resolveLocalMediaFile(video.toURI().toString())?.canonicalFile)
    }

    @Test
    fun `remote playback never becomes an automatic local target`() {
        assertNull(resolveLocalMediaFile("https://debrid.example/hash?token=secret"))
    }

    @Test
    fun `detected subtitle extension is appended to an untyped selection`() {
        assertEquals(
            File("Drama S01E02.srt"),
            subtitleDestinationWithExtension(File("Drama S01E02"), "srt"),
        )
    }

    @Test
    fun `matching subtitle extension is not duplicated`() {
        assertEquals(
            File("Drama S01E02.ASS"),
            subtitleDestinationWithExtension(File("Drama S01E02.ASS"), "ass"),
        )
    }

    @Test
    fun `subtitle chooser defaults to the downloads folder`() {
        val userHome = createTempDirectory("nuvio-subtitle-home").toFile()
        val downloads = userHome.resolve("Downloads").apply { mkdirs() }

        assertEquals(
            downloads.canonicalFile,
            preferredSubtitleSaveDirectory(
                rememberedPath = null,
                userHome = userHome,
            ).canonicalFile,
        )
    }

    @Test
    fun `subtitle chooser reuses the remembered folder`() {
        val userHome = createTempDirectory("nuvio-subtitle-home").toFile()
        userHome.resolve("Downloads").mkdirs()
        val remembered = userHome.resolve("Subtitle Packs").apply { mkdirs() }

        assertEquals(
            remembered.canonicalFile,
            preferredSubtitleSaveDirectory(
                rememberedPath = remembered.absolutePath,
                userHome = userHome,
            ).canonicalFile,
        )
    }

    @Test
    fun `deleted remembered folder falls back to downloads`() {
        val userHome = createTempDirectory("nuvio-subtitle-home").toFile()
        val downloads = userHome.resolve("Downloads").apply { mkdirs() }

        assertEquals(
            downloads.canonicalFile,
            preferredSubtitleSaveDirectory(
                rememberedPath = userHome.resolve("Deleted Folder").absolutePath,
                userHome = userHome,
            ).canonicalFile,
        )
    }
}
