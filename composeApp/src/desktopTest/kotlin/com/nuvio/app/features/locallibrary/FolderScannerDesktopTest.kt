package com.nuvio.app.features.locallibrary

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderScannerDesktopTest {

    @Test
    fun `show folder S17 label does not turn direct absolute anime files into season 17`() {
        val root = createTempDirectory("nuvio-anime-scan").toFile()
        try {
            val show = root.resolve("Pokemon the Series XYZ S17 (1080p)")
            check(show.mkdirs())
            check(show.resolve("Pokemon XYZ - 12.mkv").createNewFile())

            val result = kotlinx.coroutines.runBlocking {
                FolderScanner.scan(
                    LocalFolder(
                        id = "anime",
                        path = root.absolutePath,
                        type = LocalFolderType.SERIES,
                        isAnime = true,
                    ),
                )
            }

            val file = result.items.single().files.single()
            assertNull(file.season)
            assertEquals(12, file.episode)
        } finally {
            Files.walk(root.toPath())
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
