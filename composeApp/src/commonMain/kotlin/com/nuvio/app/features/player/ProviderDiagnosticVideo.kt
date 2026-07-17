package com.nuvio.app.features.player

internal data class ProviderDiagnosticVideo(
    val sourceUrl: String,
)

/**
 * Recognises URLs that explicitly describe themselves as a generated error/status video.
 * This is intentionally provider-neutral: AIOStreams-style `title`/`body` URLs and ordinary
 * `/error.*` or `/status.*` assets should not need an addon-specific allow-list.
 */
internal fun isExplicitProviderDiagnosticVideoUrl(sourceUrl: String): Boolean {
    val normalized = sourceUrl.trim().lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false

    val path = normalized.substringBefore('?')
    val query = normalized.substringAfter('?', missingDelimiterValue = "")
    val hasMessageQuery =
        ("title=" in query && ("body=" in query || "message=" in query)) ||
            "error=" in query
    val hasDiagnosticPath = listOf("/error.", "/error/", "/status.", "/status/", "/failed.")
        .any(path::contains)
    val isStremThruStaticStatusVideo = "/store/_/static/" in path && path.endsWith(".mp4")
    return hasMessageQuery || hasDiagnosticPath || isStremThruStaticStatusVideo
}

/**
 * Playback endpoints known to resolve dynamically to either full media or a small status video.
 * The URL shapes are protocol-level signatures; no addon hostname allow-list is required.
 */
internal fun isProviderPlaybackEndpoint(sourceUrl: String): Boolean {
    val normalized = sourceUrl.trim().lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
    val path = normalized.substringBefore('?')
    val query = normalized.substringAfter('?', missingDelimiterValue = "")
    val isCometPlayback = "/playback/" in path && "torrent_name=" in query && "name=" in query
    val isStremThruStorePlayback =
        (("/stremio/wrap/" in path || "/stremio/torz/" in path) && "/_/strem/" in path) ||
            ("/stremio/newz/" in path && "/playback/" in path)
    return isCometPlayback || isStremThruStorePlayback
}

/**
 * Resolves a response only when it is a complete, small, verified video. It is used as a narrow
 * provider-endpoint probe and after major startup failures; returned media must never scrobble.
 */
internal expect suspend fun resolveProviderDiagnosticVideo(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
): ProviderDiagnosticVideo?

internal expect fun releaseProviderDiagnosticVideo(sourceUrl: String)

internal fun shouldSkipProviderDiagnosticVideo(streamFailoverEnabled: Boolean): Boolean =
    streamFailoverEnabled

internal fun PlayerScreenRuntime.activateProviderDiagnosticVideo(diagnostic: ProviderDiagnosticVideo) {
    removeFailedStreamFromCache()
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration += 1L
    pendingScrobbleStartAfterSeek = false
    currentTraktScrobbleItem = null
    providerDiagnosticVideoSourceUrl = diagnostic.sourceUrl
    providerDiagnosticProbePendingSourceUrl = null
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    shouldPlay = true
    errorMessage = null
    activeSourceUrl = diagnostic.sourceUrl
}
