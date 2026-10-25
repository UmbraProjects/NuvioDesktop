package com.nuvio.app.features.streams

import com.nuvio.app.features.trailer.TrailerPlaybackResolver

/**
 * Makes a stream that only carries a YouTube id ([StreamItem.ytId]) playable, by resolving the
 * video on the device with the trailer extractor (upstream 94976222d). The resolved URL expires
 * after a few hours, so callers do not save it for Reuse Last Link, and autoplay never picks such
 * a stream (it has no playable URL until resolved).
 */
object YouTubeStreamResolver {
    /** [stream] unchanged when it needs no resolving, a playable copy, or null when resolving failed. */
    suspend fun resolve(stream: StreamItem): StreamItem? {
        val videoId = stream.youTubeIdToResolve ?: return stream
        val url = TrailerPlaybackResolver.resolveSingleUrlFromYouTubeUrl(watchUrl(videoId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return stream.copy(url = url)
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
