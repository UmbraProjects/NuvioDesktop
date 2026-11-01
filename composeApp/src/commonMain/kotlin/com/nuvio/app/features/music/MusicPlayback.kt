package com.nuvio.app.features.music

import com.nuvio.app.features.music.nebula.NebulaMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a music video is drawn: the bar's thumbnail, a window over the app, or the whole screen. */
/**
 * Where a real video's picture is: [Bar] (the lyric view's frame when it is open, else the bar's thumbnail) or the
 * whole window. The separate expanded video page is gone: the lyric view holds the video above the lyrics.
 */
enum class MusicVideoView { Bar, Fullscreen }

/** fork:music - the lyric view's video frame, in root pixels, where the player draws the video while that view is open. */
data class MusicInlineVideoFrame(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The app's one [MusicPlayer]. Screens start music through it (`MusicPlayback.player.play(album)`);
 * [MusicPlayerHost] at the app root plays it and draws the mini-player.
 */
object MusicPlayback {
    /** A ladder asked for from outside the Music tab (a genre chip on the Stage); the tab opens it and clears it. */
    internal val pendingLadder = MutableStateFlow<com.nuvio.app.features.music.library.LadderRequest?>(null)

    /** Whether the full-window lyric view is open. [MusicNowPlayingHost] draws it. */
    val nowPlayingOpen = MutableStateFlow(false)

    /** True while the person has tucked the bar away. The music keeps playing; the dock notch brings the bar back. */
    val barCollapsed = MutableStateFlow(false)

    /** fork:music - where the bar and the corner card were dragged and how wide they were made, for this session. */
    val barPlacement = MutableStateFlow(MusicPlayerPlacement.Default)
    val cardPlacement = MutableStateFlow(MusicPlayerPlacement.Default)
    val dockPlacement = MutableStateFlow(MusicPlayerPlacement.Default)

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
        // The lyric view stays open under a fullscreen video, so leaving fullscreen lands back on it.
    }

    /** Opens the lyric view, where a playing video shows above the lyrics (what "Expand video" means now). */
    fun showVideo() {
        setVideoView(MusicVideoView.Bar)
        nowPlayingOpen.value = true
    }

    /** Published by the lyric view while it shows a video frame; null when there is none on screen. */
    val inlineVideoFrame = MutableStateFlow<MusicInlineVideoFrame?>(null)

    val player: MusicPlayer by lazy {
        MusicPlayer(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            // Music addon tracks (ids like "nebula:<trackId>") get a direct link; everything else is YouTube Music.
            resolver = { track, album, mode ->
                if (NebulaMusicSource.owns(track)) NebulaMusicSource.resolve(track, album, mode)
                // fork:music-playlists - a playlist's song plays from the addon's copy of its album when the person
                // prefers the addon and it has that album; otherwise from YouTube Music as before.
                else if (album.kind == "Playlist") NebulaMusicSource.resolveFromItsAlbum(track, mode) ?: resolveMusicTrack(track, album, mode)
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
