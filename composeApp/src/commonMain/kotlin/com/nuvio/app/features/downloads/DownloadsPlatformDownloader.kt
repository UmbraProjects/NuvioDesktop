package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    // May be a nested relative path (e.g. "Show (2020)/Season 01/… .mkv") when writing into a
    // structured library layout; the downloader creates any missing parent directories.
    val destinationFileName: String,
    // Absolute local-library base directory. New downloads must never leave this null.
    val destinationDirOverride: String? = null,
    val maximumSizeBytes: Long? = null,
    val usesAutomaticBandwidthLimit: Boolean = false,
)

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        // isTransient distinguishes a server/transport fault (5xx, 429, timeout) from a verdict on
        // the source, so callers can retry the same stream instead of failing over to a worse one.
        onFailure: (message: String, isTransient: Boolean) -> Unit,
    ): DownloadsTaskHandle

    fun updateAutomaticBandwidthLimit(bytesPerSecond: Long?)

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String, destinationDirOverride: String? = null): Boolean

    fun resolveLocalFileUri(
        localFileUri: String?,
        destinationFileName: String,
        destinationDirOverride: String? = null,
    ): String?

    /** Usable free space on the volume backing [destinationDirOverride], or null if unknown. */
    fun usableSpaceBytes(destinationDirOverride: String? = null): Long?
}
