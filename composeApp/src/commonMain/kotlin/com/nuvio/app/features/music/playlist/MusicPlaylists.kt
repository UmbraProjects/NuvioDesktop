package com.nuvio.app.features.music.playlist

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import kotlinx.serialization.Serializable

/*
 * fork:music-playlists
 *
 * A music playlist: a named, ordered list of songs from any albums, kept per profile. It plays through the ordinary
 * player as an album of its own ([asAlbum]); each track carries its own cover and album, so the bar and the lyric
 * view show the right ones as the list moves between records. Everything here is pure, so it is tested directly.
 */

@Serializable
data class PlaylistArtist(val name: String, val browseId: String? = null)

/** One song of a playlist, enough to play it and to show it without asking YouTube Music again. */
@Serializable
data class PlaylistTrack(
    val videoId: String,
    val title: String,
    val artists: List<PlaylistArtist> = emptyList(),
    val durationSeconds: Int = 0,
    val albumTitle: String? = null,
    val albumBrowseId: String? = null,
    val coverUrl: String? = null,
)

@Serializable
data class MusicPlaylist(
    val id: String,
    val name: String,
    val tracks: List<PlaylistTrack> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    /** Where it was imported from ("Spotify", "YouTube Music", ...), or null for one made here. */
    val importedFrom: String? = null,
) {
    val totalSeconds: Int get() = tracks.sumOf { it.durationSeconds }

    /** Up to four different covers, for the playlist's tile. */
    val covers: List<String> get() = tracks.mapNotNull { it.coverUrl }.distinct().take(4)
}

/** The browse id a playing playlist's album carries, so screens can tell which playlist is loaded. */
internal fun playlistAlbumId(playlistId: String): String = "playlist:$playlistId"

/** The playlist as an album the player can take: its own name, its first cover, and the songs in order. */
internal fun MusicPlaylist.asAlbum(): MusicAlbum = MusicAlbum(
    browseId = playlistAlbumId(id),
    title = name,
    artists = emptyList(),
    year = null,
    kind = "Playlist",
    thumbnailUrl = tracks.firstNotNullOfOrNull { it.coverUrl },
    tracks = tracks.mapIndexed { i, t -> t.toMusicTrack(i + 1) },
    audioPlaylistId = null,
)

internal fun PlaylistTrack.toMusicTrack(number: Int): MusicTrack = MusicTrack(
    videoId = videoId,
    title = title,
    artists = artists.map { MusicArtistRef(it.name, it.browseId) },
    durationSeconds = durationSeconds,
    trackNumber = number,
    isAvailable = true,
    coverUrl = coverUrl,
    albumTitle = albumTitle,
    albumBrowseId = albumBrowseId,
)

/** A track as a playlist keeps it; [album] fills in the cover and album a plain album track does not carry. */
internal fun MusicTrack.toPlaylistTrack(album: MusicAlbum? = null): PlaylistTrack = PlaylistTrack(
    videoId = videoId,
    title = title,
    artists = artists.ifEmpty { album?.artists.orEmpty() }.map { PlaylistArtist(it.name, it.browseId) },
    durationSeconds = durationSeconds,
    albumTitle = albumTitle ?: album?.title?.takeUnless { album.kind == "Playlist" },
    albumBrowseId = albumBrowseId ?: album?.browseId?.takeIf { it.startsWith("MPREb_") },
    coverUrl = coverUrl ?: album?.thumbnailUrl,
)

/** [tracks] added at the end, leaving out songs already in the list (and repeats among them). Returns how many went in. */
internal fun MusicPlaylist.withAdded(tracks: List<PlaylistTrack>, nowMs: Long): Pair<MusicPlaylist, Int> {
    val have = this.tracks.mapTo(HashSet()) { it.videoId }
    val fresh = tracks.filter { have.add(it.videoId) }
    if (fresh.isEmpty()) return this to 0
    return copy(tracks = this.tracks + fresh, updatedAtMs = nowMs) to fresh.size
}

internal fun MusicPlaylist.withoutTrack(index: Int, nowMs: Long): MusicPlaylist =
    if (index !in tracks.indices) this else copy(tracks = tracks.filterIndexed { i, _ -> i != index }, updatedAtMs = nowMs)

/** The track at [from] moved to [to] (both clamped to the list). */
internal fun MusicPlaylist.withMoved(from: Int, to: Int, nowMs: Long): MusicPlaylist {
    if (from !in tracks.indices) return this
    val target = to.coerceIn(0, tracks.lastIndex)
    if (target == from) return this
    val list = tracks.toMutableList()
    val item = list.removeAt(from)
    list.add(target, item)
    return copy(tracks = list, updatedAtMs = nowMs)
}

/** A name for a new playlist that no existing one already has: "New playlist", then "New playlist 2", ... */
internal fun freshPlaylistName(existing: List<MusicPlaylist>, base: String = "New playlist"): String {
    val taken = existing.mapTo(HashSet()) { it.name.trim().lowercase() }
    if (base.lowercase() !in taken) return base
    var n = 2
    while ("$base $n".lowercase() in taken) n++
    return "$base $n"
}

/** [toPlaylistTrack] for callers outside this package (the player's menu and the dock). */
object MusicPlaylistTracks {
    fun MusicTrack.forPlaylist(album: MusicAlbum?): PlaylistTrack = toPlaylistTrack(album)
}
