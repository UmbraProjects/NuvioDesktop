package com.nuvio.app.features.librarypvr

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalMatchOverride
import com.nuvio.app.features.locallibrary.LocalMatchState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSearchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Downloads a season pack through the addon that supplied the clicked row.
 *
 * AIOStreams deliberately returns a directly playable URL for only the requested episode. Its
 * structured `streamData` does, however, retain a stable release identity: an infohash for debrid
 * rows and an NZB/release key for Usenet rows. We use that identity to request the same addon once
 * for every other episode and select the matching file from the same release. Each resulting URL
 * then enters the ordinary single-file downloader, exactly like the working Download action.
 */
internal object SeasonPackGrabService {

    private val log = Logger.withTag("SeasonPackGrabService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val SLOT_POLL_MS = 2_000L
    /** One addon request every three seconds: sequential and comfortably below typical host limits. */
    private const val EPISODE_SEARCH_DELAY_MS = 3_000L

    data class Target(
        val contentId: String,
        val contentType: String,
        val title: String,
        val year: Int? = null,
        val poster: String? = null,
        val background: String? = null,
    )

    /**
     * Finds the same release for every episode in [season]. Only the clicked addon is queried; the
     * current episode reuses [stream], and remaining requests are paced rather than fanned out.
     */
    suspend fun inspect(
        stream: StreamItem,
        folder: LocalFolder,
        season: Int?,
        currentEpisode: Int?,
        target: Target,
    ): SeasonPackInspectResult {
        val requestedSeason = season ?: return SeasonPackInspectResult.InvalidLink
        val identity = stream.seasonReleaseIdentity()
            ?: return SeasonPackInspectResult.InvalidLink
        if (stream.playableDirectUrl == null) return SeasonPackInspectResult.InvalidLink

        val meta = MetaDetailsRepository.fetch(target.contentType, target.contentId)
            ?: return SeasonPackInspectResult.Failed(null)
        val episodes = meta.videos
            .filter { it.season == requestedSeason && it.episode != null }
            .distinctBy { it.season to it.episode }
            .sortedBy { it.episode }
        if (episodes.isEmpty()) return SeasonPackInspectResult.NoVideoFiles

        val rows = mutableListOf<SeasonPackEpisode>()
        var queried = false
        for (video in episodes) {
            val episode = video.episode ?: continue
            if (episodeAlreadyPresent(target.contentId, folder, requestedSeason, episode)) continue

            val matchingStream = if (episode == currentEpisode) {
                stream
            } else {
                if (queried) delay(EPISODE_SEARCH_DELAY_MS)
                queried = true
                val candidates = StreamSearchService.search(
                    type = target.contentType,
                    videoId = video.id,
                    parentMetaId = target.contentId,
                    season = requestedSeason,
                    episode = episode,
                    addonId = stream.addonId,
                )
                candidates.firstOrNull { candidate ->
                    candidate.playableDirectUrl != null && candidate.matchesSeasonRelease(identity)
                }
            }

            if (matchingStream != null) {
                rows += SeasonPackEpisode(video = video, stream = matchingStream)
            } else {
                log.w { "same release not found for S${requestedSeason}E$episode via ${stream.addonName}" }
            }
        }

        return if (rows.isEmpty()) {
            SeasonPackInspectResult.NoVideoFiles
        } else {
            SeasonPackInspectResult.Success(SeasonPackSession(rows))
        }
    }

    /** Persists every matched episode immediately, then drains that visible queue in the background. */
    fun enqueue(session: SeasonPackSession, target: Target, folder: LocalFolder): Int {
        if (session.rows.isEmpty()) return 0
        prepareLibraryMatch(folder, target)

        val queuedIds = session.rows.mapNotNull { row ->
            runCatching { enqueuePaused(row, target, folder) }
                .onFailure { error -> log.w(error) { "season grab failed for ${row.video.id}" } }
                .getOrNull()
        }
        if (queuedIds.isEmpty()) return 0

        scope.launch {
            for (downloadId in queuedIds) {
                awaitDownloadSlot()
                val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId }
                if (item?.status == DownloadStatus.Paused) {
                    DownloadsRepository.resumeDownload(downloadId)
                }
            }
        }
        return queuedIds.size
    }

    /**
     * Gives the scanner the identity of files placed by a manual stream/season action. Completion
     * triggers the rescan in [DownloadsRepository], so the title appears in Local Library without
     * waiting for a scheduled PVR sweep.
     */
    fun prepareLibraryMatch(folder: LocalFolder, target: Target) {
        val id = target.contentId.trim()
        LocalLibraryRepository.preseedMatchOverride(
            LocalMatchOverride(
                key = LibraryFileNaming.expectedItemKey(folder, target.title, target.year),
                imdbId = id.takeIf { it.startsWith("tt", ignoreCase = true) },
                tmdbId = id.removePrefixIgnoreCase("tmdb:")?.toIntOrNull(),
                kitsuId = id.removePrefixIgnoreCase("kitsu:")?.substringBefore(':')?.toIntOrNull(),
                malId = id.removePrefixIgnoreCase("mal:")?.substringBefore(':')?.toIntOrNull(),
                title = target.title,
                poster = target.poster,
                background = target.background,
                matchState = LocalMatchState.MANUAL,
            ),
        )
    }

    private suspend fun awaitDownloadSlot() {
        while (true) {
            val limit = LibraryPvrRepository.currentSettings().maxConcurrentDownloads.coerceIn(1, 4)
            val active = DownloadsRepository.uiState.value.items.count {
                it.status == DownloadStatus.Downloading
            }
            if (active < limit) return
            delay(SLOT_POLL_MS)
        }
    }

    private fun enqueuePaused(row: SeasonPackEpisode, target: Target, folder: LocalFolder): String? {
        val season = row.video.season ?: return null
        val episode = row.video.episode ?: return null
        val stream = row.stream
        val extension = stream.behaviorHints.filename?.videoExtension()
            ?: stream.playableDirectUrl
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.substringAfterLast('/')
                ?.videoExtension()
            ?: "mkv"
        // Same rule as the pack picker: numbering follows the opened structure, not the folder tag.
        // An anime-native id has no season to file under; a franchise meta keeps its real seasons.
        val relativePath = if (target.contentId.isAnimeNativeId()) {
            LibraryFileNaming.animeEpisodeRelativePath(target.title, target.year, episode, extension)
        } else {
            LibraryFileNaming.episodeRelativePath(
                title = target.title,
                year = target.year,
                season = season,
                episode = episode,
                episodeTitle = row.video.title,
                extension = extension,
            )
        }

        val result = DownloadsRepository.enqueueFromStream(
            contentType = target.contentType,
            videoId = row.video.id,
            parentMetaId = target.contentId,
            parentMetaType = target.contentType,
            title = target.title,
            logo = null,
            poster = target.poster,
            background = target.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = row.video.title,
            episodeThumbnail = row.video.thumbnail,
            stream = stream,
            destinationDirOverride = folder.path,
            destinationRelativePath = relativePath,
            bandwidthLimitMbps = LibraryPvrRepository.currentSettings().bandwidthLimitMbps,
            expectedSizeBytes = stream.behaviorHints.videoSize,
            maximumSizeBytes = null,
            isAutomaticDownload = true,
            preserveExistingFileUntilSuccess = true,
            startPaused = true,
        )
        if (!result.isSuccess) {
            log.w { "season grab enqueue rejected for S${season}E$episode: $result" }
            return null
        }
        return DownloadsRepository.uiState.value.items.firstOrNull {
            it.parentMetaId == target.contentId &&
                it.seasonNumber == season &&
                it.episodeNumber == episode
        }?.id
    }

    /** Shared with [StreamPackGrabService], whose plan pre-unticks episodes that are already handled. */
    internal fun episodeAlreadyPresent(
        contentId: String,
        folder: LocalFolder,
        season: Int,
        episode: Int,
    ): Boolean {
        val local = LocalLibraryRepository.uiState.value.items.any { item ->
            item.folderId == folder.id &&
                item.contentId == contentId &&
                item.files.any { it.season == season && it.episode == episode }
        }
        if (local) return true
        return DownloadsRepository.uiState.value.items.any { download ->
            if (
                download.parentMetaId != contentId ||
                download.seasonNumber != season ||
                download.episodeNumber != episode
            ) {
                return@any false
            }
            downloadBlocksSeasonEpisode(
                status = download.status,
                completedFileExists = download.status == DownloadStatus.Completed &&
                    DownloadsRepository.playableLocalFileUri(download) != null,
            )
        }
    }
}

/** A stale Completed database row must not prevent replacing a file deleted outside Nuvio. */
internal fun downloadBlocksSeasonEpisode(
    status: DownloadStatus,
    completedFileExists: Boolean,
): Boolean = when (status) {
    DownloadStatus.Downloading, DownloadStatus.Paused -> true
    DownloadStatus.Completed -> completedFileExists
    DownloadStatus.Failed -> false
}

private fun String.removePrefixIgnoreCase(prefix: String): String? =
    takeIf { it.startsWith(prefix, ignoreCase = true) }?.drop(prefix.length)

internal data class SeasonReleaseIdentity(
    val keys: Set<String>,
    val serviceId: String?,
)

/** Stable identifiers that survive AIOStreams' per-episode URL generation. */
internal fun StreamItem.seasonReleaseIdentity(): SeasonReleaseIdentity? {
    val keys = buildSet {
        p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?.let { add("torrent:$it") }
        streamData?.nzbUrl?.trim()?.takeIf { it.isNotBlank() }
            ?.let { add("nzb:$it") }
        streamData?.releaseKey?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?.let { add("release:$it") }
    }
    if (keys.isEmpty()) return null
    return SeasonReleaseIdentity(
        keys = keys,
        serviceId = streamData?.serviceId?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
    )
}

internal fun StreamItem.matchesSeasonRelease(identity: SeasonReleaseIdentity): Boolean {
    val candidate = seasonReleaseIdentity() ?: return false
    if (candidate.keys.intersect(identity.keys).isEmpty()) return false
    return candidate.serviceId == null || identity.serviceId == null || candidate.serviceId == identity.serviceId
}

internal data class SeasonPackEpisode(
    val video: MetaVideo,
    val stream: StreamItem,
)

internal data class SeasonPackSession(
    val rows: List<SeasonPackEpisode>,
)

internal sealed interface SeasonPackInspectResult {
    data class Success(val session: SeasonPackSession) : SeasonPackInspectResult
    data object InvalidLink : SeasonPackInspectResult
    data object NoVideoFiles : SeasonPackInspectResult
    data class Failed(val message: String?) : SeasonPackInspectResult
}
