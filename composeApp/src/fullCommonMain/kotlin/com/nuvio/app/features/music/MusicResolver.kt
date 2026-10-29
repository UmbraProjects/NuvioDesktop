package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.sourceOrNull
import kotlinx.coroutines.CancellationException

private val log = Logger.withTag("MusicResolver")

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
        val source = open(track.videoId, needsVideo = false)
            ?: return MusicResolution.Failed(track, "the audio track would not open")
        return MusicResolution.Playable(track, MusicPlayMode.Audio, track.videoId, source, viaCounterpart = false)
    }

    private suspend fun video(track: MusicTrack, album: MusicAlbum): MusicResolution {
        val linked = MusicMetadataClient.counterpartOf(track.videoId)
        if (linked != null) {
            open(linked.videoId, needsVideo = true)?.let {
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
            open(hit.videoId, needsVideo = true)?.let {
                return MusicResolution.Playable(track, MusicPlayMode.Video, hit.videoId, it, viaCounterpart = false)
            }
        }
        log.i { "no official video for \"${track.title}\"; playing it as a lyric video" }
        val source = open(track.videoId, needsVideo = false)
            ?: return MusicResolution.Failed(track, "the audio track would not open")
        return MusicResolution.Playable(
            track, MusicPlayMode.Video, track.videoId, source, viaCounterpart = false, lyricVideo = true,
        )
    }

    /**
     * A stream for [videoId] that has been checked to serve bytes, or null.
     *
     * fork:music-resolver — ported from BitChord's resolver. The extractor's answer is probed (see
     * [verifiedSource]) before the player is handed it: a refused URL moves on to the HLS /
     * progressive fallbacks, and a source from a client that can be cut off is accepted when the
     * probe shows it plays, instead of being dropped by name. When every link is refused the cached
     * session is forgotten and the lookup tried once more, since a stale session is a common cause
     * of URLs that look fine and serve nothing. [needsVideo] is whether the picture will be shown;
     * the audio-only surface only reads the audio URL, so only that one has to be good.
     */
    private suspend fun open(videoId: String, needsVideo: Boolean): TrailerPlaybackSource? {
        repeat(OPEN_ATTEMPTS) { attempt ->
            val resolved = resolveSource(videoId) ?: return null
            verifiedSource(resolved, needsVideo)?.let { return it }
            if (attempt < OPEN_ATTEMPTS - 1) {
                log.w { "every stream for $videoId was refused; retrying with a fresh session" }
                try {
                    TrailerPlaybackResolver.forgetSession()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.w { "could not reset the YouTube session: ${error.message}" }
                }
            }
        }
        return null
    }

    private const val OPEN_ATTEMPTS = 2

    private suspend fun resolveSource(videoId: String): TrailerPlaybackSource? =
        try {
            TrailerPlaybackResolver.resolveFromYouTubeUrl("https://www.youtube.com/watch?v=$videoId").sourceOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
}
