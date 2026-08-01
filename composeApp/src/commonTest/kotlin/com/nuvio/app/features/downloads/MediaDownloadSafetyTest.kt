package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaDownloadSafetyTest {
    @Test
    fun detectsExecutableExtensionsAcrossDownloadReferences() {
        assertTrue("movie.EXE?token=abc".hasExecutableDownloadExtension())
        assertTrue("https://example.test/get?filename=movie.mkv.exe".hasExecutableDownloadExtension())
        assertTrue("attachment; filename=\"movie.scr\"".hasExecutableDownloadExtension())
        assertTrue("https://example.test/movie%252eMsIx".hasExecutableDownloadExtension())
        assertFalse("https://example.test/movie.mkv?token=abc".hasExecutableDownloadExtension())
    }

    @Test
    fun browserHandoffRequiresAnUnambiguousVideoReference() {
        assertTrue("https://example.test/movie.mp4?token=abc".isSafeVideoDownloadReference())
        assertFalse("https://example.test/download?id=42".isSafeVideoDownloadReference())
        assertFalse("https://example.test/movie.mkv.exe".isSafeVideoDownloadReference())
    }

    @Test
    fun detectsExecutableMimeTypes() {
        assertTrue("application/x-msdownload; charset=binary".isExecutableDownloadContentType())
        assertTrue("application/java-archive".isExecutableDownloadContentType())
        assertFalse("video/mp4".isExecutableDownloadContentType())
        assertFalse("application/octet-stream".isExecutableDownloadContentType())
    }

    @Test
    fun detectsDisguisedExecutableSignatures() {
        assertTrue(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0).hasExecutableFileSignature())
        assertTrue(byteArrayOf(0x7f, 0x45, 0x4c, 0x46).hasExecutableFileSignature())
        assertTrue("#!/bin/sh\necho bad".encodeToByteArray().hasExecutableFileSignature())
        assertFalse("WEBVTT\n\n00:00.000 --> 00:01.000".encodeToByteArray().hasExecutableFileSignature())
    }
}
