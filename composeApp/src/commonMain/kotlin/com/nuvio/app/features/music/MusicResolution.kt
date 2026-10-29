package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackSource

/** How an album plays: just the song, or its music video. */
enum class MusicPlayMode { Audio, Video }

sealed interface MusicResolution {
    /** Ready to play. [videoId] is the YouTube id behind [source]. */
    data class Playable(
        val track: MusicTrack,
        val mode: MusicPlayMode,
        val videoId: String,
        val source: TrailerPlaybackSource,
        /** The video came from YouTube Music's own link, not from searching. */
        val viaCounterpart: Boolean,
        /**
         * Video mode found no official video, so this is the song's audio and the lyric view is the
         * picture. [source] is audio only; the surface stays parked.
         */
        val lyricVideo: Boolean = false,
    ) : MusicResolution

    /** Video mode, and neither a linked nor a searched video was good enough. */
    data class NoVideo(val track: MusicTrack) : MusicResolution

    /** A video was found but its stream could not be opened. */
    data class Failed(val track: MusicTrack, val reason: String) : MusicResolution
}

/** The next track that can play, and the tracks passed over to reach it. */
data class NextPlayable(
    val index: Int,
    val playable: MusicResolution.Playable,
    val skipped: List<MusicTrack>,
)

/**
 * Walks [tracks] from [from] and returns the first that [resolve] can play, skipping greyed-out
 * tracks without asking and any track that has no video or will not open. Null when none is left.
 * This is the rule for an album: a missing video never stops the album, it moves on.
 */
suspend fun findNextPlayable(
    tracks: List<MusicTrack>,
    from: Int,
    resolve: suspend (MusicTrack) -> MusicResolution,
): NextPlayable? {
    val skipped = ArrayList<MusicTrack>()
    for (index in from.coerceAtLeast(0) until tracks.size) {
        val track = tracks[index]
        if (!track.isAvailable) {
            skipped += track
            continue
        }
        when (val result = resolve(track)) {
            is MusicResolution.Playable -> return NextPlayable(index, result, skipped)
            is MusicResolution.NoVideo, is MusicResolution.Failed -> skipped += track
        }
    }
    return null
}
