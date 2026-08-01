package com.nuvio.app.features.player

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the parts of external-player subtitle caching that decide whether the file a player is
 * handed is actually parseable: gzip handling, extension selection, and file-name sanitising.
 */
class SubtitleCacheNamingTest {

    @Test
    fun decompressesGzippedSubtitleBodies() {
        val plain = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
        assertEquals(plain, gzip(plain).decompressIfGzipped().decodeToString())
    }

    @Test
    fun leavesPlainBodiesUntouched() {
        val plain = "WEBVTT\n\n00:00.000 --> 00:02.000\nHello\n".encodeToByteArray()
        assertTrue(plain.contentEquals(plain.decompressIfGzipped()))
    }

    @Test
    fun prefersKnownExtensionFromUrl() {
        val body = "irrelevant".encodeToByteArray()
        assertEquals("vtt", body.subtitleExtension("https://addon.example/sub/1234.vtt"))
        assertEquals("ass", body.subtitleExtension("https://addon.example/sub/1234.ass?token=abc"))
    }

    @Test
    fun sniffsFormatWhenUrlHasNoUsableExtension() {
        val url = "https://addon.example/subtitles/download?id=99"
        assertEquals("vtt", "WEBVTT\n\n1\n".encodeToByteArray().subtitleExtension(url))
        assertEquals("ass", "[Script Info]\nTitle: x\n".encodeToByteArray().subtitleExtension(url))
        assertEquals("srt", "1\n00:00:01,000 --> 00:00:02,000\n".encodeToByteArray().subtitleExtension(url))
    }

    @Test
    fun sniffingToleratesLeadingBomAndWhitespace() {
        val url = "https://addon.example/subtitles/download?id=99"
        assertEquals("vtt", "﻿  \r\nWEBVTT\n".encodeToByteArray().subtitleExtension(url))
    }

    @Test
    fun sanitisesLabelsIntoSafeFileNames() {
        // Path separators and reserved characters must not survive into the file name.
        assertEquals("English OpenSubtitles v2", "English/OpenSubtitles: v2".sanitizedForFileName())
        assertEquals("sub (1) [hi]", "sub (1) [hi]".sanitizedForFileName())
        assertTrue("x".repeat(200).sanitizedForFileName().length <= 48)
    }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(value.encodeToByteArray()) }
    }.toByteArray()
}
