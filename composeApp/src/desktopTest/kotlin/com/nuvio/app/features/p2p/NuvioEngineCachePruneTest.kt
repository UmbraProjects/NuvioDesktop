package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The startup prune in NuvioEngineP2pBackend relies on the engine reclaiming torrent folders it
 * did not add this session (left over from an earlier run), least recently used first. Runs
 * against the real DLL; skipped when it has not been built.
 */
class NuvioEngineCachePruneTest {
    @Test
    fun reclaimEvictsLeftoverTorrentFoldersOldestFirst() {
        if (!NuvioEngineLibrary.isAvailable) {
            println("NuvioEngineCachePruneTest skipped: ${NuvioEngineLibrary.LIBRARY_FILE_NAME} not built")
            return
        }
        val root = Files.createTempDirectory("nuvio-engine-prune").toFile()
        try {
            val cacheDirectory = root.resolve(NUVIO_ENGINE_CACHE_DIRECTORY).apply { mkdirs() }
            val payloadRoot = cacheDirectory.resolve("payload").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val oldest = leftoverTorrent(payloadRoot, "a".repeat(40), lastUsed = now - 3_600_000L)
            val newest = leftoverTorrent(payloadRoot, "b".repeat(40), lastUsed = now - 60_000L)
            val capacity = 4L * 1024L * 1024L

            val engine = NuvioEngine.create(
                buildNuvioEngineConfig(
                    stateDirectory = root.resolve("state").apply { mkdirs() },
                    cacheDirectory = cacheDirectory,
                    uploadEnabled = false,
                    torrentProfile = P2pTorrentProfile.BALANCED,
                    diskCacheCapacityBytes = capacity,
                ),
            )
            try {
                runBlocking { engine.reclaimDiskCache(capacity) }
                assertFalse(oldest.exists(), "least recently used leftover should be evicted")
                assertTrue(newest.exists(), "the leftover that fits the cap should stay")
            } finally {
                engine.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // 3 MiB of real (non-sparse) data, so two of them exceed the 4 MiB cap.
    private fun leftoverTorrent(payloadRoot: File, infoHash: String, lastUsed: Long): File =
        payloadRoot.resolve(infoHash).apply {
            mkdirs()
            resolve("video.mkv").writeBytes(ByteArray(3 * 1024 * 1024) { 1 })
            setLastModified(lastUsed)
        }
}
