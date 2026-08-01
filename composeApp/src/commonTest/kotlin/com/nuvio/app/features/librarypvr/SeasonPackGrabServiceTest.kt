package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.streams.StreamAddonData
import com.nuvio.app.features.streams.StreamAddonTorrentData
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeasonPackGrabServiceTest {

    @Test
    fun `completed download only blocks an episode while its file still exists`() {
        assertFalse(downloadBlocksSeasonEpisode(DownloadStatus.Completed, completedFileExists = false))
        assertTrue(downloadBlocksSeasonEpisode(DownloadStatus.Completed, completedFileExists = true))
        assertTrue(downloadBlocksSeasonEpisode(DownloadStatus.Downloading, completedFileExists = false))
        assertTrue(downloadBlocksSeasonEpisode(DownloadStatus.Paused, completedFileExists = false))
        assertFalse(downloadBlocksSeasonEpisode(DownloadStatus.Failed, completedFileExists = false))
    }

    @Test
    fun `matches AIOStreams Usenet rows for the same NZB across episodes`() {
        val selected = stream(
            data = StreamAddonData(
                type = "usenet",
                serviceId = "aiostreams",
                nzbUrl = "https://aio.example/nzb/season-pack",
                releaseKey = "wd1:u:season-pack",
            ),
        )
        val nextEpisode = stream(
            data = StreamAddonData(
                type = "usenet",
                serviceId = "aiostreams",
                nzbUrl = "https://aio.example/nzb/season-pack",
                releaseKey = "wd1:u:season-pack",
            ),
        )

        val identity = assertNotNull(selected.seasonReleaseIdentity())
        assertTrue(nextEpisode.matchesSeasonRelease(identity))
    }

    @Test
    fun `rejects a different Usenet release`() {
        val selected = stream(
            data = StreamAddonData(type = "usenet", nzbUrl = "https://aio.example/nzb/pack-a"),
        )
        val other = stream(
            data = StreamAddonData(type = "usenet", nzbUrl = "https://aio.example/nzb/pack-b"),
        )

        assertFalse(other.matchesSeasonRelease(assertNotNull(selected.seasonReleaseIdentity())))
    }

    @Test
    fun `matches AIOStreams debrid rows by nested torrent hash`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val selected = stream(
            data = StreamAddonData(
                type = "debrid",
                serviceId = "torbox",
                torrent = StreamAddonTorrentData(infoHash = hash, fileIdx = 0),
            ),
        )
        val nextEpisode = stream(
            data = StreamAddonData(
                type = "debrid",
                serviceId = "torbox",
                torrent = StreamAddonTorrentData(infoHash = hash, fileIdx = 1),
            ),
        )

        assertTrue(nextEpisode.matchesSeasonRelease(assertNotNull(selected.seasonReleaseIdentity())))
    }

    @Test
    fun `does not cross AIOStreams services even when source identity is shared`() {
        val selected = stream(
            data = StreamAddonData(
                type = "usenet",
                serviceId = "aiostreams",
                releaseKey = "wd1:u:shared",
            ),
        )
        val otherService = stream(
            data = StreamAddonData(
                type = "usenet",
                serviceId = "torbox",
                releaseKey = "wd1:u:shared",
            ),
        )

        assertFalse(otherService.matchesSeasonRelease(assertNotNull(selected.seasonReleaseIdentity())))
    }

    private fun stream(data: StreamAddonData): StreamItem = StreamItem(
        url = "https://aio.example/playback/file",
        addonName = "AIOStreams",
        addonId = "addon:aio",
        streamData = data,
    )
}
