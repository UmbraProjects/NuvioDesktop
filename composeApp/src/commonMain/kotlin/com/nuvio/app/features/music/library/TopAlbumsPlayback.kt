package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.home.songKey
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

/**
 * Shuffle all: the artist's playable [releases] are opened in a random order, a few at a time, until the mix holds at least
 * [SHUFFLE_ALL_MIN_SONGS] different songs from at least [SHUFFLE_ALL_MIN_RELEASES] releases (or nothing is left to open,
 * [SHUFFLE_ALL_MAX_RELEASES] at most). A song already in the mix (a single that is also on an album) is left out. The
 * songs then play as one shuffled queue, each with its own cover and album. False when nothing opened.
 */
internal suspend fun shuffleAllReleases(
    artistName: String,
    artistThumb: String?,
    releases: List<LibraryRelease>,
    open: suspend (LibraryRelease) -> MusicAlbum?,
): Boolean {
    val order = releases.shuffled().take(SHUFFLE_ALL_MAX_RELEASES)
    val seen = HashSet<String>()
    val songs = ArrayList<com.nuvio.app.features.music.MusicTrack>()
    var opened = 0
    for (batch in order.chunked(SHUFFLE_ALL_BATCH)) {
        val albums = coroutineScope { batch.map { release -> async { runCatching { open(release) }.getOrNull() } }.awaitAll() }.filterNotNull()
        for (album in albums) {
            val fresh = album.tracks.filter { it.isAvailable && seen.add(songKey(it.title)) }
            if (fresh.isEmpty()) continue
            opened++
            songs += fresh.map { track ->
                track.copy(
                    coverUrl = track.coverUrl ?: album.thumbnailUrl,
                    albumTitle = track.albumTitle ?: album.title,
                    albumBrowseId = track.albumBrowseId ?: album.browseId,
                )
            }
        }
        if (songs.size >= SHUFFLE_ALL_MIN_SONGS && opened >= SHUFFLE_ALL_MIN_RELEASES) break
    }
    if (songs.isEmpty()) return false
    val mix = MusicAlbum(
        browseId = "shuffle-all:${artistName.lowercase()}",
        title = "$artistName: Shuffle all",
        artists = songs.first().artists,
        year = null,
        kind = "Mix",
        thumbnailUrl = artistThumb ?: songs.first().coverUrl,
        tracks = songs.mapIndexed { i, track -> track.copy(trackNumber = i + 1) },
        audioPlaylistId = null,
    )
    MusicPlayback.player.play(mix, trackIndex = songs.indices.random(), shuffled = true)
    return true
}

/** At least this many different songs in a Shuffle all mix, from at least this many releases, when the artist has them. */
private const val SHUFFLE_ALL_MIN_SONGS = 25
private const val SHUFFLE_ALL_MIN_RELEASES = 3
/** Releases opened at once, and at most in all (a single is one request for one song). */
private const val SHUFFLE_ALL_BATCH = 4
private const val SHUFFLE_ALL_MAX_RELEASES = 16
