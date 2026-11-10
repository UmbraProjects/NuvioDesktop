package com.nuvio.app.features.player

import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import kotlin.concurrent.Volatile

/**
 * Lets Preload Next Episode Sources warm the link of the stream next-episode autoplay is going to
 * pick, so the attach after the episode ends does not wait on it. On desktop that is the redirect
 * walk the player does before mpv opens a stream (PlaybackRedirectResolver): about a second per
 * episode on an AIOStreams-style addon, all of it before the first frame.
 *
 * Only ordinary URL streams are warmed here. Debrid streams the app resolves itself are already
 * prepared by the prefetch sweep (StreamPrefetchService.prepareTopLink), and torrents and YouTube
 * take paths of their own.
 */
internal object PlaybackSourceWarmup {
    /** Installed by the platform that can warm a link; null means nothing to do. */
    @Volatile
    var warmer: ((url: String, headers: Map<String, String>) -> Unit)? = null

    /** The URL and headers the player would open [stream] with, or null when this cannot warm it. */
    internal fun warmableSource(stream: StreamItem, debridResolves: (StreamItem) -> Boolean): Pair<String, Map<String, String>>? {
        if (stream.needsLocalDebridResolve || debridResolves(stream)) return null
        if (!stream.ytId.isNullOrBlank()) return null
        val url = stream.playableDirectUrl ?: return null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        // The same headers switchToEpisodeStream hands the player, so the warmed entry matches.
        return url to sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    }

    fun warm(stream: StreamItem): Boolean {
        val warm = warmer ?: return false
        val (url, headers) = warmableSource(stream, DirectDebridPlaybackResolver::shouldResolveToPlayableStream)
            ?: return false
        warm(url, headers)
        return true
    }
}
