package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * fork:phone-remote — what the phone remote shows and steers: the film player while it is open,
 * else the music player. The film player publishes itself here ([RemotePlayerHub.publishFilm]);
 * the music player is read straight from `MusicPlayback` on the desktop side.
 */
data class RemoteNowPlaying(
    /** "film" for the film and YouTube player, "music" for the music player. */
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    /** 0 while the length is unknown or the stream has no timeline (live TV). */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 0..[volumeMax], or null when the player cannot report it. */
    val volume: Int? = null,
    val volumeMax: Int = 100,
    /** True when Next and Previous mean something (the music queue). */
    val canSkip: Boolean = false,
    /** The film player's subtitle in use ("English SDH"), "Off", or null when it has none to report. */
    val subtitleTrack: String? = null,
    /** The film player's audio track in use ("English 5.1"), or null. */
    val audioTrack: String? = null,
)

/** What the phone can do to the film player beyond the keys a controller sends. */
interface RemoteFilmControls {
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun play()
    fun pause()
    /** 0..volumeMax, as in [RemoteNowPlaying.volume]. */
    fun setVolume(percent: Int)
}

object RemotePlayerHub {
    private val _film = MutableStateFlow<RemoteNowPlaying?>(null)
    val film: StateFlow<RemoteNowPlaying?> = _film.asStateFlow()

    /** Set while the film player is on screen; read on the remote's threads. */
    @kotlin.concurrent.Volatile
    var filmControls: RemoteFilmControls? = null
        private set

    fun publishFilm(state: RemoteNowPlaying?, controls: RemoteFilmControls?) {
        filmControls = controls
        _film.value = state
    }

    private val _youTubeOpens = MutableSharedFlow<RemoteYouTubeOpen>(extraBufferCapacity = 2)

    /** YouTube videos sent from the phone's share menu; the app root opens them in the player. */
    val youTubeOpens: SharedFlow<RemoteYouTubeOpen> = _youTubeOpens.asSharedFlow()

    fun openYouTube(request: RemoteYouTubeOpen) {
        _youTubeOpens.tryEmit(request)
    }
}

data class RemoteYouTubeOpen(val videoId: String, val startSeconds: Int? = null)
