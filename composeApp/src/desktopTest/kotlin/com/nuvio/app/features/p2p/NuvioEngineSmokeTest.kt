package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Loads the real nuvio_engine.dll through the JNI bridge and spins an engine up and down. This is
 * the check that the DLL exports what the Kotlin wrapper binds to and that libtorrent initialises
 * on this host. Skipped (not failed) when the DLL has not been built, so a box without the MSYS2
 * toolchain still gets a green desktopTest.
 */
class NuvioEngineSmokeTest {
    @Test
    fun engineCreatesAndReportsVersions() {
        if (!NuvioEngineLibrary.isAvailable) {
            println("NuvioEngineSmokeTest skipped: ${NuvioEngineLibrary.LIBRARY_FILE_NAME} not built")
            return
        }
        val root = Files.createTempDirectory("nuvio-engine-smoke").toFile()
        try {
            val engine = NuvioEngine.create(
                buildNuvioEngineConfig(
                    stateDirectory = root.resolve("state").apply { mkdirs() },
                    cacheDirectory = root.resolve("payload").apply { mkdirs() },
                    uploadEnabled = false,
                    torrentProfile = P2pTorrentProfile.BALANCED,
                    diskCacheCapacityBytes = 0L,
                ),
            )
            try {
                assertTrue(NuvioEngine.version.isNotBlank(), "engine version")
                // The backend reports libtorrent's own version string, e.g. "2.0.12.0".
                assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(NuvioEngine.protocolBackendVersion), NuvioEngine.protocolBackendVersion)
                // A request against an unknown torrent must round-trip through JNI as a status error, not a crash.
                val error = runCatching { runBlocking { engine.files("0".repeat(40)) } }.exceptionOrNull()
                assertTrue(error != null, "unknown torrent should fail")
                assertEquals(0, engine.stats.value.activeTorrents)
            } finally {
                engine.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * RAM-only mode (engine patch 0005): a torrent can be added and a stream prepared with
     * directories that do not exist, and none of them, nor the engine's relative "ram" save path,
     * appear on disk afterwards. No peer is involved, so this covers setup, not piece storage.
     */
    @Test
    fun ramOnlyModeWritesNothingToDisk() {
        if (!NuvioEngineLibrary.isAvailable) {
            println("NuvioEngineSmokeTest skipped: ${NuvioEngineLibrary.LIBRARY_FILE_NAME} not built")
            return
        }
        val root = Files.createTempDirectory("nuvio-engine-ram-only").toFile().also { it.delete() }
        val stateDirectory = root.resolve("state")
        val cacheDirectory = root.resolve("torrent-cache")
        val engine = NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = false,
                torrentProfile = P2pTorrentProfile.BALANCED,
                diskCacheCapacityBytes = 2L * 1024L * 1024L * 1024L,
                ramOnlyMode = true,
            ),
        )
        try {
            runBlocking {
                val torrentId = engine.addTorrent(singleFileTorrent(name = "ram-test.bin", pieces = 2))
                val stream = engine.prepareStream(torrentId = torrentId, fileIndex = 0)
                assertEquals(2L * 16_384L, stream.fileSize)
                assertEquals(0L, engine.stats.value.diskCacheCapacityBytes)
                engine.stopStream(stream.id)
                engine.removeTorrent(torrentId)
            }
        } finally {
            engine.close()
        }
        assertFalse(root.exists(), "RAM-only mode created ${root.path}")
        assertFalse(File("ram").exists(), "RAM-only mode created a relative 'ram' directory")
    }

    private fun singleFileTorrent(name: String, pieces: Int): ByteArray {
        val pieceLength = 16_384
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hashes = ByteArray(0).let { acc ->
            (0 until pieces).fold(acc) { all, index ->
                all + sha1.digest(ByteArray(pieceLength) { (it * 31 + index).toByte() })
            }
        }
        val out = ByteArrayOutputStream()
        out.write("d4:infod6:lengthi${pieceLength * pieces}e4:name${name.length}:$name".encodeToByteArray())
        out.write("12:piece lengthi${pieceLength}e6:pieces${hashes.size}:".encodeToByteArray())
        out.write(hashes)
        out.write("ee".encodeToByteArray())
        return out.toByteArray()
    }
}
