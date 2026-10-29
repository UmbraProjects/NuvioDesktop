package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution {
        if (youtubeUrl.isBlank()) return TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        return extractor.extractPlaybackSource(youtubeUrl)
    }

    actual suspend fun searchYouTube(query: String): List<YouTubeSearchHit> =
        extractor.searchVideos(query)

    /** Forgets the cached YouTube session so the next lookup starts a fresh one. Not part of the shared `expect` surface. */
    internal suspend fun forgetSession() = extractor.forgetSession()

    /** YouTube Music's InnerTube, for the music feature. Not part of the shared `expect` surface. */
    internal suspend fun musicRequest(endpoint: String, params: Map<String, Any?>): String? =
        extractor.musicRequest(endpoint, params)
}
