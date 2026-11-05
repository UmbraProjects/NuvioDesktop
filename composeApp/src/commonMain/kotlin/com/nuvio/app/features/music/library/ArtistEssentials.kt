package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicTrack

/*
 * fork:artist-stage - the new artist page's Essentials: YouTube Music's own most-played list for the artist (the artist
 * page's "Songs" shelf), filled in from the albums the songs sit on (length, cover, album title), and the queue that the
 * page's Play Essentials button starts. Pure functions, so they are tested without Compose.
 */

/** How many songs the Essentials list shows and plays. */
internal const val ARTIST_ESSENTIAL_SONGS = 10

/** How many of those songs' albums are opened to fill them in; the rest show without a length. */
internal const val ARTIST_ESSENTIAL_ALBUMS = 6

/** One song of the Essentials list, in YouTube Music's order ([rank] 1 is the most played). */
internal data class EssentialRow(
    val rank: Int,
    val videoId: String,
    val title: String,
    val albumTitle: String?,
    val albumBrowseId: String?,
    val durationSeconds: Int,
    /** The song as its album lists it, when the album was opened and holds it. */
    val track: MusicTrack?,
    val coverUrl: String?,
)

/** The albums to open for [top]: those of its first [ARTIST_ESSENTIAL_SONGS] songs, each once, at most [limit]. */
internal fun essentialAlbumIds(top: List<MusicTopSong>, limit: Int = ARTIST_ESSENTIAL_ALBUMS): List<String> =
    top.take(ARTIST_ESSENTIAL_SONGS).mapNotNull { it.albumBrowseId }.distinct().take(limit)

/**
 * The Essentials rows for [top], each song once (by title, brackets left out), filled in from [albums] (by browse id) where
 * the song's album was opened: matched there by video id, else by title.
 */
internal fun essentialRows(top: List<MusicTopSong>, albums: Map<String, MusicAlbum>, limit: Int = ARTIST_ESSENTIAL_SONGS): List<EssentialRow> {
    val seen = HashSet<String>()
    val rows = ArrayList<EssentialRow>()
    for (song in top) {
        if (rows.size >= limit) break
        val key = songKey(song.title)
        if (key.isNotEmpty() && !seen.add(key)) continue
        val album = song.albumBrowseId?.let { albums[it] }
        val track = album?.tracks?.firstOrNull { it.videoId == song.videoId }
            ?: album?.tracks?.firstOrNull { key.isNotEmpty() && songKey(it.title) == key }
        rows += EssentialRow(
            rank = rows.size + 1,
            videoId = track?.videoId ?: song.videoId,
            title = song.title,
            albumTitle = album?.title,
            albumBrowseId = album?.browseId ?: song.albumBrowseId,
            durationSeconds = track?.durationSeconds ?: 0,
            track = track,
            coverUrl = album?.thumbnailUrl,
        )
    }
    return rows
}

/** The browse id the Essentials queue carries, so it is never taken for a real album. */
internal fun essentialsAlbumId(artistId: String): String = "essentials:$artistId"

/**
 * The queue Play Essentials starts: [rows] in order, as one album the way a playlist is, each song keeping its own cover
 * and album so the player shows the right ones. Songs its album greys out are left out. Null when nothing can be played.
 */
internal fun essentialsMix(artistId: String, artistName: String, artistThumb: String?, rows: List<EssentialRow>): MusicAlbum? {
    val self = listOf(MusicArtistRef(artistName, artistId))
    val tracks = rows.filter { it.track?.isAvailable != false }.mapIndexed { i, row ->
        MusicTrack(
            videoId = row.videoId,
            title = row.track?.title ?: row.title,
            artists = row.track?.artists?.takeIf { it.isNotEmpty() } ?: self,
            durationSeconds = row.durationSeconds,
            trackNumber = i + 1,
            isAvailable = true,
            coverUrl = row.coverUrl,
            albumTitle = row.albumTitle,
            albumBrowseId = row.albumBrowseId,
        )
    }
    if (tracks.isEmpty()) return null
    return MusicAlbum(
        browseId = essentialsAlbumId(artistId),
        title = "$artistName: Essentials",
        artists = self,
        year = null,
        kind = "Mix",
        thumbnailUrl = artistThumb ?: rows.firstNotNullOfOrNull { it.coverUrl },
        tracks = tracks,
        audioPlaylistId = null,
    )
}
