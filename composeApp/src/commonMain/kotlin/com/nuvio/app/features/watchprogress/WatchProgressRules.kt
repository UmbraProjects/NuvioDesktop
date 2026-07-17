package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.DefaultContinueWatchingLimit
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.continueWatchingProgressEntries
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watching.domain.resumeProgressForSeries
import com.nuvio.app.features.watching.domain.shouldStoreProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ContinueWatchingLimit = DefaultContinueWatchingLimit

@Serializable
internal data class StoredWatchProgressPayload(
    val entries: List<WatchProgressEntry> = emptyList(),
    val lastSuccessfulPushEpochMs: Long = 0L,
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
)

internal object WatchProgressCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEntries(payload: String): List<WatchProgressEntry> =
        decodePayload(payload).entries

    fun decodePayload(payload: String): StoredWatchProgressPayload =
        runCatching {
            json.decodeFromString<StoredWatchProgressPayload>(payload).let { storedPayload ->
                storedPayload.copy(
                    entries = storedPayload.entries.map(WatchProgressEntry::normalizedCompletion),
                )
            }
        }.getOrDefault(StoredWatchProgressPayload())

    fun encodeEntries(entries: Collection<WatchProgressEntry>): String =
        encodePayload(
            entries = entries,
            lastSuccessfulPushEpochMs = 0L,
            deltaCursorEventId = 0L,
            deltaInitialized = false,
        )

    fun encodePayload(
        entries: Collection<WatchProgressEntry>,
        lastSuccessfulPushEpochMs: Long,
        deltaCursorEventId: Long,
        deltaInitialized: Boolean,
    ): String =
        json.encodeToString(
            StoredWatchProgressPayload(
                entries = entries.toList().sortedByDescending { it.lastUpdatedEpochMs },
                lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                deltaCursorEventId = deltaCursorEventId,
                deltaInitialized = deltaInitialized,
            ),
        )
}

internal fun shouldStoreWatchProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean = shouldStoreProgress(positionMs = positionMs, durationMs = durationMs)

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean = isProgressComplete(
    positionMs = positionMs,
    durationMs = durationMs,
    isEnded = isEnded,
)

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? =
    firstOrNull { entry -> entry.parentMetaId == metaId }?.let { seed ->
        resumeProgressForSeries(
            content = WatchingContentRef(type = seed.parentMetaType, id = metaId),
            progressRecords = map(WatchProgressEntry::toDomainProgressRecord),
        )?.let { record ->
            firstOrNull { entry -> entry.videoId == record.videoId }
        }
    }

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> {
    val inProgressEntries = filter { entry -> entry.shouldTreatAsInProgressForContinueWatching() }
    val domainEntries = continueWatchingProgressEntries(
        progressRecords = inProgressEntries.map(WatchProgressEntry::toDomainProgressRecord),
        limit = limit,
    )
    val ids = domainEntries.map { record -> record.videoId }.toSet()
    return inProgressEntries.filter { entry -> entry.videoId in ids }
        .sortedByDescending { it.lastUpdatedEpochMs }
}

internal fun WatchProgressEntry.shouldTreatAsInProgressForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (entry.isEffectivelyCompleted) return false

    val hasStartedPlayback = entry.lastPositionMs > 0L ||
        entry.normalizedProgressPercent?.let { it > 0f } == true
    if (!hasStartedPlayback) return false

    return entry.source != WatchProgressSourceTraktHistory &&
        entry.source != WatchProgressSourceTraktShowProgress
}

internal fun WatchProgressEntry.shouldUseAsCompletedSeedForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (isMalformedNextUpSeedContentId(entry.parentMetaId)) return false
    if (!entry.isEffectivelyCompleted) return false
    if (entry.source != WatchProgressSourceTraktPlayback) return true

    val explicitPercent = entry.normalizedProgressPercent ?: return false
    return explicitPercent >= WatchProgressTraktPlaybackNextUpSeedPercentThreshold
}

internal fun shouldCascadeCompletedProgressToWatchedHistory(
    entry: WatchProgressEntry,
    isUsingTraktProgress: Boolean,
): Boolean = !isUsingTraktProgress && entry.normalizedCompletion().isCompleted

internal fun String?.isSeriesTypeForContinueWatching(): Boolean =
    equals("series", ignoreCase = true) || equals("tv", ignoreCase = true)

private val nativeAnimeContentIdPrefixes = listOf(
    "kitsu:", "mal:", "myanimelist:", "al:", "anilist:", "anidb:",
)

internal fun String.isNativeAnimeContentId(): Boolean =
    nativeAnimeContentIdPrefixes.any { prefix -> startsWith(prefix, ignoreCase = true) }

/**
 * Native-anime playback can be launched from surfaces that number episodes in franchise
 * coordinates (TVDB/Trakt-style S16E64) while the kitsu/mal details meta numbers the same video
 * entry-relative (S1E76). The episode's own video id (`<parent>:<ep>` or `<parent>:<s>:<e>`)
 * always carries the entry-relative coordinates, so trust it over the session's season/episode —
 * otherwise watched marks and Continue Watching land in a coordinate space the details page
 * never checks. Video ids under a different parent (franchise-mapped sibling entries) are left
 * untouched: their episode numbers belong to the sibling's space, not this parent's.
 */
internal fun resolveNativeAnimeEpisodeCoordinates(
    parentMetaId: String,
    videoId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): Pair<Int?, Int?> {
    val parent = parentMetaId.trim()
    if (!parent.isNativeAnimeContentId()) return seasonNumber to episodeNumber
    val normalizedVideoId = videoId.trim()
    if (!normalizedVideoId.startsWith("$parent:")) return seasonNumber to episodeNumber
    val parts = normalizedVideoId.removePrefix("$parent:").split(":")
    return when (parts.size) {
        // Absolute-numbered entry episode: kitsu entries expose these as season 1 on details.
        1 -> parts[0].toIntOrNull()?.let { episode -> 1 to episode }
            ?: (seasonNumber to episodeNumber)
        2 -> {
            val season = parts[0].toIntOrNull()
            val episode = parts[1].toIntOrNull()
            if (season != null && episode != null) season to episode else seasonNumber to episodeNumber
        }
        else -> seasonNumber to episodeNumber
    }
}

internal fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase()) {
        "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
        else -> false
    }
}

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    normalizedCompletion().let { entry ->
        WatchingProgressRecord(
        content = WatchingContentRef(
            type = entry.parentMetaType,
            id = entry.parentMetaId,
        ),
        videoId = entry.videoId,
        seasonNumber = entry.seasonNumber,
        episodeNumber = entry.episodeNumber,
        lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
        lastPositionMs = entry.lastPositionMs,
        isCompleted = entry.isCompleted,
        episodeTitle = entry.episodeTitle,
        episodeThumbnail = entry.episodeThumbnail,
    )
    }
