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

    private val _resumes = MutableSharedFlow<com.nuvio.app.features.watchprogress.ContinueWatchingItem>(extraBufferCapacity = 2)

    /**
     * Continue Watching titles the phone asked to resume (its Watch tab, Resume on the TV). The app root plays them as
     * the card's last-link shortcut does: the same source again, from where it stopped.
     */
    val resumes: SharedFlow<com.nuvio.app.features.watchprogress.ContinueWatchingItem> = _resumes.asSharedFlow()

    fun resume(item: com.nuvio.app.features.watchprogress.ContinueWatchingItem) {
        _resumes.tryEmit(item)
    }

    private val _episodePlays = MutableSharedFlow<RemoteEpisodePlay>(extraBufferCapacity = 2)

    /** Episodes the phone asked to play (its season list): the app root plays them as the details page's episode does. */
    val episodePlays: SharedFlow<RemoteEpisodePlay> = _episodePlays.asSharedFlow()

    fun playEpisode(play: RemoteEpisodePlay) {
        _episodePlays.tryEmit(play)
    }
}

/** What the details page hands its play handler for an episode, picked on the phone instead. */
data class RemoteEpisodePlay(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val pauseDescription: String?,
    val resumePositionMs: Long?,
)

data class RemoteYouTubeOpen(val videoId: String, val startSeconds: Int? = null)
