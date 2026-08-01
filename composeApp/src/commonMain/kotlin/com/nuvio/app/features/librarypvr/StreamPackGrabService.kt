package com.nuvio.app.features.librarypvr

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSourceFile
import com.nuvio.app.features.debrid.DebridSourceInspector
import com.nuvio.app.features.debrid.DebridSourceResult
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.kitsu.KitsuService
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pack-native half of the season grab. [SeasonPackGrabService] asks the clicked addon again
 * for every episode; this service instead hands the clicked stream's own torrent identity to the
 * debrid provider once, gets the pack's real file listing back, and lets the user confirm which
 * files land as which episode before anything is queued — the same inspect → review → enqueue
 * shape as the manual link grab, minus the pasting.
 */
internal object StreamPackGrabService {

    private val log = Logger.withTag("StreamPackGrabService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val SLOT_POLL_MS = 2_000L
    private const val ADDON_ID = "season-pack"

    /**
     * Uploads the stream's magnet to the active debrid provider and plans an editable breakdown of
     * the files inside. Every file starts ticked, including episodes already on disk: pre-unticking
     * them meant opening a ten-episode pack to find one row selected with no visible reason, and the
     * dialog's Select all / Select none make the whole-pack case a single click either way.
     */
    suspend fun inspect(
        stream: StreamItem,
        folder: LocalFolder,
        target: SeasonPackGrabService.Target,
    ): ManualInspectResult {
        // A pack the addon already resolved to a debrid URL still carries its infohash in the URL
        // path, so resolvedSourceInfoHash recovers a magnet where torrentMagnetUri/p2pInfoHash can't.
        val magnet = stream.torrentMagnetUri
            ?: stream.resolvedSourceInfoHash?.let { "magnet:?xt=urn:btih:$it" }
            ?: return ManualInspectResult.InvalidLink

        // allowUncached: this runs only from a right-click the user just made, so adding the pack to
        // the account to read its contents is the point rather than a side effect. Every automatic
        // path still refuses uncached magnets.
        return when (val result = DebridSourceInspector.inspect(magnet, allowUncached = true)) {
            is DebridSourceResult.Success -> {
                val source = result.source
                val sourceName = source.name?.takeIf { it.isNotBlank() }
                    ?: stream.behaviorHints.filename
                val rows = ManualGrabPlanner.plan(
                    files = source.files.map {
                        ManualSourceFile(id = it.id, name = it.name, sizeBytes = it.sizeBytes)
                    },
                    sourceName = sourceName,
                    isMovie = false,
                    entryRelative = entryRelativeNumbering(target),
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
     * Records every selected episode as a Paused download immediately, so the whole plan is visible
     * in the Downloads/Activity list at once, then promotes them one at a time within the
     * auto-download concurrency limit. Mirrors [SeasonPackGrabService.enqueue]: queuing behind the
     * slot gate (the old behaviour) left every episode past the first few invisible until a slot
     * freed, so there was no sign anything had been queued. Returns how many were accepted.
     */
    fun enqueue(
        session: ManualGrabSession,
        rows: List<ManualGrabRow>,
        target: SeasonPackGrabService.Target,
        folder: LocalFolder,
    ): Int {
        val pending = rows
            .filter {
                it.included && it.hasRequiredCoordinates(
                    isMovie = false,
                    entryRelative = entryRelativeNumbering(target),
                )
            }
            .mapNotNull { row ->
                session.source.files.firstOrNull { it.id == row.fileId }?.let { row to it }
            }
        if (pending.isEmpty()) return 0

        SeasonPackGrabService.prepareLibraryMatch(folder, target)
        scope.launch {
            val queuedIds = pending.mapNotNull { (row, file) ->
                runCatching { enqueuePaused(session, row, file, target, folder) }
                    .onFailure { error -> log.w(error) { "pack grab failed for ${row.fileName}" } }
                    .getOrNull()
            }
            for (downloadId in queuedIds) {
                awaitDownloadSlot()
                val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId }
                if (item?.status == DownloadStatus.Paused) {
                    DownloadsRepository.resumeDownload(downloadId)
                }
            }
        }
        return pending.size
    }

    /** Resolves the file's link and records it as a Paused download; returns the new download id. */
    /**
     * How many episodes the grab's *own* entry has, per its meta.
     *
     * Under an anime-native id this is the number the files should be renumbered to: kitsu/mal
     * entries run 1..N regardless of where the release group's season numbering starts, which is
     * exactly the mismatch that made a multi-season pack painful to select by hand. Null when the
     * meta can't be read or carries no episodes.
     *
     * Specials (season 0) are excluded, and if a meta unexpectedly spans several seasons the
     * largest is taken — an entry-relative id should only ever describe one.
     */
    suspend fun entryEpisodeCount(target: SeasonPackGrabService.Target): Int? {
        val kitsuId = target.contentId
            .takeIf { it.startsWith("kitsu:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
        if (kitsuId != null) {
            KitsuService.fetchEpisodeCount(kitsuId)?.let { count ->
                log.d { "entry count target=${target.contentId} source=kitsu count=$count" }
                return count
            }
        }

        val meta = MetaDetailsRepository.fetch(target.contentType, target.contentId) ?: return null
        val count = episodeCountForTarget(target.contentId, meta)
        log.d {
            "entry count target=${target.contentId} source=meta returned=${meta.id} " +
                "videos=${meta.videos.size} count=${count ?: "rejected"}"
        }
        return count
    }

    /**
     * Counts an addon fallback only when it still describes [targetContentId].
     *
     * Native anime entries routinely share franchise IDs and artwork. Accepting a sibling's video
     * list here turns its entire episode count into the auto-match range (for example, Kitsu 12533's
     * 146 episodes under Kitsu 5605). Bare numeric video IDs are accepted because some native-anime
     * addons use them; namespaced IDs must belong to the exact requested entry.
     */
    internal fun episodeCountForTarget(targetContentId: String, meta: MetaDetails): Int? {
        if (!meta.id.equals(targetContentId, ignoreCase = true)) return null
        val nativeTarget = targetContentId.isAnimeNativeId()
        val eligibleVideos = meta.videos
            .filter { video ->
                val episode = video.episode
                episode != null &&
                    episode > 0 &&
                    video.season != 0 &&
                    (!nativeTarget || video.id.belongsToNativeEntry(targetContentId))
            }
        return eligibleVideos
            .groupBy { it.season }
            .values
            .maxOfOrNull { group -> group.mapNotNull { it.episode }.distinct().size }
            ?.takeIf { it > 0 }
    }

    private fun String.belongsToNativeEntry(targetContentId: String): Boolean {
        val candidate = trim()
        return candidate.isNotEmpty() && (
            candidate.all(Char::isDigit) ||
                candidate.startsWith("$targetContentId:", ignoreCase = true)
            )
    }

    /**
     * Whether the grab is numbered relative to a single anime entry (absolute, no season) rather
     * than in franchise season/episode coordinates. This follows the *structure the pack was opened
     * under* — an anime-native content id (kitsu/mal/anilist/anidb) has no seasons to file against —
     * and deliberately NOT the destination folder's anime tag: a franchise (TMDB/TVDB) meta grabbed
     * into an anime folder keeps its real seasons, which the local scanner still matches through the
     * anime-id mapping.
     */
    private fun entryRelativeNumbering(
        target: SeasonPackGrabService.Target,
    ): Boolean = target.contentId.isAnimeNativeId()

    private suspend fun enqueuePaused(
        session: ManualGrabSession,
        row: ManualGrabRow,
        file: DebridSourceFile,
        target: SeasonPackGrabService.Target,
        folder: LocalFolder,
    ): String? {
        val link = DebridSourceInspector.resolveLink(session.source, file)
        if (link == null) {
            log.w { "${session.source.providerName} did not return a link for ${row.fileName}" }
            return null
        }

        val extension = link.filename?.videoExtension()
            ?: row.fileName.videoExtension()
            ?: "mkv"
        val episode = row.episode ?: return null
        val season = row.season
        val entryRelative = entryRelativeNumbering(target)
        val relativePath = if (entryRelative) {
            LibraryFileNaming.animeEpisodeRelativePath(target.title, target.year, episode, extension)
        } else {
            LibraryFileNaming.episodeRelativePath(
                title = target.title,
                year = target.year,
                season = season ?: return null,
                episode = episode,
                episodeTitle = row.episodeTitle,
                extension = extension,
            )
        }
        // Anime ids stay two-part (kitsu:id:ep); everything else addresses id:season:episode.
        val videoId = if (entryRelative) {
            "${target.contentId}:$episode"
        } else {
            "${target.contentId}:$season:$episode"
        }

        val stream = StreamItem(
            name = session.source.providerName,
            title = row.fileName,
            url = link.url,
            addonName = session.source.providerName,
            addonId = ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                filename = link.filename ?: row.fileName,
                videoSize = link.sizeBytes ?: row.sizeBytes,
            ),
        )

        val result = DownloadsRepository.enqueueFromStream(
            contentType = target.contentType,
            videoId = videoId,
            parentMetaId = target.contentId,
            parentMetaType = target.contentType,
            title = target.title,
            logo = null,
            poster = target.poster,
            background = target.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = row.episodeTitle,
            episodeThumbnail = null,
            stream = stream,
            destinationDirOverride = folder.path,
            destinationRelativePath = relativePath,
            bandwidthLimitMbps = LibraryPvrRepository.currentSettings().bandwidthLimitMbps,
            expectedSizeBytes = link.sizeBytes ?: row.sizeBytes,
            // The user just confirmed this exact file; the automatic size ceilings do not apply.
            maximumSizeBytes = null,
            isAutomaticDownload = true,
            preserveExistingFileUntilSuccess = true,
            startPaused = true,
        )
        if (!result.isSuccess) {
            log.w { "pack grab enqueue rejected for ${row.fileName}: $result" }
            return null
        }
        return DownloadsRepository.uiState.value.items.firstOrNull {
            it.parentMetaId == target.contentId &&
                it.seasonNumber == season &&
                it.episodeNumber == episode
        }?.id
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
}
