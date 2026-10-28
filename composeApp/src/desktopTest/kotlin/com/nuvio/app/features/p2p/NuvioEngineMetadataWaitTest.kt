package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioEngineMetadataWaitTest {
    @Test
    fun `keeps waiting while the swarm is still moving`() {
        // Stalled for a while but not yet past the settle window.
        assertNull(metadataWaitVerdict(waitedMs = 19_000, stalledForMs = 19_000, knownPeers = 0))
        // Past the settle window but something moved recently.
        assertNull(metadataWaitVerdict(waitedMs = 45_000, stalledForMs = 3_000, knownPeers = 40))
    }

    @Test
    fun `gives up early on a swarm with nobody in it`() {
        assertEquals("No peers found for this torrent", metadataWaitVerdict(20_000, 15_000, 0))
    }

    @Test
    fun `gives up early when known peers never answer`() {
        val verdict = metadataWaitVerdict(waitedMs = 35_000, stalledForMs = 15_000, knownPeers = 7)
        assertTrue(verdict!!.contains("7 found"), verdict)
    }

    @Test
    fun `hard cap wins regardless of movement`() {
        assertEquals(
            "Torrent metadata did not arrive within 60s",
            metadataWaitVerdict(waitedMs = 60_000, stalledForMs = 0, knownPeers = 300),
        )
    }
}

class NuvioEngineWindowTest {
    @Test
    fun `window follows the buffer preset and never drops below four 16 MiB pieces`() {
        val windows = com.nuvio.app.features.player.DesktopBufferPreset.entries.map { nuvioEngineWindowBytes(it) }
        assertTrue(windows.zipWithNext().all { (smaller, larger) -> smaller < larger }, "monotonic: $windows")
        assertTrue(windows.first() >= 4L * 16 * 1024 * 1024)
        assertEquals(256L * 1024 * 1024, nuvioEngineWindowBytes(com.nuvio.app.features.player.DesktopBufferPreset.Balanced))
    }

    @Test
    fun `RAM-only mode keeps the window and drops the disk cache`() {
        val window = nuvioEngineWindowBytes(com.nuvio.app.features.player.DesktopBufferPreset.Resilient)
        val config = buildNuvioEngineConfig(
            stateDirectory = java.io.File("state"),
            cacheDirectory = java.io.File("cache"),
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.BALANCED,
            diskCacheCapacityBytes = P2pCacheSize.GB_5.bytes,
            windowBytes = window,
            ramOnlyMode = true,
        )
        assertTrue(config.ramOnlyMode)
        assertEquals(window, config.memoryCacheCapacityBytes)
        assertEquals(0L, config.diskCacheCapacityBytes)
        val disk = buildNuvioEngineConfig(
            stateDirectory = java.io.File("state"),
            cacheDirectory = java.io.File("cache"),
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.BALANCED,
            diskCacheCapacityBytes = P2pCacheSize.GB_5.bytes,
            windowBytes = window,
        )
        assertEquals(false, disk.ramOnlyMode)
        assertEquals(P2pCacheSize.GB_5.bytes, disk.diskCacheCapacityBytes)
    }
}
