package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val UNKNOWN_TORRENT_ERROR = "Unknown torrent error"
private const val STREAMING_SAMPLE_INTERVAL_MS = 5_000L
private const val STARTUP_SAMPLE_INTERVAL_MS = 1_000L

// Metadata wait. The hard cap is the most a user should ever stare at "fetching metadata"; the
// stall rule ends it sooner when the swarm has demonstrably nothing to offer: the announce round
// has had time to settle (MIN_WAIT), and for STALL ms nothing has moved — no new known peers, no
// connected peer, no payload. A slow-but-alive torrent keeps at least one of those moving (a peer
// that is connected but hasn't served the info dict yet counts as movement), so it gets the full
// cap. Measured on a live swarm the engine had 350 known peers and metadata in 1.3 s; on a dead
// hash it plateaued at 2–7 known peers, all connect failures, for three minutes.
/**
 * The engine's download window ahead of the player's read position (its rolling selection is
 * min(this, a 1 GiB ceiling in patch 0002), held as verified pieces in RAM). It follows the
 * playback buffer preset: the preset already says how much the user wants held ahead of the
 * playhead for mpv (Metered 32 MiB ... Resilient 1 GiB), and the swarm-side window is the same
 * decision one layer down. 16 MiB pieces need several in flight to keep a swarm busy, so even
 * Metered gets four.
 */
internal fun nuvioEngineWindowBytes(preset: DesktopBufferPreset): Long = when (preset) {
    DesktopBufferPreset.Metered -> 64L * 1024L * 1024L
    DesktopBufferPreset.LowData -> 128L * 1024L * 1024L
    DesktopBufferPreset.Balanced -> 256L * 1024L * 1024L
    DesktopBufferPreset.Resilient -> 512L * 1024L * 1024L
}
internal const val METADATA_DEADLINE_MS = 60_000L
internal const val METADATA_STALL_MIN_WAIT_MS = 20_000L
internal const val METADATA_STALL_MS = 15_000L

/**
 * Desktop port of upstream's Android `P2pStreamingEngine` (NuvioMedia/NuvioDesktop `1b88c5ab`)
 * over the in-process Nuvio Engine (libtorrent). The engine serves the selected file over a
 * loopback HTTP range endpoint; the URL it returns is handed to mpv unchanged.
 *
 * Lifecycle mirrors [TorrServerP2pBackend]: one active stream, a generation counter guards
 * every state write, stop is asynchronous, and the engine itself is created lazily and rebuilt
 * only when the settings that shape it (upload, profile, disk cache) change.
 */
internal object NuvioEngineP2pBackend : DesktopP2pBackend {
    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
        val windowBytes: Long,
    )

    private data class DetachedStream(
        val engine: NuvioEngine?,
        val streamId: String?,
    )

    private val log = Logger.withTag("NuvioEngineP2pBackend")
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    override val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    override val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val startMutex = Mutex()
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineEventsJob: Job? = null
    private var streamGeneration = 0L

    @Volatile
    private var currentTorrentId: String? = null

    @Volatile
    private var currentStreamId: String? = null

    @Volatile
    private var engine: NuvioEngine? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private val knownTorrentIds = mutableSetOf<String>()
    private var requestSequence = 0L

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { runBlocking { stopStreamNow(shutdownEngine = true) } }
            }.apply { name = "nuvio-engine-shutdown" },
        )
    }

    override suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        startMutex.withLock { startStreamLocked(request) }
    }

    override suspend fun clearCache(): P2pCacheClearResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(
                _state.value !is P2pStreamingState.Streaming &&
                    _state.value !is P2pStreamingState.Connecting,
            ) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
            try {
                val activeEngine = ensureEngine()
                if (!_cacheState.value.hasMeasurement) {
                    delay(STARTUP_SAMPLE_INTERVAL_MS + 100L)
                    val initial = activeEngine.stats.value
                    updateCacheState(initial.diskCacheUsedBytes, initial.diskCacheProtectedBytes)
                }
                val before = activeEngine.stats.value
                activeEngine.reclaimDiskCache(0L)
                delay(STARTUP_SAMPLE_INTERVAL_MS + 100L)
                val after = activeEngine.stats.value
                updateCacheState(after.diskCacheUsedBytes, after.diskCacheProtectedBytes)
                P2pCacheClearResult(
                    reclaimedBytes = (before.diskCacheUsedBytes - after.diskCacheUsedBytes).coerceAtLeast(0L),
                    remainingBytes = after.diskCacheUsedBytes,
                    protectedBytes = after.diskCacheProtectedBytes,
                )
            } finally {
                _cacheState.value = _cacheState.value.copy(isClearing = false)
            }
        }
    }

    private suspend fun startStreamLocked(request: P2pStreamRequest): String {
        val sequence = nextRequestSequence()
        val startedAt = System.nanoTime()
        val phase = AtomicReference("stop_previous")
        log.i {
            "P2P startup begin: request=$sequence hash=${diagnosticId(request.infoHash)} " +
                "requestedFile=${request.fileIdx ?: "auto"} filenameHint=${!request.filename.isNullOrBlank()} " +
                "trackers=${request.trackers.size}"
        }
        stopStreamNow(shutdownEngine = false)
        val generation = beginStreamGeneration()

        var activeEngine: NuvioEngine? = null
        var payloadDownloadBaseline = 0L
        var preparedStream: NuvioStream? = null
        var attached = false
        var startupStatsJob: Job? = null
        return try {
            phase.set("starting_engine")
            val magnetUri = buildP2pMagnetUri(
                request.infoHash,
                (DEFAULT_TRACKERS + request.trackers).distinct(),
            )
            val resolvedEngine = ensureEngine()
            activeEngine = resolvedEngine
            payloadDownloadBaseline = resolvedEngine.stats.value.totalPayloadDownloadBytes
            ensureCurrentGeneration(generation)
            val engineReadyMs = elapsedMillis(startedAt)
            startupStatsJob = startStartupStatsPolling(resolvedEngine, generation, phase, sequence, startedAt)

            phase.set("add_magnet")
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            val reusedTorrent = canonicalHash in knownTorrentIds
            val torrentId = if (reusedTorrent) {
                canonicalHash
            } else {
                addMagnetWithWatchdog(resolvedEngine, magnetUri, canonicalHash, sequence, startedAt)
                    .also { knownTorrentIds += it }
            }
            ensureCurrentGeneration(generation)
            val metadataReadyMs = elapsedMillis(startedAt)

            phase.set("prepare_stream")
            val stream = resolvedEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = request.fileIdx,
                filenameHint = request.filename,
            )
            preparedStream = stream
            currentCoroutineContext().ensureActive()
            phase.set("attach_route")
            if (!attachStreamIfCurrent(generation, torrentId, stream.id)) {
                withContext(NonCancellable) { stopPreparedStream(resolvedEngine, stream.id) }
                preparedStream = null
                throw CancellationException("P2P stream start was cancelled")
            }
            attached = true
            val readyMs = elapsedMillis(startedAt)
            log.i {
                "P2P startup ready: request=$sequence hash=${diagnosticId(torrentId)} file=${stream.fileIndex} " +
                    "fileBytes=${stream.fileSize} total=${readyMs}ms engine=${engineReadyMs}ms " +
                    "metadata=${metadataReadyMs - engineReadyMs}ms${if (reusedTorrent) " (warm)" else ""} " +
                    "prepare=${readyMs - metadataReadyMs}ms"
            }

            startStatsPolling(resolvedEngine, stream, generation, sequence, startedAt, payloadDownloadBaseline)
            val initialAggregate = resolvedEngine.stats.value
            val published = publishStreamingIfCurrent(
                generation = generation,
                state = P2pStreamingState.Streaming(
                    localUrl = stream.url,
                    downloadSpeed = initialAggregate.downloadRateBytesPerSecond,
                    uploadSpeed = initialAggregate.uploadRateBytesPerSecond,
                    peers = initialAggregate.connectedPeers,
                    seeds = initialAggregate.connectedSeeds,
                    bufferProgress = 0f,
                    totalProgress = 0f,
                    downloadedBytes = (initialAggregate.totalPayloadDownloadBytes - payloadDownloadBaseline)
                        .coerceAtLeast(0L),
                ),
            )
            if (!published) throw CancellationException("P2P stream start was cancelled")
            log.d { "Nuvio Engine stream ready: ${stream.url}" }
            stream.url
        } catch (cancellation: CancellationException) {
            log.i { "P2P startup cancelled: request=$sequence phase=${phase.get()} after=${elapsedMillis(startedAt)}ms" }
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, P2pStreamingState.Idle)
            }
            throw cancellation
        } catch (error: Exception) {
            log.e(error) { "P2P startup failed: request=$sequence phase=${phase.get()} after=${elapsedMillis(startedAt)}ms" }
            val terminalState = P2pStreamingState.Error(error.message ?: UNKNOWN_TORRENT_ERROR)
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, terminalState)
            }
            throw error
        } finally {
            startupStatsJob?.cancel()
        }
    }

    /**
     * Runs [NuvioEngine.addMagnet] against the deadline and stall rules described at the
     * constants. On give-up the half-added torrent is removed so it does not linger as a warm
     * torrent, and the error names what the swarm looked like so the user can tell "dead
     * torrent" from "engine broken".
     */
    private suspend fun addMagnetWithWatchdog(
        activeEngine: NuvioEngine,
        magnetUri: String,
        canonicalHash: String,
        sequence: Long,
        startedAt: Long,
    ): String = coroutineScope {
        val baseline = activeEngine.stats.value
        val add = async { activeEngine.addMagnet(magnetUri) }
        val watchdog = async {
            val waitStartedAt = System.nanoTime()
            var lastKnown = 0L
            var lastProgressAt = waitStartedAt
            while (true) {
                delay(STARTUP_SAMPLE_INTERVAL_MS)
                val stats = activeEngine.stats.value
                val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartedAt)
                // Session-wide counters minus the baseline approximate this torrent's share; the
                // engine only reports per-stream stats once a stream exists.
                val known = (stats.knownPeers - baseline.knownPeers).coerceAtLeast(0).toLong()
                val connected = (stats.connectedPeers - baseline.connectedPeers).coerceAtLeast(0)
                val downloaded = (stats.totalPayloadDownloadBytes - baseline.totalPayloadDownloadBytes)
                val moving = known > lastKnown || connected > 0 || downloaded > 0L
                if (moving) lastProgressAt = System.nanoTime()
                lastKnown = maxOf(lastKnown, known)
                val stalledFor = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastProgressAt)
                val verdict = metadataWaitVerdict(waited, stalledFor, lastKnown)
                if (verdict != null) {
                    return@async MetadataGiveUp(
                        message = verdict,
                        detail = "waited=${waited}ms stalled=${stalledFor}ms known=$lastKnown connected=$connected " +
                            "trackerReplies=${stats.trackerReplyEvents - baseline.trackerReplyEvents} " +
                            "trackerPeers=${stats.trackerPeersReturned - baseline.trackerPeersReturned} " +
                            "dhtPeers=${stats.dhtPeersReturned - baseline.dhtPeersReturned} " +
                            "connects=${stats.peerConnectEvents - baseline.peerConnectEvents} " +
                            "connectFailures=${stats.peerDisconnectConnectFailures - baseline.peerDisconnectConnectFailures}",
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        val result = select<Any> {
            add.onAwait { it }
            watchdog.onAwait { it }
        }
        when (result) {
            is String -> {
                watchdog.cancel()
                result
            }
            is MetadataGiveUp -> {
                add.cancel()
                log.w { "P2P metadata give-up: request=$sequence after=${elapsedMillis(startedAt)}ms ${result.detail}" }
                withContext(NonCancellable) {
                    try {
                        activeEngine.removeTorrent(canonicalHash)
                    } catch (error: Exception) {
                        log.d(error) { "Could not remove the unresolved torrent ${diagnosticId(canonicalHash)}" }
                    }
                }
                throw P2pStreamingException(result.message)
            }
            else -> error("unexpected watchdog result $result")
        }
    }

    private class MetadataGiveUp(val message: String, val detail: String)

    override fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    override fun shutdown() {
        scheduleStop(shutdownEngine = true)
    }

    private fun scheduleStop(shutdownEngine: Boolean) {
        val detached = detachActiveStream()
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(detached, shutdownEngine)
        }
    }

    private suspend fun stopStreamNow(shutdownEngine: Boolean) {
        cleanupJob?.join()
        val detached = detachActiveStream()
        cleanupDetachedStream(detached, shutdownEngine)
    }

    private fun detachActiveStream(): DetachedStream {
        val detached: Pair<DetachedStream, Job?> = synchronized(lifecycleLock) {
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = P2pStreamingState.Idle
            value to job
        }
        detached.second?.cancel()
        return detached.first
    }

    private fun detachGenerationIfCurrent(generation: Long, terminalState: P2pStreamingState): DetachedStream? {
        val detached: Pair<DetachedStream, Job?>? = synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized null
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = terminalState
            value to job
        }
        detached?.second?.cancel()
        return detached?.first
    }

    private suspend fun cleanupDetachedStream(detached: DetachedStream, shutdownEngine: Boolean) {
        detached.streamId?.let { streamId -> stopPreparedStream(detached.engine, streamId) }
        if (shutdownEngine) {
            closeEngine(detached.engine)
        }
    }

    private suspend fun cleanupFailedStart(
        generation: Long,
        activeEngine: NuvioEngine?,
        preparedStream: NuvioStream?,
        attached: Boolean,
        terminalState: P2pStreamingState,
    ) {
        if (attached) {
            detachGenerationIfCurrent(generation, terminalState)?.let { detached ->
                cleanupDetachedStream(detached, shutdownEngine = false)
            }
        } else {
            preparedStream?.let { stream -> stopPreparedStream(activeEngine, stream.id) }
            detachGenerationIfCurrent(generation, terminalState)
        }
    }

    private suspend fun stopPreparedStream(activeEngine: NuvioEngine?, streamId: String) {
        try {
            activeEngine?.stopStream(streamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "Error stopping Nuvio Engine stream route" }
        }
    }

    private suspend fun ensureEngine(): NuvioEngine {
        P2pSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configurationKey = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
            windowBytes = nuvioEngineWindowBytes(PlayerSettingsRepository.uiState.value.desktopBufferPreset),
        )
        engine?.takeIf { engineConfigurationKey == configurationKey }?.let { return it }

        val startedAt = System.nanoTime()
        log.i { "Nuvio Engine create: $configurationKey replacing=${engine != null}" }
        closeEngine(engine)
        currentCoroutineContext().ensureActive()
        val root = DesktopStorage.rootDir.resolve("nuvio-engine").toFile()
        val stateDirectory = File(root, "state")
        val cacheDirectory = File(root, "payload")
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }
        return NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = configurationKey.uploadEnabled,
                torrentProfile = configurationKey.torrentProfile,
                diskCacheCapacityBytes = configurationKey.diskCacheCapacityBytes,
                windowBytes = configurationKey.windowBytes,
            ),
        ).also { created ->
            engine = created
            engineConfigurationKey = configurationKey
            observeEngineEvents(created)
            log.i {
                "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion}) " +
                    "after ${elapsedMillis(startedAt)}ms"
            }
        }
    }

    private suspend fun closeEngine(target: NuvioEngine?) {
        if (target == null) return
        if (engine === target) {
            engineEventsJob?.cancel()
            engineEventsJob = null
            engine = null
            engineConfigurationKey = null
            knownTorrentIds.clear()
        }
        withContext(NonCancellable) {
            try {
                target.shutdown()
            } catch (error: Exception) {
                log.w(error) { "Error shutting down Nuvio Engine" }
            }
        }
    }

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            activeEngine.events.collect { event ->
                log.d {
                    "engine event type=${event.type} requestId=${event.requestId} dropped=${event.droppedEvents} " +
                        "torrent=${diagnosticId(event.torrentId)} stream=${diagnosticId(event.streamId)} " +
                        "message=${diagnosticMessage(event.message)}"
                }
                if (engine !== activeEngine) return@collect
                when (event.type) {
                    NuvioEventType.TorrentError -> synchronized(lifecycleLock) {
                        if (engine !== activeEngine) return@synchronized
                        val terminalError = unexpectedTorrentError(
                            requestId = event.requestId,
                            eventTorrentId = event.torrentId,
                            currentTorrentId = currentTorrentId,
                            message = event.message,
                            fallbackMessage = UNKNOWN_TORRENT_ERROR,
                        ) ?: return@synchronized
                        streamGeneration += 1
                        statsJob?.cancel()
                        statsJob = null
                        _state.value = terminalError
                    }
                    NuvioEventType.StreamStopped -> {
                        if (event.requestId != 0L) return@collect
                        synchronized(lifecycleLock) {
                            if (engine !== activeEngine) return@synchronized
                            val error = unexpectedStreamStopError(
                                requestId = event.requestId,
                                eventStreamId = event.streamId,
                                currentStreamId = currentStreamId,
                                message = event.message,
                                fallbackMessage = UNKNOWN_TORRENT_ERROR,
                            ) ?: return@synchronized
                            streamGeneration += 1
                            currentTorrentId = null
                            currentStreamId = null
                            statsJob?.cancel()
                            statsJob = null
                            _state.value = error
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startStatsPolling(
        activeEngine: NuvioEngine,
        stream: NuvioStream,
        generation: Long,
        sequence: Long,
        startedAt: Long,
        payloadDownloadBaseline: Long,
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var nextSampleAtMs = 0L
            var loggedFirstTransfer = false
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                if (_state.value is P2pStreamingState.Streaming) {
                    val route = try {
                        activeEngine.currentStreamStats(stream.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        log.w(error) { "Error sampling Nuvio Engine stream progress" }
                        null
                    }
                    val aggregate = activeEngine.stats.value
                    updateCacheState(aggregate.diskCacheUsedBytes, aggregate.diskCacheProtectedBytes)
                    val downloaded = (aggregate.totalPayloadDownloadBytes - payloadDownloadBaseline).coerceAtLeast(0L)
                    if (!loggedFirstTransfer && downloaded > 0L) {
                        loggedFirstTransfer = true
                        log.i {
                            "P2P first transfer: request=$sequence after=${elapsedMillis(startedAt)}ms " +
                                "peers=${aggregate.connectedPeers} seeds=${aggregate.connectedSeeds} " +
                                "speed=${aggregate.downloadRateBytesPerSecond}B/s"
                        }
                    }
                    val nowMs = elapsedMillis(startedAt)
                    if (nowMs >= nextSampleAtMs) {
                        nextSampleAtMs = nowMs + STREAMING_SAMPLE_INTERVAL_MS
                        log.d {
                            "P2P sample: request=$sequence after=${nowMs}ms peers=${aggregate.connectedPeers} " +
                                "seeds=${aggregate.connectedSeeds} known=${aggregate.knownPeers} " +
                                "downloading=${aggregate.downloadingPeers} unchoked=${aggregate.unchokedPeers} " +
                                "downBps=${aggregate.downloadRateBytesPerSecond} upBps=${aggregate.uploadRateBytesPerSecond} " +
                                "downloaded=$downloaded contiguous=${route?.contiguousReadyBytes ?: -1L} " +
                                "delivered=${route?.deliveredBytes ?: -1L} blockingPieces=${route?.blockingPieces ?: -1} " +
                                "blockingPiece=${route?.primaryBlockingPiece ?: -1} lastReady=${route?.lastReadyPiece ?: -1} " +
                                "demand=${route?.primaryDemandStart ?: -1L}-${route?.primaryDemandEnd ?: -1L} " +
                                "secondary=${route?.secondaryDemandStart ?: -1L}-${route?.secondaryDemandEnd ?: -1L} " +
                                "scheduled=${route?.scheduledPieces ?: -1} demands=${route?.activeDemands ?: -1} " +
                                "trackerPeers=${aggregate.trackerPeersReturned} dhtPeers=${aggregate.dhtPeersReturned} " +
                                "timedOutBlocks=${aggregate.timedOutBlockRequests} http=${aggregate.activeHttpRequests} " +
                                "diskUsed=${aggregate.diskCacheUsedBytes} overBudget=${aggregate.diskCacheOverBudget}"
                        }
                    }
                    updateStreamingIfCurrent(generation) { latest ->
                        latest.copy(
                            downloadSpeed = aggregate.downloadRateBytesPerSecond,
                            uploadSpeed = aggregate.uploadRateBytesPerSecond,
                            peers = aggregate.connectedPeers,
                            seeds = aggregate.connectedSeeds,
                            bufferProgress = route?.bufferProgress ?: latest.bufferProgress,
                            totalProgress = route?.fileProgress ?: latest.totalProgress,
                            downloadedBytes = downloaded,
                            verifiedBytes = route?.verifiedFileBytes ?: latest.verifiedBytes,
                            deliveredBytes = route?.deliveredBytes ?: latest.deliveredBytes,
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun updateCacheState(usedBytes: Long, protectedBytes: Long) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = usedBytes,
            protectedBytes = protectedBytes,
            hasMeasurement = true,
        )
    }

    private fun startStartupStatsPolling(
        activeEngine: NuvioEngine,
        generation: Long,
        phase: AtomicReference<String>,
        sequence: Long,
        startedAt: Long,
    ): Job = scope.launch {
        while (isActive) {
            val aggregate = activeEngine.stats.value
            updateConnectingIfCurrent(
                generation = generation,
                phase = phase.get(),
                downloadSpeed = aggregate.downloadRateBytesPerSecond,
                uploadSpeed = aggregate.uploadRateBytesPerSecond,
                peers = aggregate.connectedPeers,
                seeds = aggregate.connectedSeeds,
            )
            log.d {
                "P2P startup sample: request=$sequence phase=${phase.get()} after=${elapsedMillis(startedAt)}ms " +
                    "peers=${aggregate.connectedPeers} seeds=${aggregate.connectedSeeds} known=${aggregate.knownPeers} " +
                    "connecting=${aggregate.connectingPeers} handshaking=${aggregate.handshakingPeers} " +
                    "trackerReplies=${aggregate.trackerReplyEvents} trackerErrors=${aggregate.trackerErrorEvents} " +
                    "trackerPeers=${aggregate.trackerPeersReturned} dhtReplies=${aggregate.dhtReplyEvents} " +
                    "dhtPeers=${aggregate.dhtPeersReturned} connects=${aggregate.peerConnectEvents} " +
                    "connectFailures=${aggregate.peerDisconnectConnectFailures} downBps=${aggregate.downloadRateBytesPerSecond}"
            }
            delay(STARTUP_SAMPLE_INTERVAL_MS)
        }
    }

    private fun beginStreamGeneration(): Long = synchronized(lifecycleLock) {
        streamGeneration += 1
        _state.value = P2pStreamingState.Connecting()
        streamGeneration
    }

    private fun updateConnectingIfCurrent(
        generation: Long,
        phase: String,
        downloadSpeed: Long,
        uploadSpeed: Long,
        peers: Int,
        seeds: Int,
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation || _state.value !is P2pStreamingState.Connecting) {
            return@synchronized
        }
        _state.value = P2pStreamingState.Connecting(
            phase = phase,
            downloadSpeed = downloadSpeed,
            uploadSpeed = uploadSpeed,
            peers = peers,
            seeds = seeds,
        )
    }

    private fun nextRequestSequence(): Long = synchronized(lifecycleLock) {
        requestSequence += 1
        requestSequence
    }

    private fun attachStreamIfCurrent(generation: Long, torrentId: String, streamId: String): Boolean =
        synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized false
            currentTorrentId = torrentId
            currentStreamId = streamId
            true
        }

    private fun publishStreamingIfCurrent(generation: Long, state: P2pStreamingState.Streaming): Boolean =
        synchronized(lifecycleLock) {
            if (streamGeneration != generation || currentStreamId == null) return@synchronized false
            _state.value = state
            true
        }

    private fun updateStreamingIfCurrent(
        generation: Long,
        update: (P2pStreamingState.Streaming) -> P2pStreamingState.Streaming,
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized
        val current = _state.value as? P2pStreamingState.Streaming ?: return@synchronized
        _state.value = update(current)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private fun diagnosticId(value: String?): String =
        value?.trim()?.take(12)?.ifBlank { "none" } ?: "none"

    private fun diagnosticMessage(value: String?): String =
        value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

    // Same seed list TorrServerP2pBackend announces to; the engine adds DHT/PEX on top.
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://opentracker.io:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://wepzone.net:6969/announce",
        "udp://bt.bontal.net:6969/announce",
    )
}

internal fun buildNuvioEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: P2pTorrentProfile,
    diskCacheCapacityBytes: Long,
    windowBytes: Long = nuvioEngineWindowBytes(DesktopBufferPreset.Balanced),
): NuvioEngineConfig = NuvioEngineConfig(
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    // See nuvioEngineWindowBytes: the memory cache IS the download window on desktop.
    memoryCacheCapacityBytes = windowBytes,
    diskCacheCapacityBytes = diskCacheCapacityBytes,
    torrentProfile = when (torrentProfile) {
        P2pTorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        P2pTorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        P2pTorrentProfile.FAST -> NuvioTorrentProfile.Fast
    },
    uploadMode = if (uploadEnabled) NuvioUploadMode.Unlimited else NuvioUploadMode.Disabled,
    // mpv pauses its reads while its own cache is full; a route-inactivity timeout would tear the
    // stream down mid-film. Upstream disables it for the same reason.
    streamInactivityTimeoutMilliseconds = 0,
)

internal fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    return P2pStreamingState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

internal fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L ||
        eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return P2pStreamingState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

/** Null while it is still worth waiting for metadata; otherwise the user-facing reason to stop. */
internal fun metadataWaitVerdict(waitedMs: Long, stalledForMs: Long, knownPeers: Long): String? = when {
    waitedMs >= METADATA_DEADLINE_MS ->
        "Torrent metadata did not arrive within ${METADATA_DEADLINE_MS / 1000}s"
    waitedMs >= METADATA_STALL_MIN_WAIT_MS && stalledForMs >= METADATA_STALL_MS ->
        if (knownPeers == 0L) {
            "No peers found for this torrent"
        } else {
            "No reachable peers for this torrent ($knownPeers found, none answered)"
        }
    else -> null
}
