package com.nuvio.app.features.librarypvr

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSource
import com.nuvio.app.features.debrid.DebridSourceFile
import com.nuvio.app.features.debrid.DebridSourceInspector
import com.nuvio.app.features.debrid.DebridSourceResult
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The manual half of library auto-download: instead of searching addons for an episode, the user
 * pastes a link to the exact source they want. Everything downstream of source selection is the
 * scheduler's pipeline — same grab history, same Plex-style naming, same completion watcher that
 * files the finished transfer into the local library.
 *
 * Downloads are promoted one at a time within [LibraryPvrSettings.maxConcurrentDownloads]: a season
 * pack is routinely 20+ files and [DownloadsRepository] starts every enqueued item immediately.
 */
internal object ManualGrabService {

    private val log = Logger.withTag("ManualGrabService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val SLOT_POLL_MS = 2_000L
    private const val ADDON_ID = "manual-link"

    /** Resolves a pasted link into an editable download plan. */
    suspend fun inspect(link: String, item: MonitoredItem): ManualInspectResult {
        val parsed = ManualLinkParser.parse(link) ?: return ManualInspectResult.InvalidLink
        // The user just pasted this link to grab from it, so an uncached magnet is added rather than
        // rejected — refusing it made the paste look broken when the answer was "not cached yet".
        return when (val result = DebridSourceInspector.inspect(parsed.magnetUri, allowUncached = true)) {
            is DebridSourceResult.Success -> {
                val source = result.source
                val sourceName = source.name?.takeIf { it.isNotBlank() } ?: parsed.displayName
                val rows = ManualGrabPlanner.plan(
                    files = source.files.map { it.toSourceFile() },
                    sourceName = sourceName,
                    item = item,
                )
                if (rows.isEmpty()) {
                    ManualInspectResult.NoVideoFiles
                } else {
                    ManualInspectResult.Success(
                        ManualGrabSession(source = source, sourceName = sourceName, rows = rows),
                    )
                }
            }
            DebridSourceResult.MissingProvider -> ManualInspectResult.MissingProvider
            DebridSourceResult.NotCached -> ManualInspectResult.NotCached
            is DebridSourceResult.Failed -> ManualInspectResult.Failed(result.message)
        }
    }

    /**
     * Records every selected row as a queued grab (so the activity list shows the whole plan at
     * once) and starts promoting them in the background. Returns how many were queued.
     */
    fun enqueue(session: ManualGrabSession, rows: List<ManualGrabRow>, item: MonitoredItem): Int {
        val selected = rows.filter { it.included && it.hasRequiredCoordinates(item) }
        if (selected.isEmpty()) return 0
        val folder = LocalLibraryRepository.uiState.value.folders
            .firstOrNull { it.id == item.targetFolderId }
            ?: return 0

        val pending = selected.mapNotNull { row ->
            val file = session.source.files.firstOrNull { it.id == row.fileId } ?: return@mapNotNull null
            PendingManualGrab(grabId = LibraryPvrRepository.newGrabId(), row = row, file = file)
        }

        // Recording a grab persists the history file, so keep the whole batch off the UI thread.
        scope.launch {
            pending.forEach { entry ->
                val now = TraktPlatformClock.nowEpochMs()
                LibraryPvrRepository.upsertGrab(
                    GrabRecord(
                        id = entry.grabId,
                        monitoredItemId = item.id,
                        title = item.title,
                        season = entry.row.season,
                        episode = entry.row.episode,
                        episodeTitle = entry.row.episodeTitle,
                        status = GrabStatus.QUEUED,
                        streamLabel = entry.row.fileName,
                        // Manual grabs exist to correct a bad automatic pick, so they supersede
                        // whatever is already filed under the same coordinates.
                        replaceExistingFile = true,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
            }
            drain(session.source, pending, item, folder)
        }
        return pending.size
    }

    private suspend fun drain(
        source: DebridSource,
        pending: List<PendingManualGrab>,
        item: MonitoredItem,
        folder: LocalFolder,
    ) {
        for (entry in pending) {
            if (!awaitDownloadSlot(entry.grabId)) continue
            runCatching { start(source, entry, item, folder) }
                .onFailure { error ->
                    log.w(error) { "manual grab failed for ${entry.row.fileName}" }
                    fail(entry.grabId, error.message ?: "Manual grab failed")
                }
        }
    }

    /**
     * Waits for a free transfer slot. Returns false when the grab is no longer waiting to start —
     * the user cancelled it or cleared the activity list.
     */
    private suspend fun awaitDownloadSlot(grabId: String): Boolean {
        while (true) {
            val stillQueued = LibraryPvrRepository.uiState.value.grabHistory
                .firstOrNull { it.id == grabId }
                ?.status == GrabStatus.QUEUED
            if (!stillQueued) return false
            val limit = LibraryPvrRepository.currentSettings().maxConcurrentDownloads.coerceIn(1, 4)
            val active = DownloadsRepository.uiState.value.items.count {
                it.status == DownloadStatus.Downloading
            }
            if (active < limit) return true
            delay(SLOT_POLL_MS)
        }
    }

    private suspend fun start(
        source: DebridSource,
        entry: PendingManualGrab,
        item: MonitoredItem,
        folder: LocalFolder,
    ) {
        val link = DebridSourceInspector.resolveLink(source, entry.file)
        if (link == null) {
            fail(entry.grabId, "${source.providerName} did not return a download link")
            return
        }

        val row = entry.row
        val extension = link.filename?.videoExtension()
            ?: row.fileName.videoExtension()
            ?: "mkv"
        val season = row.season
        val episode = row.episode
        // Land beside whatever this title already occupies in the folder — see
        // LibraryDestinationFolders for why the built name alone is not dependable.
        val existingFolderNames = LibraryDestinationFolders.existingFolderNames(
            folder = folder,
            contentId = item.contentId,
        )
        val relativePath = when {
            item.isMovie -> LibraryFileNaming.movieRelativePath(
                item.title,
                item.year,
                extension,
                existingFolderNames,
            )
            item.isAnime && season == null ->
                LibraryFileNaming.animeEpisodeRelativePath(
                    item.title,
                    item.year,
                    episode ?: return,
                    extension,
                    existingFolderNames,
                )
            else -> LibraryFileNaming.episodeRelativePath(
                title = item.title,
                year = item.year,
                season = season ?: return,
                episode = episode ?: return,
                episodeTitle = row.episodeTitle,
                extension = extension,
                existingFolderNames = existingFolderNames,
            )
        }
        val videoId = when {
            item.isMovie -> item.contentId
            season == null -> "${item.contentId}:$episode"
            else -> "${item.contentId}:$season:$episode"
        }

        val stream = StreamItem(
            name = source.providerName,
            title = row.fileName,
            url = link.url,
            addonName = source.providerName,
            addonId = ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                filename = link.filename ?: row.fileName,
                videoSize = link.sizeBytes ?: row.sizeBytes,
            ),
        )

        val result = DownloadsRepository.enqueueFromStream(
            contentType = item.contentType,
            videoId = videoId,
            parentMetaId = item.contentId,
            parentMetaType = item.contentType,
            title = item.title,
            logo = null,
            poster = item.poster,
            background = item.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = row.episodeTitle,
            episodeThumbnail = null,
            stream = stream,
            destinationDirOverride = folder.path,
            destinationRelativePath = relativePath,
            bandwidthLimitMbps = LibraryPvrRepository.currentSettings().bandwidthLimitMbps,
            expectedSizeBytes = link.sizeBytes ?: row.sizeBytes,
            // A manual pick is an explicit instruction; the automatic size ceilings do not apply.
            maximumSizeBytes = null,
            isAutomaticDownload = true,
            preserveExistingFileUntilSuccess = true,
        )
        if (!result.isSuccess) {
            fail(
                entry.grabId,
                when (result) {
                    DownloadEnqueueResult.MissingUrl -> "The link had no downloadable URL"
                    DownloadEnqueueResult.MissingDestination -> "The Local Library destination is missing"
                    DownloadEnqueueResult.UnsupportedFormat -> "Unsupported file type"
                    DownloadEnqueueResult.InsufficientSpace -> "Not enough free disk space"
                    else -> "Could not start the download"
                },
            )
            return
        }

        val downloadId = DownloadsRepository.uiState.value.items.firstOrNull {
            it.parentMetaId == item.contentId &&
                it.seasonNumber == season &&
                it.episodeNumber == episode
        }?.id
        LibraryPvrRepository.updateGrab(entry.grabId) {
            it.copy(
                status = GrabStatus.DOWNLOADING,
                downloadId = downloadId,
                failReason = null,
            )
        }
    }

    private fun fail(grabId: String, reason: String) {
        LibraryPvrRepository.updateGrab(grabId) {
            it.copy(status = GrabStatus.FAILED, failReason = reason)
        }
    }

    private fun DebridSourceFile.toSourceFile(): ManualSourceFile =
        ManualSourceFile(id = id, name = name, sizeBytes = sizeBytes)
}

internal data class ManualGrabSession(
    val source: DebridSource,
    val sourceName: String?,
    val rows: List<ManualGrabRow>,
)

private data class PendingManualGrab(
    val grabId: String,
    val row: ManualGrabRow,
    val file: DebridSourceFile,
)

internal sealed interface ManualInspectResult {
    data class Success(val session: ManualGrabSession) : ManualInspectResult
    data object InvalidLink : ManualInspectResult
    data object MissingProvider : ManualInspectResult
    data object NotCached : ManualInspectResult
    data object NoVideoFiles : ManualInspectResult
    data class Failed(val message: String?) : ManualInspectResult
}
