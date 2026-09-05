package com.nuvio.app.features.locallibrary

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderScannerDesktopTest {

    @Test
    fun `show folder S17 label does not turn direct absolute anime files into season 17`() {
        val root = createTempDirectory("nuvio-anime-scan").toFile()
        try {
            val show = root.resolve("Pokemon the Series XYZ S17 (1080p)")
            check(show.mkdirs())
            check(show.resolve("Pokemon XYZ - 12.mkv").createNewFile())

            val file = scan(root, isAnime = true).items.single().files.single()
            assertNull(file.season)
            assertEquals(12, file.episode)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same title under different years stays two shows`() {
        // "Kaguya-sama: Love is War?" cannot be spelled as a Windows folder, so its only distinction
        // from its predecessor is the year. Merging them put both runs on one item and one Kitsu id.
        val root = createTempDirectory("nuvio-year-split").toFile()
        try {
            val season1 = root.resolve("Kaguya Sama Love Is War (2019)")
            check(season1.mkdirs())
            check(season1.resolve("Kaguya Sama - Love Is War - 01.mkv").createNewFile())
            val season2 = root.resolve("Kaguya Sama Love Is War (2020)")
            check(season2.mkdirs())
            check(season2.resolve("Kaguya Sama - Love Is War Season 2 - 01.mkv").createNewFile())

            val items = scan(root, isAnime = true).items

            assertEquals(2, items.size)
            // The earliest year keeps the bare key so an existing show is never re-keyed (and its
            // manual match lost) by adding a sequel folder next to it.
            assertEquals(
                listOf("anime:kaguya-sama-love-is-war", "anime:kaguya-sama-love-is-war-2020"),
                items.map { it.key }.sorted(),
            )
            assertEquals(listOf(2019, 2020), items.mapNotNull { it.year }.sorted())
            assertTrue(items.all { it.files.size == 1 })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a year-less folder still merges with its dated twin`() {
        // The year is only promoted into the key when two folders actually disagree about it.
        val root = createTempDirectory("nuvio-year-merge").toFile()
        try {
            val undated = root.resolve("Breaking Bad")
            check(undated.mkdirs())
            check(undated.resolve("Breaking Bad - S01E01.mkv").createNewFile())
            val dated = root.resolve("Breaking Bad (2008)")
            check(dated.mkdirs())
            check(dated.resolve("Breaking Bad - S02E01.mkv").createNewFile())

            val items = scan(root, isAnime = false).items

            val show = items.single()
            assertEquals("shows:breaking-bad", show.key)
            assertEquals(2, show.files.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun scan(root: java.io.File, isAnime: Boolean) = kotlinx.coroutines.runBlocking {
        FolderScanner.scan(
            LocalFolder(
                id = if (isAnime) "anime" else "shows",
                path = root.absolutePath,
                type = LocalFolderType.SERIES,
                isAnime = isAnime,
            ),
        )
    }
}
