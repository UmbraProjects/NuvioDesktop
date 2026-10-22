package com.nuvio.app.features.streams

import com.nuvio.app.features.player.isPlaybackPlaceholderUrl
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSourceShortcut
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedStreamLink(
    val url: String = "",
    val streamName: String,
    val addonName: String,
    val addonId: String,
    val cachedAtMs: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String> = emptyList(),
    val bingeGroup: String? = null,
    val streamType: String? = null,
    /**
     * Identity of the source as it was listed (before any debrid resolve), so a reused link can
     * still mark its row as playing in the sources panel. The cached URL cannot do that: it is the
     * resolved or re-signed one, which never appears in a fresh listing.
     */
    val sourceIdentityKey: String? = null,
)

internal expect fun epochMs(): Long

object StreamLinkCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun contentKey(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): String {
        val normalizedType = type.lowercase()
        return if (!parentMetaId.isNullOrBlank() && season != null && episode != null) {
            "$normalizedType|${parentMetaId.trim()}|s$season|e$episode|$videoId"
        } else {
            "$normalizedType|$videoId"
        }
    }

    /**
     * Whether a played link is worth caching: Reuse Last Link replays it, and so does Continue
     * Watching's source shortcut when Reuse Last Link is off.
     */
    fun shouldSaveLinks(reuseLastLinkEnabled: Boolean): Boolean =
        reuseLastLinkEnabled ||
            ContinueWatchingPreferencesRepository.uiState.value.sourceShortcut != ContinueWatchingSourceShortcut.OFF

    fun save(
        contentKey: String,
        url: String,
        streamName: String,
        addonName: String,
        addonId: String,
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        filename: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String> = emptyList(),
        bingeGroup: String? = null,
        streamType: String? = null,
        sourceIdentityKey: String? = null,
    ) {
        // A provider's error/status placeholder is the outcome of one failed resolve, not the
        // title's link: caching it would replay "Debrid Rate Limited" on every reopen until the
        // entry aged out, long after the provider recovered. Drop whatever was there too.
        if (url.isNotBlank() && (url.hasLikelyExpiringPlaybackCredentials() || isPlaybackPlaceholderUrl(url))) {
            remove(contentKey)
            return
        }

        val entry = CachedStreamLink(
            url = url,
            streamName = streamName,
            addonName = addonName,
            addonId = addonId,
            cachedAtMs = epochMs(),
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            filename = filename,
            videoSize = videoSize,
            infoHash = infoHash,
            fileIdx = fileIdx,
            sources = sources,
            bingeGroup = bingeGroup,
            streamType = streamType,
            sourceIdentityKey = sourceIdentityKey,
        )
        val payload = json.encodeToString(CachedStreamLink.serializer(), entry)
        StreamLinkCacheStorage.saveEntry(hashedKey(contentKey), payload)
    }

    fun remove(contentKey: String) {
        StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
    }

    fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null
        val raw = StreamLinkCacheStorage.loadEntry(hashedKey(contentKey)) ?: return null
        val entry = runCatching {
            json.decodeFromString(CachedStreamLink.serializer(), raw)
        }.getOrNull() ?: run {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        val age = epochMs() - entry.cachedAtMs
        if (entry.cachedAtMs <= 0L || age > maxAgeMs) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isNotBlank() &&
            (entry.url.hasLikelyExpiringPlaybackCredentials() || isPlaybackPlaceholderUrl(entry.url))
        ) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        if (entry.url.isBlank() && entry.infoHash.isNullOrBlank()) {
            StreamLinkCacheStorage.removeEntry(hashedKey(contentKey))
            return null
        }
        return entry
    }

    private fun hashedKey(contentKey: String): String {
        val hash = contentKey.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "stream_link_$hash"
    }
}
