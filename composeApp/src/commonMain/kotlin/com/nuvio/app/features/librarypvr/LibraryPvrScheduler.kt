package com.nuvio.app.features.librarypvr

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSource
import com.nuvio.app.features.debrid.DebridSourceInspector
import com.nuvio.app.features.debrid.DebridSourceResult
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.SAFE_VIDEO_DOWNLOAD_EXTENSIONS
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalMatchOverride
import com.nuvio.app.features.locallibrary.LocalMatchState
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamTraitDetector
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamSearchService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives library auto-download: a periodic sweep (optional app-start catch-up + configured interval +
 * manual "Check now") that diffs what's aired/available against what's on disk, then grabs the
 * missing pieces through the headless [StreamSearchService] into the target library folder.
 *
 * The app must be running for this to fire (there is no Windows service) — the UI copy sets that
 * expectation. The loop skips entirely when nothing is monitored.
 */
object LibraryPvrScheduler {
    private val log = Logger.withTag("LibraryPvrScheduler")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Max stream candidates tried per episode/movie per cycle before recording a failure. */
    private const val MAX_CANDIDATES_PER_GRAB = 3

    /** Small pause between grabs so a sweep doesn't hammer providers back-to-back. */
    private const val INTER_GRAB_DELAY_MS = 2_000L

    /** How often the pack-expansion drain re-checks for a free download slot. */
    private const val SLOT_POLL_MS = 2_000L

    /** Addon id recorded against episodes taken out of an already-grabbed pack. */
    private const val PACK_EXPANSION_ADDON_ID = "season-pack-auto"

    /**
     * How many times a failed episode/movie is re-attempted before the scheduler gives up on it.
     * Exhausting the budget is what lets a genuinely unobtainable episode stop blocking the rest of
     * the series; "Force start" is the manual escape hatch afterwards.
     */
    private const val MAX_GRAB_RETRIES = 5

    /** Backoff before retry N (1-based), clamped to the last entry. Total horizon ≈ 9.5 hours. */
    private val RETRY_BACKOFF_MS = longArrayOf(
        5L * 60_000L,
        15L * 60_000L,
        45L * 60_000L,
        2L * 60L * 60_000L,
        6L * 60L * 60_000L,
    )

    private var loopJob: Job? = null
    private var checkJob: Job? = null
    private var completionJob: Job? = null
    @Volatile
    private var playbackActive = false
    private val playbackPauseLock = Any()
    private var playbackPausedDownloadIds: Set<String> = emptySet()
    private val forceStartLock = Any()
    private val forceStartsInFlight = mutableSetOf<String>()

    /** Starts the periodic loop and the download-completion watcher. Idempotent. */
    fun start() {
        LibraryPvrRepository.ensureLoaded()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.resumeInterruptedAutomaticDownloads()
        cancelInterruptedQueuedGrabs()
        if (loopJob == null) {
            loopJob = scope.launch {
                val bootAt = TraktPlatformClock.nowEpochMs()
                val startupSettings = LibraryPvrRepository.currentSettings()
                val lastCheck = startupSettings.lastCompletedCheckEpochMs
                val startupCatchUpDue =
                    startupSettings.enabled &&
                        startupSettings.checkOnAppStart &&
                        lastCheck != null &&
                        bootAt - lastCheck >= startupSettings.checkIntervalMs
                if (startupCatchUpDue) runCheck()

                // Turning catch-up off means launch never causes an overdue check; the next
                // periodic check is measured from this app session instead.
                val bootDeferralUntil = if (startupSettings.checkOnAppStart) null
                else bootAt + startupSettings.checkIntervalMs
                while (isActive) {
                    val settings = LibraryPvrRepository.currentSettings()
                    val now = TraktPlatformClock.nowEpochMs()
                    val dueAt = maxOf(
                        (settings.lastCompletedCheckEpochMs ?: bootAt) + settings.checkIntervalMs,
                        bootDeferralUntil ?: Long.MIN_VALUE,
                    )
                    val remaining = dueAt - now
                    // A backed-off retry can come due long before the next periodic sweep; the
                    // poll interval below means it starts within a minute of its scheduled time.
                    if (remaining <= 0L || hasDueRetry(now)) {
                        runCheck()
                        // A disabled/empty/playback-paused check intentionally leaves the last
                        // completed time untouched; avoid spinning while it remains overdue.
                        delay(SCHEDULER_POLL_MS)
                    } else {
                        // Wake periodically so a changed interval takes effect without restarting.
                        delay(remaining.coerceAtMost(SCHEDULER_POLL_MS).coerceAtLeast(1_000L))
                    }
                }
            }
        }
        if (completionJob == null) {
            completionJob = scope.launch {
                DownloadsRepository.uiState.collect { state -> reconcileCompletions(state.items) }
            }
        }
    }

    /** Manual "Check now": runs a sweep immediately unless one is already in flight. */
    fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runCheck() }
    }

    /** Explicitly bypasses normal release/local-file gates for one monitored title. */
    fun forceStart(monitoredItemId: String) {
        val accepted = synchronized(forceStartLock) { forceStartsInFlight.add(monitoredItemId) }
        if (!accepted) return
        scope.launch {
            try {
                forceStartItem(monitoredItemId)
            } finally {
                synchronized(forceStartLock) { forceStartsInFlight.remove(monitoredItemId) }
            }
        }
    }

    /**
     * Drops a grab that is still waiting for a slot. The manual-grab worker re-checks the record
     * before it starts a transfer, so flipping the status here is enough to stop it.
     */
    fun cancelQueuedGrab(grabId: String) {
        LibraryPvrRepository.updateGrab(grabId) {
            if (it.status == GrabStatus.QUEUED) {
                it.copy(status = GrabStatus.CANCELLED, failReason = null)
            } else {
                it
            }
        }
    }

    fun cancelDownload(grabId: String, downloadId: String) {
        DownloadsRepository.cancelDownload(downloadId)
        LibraryPvrRepository.updateGrab(grabId) {
            it.copy(
                status = GrabStatus.CANCELLED,
                downloadId = downloadId,
                failReason = null,
            )
        }
    }

    /**
     * Called while the in-app player route is mounted. Only transfers paused here are resumed,
     * preserving anything the user had already paused manually.
     */
    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        synchronized(playbackPauseLock) {
            val shouldPause = active && LibraryPvrRepository.currentSettings().pauseDownloadsWhilePlaying
            if (shouldPause) {
                playbackPausedDownloadIds =
                    playbackPausedDownloadIds + DownloadsRepository.pauseAutomaticDownloadsForPlayback()
            } else if (playbackPausedDownloadIds.isNotEmpty()) {
                DownloadsRepository.resumeDownloads(playbackPausedDownloadIds)
                playbackPausedDownloadIds = emptySet()
            }
        }
    }

    fun onPauseWhilePlayingSettingChanged() {
        setPlaybackActive(playbackActive)
    }

    private suspend fun runCheck() {
        LibraryPvrRepository.ensureLoaded()
        val settings = LibraryPvrRepository.currentSettings()
        if (!settings.enabled) return
        if (playbackActive && settings.pauseDownloadsWhilePlaying) return
        val items = LibraryPvrRepository.monitoredItems().filterNot { it.paused }
        if (items.isEmpty()) return

        LibraryPvrRepository.setChecking(true)
        try {
            val folders = LocalLibraryRepository.uiState.value.folders.associateBy(LocalFolder::id)
            for (item in items) {
                if (!hasAvailableDownloadSlot(settings.maxConcurrentDownloads)) break
                val folder = folders[item.targetFolderId] ?: continue
                runCatching {
                    if (item.isSeries) {
                        processSeries(item, folder, settings)
                    } else {
                        processMovie(item, folder, settings)
                    }
                }.onFailure { error ->
                    log.w(error) { "check failed for ${item.title}" }
                    LibraryPvrRepository.mutateMonitored(item.id) { it.copy(lastError = error.message) }
                }
                LibraryPvrRepository.mutateMonitored(item.id) {
                    it.copy(lastCheckedAtEpochMs = TraktPlatformClock.nowEpochMs())
                }
            }
        } finally {
            LibraryPvrRepository.setChecking(false)
        }
    }

    private suspend fun forceStartItem(monitoredItemId: String) {
        LibraryPvrRepository.ensureLoaded()
        LocalLibraryRepository.ensureLoaded()
        val item = LibraryPvrRepository.monitoredItems()
            .firstOrNull { it.id == monitoredItemId }
            ?: return
        val folder = LocalLibraryRepository.uiState.value.folders
            .firstOrNull { it.id == item.targetFolderId }
            ?: return
        val settings = LibraryPvrRepository.currentSettings()

        if (item.isMovie) {
            grabMovie(
                item = item,
                folder = folder,
                bandwidthLimitMbps = settings.bandwidthLimitMbps,
                maximumSizeBytes = settings.maxMovieSizeBytes,
                forceFreshAttempt = true,
                replaceExistingFile = true,
            )
            return
        }

        val meta = MetaDetailsRepository.fetch(item.contentType, item.contentId, forceRefresh = true)
            ?: return
        val allEpisodes = meta.videos
            .filter { it.season != null && it.episode != null && (it.season ?: 0) > 0 }
            .filter { item.wantsEpisode(it.season!!, it.episode!!, parseReleaseDateToEpochMs(it.released)) }
        val have = haveEpisodes(item, folder)
        val candidate = allEpisodes
            .filter { (it.season!! to it.episode!!) !in have }
            .minWithOrNull(compareBy({ it.season }, { it.episode }))
            // Nothing is missing: force an upgrade of the latest episode in scope.
            ?: allEpisodes.maxWithOrNull(compareBy<MetaVideo>({ it.season }, { it.episode }))
            ?: return

        grabEpisode(
            item = item,
            folder = folder,
            video = candidate,
            bandwidthLimitMbps = settings.bandwidthLimitMbps,
            maximumSizeBytes = settings.maxEpisodeSizeBytes,
            forceFreshAttempt = true,
            replaceExistingFile = true,
        )
    }

    private suspend fun processSeries(
        item: MonitoredItem,
        folder: LocalFolder,
        settings: LibraryPvrSettings,
    ) {
        val meta = MetaDetailsRepository.fetch(item.contentType, item.contentId, forceRefresh = true) ?: return
        val now = TraktPlatformClock.nowEpochMs()
        val releaseDelayMs = settings.postReleaseDelayMs
        val have = haveEpisodes(item, folder)

        val missing = meta.videos
            .filter { it.season != null && it.episode != null && (it.season ?: 0) > 0 }
            .filter {
                parseReleaseDateToEpochMs(it.released)
                    ?.let { releasedAt -> releasedAt <= now - releaseDelayMs }
                    ?: false
            }
            .filter { (it.season!! to it.episode!!) !in have }
            .filter { item.wantsEpisode(it.season!!, it.episode!!, parseReleaseDateToEpochMs(it.released)) }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        // Strict in-order: walk from the earliest missing episode and refuse to start anything
        // behind an episode that is still unfinished. Only an episode that has completed, been
        // cancelled, or burned its whole retry budget lets the walk move past it — so a transient
        // provider failure on E01 can no longer leave a permanent hole while E03 downloads.
        val grabs = LibraryPvrRepository.grabsForItem(item.id)
        for (video in missing) {
            val episodeGrabs = grabs.filter { it.season == video.season && it.episode == video.episode }

            when (grabWalkDecision(episodeGrabs, now)) {
                GrabWalkDecision.BLOCK -> return
                // Deliberately uncapped: capping skips would stall a series whose leading run of
                // episodes is all given up on, since every cycle re-walks from the same start.
                GrabWalkDecision.SKIP -> continue
                GrabWalkDecision.GRAB -> Unit
            }

            if (!hasAvailableDownloadSlot(settings.maxConcurrentDownloads)) return
            val grabbed = grabEpisode(
                item = item,
                folder = folder,
                video = video,
                bandwidthLimitMbps = settings.bandwidthLimitMbps,
                maximumSizeBytes = settings.maxEpisodeSizeBytes,
            )
            if (grabbed != null && settings.expandSeasonPacks) {
                expandSeasonPackGrab(
                    item = item,
                    folder = folder,
                    settings = settings,
                    source = grabbed,
                    grabbedVideo = video,
                    missing = missing,
                )
            }
            delay(INTER_GRAB_DELAY_MS)
            return
        }
    }

    /**
     * After a grab lands on a season pack, files the season's other wanted episodes out of that same
     * pack instead of leaving them to be searched for one per cycle.
     *
     * This is the automatic counterpart of the season action's listing route, and it exists because
     * the walk above deliberately grabs a single episode per check: a ten-episode gap otherwise
     * costs ten searches spread across ten intervals, when the source already on the account holds
     * all ten. Only episodes [processSeries] already decided it wants are considered, so monitoring
     * mode, release delay, and what is already on disk all still apply — this changes where the
     * episodes come from, never which ones.
     *
     * Anything that makes the pack unreadable is not an error worth reporting: the episode that
     * triggered this is already downloading, and the rest fall back to the normal per-cycle walk.
     */
    private suspend fun expandSeasonPackGrab(
        item: MonitoredItem,
        folder: LocalFolder,
        settings: LibraryPvrSettings,
        source: StreamItem,
        grabbedVideo: MetaVideo,
        missing: List<MetaVideo>,
    ) {
        if (!DebridSourceInspector.canInspect()) return
        if (!StreamTraitDetector.detect(source).isSeasonPack) return
        // allowUncached stays false: adding an uncached torrent burns a 60/hour budget and starts a
        // transfer nobody asked for, which is never acceptable off the back of a background check.
        val magnet = source.torrentMagnetUri
            ?: source.resolvedSourceInfoHash?.let { "magnet:?xt=urn:btih:$it" }
            ?: return
        val inspected = (DebridSourceInspector.inspect(magnet) as? DebridSourceResult.Success)
            ?.source
            ?: return

        val wanted = missing
            .filter { it.season != null && it.episode != null }
            .filterNot { it.season == grabbedVideo.season && it.episode == grabbedVideo.episode }
            .associateBy { it.season!! to it.episode!! }
        if (wanted.isEmpty()) return

        val rows = ManualGrabPlanner.plan(
            files = inspected.files.map { ManualSourceFile(id = it.id, name = it.name, sizeBytes = it.sizeBytes) },
            sourceName = inspected.name,
            isMovie = false,
            entryRelative = item.usesEntryRelativeNumbering,
        )

        val queued = mutableListOf<String>()
        for (row in rows) {
            val season = row.season ?: continue
            val episode = row.episode ?: continue
            val video = wanted[season to episode] ?: continue
            if (row.sizeBytes != null && settings.maxEpisodeSizeBytes?.let { row.sizeBytes > it } == true) continue
            // A grab record per episode is what stops the next cycle re-grabbing these: the walk
            // reads grabs, not in-flight downloads, so an untracked enqueue would be downloaded twice.
            val grabId = LibraryPvrRepository.newGrabId()
            LibraryPvrRepository.upsertGrab(
                newQueuedGrab(grabId, item, item.title, season, episode, video.title),
            )
            val downloadId = enqueuePackEpisode(
                item = item,
                folder = folder,
                settings = settings,
                source = inspected,
                row = row,
                video = video,
            )
            if (downloadId == null) {
                markTerminal(grabId, GrabStatus.FAILED, "Pack file could not be resolved", emptySet())
                continue
            }
            queued += downloadId
            LibraryPvrRepository.updateGrab(grabId) {
                it.copy(
                    status = GrabStatus.DOWNLOADING,
                    downloadId = downloadId,
                    streamLabel = inspected.name ?: row.fileName,
                    failReason = null,
                )
            }
        }
        if (queued.isEmpty()) return

        log.i { "season pack expanded: ${queued.size} extra episodes from ${inspected.name}" }
        // Everything went in paused so the whole plan is visible at once; release them within the
        // configured concurrency rather than starting a season simultaneously.
        scope.launch {
            for (downloadId in queued) {
                while (!hasAvailableDownloadSlot(settings.maxConcurrentDownloads)) {
                    delay(SLOT_POLL_MS)
                }
                val download = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId }
                if (download?.status == DownloadStatus.Paused) {
                    DownloadsRepository.resumeDownload(downloadId)
                }
            }
        }
    }

    /** Resolves one file inside an inspected pack and records it as a paused download. */
    private suspend fun enqueuePackEpisode(
        item: MonitoredItem,
        folder: LocalFolder,
        settings: LibraryPvrSettings,
        source: DebridSource,
        row: ManualGrabRow,
        video: MetaVideo,
    ): String? {
        val season = row.season ?: return null
        val episode = row.episode ?: return null
        val file = source.files.firstOrNull { it.id == row.fileId } ?: return null
        val link = DebridSourceInspector.resolveLink(source, file) ?: return null
        val extension = link.filename?.videoExtension() ?: row.fileName.videoExtension() ?: "mkv"
        val stream = StreamItem(
            name = source.providerName,
            title = row.fileName,
            url = link.url,
            addonName = source.providerName,
            addonId = PACK_EXPANSION_ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                filename = link.filename ?: row.fileName,
                videoSize = link.sizeBytes ?: row.sizeBytes,
            ),
        )
        val result = DownloadsRepository.enqueueFromStream(
            contentType = item.contentType,
            videoId = "${item.contentId}:$season:$episode",
            parentMetaId = item.contentId,
            parentMetaType = item.contentType,
            title = item.title,
            logo = null,
            poster = item.poster,
            background = item.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = video.title,
            episodeThumbnail = video.thumbnail,
            stream = stream,
            destinationDirOverride = folder.path,
            destinationRelativePath = if (item.isAnime) {
                LibraryFileNaming.animeEpisodeRelativePath(item.title, item.year, episode, extension)
            } else {
                LibraryFileNaming.episodeRelativePath(item.title, item.year, season, episode, video.title, extension)
            },
            bandwidthLimitMbps = settings.bandwidthLimitMbps,
            expectedSizeBytes = link.sizeBytes ?: row.sizeBytes,
            maximumSizeBytes = settings.maxEpisodeSizeBytes,
            isAutomaticDownload = true,
            startPaused = true,
        )
        if (!result.isSuccess) return null
        return DownloadsRepository.uiState.value.items.firstOrNull {
            it.parentMetaId == item.contentId &&
                it.seasonNumber == season &&
                it.episodeNumber == episode
        }?.id
    }

    private suspend fun processMovie(
        item: MonitoredItem,
        folder: LocalFolder,
        settings: LibraryPvrSettings,
    ) {
        val now = TraktPlatformClock.nowEpochMs()
        if (hasMovieLocally(item, folder) || LibraryPvrRepository.hasActiveGrab(item.id, null, null)) return
        // Respect the backoff so a failing movie is not re-attempted on every single sweep.
        val movieGrabs = LibraryPvrRepository.grabsForItem(item.id).filter { it.season == null && it.episode == null }
        val retryDueAt = movieGrabs.filter { it.isAwaitingRetry }.mapNotNull { it.nextAttemptAtEpochMs }.minOrNull()
        if (retryDueAt != null) {
            if (now < retryDueAt) return
        } else if (movieGrabs.any { it.retryCount >= MAX_GRAB_RETRIES }) {
            // Budget spent: stop re-searching every sweep. "Force start" re-arms it.
            return
        }

        LibraryPvrRepository.mutateMonitored(item.id) { it.copy(lastAvailabilityCheckEpochMs = now) }

        val availabilityIso = item.tmdbId?.let { runCatching { TmdbService.fetchMovieAvailabilityDate(it) }.getOrNull() }
        val available = availabilityIso
            ?.let { parseReleaseDateToEpochMs(it) }
            ?.let { it <= now - settings.postReleaseDelayMs }
            ?: false
        if (!available) return

        if (hasAvailableDownloadSlot(settings.maxConcurrentDownloads)) {
            grabMovie(
                item = item,
                folder = folder,
                bandwidthLimitMbps = settings.bandwidthLimitMbps,
                maximumSizeBytes = settings.maxMovieSizeBytes,
            )
        }
    }

    // --- Grab: search → order → (debrid resolve) → enqueue with failover ---

    private suspend fun grabEpisode(
        item: MonitoredItem,
        folder: LocalFolder,
        video: MetaVideo,
        bandwidthLimitMbps: Int,
        maximumSizeBytes: Long?,
        forceFreshAttempt: Boolean = false,
        replaceExistingFile: Boolean = false,
    ): StreamItem? {
        val season = video.season ?: return null
        val episode = video.episode ?: return null
        val streamVideoId = "${item.contentId}:$season:$episode"
        val previous = if (forceFreshAttempt) null else previousTerminalGrab(item.id, season, episode)
        val grabId = previous?.id ?: LibraryPvrRepository.newGrabId()
        LibraryPvrRepository.upsertGrab(
            newQueuedGrab(
                grabId,
                item,
                // The row title is the show; the episode's own title (which for talk shows is the
                // guest list) belongs in the episodeTitle slot, not doubled up as the title.
                item.title,
                season,
                episode,
                video.title,
                previous,
                replaceExistingFile,
            ),
        )

        val streams = runCatching {
            StreamSearchService.search(item.contentType, streamVideoId, item.contentId, season, episode)
        }.getOrDefault(emptyList())

        return finishGrab(
            grabId = grabId,
            streams = streams,
            season = season,
            episode = episode,
            contentId = item.contentId,
            // The monitored title already knows it's anime; feed that straight to scoring so the
            // implausible-size floor uses the animation table.
            contentType = if (item.isAnime) "anime" else item.contentType,
            maximumSizeBytes = maximumSizeBytes,
            attemptedStreamKeys = previous?.attemptedStreamKeys.orEmpty(),
            enqueue = { stream, ext ->
                val result = DownloadsRepository.enqueueFromStream(
                    contentType = item.contentType,
                    videoId = streamVideoId,
                    parentMetaId = item.contentId,
                    parentMetaType = item.contentType,
                    title = item.title,
                    logo = null,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = season,
                    episodeNumber = episode,
                    episodeTitle = video.title,
                    episodeThumbnail = video.thumbnail,
                    stream = stream,
                    destinationDirOverride = folder.path,
                    destinationRelativePath = if (item.isAnime) {
                        LibraryFileNaming.animeEpisodeRelativePath(item.title, item.year, episode, ext)
                    } else {
                        LibraryFileNaming.episodeRelativePath(item.title, item.year, season, episode, video.title, ext)
                    },
                    bandwidthLimitMbps = bandwidthLimitMbps,
                    expectedSizeBytes = stream.knownDownloadSizeBytes(),
                    maximumSizeBytes = maximumSizeBytes,
                    isAutomaticDownload = true,
                    preserveExistingFileUntilSuccess = replaceExistingFile,
                )
                if (result.isSuccess) {
                    DownloadsRepository.uiState.value.items.firstOrNull {
                        it.parentMetaId == item.contentId &&
                            it.seasonNumber == season &&
                            it.episodeNumber == episode
                    }?.id
                } else {
                    null
                }
            },
        )
    }

    private suspend fun grabMovie(
        item: MonitoredItem,
        folder: LocalFolder,
        bandwidthLimitMbps: Int,
        maximumSizeBytes: Long?,
        forceFreshAttempt: Boolean = false,
        replaceExistingFile: Boolean = false,
    ) {
        val previous = if (forceFreshAttempt) null else previousTerminalGrab(item.id, null, null)
        val grabId = previous?.id ?: LibraryPvrRepository.newGrabId()
        LibraryPvrRepository.upsertGrab(
            newQueuedGrab(
                grabId,
                item,
                item.title,
                null,
                null,
                null,
                previous,
                replaceExistingFile,
            ),
        )

        val streams = runCatching {
            StreamSearchService.search(item.contentType, item.contentId, item.contentId, null, null)
        }.getOrDefault(emptyList())

        finishGrab(
            grabId = grabId,
            streams = streams,
            season = null,
            episode = null,
            contentId = item.contentId,
            // The monitored title already knows it's anime; feed that straight to scoring so the
            // implausible-size floor uses the animation table.
            contentType = if (item.isAnime) "anime" else item.contentType,
            maximumSizeBytes = maximumSizeBytes,
            attemptedStreamKeys = previous?.attemptedStreamKeys.orEmpty(),
            enqueue = { stream, ext ->
                val result = DownloadsRepository.enqueueFromStream(
                    contentType = item.contentType,
                    videoId = item.contentId,
                    parentMetaId = item.contentId,
                    parentMetaType = item.contentType,
                    title = item.title,
                    logo = null,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    episodeThumbnail = null,
                    stream = stream,
                    destinationDirOverride = folder.path,
                    destinationRelativePath = LibraryFileNaming.movieRelativePath(item.title, item.year, ext),
                    bandwidthLimitMbps = bandwidthLimitMbps,
                    expectedSizeBytes = stream.knownDownloadSizeBytes(),
                    maximumSizeBytes = maximumSizeBytes,
                    isAutomaticDownload = true,
                    preserveExistingFileUntilSuccess = replaceExistingFile,
                )
                if (result.isSuccess) {
                    DownloadsRepository.uiState.value.items.firstOrNull {
                        it.parentMetaId == item.contentId &&
                            it.seasonNumber == null &&
                            it.episodeNumber == null
                    }?.id
                } else {
                    null
                }
            },
        )
    }

    /**
     * Walks the candidate streams (best first) trying to enqueue one, up to [MAX_CANDIDATES_PER_GRAB]
     * attempts. Records the grab as DOWNLOADING on the first successful enqueue, SKIPPED when no
     * downloadable source exists, or FAILED after exhausting attempts.
     *
     * Returns the resolved stream that was enqueued, or null if nothing was. The caller needs the
     * stream itself, not just the outcome, to tell whether the season's other episodes are sitting
     * inside the very source it just took one file from.
     */
    private suspend fun finishGrab(
        grabId: String,
        streams: List<StreamItem>,
        season: Int?,
        episode: Int?,
        contentId: String?,
        contentType: String?,
        maximumSizeBytes: Long?,
        attemptedStreamKeys: Set<String>,
        enqueue: suspend (stream: StreamItem, extension: String) -> String?,
    ): StreamItem? {
        if (streams.isEmpty()) {
            // Sources for a freshly aired episode often show up later, so this is retry-budgeted
            // rather than final.
            markTerminal(grabId, GrabStatus.SKIPPED, "No sources found", attemptedStreamKeys)
            return null
        }

        // Scoring replaces provider order as the candidate ranking, and drops anything below the
        // profile's minimum before the attempt budget is spent on it. A disabled or inapplicable
        // profile leaves the list exactly as the providers returned it.
        val scoreProfile = StreamScoreRepository.profile
        val orderedStreams = StreamScorer.rank(
            streams = streams,
            profile = scoreProfile.takeIf { it.appliesToAutoDownload() } ?: StreamScoreProfile(),
            context = StreamScoreContexts.forPlayback(
                isEpisode = episode != null,
                contentId = contentId,
                contentType = contentType,
            ),
        )
        if (orderedStreams.isEmpty()) {
            markTerminal(grabId, GrabStatus.SKIPPED, "No sources met the score threshold", attemptedStreamKeys)
            return null
        }

        var attempts = 0
        var lastReason: String? = null
        val attempted = attemptedStreamKeys.toMutableSet()
        var foundUntriedCandidate = false
        for (candidate in orderedStreams) {
            if (attempts >= MAX_CANDIDATES_PER_GRAB) break
            if (candidate.exceedsSizeLimit(maximumSizeBytes)) {
                lastReason = "Source exceeds the configured size limit"
                continue
            }
            val streamKey = candidate.downloadCandidateKey()
            if (streamKey in attempted) continue
            foundUntriedCandidate = true
            attempts++
            attempted += streamKey
            LibraryPvrRepository.updateGrab(grabId) {
                it.copy(attemptedStreamKeys = attempted.toSet())
            }
            val resolved = when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, season, episode)) {
                is DirectDebridPlayableResult.Success -> result.stream
                else -> {
                    lastReason = "Debrid resolve failed"
                    continue
                }
            }
            if (resolved.exceedsSizeLimit(maximumSizeBytes)) {
                attempts--
                lastReason = "Source exceeds the configured size limit"
                continue
            }
            val downloadId = runCatching { enqueue(resolved, extensionOf(resolved)) }.getOrNull()
            if (downloadId != null) {
                LibraryPvrRepository.updateGrab(grabId) {
                    it.copy(
                        status = GrabStatus.DOWNLOADING,
                        downloadId = downloadId,
                        streamLabel = resolved.streamLabel,
                        streamKey = streamKey,
                        attemptedStreamKeys = attempted.toSet(),
                        failReason = null,
                    )
                }
                return resolved
            }
            lastReason = "No downloadable source"
        }

        markTerminal(
            grabId = grabId,
            status = GrabStatus.FAILED,
            reason = if (!foundUntriedCandidate && attempted.isNotEmpty()) {
                "No untried downloadable sources remain"
            } else {
                lastReason ?: "No downloadable source"
            },
            attempted = attempted.toSet(),
        )
        return null
    }

    /**
     * Records a terminal grab outcome and schedules the next attempt while the retry budget lasts.
     * A grab with [GrabRecord.nextAttemptAtEpochMs] set still blocks later episodes of the series;
     * once the budget is spent the field stays null and the walk is free to move past it.
     */
    private fun markTerminal(
        grabId: String,
        status: GrabStatus,
        reason: String?,
        attempted: Set<String>,
        clearStreamKeyFromAttempts: Boolean = false,
        downloadId: String? = null,
    ) {
        LibraryPvrRepository.updateGrab(grabId) { grab ->
            val nextRetry = grab.retryCount + 1
            val withinBudget = nextRetry <= MAX_GRAB_RETRIES
            val failedKey = grab.streamKey
            grab.copy(
                status = status,
                downloadId = downloadId ?: grab.downloadId,
                failReason = reason,
                // A server-side fault is no verdict on this release, so keep it eligible for the
                // next attempt instead of failing over to a worse source.
                attemptedStreamKeys = if (clearStreamKeyFromAttempts && withinBudget && failedKey != null) {
                    attempted - failedKey
                } else {
                    attempted
                },
                retryCount = nextRetry,
                nextAttemptAtEpochMs = if (withinBudget) {
                    TraktPlatformClock.nowEpochMs() +
                        RETRY_BACKOFF_MS[(nextRetry - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
                } else {
                    null
                },
            )
        }
    }

    private fun hasDueRetry(now: Long): Boolean =
        LibraryPvrRepository.uiState.value.grabHistory.any { grab ->
            grab.isAwaitingRetry && (grab.nextAttemptAtEpochMs ?: Long.MAX_VALUE) <= now
        }

    // --- Completion watcher: download done → place into library ---

    private fun reconcileCompletions(downloads: List<DownloadItem>) {
        val monitored = LibraryPvrRepository.monitoredItems()
        if (monitored.isEmpty()) return
        val grabs = LibraryPvrRepository.uiState.value.grabHistory
        val folders = LocalLibraryRepository.uiState.value.folders.associateBy(LocalFolder::id)

        var freedDownloadSlot = false
        var libraryRescanRequired = false
        downloads.forEach { download ->
            val mon = monitored.firstOrNull { it.contentId == download.parentMetaId } ?: return@forEach
            val matchingGrabs = grabs.filter {
                it.monitoredItemId == mon.id &&
                    it.season == download.seasonNumber &&
                    it.episode == download.episodeNumber &&
                    it.status == GrabStatus.DOWNLOADING &&
                    (it.downloadId == null || it.downloadId == download.id)
            }
            if (matchingGrabs.isEmpty()) return@forEach

            when (download.status) {
                DownloadStatus.Completed -> {
                    if (matchingGrabs.any { it.replaceExistingFile }) {
                        deleteSupersededLocalFiles(mon, download)
                    }
                    matchingGrabs.forEach { grab ->
                        LibraryPvrRepository.updateGrab(grab.id) {
                            it.copy(status = GrabStatus.COMPLETED, downloadId = download.id)
                        }
                    }
                    libraryRescanRequired =
                        prepareLibraryMatch(mon, folders[mon.targetFolderId]) || libraryRescanRequired
                    freedDownloadSlot = true
                }
                DownloadStatus.Failed -> {
                    matchingGrabs.forEach { grab ->
                        markTerminal(
                            grabId = grab.id,
                            status = GrabStatus.FAILED,
                            reason = download.errorMessage,
                            attempted = grab.attemptedStreamKeys,
                            // HTTP 5xx/429/timeouts are the provider having a moment, not a bad
                            // release: retry the same source rather than downgrading.
                            clearStreamKeyFromAttempts = download.failureIsTransient,
                            downloadId = download.id,
                        )
                    }
                    freedDownloadSlot = true
                }
                else -> Unit
            }
        }
        // A state emission can contain several newly completed transfers. Seed every identity
        // first, then run one scan so repeated completions cannot continually cancel each other's
        // scan and leave the Local Library UI stale.
        if (libraryRescanRequired) {
            LocalLibraryRepository.rescan()
        }
        if (freedDownloadSlot) checkNow()
    }

    /**
     * Seeds the known ids as a MANUAL match override keyed on the scanner's future item key, then
     * rescans so the newly downloaded file attaches to the right title without a TMDB re-match.
     */
    private fun prepareLibraryMatch(item: MonitoredItem, folder: LocalFolder?): Boolean {
        if (folder == null) return false
        LocalLibraryRepository.preseedMatchOverride(
            LocalMatchOverride(
                key = LibraryFileNaming.expectedItemKey(folder, item.title, item.year),
                imdbId = item.imdbId,
                tmdbId = item.tmdbId,
                kitsuId = item.kitsuId,
                malId = item.malId,
                title = item.title,
                poster = item.poster,
                background = item.background,
                matchState = LocalMatchState.MANUAL,
            ),
        )
        return true
    }

    private fun deleteSupersededLocalFiles(item: MonitoredItem, download: DownloadItem) {
        val protectedSuffix = download.fileName.replace('\\', '/').lowercase()
        val localItem = LocalLibraryRepository.uiState.value.items.firstOrNull {
            it.folderId == item.targetFolderId && it.contentId == item.contentId
        } ?: return
        localItem.files
            .filter { file ->
                if (item.isMovie) {
                    true
                } else {
                    file.season == download.seasonNumber && file.episode == download.episodeNumber
                }
            }
            .filterNot { file ->
                file.path.replace('\\', '/').lowercase().endsWith(protectedSuffix)
            }
            .forEach { file ->
                DownloadsPlatformDownloader.removeFile(file.path)
            }
    }

    // --- Helpers ---

    /**
     * QUEUED is a momentary state: a scheduler grab leaves it within one call, and a manual grab is
     * promoted by an in-memory worker. Anything still QUEUED at launch was interrupted by a restart
     * and will never start on its own, so retire it rather than leaving a permanent "queued" row.
     */
    private fun cancelInterruptedQueuedGrabs() {
        LibraryPvrRepository.uiState.value.grabHistory
            .filter { it.status == GrabStatus.QUEUED }
            .forEach { grab ->
                LibraryPvrRepository.updateGrab(grab.id) {
                    it.copy(
                        status = GrabStatus.CANCELLED,
                        failReason = "Nuvio closed before this download started",
                    )
                }
            }
    }

    private fun hasAvailableDownloadSlot(maxConcurrentDownloads: Int): Boolean {
        val activeCount = LibraryPvrRepository.uiState.value.grabHistory.count {
            it.status == GrabStatus.QUEUED || it.status == GrabStatus.DOWNLOADING
        }
        return activeCount < maxConcurrentDownloads.coerceIn(1, 4)
    }

    private fun haveEpisodes(item: MonitoredItem, folder: LocalFolder): Set<Pair<Int, Int>> {
        val fromLocal = LocalLibraryRepository.uiState.value.items
            .filter { it.folderId == folder.id && it.contentId == item.contentId }
            .flatMap { it.files }
            .mapNotNull { file -> file.season?.let { s -> file.episode?.let { e -> s to e } } }
        val fromDownloads = DownloadsRepository.uiState.value.items
            .filter {
                it.parentMetaId == item.contentId &&
                    it.status == DownloadStatus.Completed &&
                    it.seasonNumber != null && it.episodeNumber != null
            }
            .map { it.seasonNumber!! to it.episodeNumber!! }
        return (fromLocal + fromDownloads).toSet()
    }

    private fun hasMovieLocally(item: MonitoredItem, folder: LocalFolder): Boolean {
        val local = LocalLibraryRepository.uiState.value.items.any {
            it.folderId == folder.id && it.contentId == item.contentId && it.files.isNotEmpty()
        }
        val downloaded = DownloadsRepository.uiState.value.items.any {
            it.parentMetaId == item.contentId && it.status == DownloadStatus.Completed
        }
        return local || downloaded
    }

    private fun extensionOf(stream: StreamItem): String {
        val advertisedNames = listOfNotNull(
            stream.behaviorHints.filename,
            stream.clientResolve?.filename,
            stream.debridCacheStatus?.cachedName,
            stream.playableDirectUrl
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.substringAfterLast('/'),
        )
        return advertisedNames
            .asSequence()
            .map { it.substringAfterLast('.', "").lowercase() }
            .firstOrNull { it in SAFE_VIDEO_DOWNLOAD_EXTENSIONS }
            ?: "mkv"
    }

    private fun StreamItem.knownDownloadSizeBytes(): Long? =
        behaviorHints.videoSize?.takeIf { it > 0L }
            ?: debridCacheStatus?.cachedSize?.takeIf { it > 0L }

    private fun StreamItem.exceedsSizeLimit(maximumSizeBytes: Long?): Boolean {
        val limit = maximumSizeBytes?.takeIf { it > 0L } ?: return false
        return knownDownloadSizeBytes()?.let { it > limit } == true
    }

    private fun StreamItem.downloadCandidateKey(): String {
        val stableFileName = behaviorHints.filename
            ?.takeIf { it.isNotBlank() }
            ?: clientResolve?.filename?.takeIf { it.isNotBlank() }
        val directUrl = playableDirectUrl
        val identity = when {
            !infoHash.isNullOrBlank() ->
                "$addonId|hash:${infoHash.lowercase()}|${fileIdx ?: -1}"
            !stableFileName.isNullOrBlank() ->
                "$addonId|file:${stableFileName.lowercase()}"
            !directUrl.isNullOrBlank() ->
                "$addonId|url:${directUrl.substringBefore('?').substringBefore('#').lowercase()}"
            else ->
                "$addonId|label:${name.orEmpty().lowercase()}|${title.orEmpty().lowercase()}"
        }
        val hash = identity.fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
        return hash.toULong().toString(16)
    }

    private fun previousTerminalGrab(
        monitoredItemId: String,
        season: Int?,
        episode: Int?,
    ): GrabRecord? {
        val terminal = LibraryPvrRepository.grabsForItem(monitoredItemId)
            .filter { it.season == season && it.episode == episode }
            .filter { it.status == GrabStatus.FAILED || it.status == GrabStatus.SKIPPED }
        val latest = terminal.maxByOrNull { it.updatedAtEpochMs } ?: return null
        val union = terminal.flatMapTo(mutableSetOf()) { it.attemptedStreamKeys }
        // The union exists to defend against duplicate rows left by older builds, but it must not
        // resurrect a key that the last transient failure deliberately released for re-use.
        val releasedForRetry = latest.streamKey
            ?.takeIf { latest.isAwaitingRetry && it !in latest.attemptedStreamKeys }
        return latest.copy(
            attemptedStreamKeys = if (releasedForRetry != null) union - releasedForRetry else union,
        )
    }

    private fun newQueuedGrab(
        id: String,
        item: MonitoredItem,
        title: String,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        previous: GrabRecord? = null,
        replaceExistingFile: Boolean = false,
    ): GrabRecord {
        val now = TraktPlatformClock.nowEpochMs()
        return GrabRecord(
            id = id,
            monitoredItemId = item.id,
            title = title.ifBlank { item.title },
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            status = GrabStatus.QUEUED,
            attemptedStreamKeys = previous?.attemptedStreamKeys.orEmpty(),
            replaceExistingFile = replaceExistingFile,
            // Carry the budget forward across retries; the pending-retry marker is consumed by
            // starting this attempt and is re-armed only if it fails again.
            retryCount = previous?.retryCount ?: 0,
            nextAttemptAtEpochMs = null,
            createdAtEpochMs = previous?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
        )
    }

    private val LibraryPvrSettings.postReleaseDelayMs: Long
        get() = postReleaseDelayHours.coerceIn(0, 168).toLong() * 60L * 60L * 1000L

    private const val SCHEDULER_POLL_MS = 60_000L
}
