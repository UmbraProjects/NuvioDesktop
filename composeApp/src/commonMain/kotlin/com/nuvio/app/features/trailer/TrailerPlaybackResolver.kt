package com.nuvio.app.features.trailer

expect object TrailerPlaybackResolver {
    suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution

    /** YouTube search results for [query]; empty on any failure. */
    suspend fun searchYouTube(query: String): List<YouTubeSearchHit>
}
