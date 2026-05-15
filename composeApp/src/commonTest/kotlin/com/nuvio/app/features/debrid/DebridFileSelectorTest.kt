package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve
import kotlin.test.Test
import kotlin.test.assertEquals

class DebridFileSelectorTest {
    @Test
    fun `Torbox selector prefers exact file id`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "small.mkv", size = 1),
            TorboxTorrentFileDto(id = 8, name = "target.mkv", size = 2),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(fileIdx = 8),
            season = null,
            episode = null,
        )

        assertEquals(8, selected?.id)
    }

    @Test
    fun `Torbox selector falls back to largest playable video`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "sample.txt", size = 999),
            TorboxTorrentFileDto(id = 2, name = "episode.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "episode-1080p.mp4", size = 500),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(),
            season = null,
            episode = null,
        )

        assertEquals(3, selected?.id)
    }

    @Test
    fun `Real-Debrid selector matches episode pattern before largest file`() {
        val files = listOf(
            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 1_000),
            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 2_000),
        )

        val selected = RealDebridFileSelector().selectFile(
            files = files,
            resolve = resolve(season = 1, episode = 1),
            season = null,
            episode = null,
        )

        assertEquals(1, selected?.id)
    }

    private fun resolve(
        fileIdx: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ): StreamClientResolve =
        StreamClientResolve(
            type = "debrid",
            service = DebridProviders.TORBOX_ID,
            isCached = true,
            infoHash = "hash",
            fileIdx = fileIdx,
            season = season,
            episode = episode,
        )
}

