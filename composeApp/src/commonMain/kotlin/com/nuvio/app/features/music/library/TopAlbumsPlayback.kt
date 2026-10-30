package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicPlayback
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Opens [releases] (an artist's top albums) on YouTube Music, all at once, and plays them one after
 * the other. [shuffled] mixes the order of the albums and each album's own track order. Albums that
 * will not open are left out. False when none opened, so the caller can say so.
 */
internal suspend fun playTopAlbums(catalog: MusicCatalog, releases: List<LibraryRelease>, shuffled: Boolean): Boolean {
    val ids = releases.mapNotNull { it.ytBrowseId }
    if (ids.isEmpty()) return false
    val albums = coroutineScope {
        ids.map { id -> async { runCatching { catalog.album(id) }.getOrNull() } }.awaitAll()
    }.filterNotNull()
    if (albums.isEmpty()) return false
    MusicPlayback.player.playAlbums(if (shuffled) albums.shuffled() else albums, shuffled = shuffled)
    return true
}
