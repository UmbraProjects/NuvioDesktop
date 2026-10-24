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
    isActive && this.album?.browseId == album.browseId

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
