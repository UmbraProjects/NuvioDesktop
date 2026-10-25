package com.nuvio.app.features.trailer

expect object TrailerPlaybackResolver {
    suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution

    /** One URL with video and audio together, for playing a YouTube video as a stream. */
    suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String?
}
