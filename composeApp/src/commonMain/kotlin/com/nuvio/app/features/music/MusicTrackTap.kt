package com.nuvio.app.features.music

import kotlin.random.Random

/** What a tap on a track in an album's list should do. */
internal sealed interface MusicTrackTap {
    /** This album is already what the player holds: jump inside its queue (mode and shuffle stay). */
    data class JumpInQueue(val trackIndex: Int) : MusicTrackTap

    /** Something else (or nothing) is loaded: start this album at the tapped track, in album order. */
    data class StartAlbum(val trackIndex: Int, val mode: MusicPlayMode) : MusicTrackTap
}

/** True when [album] is the album the player has loaded right now. */
internal fun MusicPlayerState.holds(album: MusicAlbum): Boolean =
    // fork:lenses - the same record with fewer songs (a Cut to fit queue) is not the album: its indices are the cut's.
    isActive && this.album?.browseId == album.browseId && this.album?.tracks?.size == album.tracks.size

/**
 * fork:lenses - where the playing song is in [album]'s own list, or -1: its queue index when the whole album is loaded,
 * else (a cut of it is playing) the place of the same song. Never -1 just because the queue is a cut.
 */
internal fun MusicPlayerState.playingIndexIn(album: MusicAlbum): Int {
    if (!isActive || this.album?.browseId != album.browseId) return -1
    if (holds(album)) return trackIndex
    val playing = track ?: return -1
    return album.tracks.indexOfFirst { it.videoId == playing.videoId }
}

internal fun musicTrackTap(
    state: MusicPlayerState,
    album: MusicAlbum,
    trackIndex: Int,
    chosenMode: MusicPlayMode,
): MusicTrackTap =
    if (state.holds(album)) MusicTrackTap.JumpInQueue(trackIndex)
    else MusicTrackTap.StartAlbum(trackIndex, chosenMode)

/** A random starting track for "Shuffle" that is not greyed out; 0 when none is available. */
internal fun randomPlayableIndex(album: MusicAlbum, random: Random = Random.Default): Int {
    val available = album.tracks.indices.filter { album.tracks[it].isAvailable }
    return if (available.isEmpty()) 0 else available[random.nextInt(available.size)]
}
