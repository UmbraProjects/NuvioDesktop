package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.preroll.streamUrls
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.sourceOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        // fork:youtube-pause - a track the person chose: it still tries while YouTube is refusing us, but only once (see paused()).
        withContext(YouTubeCaller("music", urgent = true)) {
            when (mode) {
                MusicPlayMode.Audio -> audio(track, album)
                MusicPlayMode.Video -> video(track, album)
            }
        }

    /** True while YouTube is refusing this address: no retries, no hunt for replacement videos, no second client. */
    private fun paused(): Boolean = MusicRateLimit.isBlocked()

    private fun failedReason(): String =
        if (paused()) "YouTube is limiting requests; try again in ${MusicRateLimit.secondsLeft()}s" else "the audio track would not open"

    private suspend fun audio(track: MusicTrack, album: MusicAlbum): MusicResolution {
        val audioId = albumAudioId(track)
        openAudio(track, audioId)?.let {
            return MusicResolution.Playable(track, MusicPlayMode.Audio, audioId, it, viaCounterpart = false)
        }
        alternateUpload(track, album, tried = setOf(audioId, track.videoId))?.let { (id, source) ->
            return MusicResolution.Playable(track, MusicPlayMode.Audio, id, source, viaCounterpart = false)
        }
        return MusicResolution.Failed(track, failedReason())
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
        if (paused()) {
            log.i { "\"${track.title}\": YouTube is refusing requests; not searching for another upload" }
            return null
        }
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
                return hit.videoId to it.copy(evenOutLoudness = true)
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
        // fork:music-home - a picked video heard as audio keeps its own soundtrack (a live cut, a remix), not the album's.
        if (track.isVideo) track.videoId else try {
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
        // fork:music-home - a video the person picked plays as itself: not a linked video or a search hit for its title.
        // When it will not open, the usual hunt below still finds the song's official video or its lyric picture.
        if (track.isVideo) {
            open(track.videoId, needsVideo = true)?.let {
                return MusicResolution.Playable(track, MusicPlayMode.Video, track.videoId, it, viaCounterpart = false)
            }
            log.w { "picked video ${track.videoId} (\"${track.title}\") would not open; looking for its official video" }
        }
        withTimeoutOrNull(VIDEO_LOOKUP_TIMEOUT_MS) { findVideo(track, album) }?.let { return it }
        log.i { "no official video for \"${track.title}\"; playing it as a lyric video" }
        val audioId = albumAudioId(track)
        val source = openAudio(track, audioId)
            ?: return MusicResolution.Failed(track, failedReason())
        return MusicResolution.Playable(
            track, MusicPlayMode.Video, audioId, source, viaCounterpart = false, lyricVideo = true,
        )
    }

    /** The track's linked video or the best official video that opens, else null. */
    private suspend fun findVideo(track: MusicTrack, album: MusicAlbum): MusicResolution? {
        val linked = MusicMetadataClient.counterpartOf(track.videoId)
        if (linked != null) {
            open(linked.videoId, needsVideo = true)?.let {
                return MusicResolution.Playable(track, MusicPlayMode.Video, linked.videoId, it, viaCounterpart = true)
            }
            if (paused()) {
                log.w { "linked video ${linked.videoId} for \"${track.title}\" would not open; YouTube is refusing requests, so not searching" }
                return null
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
        return null
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
    /**
     * fork:youtube-mode - a full-length video with its audio, through the same restricted-client retry and stream
     * probe the music videos get, so a long upload is not handed over on URLs that stop after a minute.
     */
    suspend fun openVideo(videoId: String): TrailerPlaybackSource? {
        openStream(videoId, needsVideo = true)?.let { return it }
        // fork:youtube-age-gate - the in-app extractor cannot open an age-gated video even signed in (its
        // signed-in clients send ciphered URLs it cannot decode); the fallback resolver can.
        val refusal = com.nuvio.app.features.youtube.YouTubeUnplayable.refusal(videoId)
        return if (refusal?.ageGated == true) openAgeGatedVideo(videoId) else null
    }

    /** True when this build has a resolver that can open an age-gated video (the extractor cannot). */
    val canOpenAgeGatedVideos: Boolean get() = MusicStreamFallbacks.active != null

    /**
     * fork:youtube-age-gate - an age-gated video through the fallback resolver alone, for a video already known
     * to be one: the in-app extractor would only be refused again.
     */
    suspend fun openAgeGatedVideo(videoId: String): TrailerPlaybackSource? {
        val fallback = MusicStreamFallbacks.active ?: return null
        return try {
            fallback.resolveVideo(videoId, youTubeCaller()?.maxShortSide)
                ?.also { log.i { "$videoId (age-gated) opened through ${fallback.name}" } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "${fallback.name} could not open age-gated $videoId: ${error.message}" }
            null
        }
    }

    private suspend fun open(videoId: String, needsVideo: Boolean): TrailerPlaybackSource? =
        // fork:loudness - kept under the video's id, which outlives the stream's URL.
        openStream(videoId, needsVideo)?.copy(loudnessKey = "yt:$videoId")?.also { noteLoudness(videoId, it) }

    /**
     * fork:loudness - a stream that came without YouTube's loudness figure (some clients leave it out) and that has not been
     * measured here gets the figure from one more player request, so the track starts at its level instead of a guess.
     */
    private suspend fun noteLoudness(videoId: String, source: TrailerPlaybackSource) {
        if (hasKeptLoudness("yt:$videoId")) return
        val urls = source.streamUrls()
        if (com.nuvio.app.features.preroll.StreamLoudnessBook.lufsFor(*urls.toTypedArray()) != null) return
        val loudnessDb = try {
            withTimeoutOrNull(LOUDNESS_LOOKUP_TIMEOUT_MS) { TrailerPlaybackResolver.loudnessDbOf(videoId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
        com.nuvio.app.features.preroll.StreamLoudnessBook.record(urls, loudnessDb)
        log.i { "$videoId: loudness ${loudnessDb?.let { "$it dB over YouTube's reference, from a second request" } ?: "not given by YouTube either; it will be measured first"}" }
    }

    private const val LOUDNESS_LOOKUP_TIMEOUT_MS = 2_500L

    private suspend fun openStream(videoId: String, needsVideo: Boolean): TrailerPlaybackSource? {
        // fork:potoken-first — the resolver that carries PO tokens goes first for audio, as in BitChord.
        // It is asked at most once per open; when it finds nothing the in-app extractor runs as before.
        val tokenFirst = !needsVideo && MusicStreamFallbacks.active?.preferred == true
        if (tokenFirst) fallbackSource(videoId, needsVideo)?.let { return it }
        repeat(OPEN_ATTEMPTS) { attempt ->
            var resolved = resolveSource(videoId) ?: return fallbackSource(videoId, needsVideo, alreadyAsked = tokenFirst)
            if (paused()) {
                // YouTube is refusing us: a second ask or another client would only lengthen the block.
                return verifiedSource(resolved, needsVideo)
            }
            // A restricted client answered (usually throttling during a burst of lookups): its URLs are cut off and
            // cannot be opened part way in. Asked once more after a pause, as the trailer resolver does.
            if (!resolved.reliable) {
                log.i { "$videoId came back through a restricted client; asking again" }
                delay(RESTRICTED_RETRY_DELAY_MS)
                resolveSource(videoId)?.takeIf { it.reliable }?.let {
                    log.i { "$videoId: the unrestricted client answered on the second ask" }
                    resolved = it
                }
            }
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
        return fallbackSource(videoId, needsVideo, alreadyAsked = tokenFirst)
    }

    /**
     * fork:innertubex — the second chance, for a track the in-app extractor could not open (an age
     * gate it cannot get past, typically). Audio only: the fallback resolver finds one audio stream,
     * and a video-mode album already degrades to its audio with a lyric picture when no video opens.
     * Null when the build has no fallback resolver (see [MusicStreamFallbacks]).
     *
     * fork:potoken-first — while it can mint PO tokens it is also the first ask ([openStream]), and a
     * track the person chose may ask it once while YouTube is refusing the in-app extractor: it is a
     * different, attested identity, and the refusal was given to the extractor's requests. Without
     * tokens it stays out while YouTube is refusing us, as before.
     */
    private suspend fun fallbackSource(
        videoId: String,
        needsVideo: Boolean,
        alreadyAsked: Boolean = false,
    ): TrailerPlaybackSource? {
        if (needsVideo || alreadyAsked) return null
        val fallback = MusicStreamFallbacks.active ?: return null
        if (paused() && !fallback.preferred) return null // the fallback resolver asks YouTube too
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
    private const val RESTRICTED_RETRY_DELAY_MS = 600L

    private suspend fun resolveSource(videoId: String): TrailerPlaybackSource? =
        try {
            val resolution = TrailerPlaybackResolver.resolveFromYouTubeUrl("https://www.youtube.com/watch?v=$videoId")
            // fork:youtube-unplayable - what YouTube said, for the YouTube tab's message and to stop asking again.
            when (resolution) {
                is com.nuvio.app.features.trailer.TrailerResolution.Available ->
                    com.nuvio.app.features.youtube.YouTubeUnplayable.clear(videoId)
                is com.nuvio.app.features.trailer.TrailerResolution.Unavailable ->
                    com.nuvio.app.features.youtube.YouTubeUnplayable.note(videoId, resolution.reason, resolution.youTubeReason)
            }
            resolution.sourceOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
}
