package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Files dropped on the player: only real subtitle files are loaded. */
class DroppedSubtitleFileTest {

    @Test
    fun `subtitle files load, videos, folders and missing files do not`() {
        val dir = Files.createTempDirectory("nuvio-drop").toFile()
        try {
            val srt = dir.resolve("Show.S01E01.en.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n") }
            val ass = dir.resolve("Show.S01E01.ASS").apply { writeText("[Script Info]\n") }
            val video = dir.resolve("Show.S01E01.mkv").apply { writeText("") }
            val folder = dir.resolve("subs.srt").apply { mkdirs() }

            assertTrue(droppedSubtitleFile(srt.absolutePath))
            assertTrue(droppedSubtitleFile(ass.absolutePath), "extension case does not matter")
            assertFalse(droppedSubtitleFile(video.absolutePath))
            assertFalse(droppedSubtitleFile(folder.absolutePath), "a folder named like a subtitle is not one")
            assertFalse(droppedSubtitleFile(dir.resolve("gone.srt").absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
