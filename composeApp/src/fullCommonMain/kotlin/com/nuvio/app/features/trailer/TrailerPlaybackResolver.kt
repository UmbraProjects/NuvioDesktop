package com.nuvio.app.features.trailer

import kotlinx.coroutines.async

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerResolution {
        if (youtubeUrl.isBlank()) return TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        return extractor.extractPlaybackSource(youtubeUrl)
    }

    actual suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String? {
        if (youtubeUrl.isBlank()) return null
        return extractor.extractSingleUrl(youtubeUrl)
    }
    actual suspend fun searchYouTube(query: String): List<YouTubeSearchHit> =
        extractor.searchVideos(query)

    // fork:trailer-probe — the music resolver's BitChord-style probe, reused for trailers.
    actual suspend fun streamRefused(source: TrailerPlaybackSource): Boolean = kotlinx.coroutines.coroutineScope {
        com.nuvio.app.features.music.urlsToVerify(source, needsVideo = true)
            .map { url -> async { com.nuvio.app.features.music.StreamProbe.probe(url) } }
            .map { it.await() }
            .any { it == com.nuvio.app.features.music.ProbeVerdict.Refused }
    }

    actual suspend fun loudnessDbOf(videoId: String): Double? = extractor.loudnessDbOf(videoId)

    /** Forgets the cached YouTube session so the next lookup starts a fresh one. Not part of the shared `expect` surface. */
    internal suspend fun forgetSession() = extractor.forgetSession()

    /** fork:innertubex — the session's visitor data, for resolvers that make their own InnerTube requests. */
    internal suspend fun visitorData(): String? = extractor.innerTubeSession().visitorData

    /** fork:youtube-mode - youtube.com's InnerTube as the WEB client, for the YouTube tab. Not part of the shared `expect` surface. */
    internal suspend fun webRequest(
        endpoint: String,
        params: Map<String, Any?>,
        accountHeaders: Map<String, String>,
        pageId: String?,
        shell: com.nuvio.app.features.youtube.YouTubeWebShell?,
    ): TrailerRequestResponse = extractor.webRequest(endpoint, params, accountHeaders, pageId, shell)

    /** YouTube Music's InnerTube, for the music feature. Not part of the shared `expect` surface. */
    internal suspend fun musicRequest(endpoint: String, params: Map<String, Any?>): String? =
        extractor.musicRequest(endpoint, params)

    /** fork:music-account - [musicRequest] as the signed-in YouTube account; null when nobody is signed in. */
    internal suspend fun musicRequestSigned(endpoint: String, params: Map<String, Any?>): String? =
        extractor.musicRequestSigned(endpoint, params)
}
