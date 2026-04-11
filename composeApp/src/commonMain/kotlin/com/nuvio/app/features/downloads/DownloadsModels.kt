package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

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
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = if (isEpisode) {
            buildString {
                append("S")
                append(seasonNumber)
                append("E")
                append(episodeNumber)
                episodeTitle
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" • ")
                        append(it)
                    }
            }
        } else {
            "Movie"
        }

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
) {
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed }

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }
}

enum class DownloadEnqueueResult(
    val toastMessage: String,
) {
    Started("Download started"),
    Replaced("Replaced previous download"),
    MissingUrl("No direct stream link available"),
    UnsupportedFormat("Unsupported stream format for downloads"),
}
