package com.nuvio.app.features.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Desktop counterpart of the per-variant [TrailerExtractionPlatform] objects used by
 * [InAppYouTubeExtractor]. Backed by the JDK HTTP client, mirroring the rest of the
 * desktop networking layer.
 *
 * Desktop playback can use separate adaptive video/audio tracks. Windows presents them to
 * mpv as one multi-stream EDL input; macOS attaches the audio track during initialization.
 */
internal object TrailerExtractionPlatform {
    private val isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

    // Windows is primarily used on 4K TVs and desktop monitors. Prefer 1440p as the
    // quality/performance sweet spot, then use 4K before falling back to 1080p.
    val preferredSeparateVideoHeights: List<Int> =
        if (isWindows) listOf(1440, 2160, 1080) else listOf(1080)
    val preferSeparateVideoClient: Boolean = !isWindows

    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val normalizedMethod = method.trim().uppercase().ifBlank { "GET" }
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofMillis(timeoutMillis))
            .method(
                normalizedMethod,
                if (normalizedMethod == "GET" || normalizedMethod == "HEAD") {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofString(body.orEmpty())
                },
            )

        headers.forEach { (key, value) ->
            // The JDK client manages Accept-Encoding itself and rejects attempts to set it.
            if (key.isNotBlank() && value.isNotBlank() && !key.equals("Accept-Encoding", ignoreCase = true)) {
                requestBuilder.header(key, value)
            }
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        TrailerRequestResponse(
            ok = status in 200..299,
            status = status,
            statusText = "HTTP $status",
            url = response.uri().toString(),
            body = response.body().orEmpty(),
        )
    }

    fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? {
        // Prefer adaptive video/audio so trailers can use the selected 1080p rendition.
        // The platform player combines these at initial load rather than attaching audio
        // after playback starts.
        if (bestVideo != null && bestAudio != null) {
            return TrailerPlaybackSource(videoUrl = bestVideo.url, audioUrl = bestAudio.url)
        }

        val manifestUrl = bestManifest?.manifestUrl
        val progressiveUrl = bestProgressive?.url
        val videoUrl = manifestUrl ?: progressiveUrl
        return videoUrl?.let { TrailerPlaybackSource(videoUrl = it, audioUrl = null) }
            ?: bestVideo?.url?.let { TrailerPlaybackSource(videoUrl = it, audioUrl = null) }
    }
}
