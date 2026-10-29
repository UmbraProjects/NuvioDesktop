package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.sourceOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private val log = Logger.withTag("MusicResolver")

/**
 * The hunt for a video (linked video, search, then each search hit in turn) is bounded, so a slow
 * or throttled YouTube leaves the player on the lyric video rather than loading for good.
 */
private const val VIDEO_LOOKUP_TIMEOUT_MS = 15_000L

/**
 * Turns an album track into something the player can open.
 *
 * Audio mode plays the album's own audio track. Video mode plays the video YouTube Music links to
 * that track; failing that it searches YouTube for the official video. When there is none (or the
 * one found will not open) the track plays as audio with the lyric view as its picture
 * ([MusicResolution.Playable.lyricVideo]), so a video-mode album never skips a song for lack of one.
 */
object MusicResolver {
    suspend fun resolve(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution =
        when (mode) {
            MusicPlayMode.Audio -> audio(track)
            MusicPlayMode.Video -> video(track, album)
        }

    private suspend fun audio(track: MusicTrack): MusicResolution {
        val source = open(track.videoId)
            ?: return MusicResolution.Failed(track, "the audio track would not open")
        return MusicResolution.Playable(track, MusicPlayMode.Audio, track.videoId, source, viaCounterpart = false)
    }

    private suspend fun video(track: MusicTrack, album: MusicAlbum): MusicResolution {
        withTimeoutOrNull(VIDEO_LOOKUP_TIMEOUT_MS) { findVideo(track, album) }?.let { return it }
        log.i { "no official video for \"${track.title}\"; playing it as a lyric video" }
        val source = open(track.videoId)
            ?: return MusicResolution.Failed(track, "the audio track would not open")
        return MusicResolution.Playable(
            track, MusicPlayMode.Video, track.videoId, source, viaCounterpart = false, lyricVideo = true,
        )
    }

    /** The track's linked video or the best official video that opens, else null. */
    private suspend fun findVideo(track: MusicTrack, album: MusicAlbum): MusicResolution? {
        val linked = MusicMetadataClient.counterpartOf(track.videoId)
        if (linked != null) {
            open(linked.videoId)?.let {
                return MusicResolution.Playable(track, MusicPlayMode.Video, linked.videoId, it, viaCounterpart = true)
            }
            log.w { "linked video ${linked.videoId} for \"${track.title}\" would not open; searching" }
        }
        val artist = (track.artists.firstOrNull() ?: album.artists.firstOrNull())?.name.orEmpty()
        val hits = try {
            TrailerPlaybackResolver.searchYouTube("$artist ${coreTrackTitle(track.title)} official video")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "video search failed for \"${track.title}\": ${error.message}" }
            emptyList()
        }
        for (hit in rankMusicVideoHits(hits, track.title, artist, track.durationSeconds)) {
            if (hit.videoId == linked?.videoId) continue
            open(hit.videoId)?.let {
                return MusicResolution.Playable(track, MusicPlayMode.Video, hit.videoId, it, viaCounterpart = false)
            }
        }
        return null
    }

    /** A stream for [videoId], or null. A source that stops serving after about a minute is not accepted. */
    private suspend fun open(videoId: String): TrailerPlaybackSource? =
        try {
            TrailerPlaybackResolver.resolveFromYouTubeUrl("https://www.youtube.com/watch?v=$videoId")
                .sourceOrNull()?.takeIf { it.reliable }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
}
