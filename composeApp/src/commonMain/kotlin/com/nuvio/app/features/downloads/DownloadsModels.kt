package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_insufficient_space
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_destination
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadStatus {
    Downloading,
    Paused,
    Completed,
    Failed,
}

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val localFileUri: String? = null,
    val fileName: String,
    // Absolute local-library base directory. Null is retained only for legacy persisted entries;
    // newly enqueued downloads require a configured destination.
    val destinationDir: String? = null,
    /** Aggregate automatic-download bandwidth cap in megabits/sec; null means unlimited. */
    val bandwidthLimitMbps: Int? = null,
    val maximumSizeBytes: Long? = null,
    val isAutomaticDownload: Boolean = false,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val errorMessage: String? = null,
    /**
     * True when the failure was a transport/server fault (5xx, 429, timeout, truncated body) rather
     * than a verdict on the source itself. The library scheduler retries these against the *same*
     * stream instead of blacklisting it and falling back to a worse release.
     */
    val failureIsTransient: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isEpisode: Boolean
        // Anime-native entries use absolute episode numbers with no season coordinate.
        get() = episodeNumber != null

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = downloadLogicalContentKey(parentMetaId, seasonNumber, episodeNumber)
}

/**
 * Stable destination identity used both before enqueue and by persisted [DownloadItem] records.
 *
 * A null season does not imply a movie: Kitsu/MAL/AniList entries address episodes absolutely as
 * `<entry>:<episode>`. Keeping that episode in the key prevents each paused pack row from replacing
 * the previous one before the queue starts.
 */
internal fun downloadLogicalContentKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = when {
    episodeNumber != null && seasonNumber != null ->
        "${parentMetaId.trim()}|season|$seasonNumber|episode|$episodeNumber"
    episodeNumber != null ->
        "${parentMetaId.trim()}|entry|episode|$episodeNumber"
    else ->
        "${parentMetaId.trim()}|movie"
}

internal val SAFE_VIDEO_DOWNLOAD_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
    "mpg", "mpeg", "ts", "m2ts", "mts", "vob", "ogv", "3gp",
    "rm", "rmvb", "divx", "xvid", "asf", "f4v", "m2v",
)

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
) {
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed }

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }
}

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    MissingDestination,
    UnsupportedFormat,
    InsufficientSpace;

    val isSuccess: Boolean
        get() = this == Started || this == Replaced

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            MissingDestination -> getString(Res.string.downloads_enqueue_missing_destination)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
            InsufficientSpace -> getString(Res.string.downloads_enqueue_insufficient_space)
        }
    }
}

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.title.trim().lowercase() }
        .thenBy { it.id }
