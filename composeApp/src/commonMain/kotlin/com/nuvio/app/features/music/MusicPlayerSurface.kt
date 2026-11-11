package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.trailer.TrailerPlaybackSource

/**
 * The one native mpv surface the music player plays through (the same host the hero trailers use).
 *
 * Audio mode keeps it parked at one pixel; video mode fills [modifier]. [loadId] says when [source]
 * is something new to open (a track change or a mode switch); the same [loadId] never reopens it.
 * Seeks arrive as [seekRequestId] / [seekRequestMs]: each new id is applied once.
 *
 * Reports go back with the [loadId] they belong to, so a late report from the previous track is
 * dropped by the caller.
 */
@Composable
expect fun MusicPlayerSurface(
    source: TrailerPlaybackSource,
    loadId: Long,
    videoVisible: Boolean,
    startPositionMs: Long,
    playWhenReady: Boolean,
    volume: Int,
    seekRequestId: Long,
    seekRequestMs: Long,
    /** Fit the whole picture inside [modifier] (bars at the sides) instead of filling it and cropping. */
    letterbox: Boolean,
    /** The Now Playing picture: rounded corners and side bars in the Stage's dark tone, as the glass panels. */
    framed: Boolean,
    modifier: Modifier,
    onSnapshot: (loadId: Long, snapshot: PlayerPlaybackSnapshot) -> Unit,
    onEnded: (loadId: Long) -> Unit,
    onError: (loadId: Long) -> Unit,
    /**
     * fork:gapless - the next track's stream, to queue behind [source] so it follows with no gap, or null for nothing
     * queued. When the player moves on to it, [onNextStarted] reports it with the [loadId] it followed; the caller then
     * gives the surface a new [loadId] with [continued] true, which it does not reopen.
     */
    nextSource: TrailerPlaybackSource?,
    continued: Boolean,
    onNextStarted: (loadId: Long) -> Unit,
)

/** Resolves a track the way the app does: [MusicResolver] on desktop. */
expect suspend fun resolveMusicTrack(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution
