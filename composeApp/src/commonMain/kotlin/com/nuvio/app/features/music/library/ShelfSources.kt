package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicHomeShelf
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.listQueue
import com.nuvio.app.features.music.MusicPlaylistSummary
import com.nuvio.app.features.music.loadArtistRadio
import com.nuvio.app.features.music.loadPlaylistQueue
import com.nuvio.app.features.music.loadSongRadio
import com.nuvio.app.features.music.playlist.MusicPlaylist
import com.nuvio.app.features.music.playlist.MusicPlaylistRepository
import com.nuvio.app.features.music.playlist.asAlbum
import com.nuvio.app.features.music.playlist.toPlaylistTrack
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * fork:shelf - what the Shelf shows besides the library itself: YouTube Music's home mixes and the account's playlists (asked
 * for as the signed-in account, kept for half an hour), and what one item on a shelf does when played, queued or saved.
 */

/** One thing on a shelf's page of covers. */
internal sealed interface ShelfItem {
    val key: String

    /** An album in the library. */
    data class Album(val album: ShelfAlbum) : ShelfItem {
        override val key: String get() = "album:" + album.key
    }

    /** An album from the play log that is not (or no longer) in the library. */
    data class PlayedAlbum(val play: RecentPlay.Album) : ShelfItem {
        override val key: String get() = play.key
    }

    /** A YouTube Music playlist or mix. */
    data class Playlist(val list: MusicPlaylistSummary) : ShelfItem {
        override val key: String get() = "list:" + list.playlistId
    }

    /** A playlist, mix or radio from the play log. */
    data class Played(val play: RecentPlay.Queue) : ShelfItem {
        override val key: String get() = play.key
    }

    /** A song from the account's Liked Music; plays Liked Music from this song. */
    data class Song(val track: MusicTrack) : ShelfItem {
        override val key: String get() = "song:" + track.videoId
    }

    /** A playlist made or imported here. */
    data class Local(val playlist: MusicPlaylist) : ShelfItem {
        override val key: String get() = "local:" + playlist.id
    }
}

/** The YouTube Music account's shelves and playlists, for Mixed for you and Your playlists. */
internal object MusicAccountShelves {
    data class State(
        val signedIn: Boolean = false,
        val loading: Boolean = false,
        val home: List<MusicHomeShelf> = emptyList(),
        val playlists: List<MusicPlaylistSummary> = emptyList(),
        /** The newest songs of Liked Music, newest first. */
        val liked: List<MusicTrack> = emptyList(),
        val loadedAtMs: Long = 0L,
        val failed: Boolean = false,
    )

    private const val FRESH_MS = 30 * 60_000L
    private const val LIKED_MAX = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Asks YouTube Music again when signed in and what is kept is older than half an hour (or [force]). */
    fun refresh(catalog: MusicCatalog, force: Boolean = false) {
        if (!YouTubeMusicAuth.isSignedIn) {
            _state.value = State(signedIn = false)
            return
        }
        val now = LibraryClock.nowEpochMs()
        val s = _state.value
        if (s.loading || (!force && s.signedIn && s.loadedAtMs > 0 && now - s.loadedAtMs < FRESH_MS)) return
        _state.update { it.copy(signedIn = true, loading = true) }
        scope.launch {
            val home = runCatching { catalog.accountHome() }.getOrNull()
            val lists = runCatching { catalog.accountPlaylists() }.getOrNull()
            val liked = if (lists.orEmpty().any { it.playlistId == "LM" }) runCatching { catalog.playlist("LM") }.getOrNull()?.tracks?.filter { it.isAvailable }?.take(LIKED_MAX).orEmpty() else emptyList()
            _state.value = State(
                signedIn = true,
                loading = false,
                home = home.orEmpty(),
                playlists = lists.orEmpty(),
                liked = liked,
                loadedAtMs = LibraryClock.nowEpochMs(),
                failed = home == null && lists == null,
            )
        }
    }
}

/** Mixed for you: the account's mixes first, then the playlists on the other home shelves, each once. */
internal fun shelfMixes(home: List<MusicHomeShelf>): List<MusicPlaylistSummary> =
    (home.filter { it.isMixes } + home.filterNot { it.isMixes }).flatMap { it.playlists }.distinctBy { it.playlistId }

/** Liked Music: the account's Liked Music playlist, when it is among its playlists. */
internal fun shelfLiked(playlists: List<MusicPlaylistSummary>): List<MusicPlaylistSummary> =
    playlists.filter { it.playlistId == "LM" }.take(1)

/** Listen again: the playlists on YouTube Music's home shelves titled "Listen again" (or "Listen again to..."). */
internal fun shelfListenAgain(home: List<MusicHomeShelf>): List<MusicPlaylistSummary> =
    home.filter { it.title.contains("listen again", ignoreCase = true) }.flatMap { it.playlists }.distinctBy { it.playlistId }

/** Plays, queues and saves shelf items; long work runs in the background and says when it fails. */
internal class ShelfActions(private val catalog: MusicCatalog, private val scope: CoroutineScope) {
    private val player get() = MusicPlayback.player

    private suspend fun queueOf(item: ShelfItem, whole: Boolean = true): com.nuvio.app.features.music.MusicAlbum? = when (item) {
        is ShelfItem.Song -> if (whole) loadPlaylistQueue(catalog, "LM", "Liked Music") else listQueue("song:" + item.track.videoId, item.track.title, "Song", item.track.coverUrl, item.track.artists, listOf(item.track))
        is ShelfItem.Album -> item.album.release.ytBrowseId?.let { catalog.album(it) }
        is ShelfItem.PlayedAlbum -> catalog.album(item.play.browseId)
        is ShelfItem.Playlist -> loadPlaylistQueue(catalog, item.list.playlistId, item.list.title, item.list.thumbnailUrl)
        is ShelfItem.Local -> item.playlist.takeIf { it.tracks.isNotEmpty() }?.asAlbum()
        is ShelfItem.Played -> {
            val source = item.play.sourceId
            when {
                item.play.playlistId != null -> loadPlaylistQueue(catalog, item.play.playlistId!!, item.play.title, item.play.coverUrl)
                source.startsWith("radio:") -> loadSongRadio(catalog, source.removePrefix("radio:"), item.play.title.removeSuffix(" radio"))
                source.startsWith("artist-radio:") -> loadArtistRadio(catalog, source.removePrefix("artist-radio:"), item.play.title.removeSuffix(" radio"))
                else -> null
            }
        }
    }

    fun titleOf(item: ShelfItem): String = when (item) {
        is ShelfItem.Song -> item.track.title
        is ShelfItem.Album -> item.album.release.title
        is ShelfItem.PlayedAlbum -> item.play.title
        is ShelfItem.Playlist -> item.list.title
        is ShelfItem.Local -> item.playlist.name
        is ShelfItem.Played -> item.play.title
    }

    fun play(item: ShelfItem, shuffled: Boolean = false) = launchWith(item, "Opening") { queue ->
        // A liked song plays Liked Music from that song (shuffling mixes the lot).
        val at = (item as? ShelfItem.Song)?.let { s -> queue.tracks.indexOfFirst { it.videoId == s.track.videoId } }?.coerceAtLeast(0) ?: 0
        player.play(queue, trackIndex = if (shuffled) 0 else at, shuffled = shuffled)
        null
    }

    fun queue(item: ShelfItem) = launchWith(item, "Opening", whole = false) { queue ->
        if (player.enqueue(queue)) "Added ${titleOf(item)} to the queue" else null
    }

    /** A YouTube Music playlist or mix kept as a playlist here (its tracks as they are now). */
    fun save(item: ShelfItem) = launchWith(item, "Saving") { queue ->
        val playlist = MusicPlaylistRepository.create(titleOf(item), queue.tracks.map { it.toPlaylistTrack(queue) }, importedFrom = "YouTube Music")
        "Saved to your playlists as ${playlist.name}"
    }

    private fun launchWith(item: ShelfItem, verb: String, whole: Boolean = true, then: (com.nuvio.app.features.music.MusicAlbum) -> String?) {
        val title = titleOf(item)
        if (item !is ShelfItem.Local && item !is ShelfItem.Album && item !is ShelfItem.Song) NuvioToastController.show("$verb $title…")
        scope.launch {
            val queue = runCatching { queueOf(item, whole) }.getOrNull()
            if (queue == null || queue.tracks.isEmpty()) {
                NuvioToastController.show("Couldn't open $title")
                return@launch
            }
            then(queue)?.let { NuvioToastController.show(it) }
        }
    }
}
