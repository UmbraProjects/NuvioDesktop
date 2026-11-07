package com.nuvio.app.features.music

import kotlinx.coroutines.launch

/*
 * fork:music-radio - playing YouTube Music's lists that are not albums: a playlist (community or YouTube Music's own), a
 * station or mix (made as it plays, read with the watch-next "up next" call), a song's radio and an artist's radio. Each
 * becomes one queue the player plays like an album (as the music home's Play all does), each track with its own cover.
 *
 * A radio is read about 100 tracks ahead when it starts; it does not top itself up after that yet.
 */

/** How far ahead a radio is read when it starts. */
internal const val RADIO_TRACKS = 100

/** [tracks] as one queue the player plays like an album, numbered in order, without repeats. */
internal fun listQueue(id: String, title: String, kind: String, cover: String?, artists: List<MusicArtistRef>, tracks: List<MusicTrack>): MusicAlbum =
    MusicAlbum(
        browseId = id,
        title = title,
        artists = artists,
        year = null,
        kind = kind,
        thumbnailUrl = cover ?: tracks.firstNotNullOfOrNull { it.coverUrl },
        tracks = tracks.filter { it.isAvailable }.distinctBy { it.videoId }.mapIndexed { i, t -> t.copy(trackNumber = i + 1) },
        audioPlaylistId = null,
    )

/**
 * The list [playlistId] as a queue: a radio or mix through the watch-next call, a fixed playlist as its page (every track),
 * falling back to the other way when the first reads nothing. Null when neither does.
 */
internal suspend fun loadPlaylistQueue(catalog: MusicCatalog, playlistId: String, title: String, cover: String? = null): MusicAlbum? {
    val id = playlistId.removePrefix("VL")
    val radio = isRadioPlaylistId(id)
    val page = if (radio) {
        catalog.radio(id, maxTracks = RADIO_TRACKS) ?: catalog.playlist(id)
    } else {
        catalog.playlist(id)?.takeIf { it.tracks.isNotEmpty() } ?: catalog.radio(id, maxTracks = RADIO_TRACKS)
    } ?: return null
    if (page.tracks.isEmpty()) return null
    return listQueue("list:$id", page.title ?: title, if (radio) "Radio" else "Playlist", cover, emptyList(), page.tracks)
}

/** A radio started from the song [videoId] (YouTube Music's "Start radio"), the song first. */
internal suspend fun loadSongRadio(catalog: MusicCatalog, videoId: String, title: String, artists: List<MusicArtistRef> = emptyList()): MusicAlbum? {
    val page = catalog.radio("RDAMVM$videoId", videoId = videoId, maxTracks = RADIO_TRACKS) ?: return null
    if (page.tracks.isEmpty()) return null
    return listQueue("radio:$videoId", "$title radio", "Radio", null, artists, page.tracks)
}

/**
 * An artist's radio: the radio their page offers, else a radio started from their most played song. Null when the
 * artist page cannot be read or offers neither.
 */
internal suspend fun loadArtistRadio(catalog: MusicCatalog, artistId: String, name: String): MusicAlbum? {
    val artist = catalog.artist(artistId) ?: return null
    val self = listOf(MusicArtistRef(artist.name, artistId))
    artist.radioPlaylistId?.let { id ->
        val page = catalog.radio(id, maxTracks = RADIO_TRACKS)
        if (page != null && page.tracks.isNotEmpty()) {
            return listQueue("artist-radio:$artistId", "${artist.name} radio", "Radio", artist.thumbnailUrl, self, page.tracks)
        }
    }
    val seed = artist.topSongs.firstOrNull() ?: return null
    return loadSongRadio(catalog, seed.videoId, name, self)?.copy(
        browseId = "artist-radio:$artistId",
        title = "${artist.name} radio",
        thumbnailUrl = artist.thumbnailUrl,
    )
}

/**
 * Starts a song's or an artist's radio from any screen's menu: it says what it is doing, reads the radio in the background
 * and plays it, or says it could not.
 */
internal object MusicRadioStarter {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun song(videoId: String, title: String, artists: List<MusicArtistRef> = emptyList()) {
        com.nuvio.app.core.ui.NuvioToastController.show("Starting $title radio…")
        scope.launch {
            val queue = loadSongRadio(defaultMusicCatalog(), videoId, title, artists)
            if (queue == null) com.nuvio.app.core.ui.NuvioToastController.show("Couldn't start a radio for $title")
            else MusicPlayback.player.play(queue)
        }
    }

    fun artist(artistId: String, name: String) {
        com.nuvio.app.core.ui.NuvioToastController.show("Starting $name radio…")
        scope.launch {
            val queue = loadArtistRadio(defaultMusicCatalog(), artistId, name)
            if (queue == null) com.nuvio.app.core.ui.NuvioToastController.show("Couldn't start a radio for $name")
            else MusicPlayback.player.play(queue)
        }
    }
}
