package com.nuvio.app.features.player

/**
 * Desktop implementation: returns subtitles unchanged (no caching needed).
 * Desktop external players accept remote subtitle URLs directly, so we just
 * pass through the original HTTP URLs without downloading.
 */
actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        return subtitles.ifEmpty { null }
    }
}
