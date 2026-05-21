package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.TorboxCloudFileDto
import com.nuvio.app.features.debrid.TorboxCloudItemDto
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorboxCloudLibraryProviderApiTest {
    @Test
    fun `maps torrent dto with status progress size and playable files`() {
        val item = TorboxCloudItemDto(
            id = JsonPrimitive(42),
            name = "Movie Pack",
            status = "completed",
            progress = 75.0,
            size = 1_024L,
            files = listOf(
                TorboxCloudFileDto(
                    id = JsonPrimitive(8),
                    name = "movie.mkv",
                    mimeType = "video/x-matroska",
                    size = 512L,
                ),
            ),
        ).toCloudLibraryItem(
            providerId = DebridProviders.Torbox.id,
            providerName = DebridProviders.Torbox.displayName,
            type = CloudLibraryItemType.Torrent,
        )

        assertNotNull(item)
        assertEquals("42", item.id)
        assertEquals(CloudLibraryItemType.Torrent, item.type)
        assertEquals("completed", item.status)
        assertEquals(0.75f, item.progressFraction)
        assertEquals(1_024L, item.sizeBytes)
        assertEquals(listOf("8"), item.files.map { it.id })
        assertTrue(item.files.single().playable)
    }

    @Test
    fun `mapping falls back to hash and file absolute path when friendly fields are missing`() {
        val item = TorboxCloudItemDto(
            hash = "abc123",
            files = listOf(
                TorboxCloudFileDto(
                    id = JsonPrimitive("file-1"),
                    absolutePath = "/downloads/show.mp4",
                    size = 256L,
                ),
            ),
        ).toCloudLibraryItem(
            providerId = "torbox",
            providerName = "Torbox",
            type = CloudLibraryItemType.Usenet,
        )

        assertNotNull(item)
        assertEquals("abc123", item.id)
        assertEquals("abc123", item.name)
        assertEquals("/downloads/show.mp4", item.files.single().name)
        assertTrue(item.files.single().playable)
    }

    @Test
    fun `mapping handles missing item ids and empty file lists`() {
        assertNull(
            TorboxCloudItemDto(name = "No ID").toCloudLibraryItem(
                providerId = "torbox",
                providerName = "Torbox",
                type = CloudLibraryItemType.WebDownload,
            ),
        )

        val item = TorboxCloudItemDto(
            id = JsonPrimitive(7),
            name = "Empty",
            files = emptyList(),
        ).toCloudLibraryItem(
            providerId = "torbox",
            providerName = "Torbox",
            type = CloudLibraryItemType.WebDownload,
        )

        assertNotNull(item)
        assertTrue(item.files.isEmpty())
        assertTrue(item.playableFiles.isEmpty())
    }

    @Test
    fun `mapping keeps non-playable files but excludes them from playable files`() {
        val item = TorboxCloudItemDto(
            id = JsonPrimitive(9),
            name = "Mixed",
            files = listOf(
                TorboxCloudFileDto(
                    id = JsonPrimitive(1),
                    name = "readme.txt",
                    mimeType = "text/plain",
                ),
                TorboxCloudFileDto(
                    name = "missing-id.mkv",
                    mimeType = "video/x-matroska",
                ),
            ),
        ).toCloudLibraryItem(
            providerId = "torbox",
            providerName = "Torbox",
            type = CloudLibraryItemType.Torrent,
        )

        assertNotNull(item)
        assertEquals(2, item.files.size)
        assertFalse(item.files[0].playable)
        assertFalse(item.files[1].playable)
        assertTrue(item.playableFiles.isEmpty())
    }

    @Test
    fun `request download parameter names match Torbox item type`() {
        assertEquals("torrent_id", torboxRequestIdParameterName(CloudLibraryItemType.Torrent))
        assertEquals("usenet_id", torboxRequestIdParameterName(CloudLibraryItemType.Usenet))
        assertEquals("web_id", torboxRequestIdParameterName(CloudLibraryItemType.WebDownload))
    }
}
