package com.nuvio.app.features.trailer

expect object TrailerPlaybackResolver {
    suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution

    /**
     * fork:trailer-signed-in - [resolveFromYouTubeUrl] for a trailer (Home and details heroes, the pre-show, Tonight).
     * When YouTube gates the video behind a sign-in ("Sign in to confirm you're not a bot", an age gate), it is
     * opened the way the YouTube tab opens such a video: the signed-in resolver with PO tokens. Music and the
     * YouTube tab keep calling [resolveFromYouTubeUrl]; their route is unchanged.
     */
    suspend fun resolveTrailerFromYouTubeUrl(youtubeUrl: String): TrailerResolution

    /** One URL with video and audio together, for playing a YouTube video as a stream. */
    suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String?
    /** YouTube search results for [query]; empty on any failure. */
    suspend fun searchYouTube(query: String): List<YouTubeSearchHit>

    /**
     * fork:trailer-probe — true only when googlevideo definitely refuses [source]'s own streams (403/404/410 or an
     * error page where media should be). Anything inconclusive (offline, timeout, a manifest) answers false.
     */
    suspend fun streamRefused(source: TrailerPlaybackSource): Boolean

    /**
     * fork:loudness - YouTube's `loudnessDb` for [videoId] (how far above its -14 LUFS reference the song is) from one player
     * request, or null when it sent none or could not be asked. For the same song played from elsewhere (the music addon),
     * so its gain can be right from the first second.
     */
    suspend fun loudnessDbOf(videoId: String): Double?
}
