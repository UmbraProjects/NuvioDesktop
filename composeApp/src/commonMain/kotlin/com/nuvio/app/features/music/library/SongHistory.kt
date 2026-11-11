package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicAlbum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * fork:history - counts each song's plays and skips as the player plays them (rules in [ListenTracker]), for the Your
 * history lens. Started with the app; nothing is counted from before it existed. Only YouTube Music albums are counted
 * (an "MPRE" id), so a playlist or a radio does not land songs on the wrong album.
 */
internal object SongHistory {
    private val _changes = MutableStateFlow(0)

    /** Goes up each time a play or skip is kept, so a page showing history reads it again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** The history of each of [album]'s tracks, in track order (null for a song with none). */
    fun forAlbum(album: MusicAlbum): List<SongStat?> =
        album.tracks.map { decodeSongStat(SongHistoryStorage.get(songHistoryKey(album.browseId, it.title))) }

    private fun keep(verdict: ListenVerdict) {
        val old = decodeSongStat(SongHistoryStorage.get(verdict.key))
        SongHistoryStorage.put(verdict.key, encodeSongStat(old.plus(verdict.played, now())))
        _changes.update { it + 1 }
    }

    /** Runs for the life of the app (started from the root hosts); cancelled with it. */
    suspend fun run() {
        val tracker = ListenTracker()
        MusicPlayback.player.state.collect { s ->
            val albumId = s.album?.browseId?.takeIf { it.startsWith("MPRE") }
            val key = if (albumId != null && s.track != null) songHistoryKey(albumId, s.track.title) else null
            tracker.feed(ListenSample(key, s.positionMs, s.durationMs, s.isPlaying, now())).forEach(::keep)
        }
    }
}
