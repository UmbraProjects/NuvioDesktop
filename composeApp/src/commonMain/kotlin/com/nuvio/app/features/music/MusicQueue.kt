package com.nuvio.app.features.music

import kotlin.random.Random

/** Why a mode switch did not happen. The current track keeps playing as it was. */
sealed interface MusicModeSwitch {
    /** Open [playable] and seek to [startPositionMs] so the song carries on where it was. */
    data class Switched(val playable: MusicResolution.Playable, val startPositionMs: Long) : MusicModeSwitch

    /** The other mode has nothing for this track (usually: no video). */
    data object Unavailable : MusicModeSwitch
}

/**
 * The order an album plays in, and where it is. Pure state: it never plays anything, it only says
 * which track is next, asking [MusicResolution] results from the caller so missing videos are
 * skipped by [findNextPlayable]. [shuffled] is reproducible from [seed], so a restored queue plays
 * the same order.
 */
class MusicQueue(
    val album: MusicAlbum,
    mode: MusicPlayMode,
    shuffled: Boolean = false,
    private val seed: Long = 0L,
    startIndex: Int = 0,
) {
    var mode: MusicPlayMode = mode
        private set
    var shuffled: Boolean = shuffled
        private set

    /** Album track indices in playing order. */
    private var order: List<Int> = buildOrder(startIndex.coerceIn(0, (album.tracks.size - 1).coerceAtLeast(0)))

    /** Place in [order] of the track now playing. */
    private var position: Int = order.indexOf(startIndex.coerceIn(0, (album.tracks.size - 1).coerceAtLeast(0))).coerceAtLeast(0)

    val tracks: List<MusicTrack> get() = album.tracks
    val currentIndex: Int? get() = order.getOrNull(position)
    val current: MusicTrack? get() = currentIndex?.let { album.tracks.getOrNull(it) }

    /** Tracks still to come, in playing order. */
    val upNext: List<MusicTrack> get() = order.drop(position + 1).mapNotNull { album.tracks.getOrNull(it) }

    private fun buildOrder(first: Int): List<Int> {
        val all = album.tracks.indices.toList()
        if (!shuffled) return all
        // The track being played stays first so turning shuffle on never changes the song.
        return listOf(first) + all.filter { it != first }.shuffled(Random(seed))
    }

    fun setShuffled(on: Boolean) {
        if (on == shuffled) return
        val playing = currentIndex ?: 0
        shuffled = on
        order = buildOrder(playing)
        position = order.indexOf(playing).coerceAtLeast(0)
    }

    fun setMode(newMode: MusicPlayMode) {
        mode = newMode
    }

    /**
     * Moves to the next track that can play and returns it, or null at the end (the position is
     * then unchanged). Tracks passed over are in [NextPlayable.skipped].
     */
    suspend fun advance(resolve: suspend (MusicTrack) -> MusicResolution): NextPlayable? =
        move(position + 1, 1, resolve)

    /** Same, going back. Starts from the track before the current one. */
    suspend fun retreat(resolve: suspend (MusicTrack) -> MusicResolution): NextPlayable? =
        move(position - 1, -1, resolve)

    /** Plays the album track at [trackIndex] (a tap in the track list), skipping on if it cannot play. */
    suspend fun jumpTo(trackIndex: Int, resolve: suspend (MusicTrack) -> MusicResolution): NextPlayable? =
        move(order.indexOf(trackIndex), 1, resolve)

    private suspend fun move(from: Int, step: Int, resolve: suspend (MusicTrack) -> MusicResolution): NextPlayable? {
        if (from < 0 || from >= order.size) return null
        val ordered = order.mapNotNull { album.tracks.getOrNull(it) }
        val window = if (step > 0) ordered else ordered.reversed()
        val start = if (step > 0) from else ordered.size - 1 - from
        val found = findNextPlayable(window, start, resolve) ?: return null
        val newPosition = if (step > 0) found.index else ordered.size - 1 - found.index
        position = newPosition
        // `index` in the result is the album track's own index, whatever the playing order.
        return found.copy(index = order[newPosition])
    }

    /**
     * Plays the current track in [newMode] from [positionMs]. On success the queue's mode changes;
     * otherwise nothing does and the current track should keep playing.
     */
    suspend fun switchMode(
        newMode: MusicPlayMode,
        positionMs: Long,
        resolve: suspend (MusicTrack, MusicPlayMode) -> MusicResolution,
    ): MusicModeSwitch {
        val track = current ?: return MusicModeSwitch.Unavailable
        if (newMode == mode) return MusicModeSwitch.Unavailable
        return when (val result = resolve(track, newMode)) {
            is MusicResolution.Playable -> {
                mode = newMode
                MusicModeSwitch.Switched(result, positionMs.coerceAtLeast(0L))
            }
            else -> MusicModeSwitch.Unavailable
        }
    }
}
