package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution {
        if (youtubeUrl.isBlank()) return TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        return extractor.extractPlaybackSource(youtubeUrl)
    }
}
