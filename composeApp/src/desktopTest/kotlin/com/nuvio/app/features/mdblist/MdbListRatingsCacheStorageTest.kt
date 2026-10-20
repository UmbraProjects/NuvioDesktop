package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MdbListRatingsCacheStorageTest {
    private fun readRatings(): String? = MdbListRatingsCacheStorage.readRatings { String(it.readBytes()) }
    private fun readCast(): String? = MdbListRatingsCacheStorage.readCast { String(it.readBytes()) }

    @Test
    fun `both caches move out of the old shared store before it is deleted`() {
        MdbListRatingsCacheStorage.clearRatings()
        Files.deleteIfExists(DesktopStorage.rootDir.resolve("nuvio_hero_cast.json"))
        DesktopStorage.store("nuvio_mdblist_ratings_cache").apply {
            putString("ratings_cache", """{"v1:tt1":{"ratings":[],"expiresAtMs":1}}""")
            putString("cast_cache", """{"v5:tt1":{"cast":[],"expiresAtMs":2}}""")
            flush()
        }

        // Reading the cast first must not lose the ratings (the old store held both).
        assertEquals("""{"v5:tt1":{"cast":[],"expiresAtMs":2}}""", readCast())
        assertEquals("""{"v1:tt1":{"ratings":[],"expiresAtMs":1}}""", readRatings())
        assertFalse(Files.exists(DesktopStorage.storeFile("nuvio_mdblist_ratings_cache")))
        assertEquals(
            """{"v1:tt1":{"ratings":[],"expiresAtMs":1}}""",
            Files.readString(DesktopStorage.rootDir.resolve("nuvio_mdblist_ratings.json")),
        )
    }

    @Test
    fun `writes stream to their own files and clearing ratings leaves the cast`() {
        MdbListRatingsCacheStorage.writeRatings { it.write("{\"r\":1}".toByteArray()) }
        MdbListRatingsCacheStorage.writeCast { it.write("{\"c\":1}".toByteArray()) }
        assertEquals("{\"r\":1}", readRatings())
        assertEquals("{\"c\":1}", readCast())

        MdbListRatingsCacheStorage.clearRatings()
        assertNull(readRatings())
        assertEquals("{\"c\":1}", readCast())
    }
}
