package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * fork:music-radio - playing YouTube Music's lists that are not albums: a playlist (community or YouTube Music's own), a
 * station or mix (made as it plays, read with the watch-next "up next" call), a song's radio and an artist's radio. Each
 * becomes one queue the player plays like an album (as the music home's Play all does), each track with its own cover.
 *
 * A radio is read about 100 tracks ahead when it starts; near the end of them the player asks whether to keep going ([RadioKeepGoing]).
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

/** How many tracks from the end of a radio the question comes. */
private const val KEEP_GOING_AHEAD = 3

/** True for the queues [loadPlaylistQueue], [loadSongRadio] and [loadArtistRadio] make from a radio (not a fixed playlist or an album). */
internal fun isRadioQueue(browseId: String?): Boolean = when {
    browseId == null -> false
    browseId.startsWith("radio:") || browseId.startsWith("artist-radio:") -> true
    browseId.startsWith("list:") -> isRadioPlaylistId(browseId.removePrefix("list:"))
    else -> false
}

/** Whether [index] is within the last few of [size] tracks of a radio. */
internal fun nearRadioEnd(browseId: String?, index: Int, size: Int): Boolean =
    isRadioQueue(browseId) && size > 0 && index >= 0 && index >= size - KEEP_GOING_AHEAD

/** The tracks of [more] not already in [played], for topping a radio up without repeats. */
internal fun freshTracks(more: List<MusicTrack>, played: List<MusicTrack>): List<MusicTrack> {
    val seen = played.mapTo(HashSet()) { it.videoId }
    return more.filter { seen.add(it.videoId) }
}

/**
 * fork:music-radio - a radio is read about 100 tracks ahead. Near the end of them the player asks "Keep this radio going?";
 * Yes reads another radio from the last song and queues it after, which asks again near its own end. Each queue is asked once.
 */
internal object RadioKeepGoing {
    data class Offer(val queueId: String, val title: String, val lastVideoId: String, val played: List<MusicTrack>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()
    private val asked = HashSet<String>()

    /** Follows [player] for the life of the app. */
    suspend fun watch(player: MusicPlayer) {
        player.state.collect { s ->
            val album = s.album ?: return@collect
            if (album.browseId in asked || _offer.value != null) return@collect
            if (!nearRadioEnd(album.browseId, s.trackIndex, album.tracks.size)) return@collect
            val last = album.tracks.lastOrNull() ?: return@collect
            asked += album.browseId
            _offer.value = Offer(album.browseId, album.title.removeSuffix(" radio"), last.videoId, album.tracks)
        }
    }

    fun no() { _offer.value = null }

    fun yes() {
        val offer = _offer.value ?: return
        _offer.value = null
        scope.launch {
            val page = defaultMusicCatalog().radio("RDAMVM${offer.lastVideoId}", videoId = offer.lastVideoId, maxTracks = RADIO_TRACKS)
            val tracks = page?.let { freshTracks(it.tracks, offer.played) }.orEmpty()
            if (tracks.isEmpty()) {
                com.nuvio.app.core.ui.NuvioToastController.show("No more for ${offer.title} radio")
                return@launch
            }
            // Queued under a new id, so it is asked about again near its own end.
            val next = listQueue("radio:${offer.lastVideoId}", "${offer.title} radio", "Radio", null, emptyList(), tracks)
            MusicPlayback.player.enqueue(next)
            com.nuvio.app.core.ui.NuvioToastController.show("Added ${next.tracks.size} more to ${offer.title} radio")
        }
    }
}

/** The question itself, over whatever is on screen; it moves nothing. Place once in the root. */
@Composable
internal fun RadioKeepGoingDialog() {
    val offer by RadioKeepGoing.offer.collectAsStateWithLifecycle()
    // fork:music - the dock asks inside its own panel (and flags the notch), so the pop-up is for the other player styles.
    if (rememberMusicBarStyle() == MusicBarStyle.Dock) return
    val o = offer ?: return
    NuvioModalDialog(
        onDismissRequest = RadioKeepGoing::no,
        title = "Keep ${o.title} radio going?",
        subtitle = "It is nearly out of songs. More like these can follow.",
        actions = {
            TextButton(onClick = RadioKeepGoing::no) { Text("No, let it end") }
            TextButton(onClick = RadioKeepGoing::yes) { Text("Keep going") }
        },
    ) {}
}

/**
 * fork:music-radio - where a queue came from when it is not an album: "From Discover Mix", "From Prince radio", "From Road trip".
 * Null for an album or single (the track's own album is already on the line) and for nothing playing.
 */
internal fun playingFromLabel(queue: MusicAlbum?): String? {
    queue ?: return null
    if (queue.browseId.startsWith("MPREb_") || queue.title.isBlank()) return null
    if (queue.kind != "Radio" && queue.kind != "Playlist") return null
    return "From ${queue.title}"
}
