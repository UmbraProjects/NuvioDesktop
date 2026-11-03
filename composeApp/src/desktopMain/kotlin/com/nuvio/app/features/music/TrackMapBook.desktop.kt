package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.music.visualiser.TrackMap
import com.nuvio.app.features.music.visualiser.decodeTrackMap
import com.nuvio.app.features.music.visualiser.encodeTrackMap
import java.io.File
import java.security.MessageDigest

/**
 * fork:visualiser - finished track maps kept on disk, one small file per track (about 0.8 KB a second), under the same
 * identity as the loudness book (`yt:<video id>`, `addon:<track id>`), so a track is analysed once. At most 400 tracks;
 * the least recently played go first.
 */
internal object TrackMapBook {
    private const val CAPACITY = 400
    private val log = Logger.withTag("TrackMapBook")

    private val directory: File by lazy { DesktopStorage.rootDir.resolve("music-maps").toFile().apply { mkdirs() } }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".nvm")
    }

    fun get(key: String): TrackMap? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        decodeTrackMap(file.readBytes())
    }.onFailure { log.w { "could not read the map for $key: ${it.message}" } }.getOrNull()

    fun put(key: String, map: TrackMap) {
        runCatching {
            val file = fileFor(key)
            val part = File(file.path + ".part")
            part.writeBytes(encodeTrackMap(map))
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
            val all = directory.listFiles { f -> f.name.endsWith(".nvm") }.orEmpty()
            if (all.size > CAPACITY) {
                all.sortedBy { it.lastModified() }.take(all.size - CAPACITY).forEach { it.delete() }
            }
        }.onFailure { log.w { "could not keep the map for $key: ${it.message}" } }
    }
}
