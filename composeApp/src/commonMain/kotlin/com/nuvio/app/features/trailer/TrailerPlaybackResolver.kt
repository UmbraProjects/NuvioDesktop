package com.nuvio.app.features.trailer

expect object TrailerPlaybackResolver {
    suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution

    /** One URL with video and audio together, for playing a YouTube video as a stream. */
    suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String?
    /** YouTube search results for [query]; empty on any failure. */
    suspend fun searchYouTube(query: String): List<YouTubeSearchHit>

    /**
     * fork:trailer-probe — true only when googlevideo definitely refuses [source]'s own streams (403/404/410 or an
     * error page where media should be). Anything inconclusive (offline, timeout, a manifest) answers false.
     */
    suspend fun streamRefused(source: TrailerPlaybackSource): Boolean
}
