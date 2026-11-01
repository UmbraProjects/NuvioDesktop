package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.randomPlayableIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * The shelf's middle button: every playable track by the artist in one shuffled queue (not their albums one after the
 * other, as [playTopAlbums] does). The tracks of the artist's playable releases are gathered into one synthetic album,
 * as a playlist is, and the player shuffles that.
 */

/** Most albums one mix opens. A huge catalogue is sampled (a different part each press), not fetched whole. */
internal const val ARTIST_MIX_ALBUM_LIMIT = 40

/** Albums fetched at once: enough to be quick, few enough not to trip YouTube Music's rate limits. */
internal const val ARTIST_MIX_PARALLEL = 5

/** The browse id the mix carries, so screens can tell it from a real album. */
internal fun artistMixAlbumId(artistId: String): String = "mix:$artistId"

/** The YouTube Music ids of [releases] to open for a mix: each once, and a random [limit] of them when there are more. */
internal fun pickMixAlbumIds(
    releases: List<LibraryRelease>,
    limit: Int = ARTIST_MIX_ALBUM_LIMIT,
    random: Random = Random.Default,
): List<String> {
    val ids = releases.mapNotNull { it.ytBrowseId }.distinct()
    return if (ids.size <= limit) ids else ids.shuffled(random).take(limit)
}

private fun mixTitleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

/**
 * One album holding every available track of [albums], each keeping its own cover and album so the player shows the
 * right ones. A song that sits on several albums (a compilation, a reissue) is kept once: by video id, then by title.
 * Null when no track can be played.
 */
internal fun buildArtistMix(artist: LibraryArtist, albums: List<MusicAlbum>): MusicAlbum? {
    val seenIds = HashSet<String>()
    val seenTitles = HashSet<String>()
    val tracks = ArrayList<MusicTrack>()
    for (album in albums) {
        for (track in album.tracks) {
            if (!track.isAvailable) continue
            if (!seenIds.add(track.videoId)) continue
            val key = mixTitleKey(track.title)
            if (key.isNotEmpty() && !seenTitles.add(key)) continue
            tracks += track.copy(
                trackNumber = tracks.size + 1,
                coverUrl = track.coverUrl ?: album.thumbnailUrl,
                albumTitle = track.albumTitle ?: album.title,
                albumBrowseId = track.albumBrowseId ?: album.browseId,
            )
        }
    }
    if (tracks.isEmpty()) return null
    return MusicAlbum(
        browseId = artistMixAlbumId(artist.id),
        title = "${artist.name}, shuffled",
        artists = listOf(MusicArtistRef(artist.name, artist.id)),
        year = null,
        kind = "Mix",
        thumbnailUrl = artist.art.thumb ?: albums.firstNotNullOfOrNull { it.thumbnailUrl },
        tracks = tracks,
        audioPlaylistId = null,
    )
}

/**
 * Opens the albums of [releases] and shuffles every playable track of them, starting on a random one. False when
 * nothing opened, so the caller can say so.
 */
internal suspend fun playArtistMix(catalog: MusicCatalog, artist: LibraryArtist, releases: List<LibraryRelease>): Boolean {
    val ids = pickMixAlbumIds(releases)
    if (ids.isEmpty()) return false
    val gate = Semaphore(ARTIST_MIX_PARALLEL)
    val albums = coroutineScope {
        ids.map { id -> async { gate.withPermit { runCatching { catalog.album(id) }.getOrNull() } } }.awaitAll()
    }.filterNotNull()
    val mix = buildArtistMix(artist, albums) ?: return false
    MusicPlayback.player.play(mix, trackIndex = randomPlayableIndex(mix), shuffled = true)
    return true
}
