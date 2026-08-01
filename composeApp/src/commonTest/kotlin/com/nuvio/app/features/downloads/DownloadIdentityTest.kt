package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DownloadIdentityTest {

    @Test
    fun `six seasonless anime episodes keep six distinct queue identities`() {
        val keys = (1..6).map { episode ->
            downloadLogicalContentKey(
                parentMetaId = "kitsu:5605",
                seasonNumber = null,
                episodeNumber = episode,
            )
        }

        assertEquals(6, keys.distinct().size)
        assertTrue(keys.none { it.endsWith("|movie") })
    }

    @Test
    fun `seasonless episodes do not collide with movies or regular seasons`() {
        val entryEpisode = downloadLogicalContentKey("kitsu:5605", null, 1)
        val regularEpisode = downloadLogicalContentKey("kitsu:5605", 1, 1)
        val movie = downloadLogicalContentKey("kitsu:5605", null, null)

        assertNotEquals(entryEpisode, regularEpisode)
        assertNotEquals(entryEpisode, movie)
        assertNotEquals(regularEpisode, movie)
    }

    @Test
    fun `persisted seasonless row is classified as an episode`() {
        val item = DownloadItem(
            id = "download-1",
            contentType = "series",
            parentMetaId = "kitsu:5605",
            parentMetaType = "series",
            videoId = "kitsu:5605:1",
            title = "Pokemon Best Wishes",
            seasonNumber = null,
            episodeNumber = 1,
            streamTitle = "Episode 1",
            providerName = "season-pack",
            sourceUrl = "https://example.invalid/episode-1.mkv",
            fileName = "Pokemon Best Wishes/Pokemon Best Wishes - 01.mkv",
            status = DownloadStatus.Paused,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        assertTrue(item.isEpisode)
        assertEquals(
            downloadLogicalContentKey("kitsu:5605", null, 1),
            item.logicalContentKey,
        )
    }
}
