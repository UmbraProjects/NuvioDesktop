package com.nuvio.app.features.downloads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadContentValidationTest {
    @Test
    fun acceptsRecognizedMp4Container() {
        withTemporaryBytes(
            byteArrayOf(
                0, 0, 0, 24,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            ),
        ) { file ->
            assertTrue(file.hasRecognizedVideoSignature())
        }
    }

    @Test
    fun rejectsWindowsExecutableEvenWhenNamedLikeVideo() {
        withTemporaryBytes(
            byteArrayOf(
                'M'.code.toByte(), 'Z'.code.toByte(), 0, 0, 0, 0, 0, 0,
                'P'.code.toByte(), 'E'.code.toByte(), 0, 0,
            ),
            suffix = ".mp4",
        ) { file ->
            assertFalse(file.hasRecognizedVideoSignature())
        }
    }

    private fun withTemporaryBytes(
        bytes: ByteArray,
        suffix: String = ".part",
        block: (File) -> Unit,
    ) {
        val file = kotlin.io.path.createTempFile("nuvio-download-validation-", suffix).toFile()
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
