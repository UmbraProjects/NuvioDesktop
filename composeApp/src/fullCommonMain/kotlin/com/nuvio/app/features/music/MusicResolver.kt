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
            MusicPlayMode.Audio -> audio(track, album)
            MusicPlayMode.Video -> video(track, album)
        }

    private suspend fun audio(track: MusicTrack, album: MusicAlbum): MusicResolution {
        val audioId = albumAudioId(track)
        openAudio(track, audioId)?.let {
            return MusicResolution.Playable(track, MusicPlayMode.Audio, audioId, it, viaCounterpart = false)
        }
        alternateUpload(track, album, tried = setOf(audioId, track.videoId))?.let { (id, source) ->
            return MusicResolution.Playable(track, MusicPlayMode.Audio, id, source, viaCounterpart = false)
        }
        return MusicResolution.Failed(track, "the audio track would not open")
    }

    /**
     * fork:alternate-upload — last resort for a track whose own audio will not open (an age gate or a
     * stream format the player cannot read): search YouTube for the same song, keep the hits whose
     * title, artist and length match, and play the first one that opens.
     */
    private suspend fun alternateUpload(
        track: MusicTrack,
        album: MusicAlbum,
        tried: Set<String>,
    ): Pair<String, TrailerPlaybackSource>? {
        val artist = (track.artists.firstOrNull() ?: album.artists.firstOrNull())?.name.orEmpty()
        val hits = try {
            TrailerPlaybackResolver.searchYouTube("$artist ${coreTrackTitle(track.title)}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "alternate-upload search failed for \"${track.title}\": ${error.message}" }
            return null
        }
        for (hit in rankMusicVideoHits(hits, track.title, artist, track.durationSeconds, limit = 4)) {
            if (hit.videoId in tried) continue
            open(hit.videoId, needsVideo = false)?.let {
                log.i { "\"${track.title}\" would not open; playing the upload ${hit.videoId} (${hit.title})" }
                return hit.videoId to it
            }
        }
        return null
    }

    /**
     * fork:album-audio — the id of the album's own audio for [track]. YouTube Music sometimes lists a
     * track under its official video's id, and playing that id gives the video's soundtrack (its own
     * edit, an intro, ...) rather than the album version. When YouTube Music links an art track to
     * that video, the art track is the one to play in audio mode. Any failure keeps the listed id.
     */
    private suspend fun albumAudioId(track: MusicTrack): String =
        try {
            MusicMetadataClient.counterpartOf(track.videoId)
                ?.takeIf { it.videoType == MusicVideoType.ArtTrack && it.videoId != track.videoId && it.videoId.length == 11 }
                ?.videoId
                ?.also { log.i { "\"${track.title}\" is listed as a video (${track.videoId}); playing its album audio $it" } }
                ?: track.videoId
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            track.videoId
        }

    /** The album audio, or the listed id when the album audio would not open. */
    private suspend fun openAudio(track: MusicTrack, audioId: String): TrailerPlaybackSource? =
        open(audioId, needsVideo = false)
            ?: if (audioId != track.videoId) open(track.videoId, needsVideo = false) else null

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
        val audioId = albumAudioId(track)
        val source = openAudio(track, audioId)
            ?: return MusicResolution.Failed(track, "the audio track would not open")
        return MusicResolution.Playable(
            track, MusicPlayMode.Video, audioId, source, viaCounterpart = false, lyricVideo = true,
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
            val resolved = resolveSource(videoId) ?: return fallbackSource(videoId, needsVideo)
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
        return fallbackSource(videoId, needsVideo)
    }

    /**
     * fork:innertubex — the second chance, for a track the in-app extractor could not open (an age
     * gate it cannot get past, typically). Audio only: the fallback resolver finds one audio stream,
     * and a video-mode album already degrades to its audio with a lyric picture when no video opens.
     * Null when the build has no fallback resolver (see [MusicStreamFallbacks]).
     */
    private suspend fun fallbackSource(videoId: String, needsVideo: Boolean): TrailerPlaybackSource? {
        if (needsVideo) return null
        val fallback = MusicStreamFallbacks.active ?: return null
        return try {
            fallback.resolve(videoId)?.also { log.i { "$videoId opened through ${fallback.name}" } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "${fallback.name} could not open $videoId: ${error.message}" }
            null
        }
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
