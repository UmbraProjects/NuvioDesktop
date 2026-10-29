package com.nuvio.app.features.music

import com.nuvio.app.features.music.nebula.NebulaMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a music video is drawn: the bar's thumbnail, a window over the app, or the whole screen. */
enum class MusicVideoView { Bar, Expanded, Fullscreen }

/**
 * The app's one [MusicPlayer]. Screens start music through it (`MusicPlayback.player.play(album)`);
 * [MusicPlayerHost] at the app root plays it and draws the mini-player.
 */
object MusicPlayback {
    /** Whether the full-window lyric view is open. [MusicNowPlayingHost] draws it. */
    val nowPlayingOpen = MutableStateFlow(false)

    /** True while the person has tucked the bar away. The music keeps playing; the dock notch brings the bar back. */
    val barCollapsed = MutableStateFlow(false)

    /** fork:music - where the bar and the corner card were dragged and how wide they were made, for this session. */
    val barPlacement = MutableStateFlow(MusicPlayerPlacement.Default)
    val cardPlacement = MutableStateFlow(MusicPlayerPlacement.Default)

    /**
     * True while a screen that needs the room (the ladder) asks for the bar to shrink to a slim pill. The bar only
     * obeys while there is no real video and the lyric view is closed ([musicBarSlim]).
     */
    val slimBar = MutableStateFlow(false)

    /** How big the music video is. Only a real video leaves [MusicVideoView.Bar]; [MusicPlayerHost] resets it. */
    val videoView = MutableStateFlow(MusicVideoView.Bar)

    private var ownsAppFullscreen = false

    /**
     * Changes [videoView]. Entering [MusicVideoView.Fullscreen] also takes the window fullscreen (unless
     * it already was); leaving it gives the window back only if this call is what took it.
     */
    fun setVideoView(view: MusicVideoView) {
        val previous = videoView.value
        if (view == previous) return
        videoView.value = view
        if (view == MusicVideoView.Fullscreen) {
            if (!isAppFullscreen()) {
                setAppFullscreen(true)
                ownsAppFullscreen = true
            }
        } else if (previous == MusicVideoView.Fullscreen && ownsAppFullscreen) {
            setAppFullscreen(false)
            ownsAppFullscreen = false
        }
        if (view != MusicVideoView.Bar) nowPlayingOpen.value = false
    }

    val player: MusicPlayer by lazy {
        MusicPlayer(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            // Music addon tracks (ids like "nebula:<trackId>") get a direct link; everything else is YouTube Music.
            resolver = { track, album, mode ->
                if (NebulaMusicSource.owns(track)) NebulaMusicSource.resolve(track, album, mode)
                else resolveMusicTrack(track, album, mode)
            },
            initialVolume = com.nuvio.app.features.music.library.MusicLibraryStorage.loadPlayerVolume() ?: DEFAULT_MUSIC_VOLUME,
            saveVolume = com.nuvio.app.features.music.library.MusicLibraryStorage::savePlayerVolume,
        )
    }
}

/** Where a first ever session starts: loud enough to hear, not the full-scale blast it used to be. */
private const val DEFAULT_MUSIC_VOLUME = 50

/** 3:07 or 1:02:09. Negative and unknown (0) lengths show as 0:00. */
internal fun formatMusicTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
