package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState

/**
 * Which timed version of a song's lyrics fits the stream that is playing. [forPrimary] and [forLoadId] say
 * what this answer was worked out for (a stale answer from the previous track or load must not be used);
 * [alternative] is a LRCLIB version cut like the stream, or null to keep the primary lyrics.
 */
internal class StreamLyricsVersion(
    val forPrimary: Lyrics.Synced?,
    val forLoadId: Long,
    val alternative: Lyrics.Synced?,
)

/** The version to show for [primary] on load [loadId], and whether that has been settled yet. */
internal class ResolvedLyrics(val resolved: Boolean, val lyrics: Lyrics.Synced?)

/**
 * Settles which LRCLIB version of [primary] (the track's lyrics, timed to the album cut) belongs to the stream
 * playing now (see [LyricsRepository.forStream]). Nothing is asked when the stream is unknown, the lyrics are not
 * timed, or they already fit: those settle at once with [primary] itself.
 */
@Composable
internal fun rememberStreamLyrics(
    track: MusicTrack?,
    album: MusicAlbum?,
    primary: Lyrics.Synced?,
    loadId: Long,
    playedDurationMs: Long,
    streamKnown: Boolean,
): ResolvedLyrics {
    val answer by produceState<StreamLyricsVersion?>(null, track?.videoId, primary, loadId, playedDurationMs, streamKnown) {
        value = null
        if (track == null || primary == null || !streamKnown) return@produceState
        value = StreamLyricsVersion(primary, loadId, LyricsRepository.forStream(track, album, primary, playedDurationMs))
    }
    if (primary == null || !streamKnown) return ResolvedLyrics(resolved = true, lyrics = primary)
    val current = answer?.takeIf { it.forPrimary === primary && it.forLoadId == loadId }
        ?: return ResolvedLyrics(resolved = false, lyrics = primary)
    return ResolvedLyrics(resolved = true, lyrics = current.alternative ?: primary)
}
