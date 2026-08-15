package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.metadata.migrateLegacyAnimeContentId
import com.nuvio.app.features.metadata.migrateLegacyAnimeVideoId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private var interruptedAutomaticDownloadIds: Set<String> = emptySet()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        interruptedAutomaticDownloadIds = emptySet()
        hasLoaded = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
            destinationDirOverride = item.destinationDir,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        destinationDirOverride: String? = null,
        destinationRelativePath: String? = null,
        expectedSizeBytes: Long? = null,
        bandwidthLimitMbps: Int? = null,
        maximumSizeBytes: Long? = null,
        isAutomaticDownload: Boolean = false,
        preserveExistingFileUntilSuccess: Boolean = false,
        /** Persist the item as paused without opening the transfer yet (used for a visible queue). */
        startPaused: Boolean = false,
    ): DownloadEnqueueResult {
        ensureLoaded()

        // Downloads are library-managed files. Never silently fall back to the app-data downloads
        // directory: callers without a configured library destination must hand the URL to the
        // browser instead.
        val destinationDir = destinationDirOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: return DownloadEnqueueResult.MissingDestination

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        if (!stream.isSafeVideoDownloadCandidate(sourceUrl)) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        if (
            expectedSizeBytes != null &&
            maximumSizeBytes != null &&
            expectedSizeBytes > maximumSizeBytes
        ) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        destinationRelativePath?.let { path ->
            if (path.substringAfterLast('.', "").lowercase() !in SAFE_VIDEO_DOWNLOAD_EXTENSIONS) {
                return DownloadEnqueueResult.UnsupportedFormat
            }
        }

        // Disk-space preflight (only when the stream advertised a size): fail cleanly up front
        // rather than part-way through a large file. 200 MB of slack covers container overhead.
        if (expectedSizeBytes != null && expectedSizeBytes > 0L) {
            val usable = DownloadsPlatformDownloader.usableSpaceBytes(destinationDir)
            if (usable != null && usable < expectedSizeBytes + DISK_SPACE_SLACK_BYTES) {
                return DownloadEnqueueResult.InsufficientSpace
            }
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = downloadLogicalContentKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            if (!preserveExistingFileUntilSuccess) {
                DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            }
            DownloadsPlatformDownloader.removePartialFile(existing.fileName, existing.destinationDir)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = destinationRelativePath?.trim()?.takeIf { it.isNotBlank() }
            ?: buildFileName(
                title = title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                fallbackTitle = stream.streamLabel,
                sourceUrl = sourceUrl,
                nowEpochMs = now,
            )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            localFileUri = null,
            fileName = fileName,
            destinationDir = destinationDir,
            bandwidthLimitMbps = bandwidthLimitMbps?.takeIf { it > 0 },
            maximumSizeBytes = maximumSizeBytes?.takeIf { it > 0L },
            isAutomaticDownload = isAutomaticDownload,
            status = if (startPaused) DownloadStatus.Paused else DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        if (!startPaused) startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                bytesPerSecond = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            bytesPerSecond = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    /**
     * Drops failed automatic-download rows and returns their ids. These are what "Clear activity" in
     * Library Downloads needs gone: a failed automatic attempt lingers here after its grab record is
     * cleared and re-surfaces as an orphaned activity row, so clearing grab history alone never
     * empties the list. Live transfers and completed library files are untouched — only the failed
     * attempts and any partial file they left behind are removed.
     */
    fun clearFailedAutomaticDownloads(): Set<String> {
        ensureLoaded()
        val doomed = _uiState.value.items.filter {
            it.isAutomaticDownload && it.status == DownloadStatus.Failed
        }
        if (doomed.isEmpty()) return emptySet()
        doomed.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            DownloadsPlatformDownloader.removePartialFile(item.fileName, item.destinationDir)
        }
        val doomedIds = doomed.mapTo(hashSetOf()) { it.id }
        publish(_uiState.value.items.filterNot { it.id in doomedIds })
        persist()
        return doomedIds
    }

    /** Pauses active automatic transfers and returns exactly the ids changed by this call. */
    fun pauseAutomaticDownloadsForPlayback(): Set<String> {
        ensureLoaded()
        val ids = _uiState.value.items
            .filter {
                it.status == DownloadStatus.Downloading &&
                    (it.isAutomaticDownload || it.destinationDir != null)
            }
            .mapTo(linkedSetOf()) { it.id }
        ids.forEach(::pauseDownload)
        return ids
    }

    /** Resumes only transfers previously paused by the playback coordinator. */
    fun resumeDownloads(downloadIds: Set<String>) {
        ensureLoaded()
        downloadIds.forEach { id ->
            if (_uiState.value.items.any { it.id == id && it.status == DownloadStatus.Paused }) {
                resumeDownload(id)
            }
        }
    }

    /** Restarts automatic transfers that were active when the previous app process stopped. */
    fun resumeInterruptedAutomaticDownloads() {
        ensureLoaded()
        val ids = interruptedAutomaticDownloadIds
        interruptedAutomaticDownloadIds = emptySet()
        resumeDownloads(ids)
    }

    fun updateAutomaticBandwidthLimit(megabitsPerSecond: Int) {
        val bytesPerSecond = megabitsPerSecond
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(1_000_000L)
            ?.div(8L)
        DownloadsPlatformDownloader.updateAutomaticBandwidthLimit(bytesPerSecond)

        val now = DownloadsClock.nowEpochMs()
        publish(
            _uiState.value.items.map { item ->
                if (
                    item.status == DownloadStatus.Downloading &&
                    (item.isAutomaticDownload || item.destinationDir != null)
                ) {
                    item.copy(bytesPerSecond = null, updatedAtEpochMs = now)
                } else {
                    item
                }
            },
        )
    }

    fun cancelDownload(downloadId: String) {
        cancelDownloads(setOf(downloadId))
    }

    fun cancelDownloads(downloadIds: Set<String>) {
        ensureLoaded()
        if (downloadIds.isEmpty()) return
        val itemsToCancel = _uiState.value.items.filter { it.id in downloadIds }
        if (itemsToCancel.isEmpty()) return

        itemsToCancel.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName, item.destinationDir)
        }

        publish(_uiState.value.items.filterNot { it.id in downloadIds })
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            interruptedAutomaticDownloadIds = emptySet()
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val decoded = DownloadsCodec.decodeItems(payload)
        interruptedAutomaticDownloadIds = decoded
            .filter {
                it.status == DownloadStatus.Downloading &&
                    (it.isAutomaticDownload || it.destinationDir != null)
            }
            .mapTo(mutableSetOf()) { it.id }
        val normalized = decoded
            .map { item ->
                val statusNormalized = if (item.status == DownloadStatus.Downloading) {
                    item.copy(
                        status = DownloadStatus.Paused,
                        bytesPerSecond = null,
                        errorMessage = null,
                    )
                } else {
                    item
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                val idNormalized = localUriNormalized.withMigratedAnimeIds()
                if (idNormalized != item) {
                    shouldPersistNormalized = true
                }
                idNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
    }

    /**
     * Rewrites ids written under the old kitsu-first anime policy.
     *
     * `parentMetaId` is matched by equality against `MonitoredItem.contentId`, which migrates on its
     * own load — leaving these behind would break the link between a monitor and the downloads it
     * already produced. `videoId` additionally carries entry-local episode coordinates, so the
     * migration converts those rather than only swapping the base: a franchise id paired with an
     * unconverted `:1:1` means the first season's first episode, not this entry's.
     */
    private fun DownloadItem.withMigratedAnimeIds(): DownloadItem {
        val migratedParent = migrateLegacyAnimeContentId(parentMetaId, parentMetaType)
            ?.takeIf { it != parentMetaId }
        val migratedVideo = migrateLegacyAnimeVideoId(videoId, parentMetaType)
            ?.takeIf { it != videoId }
        if (migratedParent == null && migratedVideo == null) return this
        return copy(
            parentMetaId = migratedParent ?: parentMetaId,
            videoId = migratedVideo ?: videoId,
        )
    }

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(
            sourceUrl = item.sourceUrl,
            sourceHeaders = item.sourceHeaders,
            destinationFileName = item.fileName,
            destinationDirOverride = item.destinationDir,
            maximumSizeBytes = item.maximumSizeBytes,
            // destinationDir is the compatibility marker for automatic downloads created before
            // isAutomaticDownload was persisted.
            usesAutomaticBandwidthLimit = item.isAutomaticDownload || item.destinationDir != null,
        )

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id, persistChange = false) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        val now = DownloadsClock.nowEpochMs()
                        val elapsedMs = now - current.updatedAtEpochMs
                        val byteDelta = downloadedBytes - current.downloadedBytes
                        val sample = if (elapsedMs > 0L && byteDelta > 0L) {
                            (byteDelta * 1_000L / elapsedMs).coerceAtLeast(1L)
                        } else {
                            null
                        }
                        val speed = sample?.let { latest ->
                            current.bytesPerSecond?.let { previous ->
                                (previous * 3L + latest) / 4L
                            } ?: latest
                        } ?: current.bytesPerSecond
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            bytesPerSecond = speed,
                            updatedAtEpochMs = now,
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        bytesPerSecond = null,
                        errorMessage = null,
                        failureIsTransient = false,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                if (item.destinationDir != null) {
                    LocalLibraryRepository.rescan()
                }
            },
            onFailure = { message, isTransient ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            bytesPerSecond = null,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            failureIsTransient = isTransient,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(
        downloadId: String,
        persistChange: Boolean = true,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            if (persistChange) persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
            destinationDirOverride = item.destinationDir,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
                destinationDirOverride = destinationDir,
            ) != null

    private const val DISK_SPACE_SLACK_BYTES = 200L * 1024L * 1024L
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    nowEpochMs: Long,
): String {
    val baseTitle = if (episodeNumber != null) {
        buildString {
            append(title)
            if (seasonNumber != null) {
                append(" S")
                append(seasonNumber.toString().padStart(2, '0'))
                append('E')
            } else {
                append(" E")
            }
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(nowEpochMs.toString(36))
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix in SAFE_VIDEO_DOWNLOAD_EXTENSIONS) {
        suffix
    } else {
        "mp4"
    }
}

internal fun StreamItem.isSafeVideoDownloadCandidate(
    sourceUrl: String,
    requireSafeVideoReference: Boolean = false,
): Boolean {
    val advertisedNames = listOfNotNull(
        behaviorHints.filename,
        clientResolve?.filename,
        debridCacheStatus?.cachedName,
        sourceUrl.substringBefore('?').substringBefore('#').substringAfterLast('/'),
        sourceUrl,
        behaviorHints.proxyHeaders?.response
            ?.entries
            ?.firstOrNull { (key, _) -> key.equals("Content-Disposition", ignoreCase = true) }
            ?.value,
    )
    if (advertisedNames.any(String::hasExecutableDownloadExtension)) return false
    return !requireSafeVideoReference ||
        advertisedNames.any(String::isSafeVideoDownloadReference)
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
