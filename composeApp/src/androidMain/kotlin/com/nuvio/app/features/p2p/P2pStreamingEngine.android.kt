package com.nuvio.app.features.p2p

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "P2pStreamingEngine"
private const val IDLE_TORRENT_TTL_MS = 120_000L
private const val FILE_INDEX_METADATA_TIMEOUT_MS = 15_000L
private const val FILE_INDEX_FAST_VALIDATION_TIMEOUT_MS = 10_000L
private const val FILE_INDEX_POLL_INTERVAL_MS = 250L
private const val STREAMING_CACHE_SIZE_BYTES = 128L * 1024L * 1024L
private const val STREAMING_CONNECTION_LIMIT = 80
private const val STREAMING_DISCONNECT_TIMEOUT_SECONDS = 120
private const val STREAMING_READ_AHEAD_PERCENT = 95
private const val STREAMING_PRELOAD_CACHE_PERCENT = 50
private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

actual object P2pStreamingEngine {
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var statsJob: Job? = null
    private var warmupJob: Job? = null
    private var currentHash: String? = null
    private var streamGeneration = 0L
    private var appContext: Context? = null
    private val idleDropJobs = mutableMapOf<String, Job>()
    private val binary = TorrServerBinary()
    private val api = TorrServerApi(binary)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        binary.initialize(context.applicationContext)
        if (P2pSettingsRepository.isVisible && P2pSettingsStorage.loadP2pEnabled() == true) {
            warmup()
        }
    }

    actual fun warmup() {
        if (appContext == null) return
        synchronized(lifecycleLock) {
            if (warmupJob?.isActive == true) return
            warmupJob = scope.launch {
                val startedAt = System.currentTimeMillis()
                Log.d(TAG, "warmup: starting TorrServer")
                try {
                    binary.start()
                    api.ensureStreamingSettings()
                    Log.d(TAG, "warmup: TorrServer ready in ${System.currentTimeMillis() - startedAt}ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "warmup: TorrServer failed after ${System.currentTimeMillis() - startedAt}ms", e)
                }
            }
        }
    }

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        val startAt = System.currentTimeMillis()
        val requestedHash = request.infoHash.trim().takeIf { it.isNotEmpty() }
            ?: throw P2pStreamingException("Missing torrent info hash")
        val detached = beginStreamGeneration()
        val generation = detached.generation
        detached.hash?.let(::scheduleIdleDrop)
        _state.value = P2pStreamingState.Connecting
        Log.d(
            TAG,
            "startStream[$generation]: request hash=$requestedHash fileIdx=${request.fileIdx} " +
                "filename=${request.filename.orEmpty()} magnet=${!request.magnetUri.isNullOrBlank()} " +
                "trackers=${request.trackers.size} detachedHash=${detached.hash}",
        )

        var attachedHash: String? = null
        try {
            val warmupWaitAt = System.currentTimeMillis()
            awaitWarmup()
            Log.d(TAG, "startStream[$generation]: warmup wait ${System.currentTimeMillis() - warmupWaitAt}ms")
            val binaryStartAt = System.currentTimeMillis()
            binary.start()
            Log.d(TAG, "startStream[$generation]: binary ready ${System.currentTimeMillis() - binaryStartAt}ms")
            ensureCurrentGeneration(generation)
            val settingsAt = System.currentTimeMillis()
            api.ensureStreamingSettings()
            Log.d(TAG, "startStream[$generation]: streaming settings ready ${System.currentTimeMillis() - settingsAt}ms")
            ensureCurrentGeneration(generation)

            val magnetLink = buildMagnetUri(
                infoHash = requestedHash,
                magnetUri = request.magnetUri,
                extraTrackers = request.trackers,
            )
            Log.d(TAG, "startStream[$generation]: magnet ${summarizeMagnet(magnetLink)}")

            val addAt = System.currentTimeMillis()
            val hash = api.addTorrent(magnetLink)
                ?: throw P2pStreamingException("Failed to add torrent")
            Log.d(TAG, "startStream[$generation]: addTorrent hash=$hash in ${System.currentTimeMillis() - addAt}ms")
            attachedHash = hash
            cancelIdleDrop(hash)
            if (!attachTorrentIfCurrent(generation, hash)) {
                scheduleIdleDrop(hash)
                throw CancellationException("P2P stream start was cancelled")
            }

            val requestedName = request.filename?.trim()?.takeIf { it.isNotEmpty() }
            val useEngineFileSelector = requestedName != null || request.fileIdx != null
            val resolvedIdx = if (useEngineFileSelector) {
                Log.d(
                    TAG,
                    "startStream[$generation]: using TorrServer selector filename=${requestedName.orEmpty()} " +
                        "fileIdx=${request.fileIdx ?: -1}",
                )
                null
            } else {
                val resolveAt = System.currentTimeMillis()
                resolveFileIndex(
                    hash = hash,
                    requestedIdx = request.fileIdx,
                    filename = request.filename,
                ).also {
                    Log.d(TAG, "startStream[$generation]: resolved file index=$it in ${System.currentTimeMillis() - resolveAt}ms")
                }
            }
            ensureCurrentGeneration(generation)

            val streamUrl = api.getStreamUrl(
                magnetLink = magnetLink,
                selector = TorrServerStreamSelector(
                    legacyIndex = resolvedIdx,
                    fileIdx = request.fileIdx,
                    filename = requestedName,
                ),
            )
            Log.d(TAG, "startStream[$generation]: streamUrl=${summarizeStreamUrl(streamUrl)}")

            startStatsPolling(
                hash = hash,
                generation = generation,
                fileSelection = ActiveFileSelection(
                    requestedIdx = request.fileIdx,
                    requestedFilename = request.filename,
                    resolvedIdx = resolvedIdx,
                ),
            )

            ensureCurrentGeneration(generation)
            _state.value = P2pStreamingState.Streaming(
                localUrl = streamUrl,
                downloadSpeed = 0,
                uploadSpeed = 0,
                peers = 0,
                seeds = 0,
                bufferProgress = 0f,
                totalProgress = 0f,
            )

            Log.d(TAG, "startStream[$generation]: ready in ${System.currentTimeMillis() - startAt}ms")
            streamUrl
        } catch (e: CancellationException) {
            Log.d(TAG, "startStream[$generation]: cancelled after ${System.currentTimeMillis() - startAt}ms")
            attachedHash?.takeUnless(::isHashCurrent)?.let(::scheduleIdleDrop)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "startStream[$generation]: failed after ${System.currentTimeMillis() - startAt}ms", e)
            attachedHash?.takeUnless(::isHashCurrent)?.let(::scheduleIdleDrop)
            if (isCurrentGeneration(generation)) {
                _state.value = P2pStreamingState.Error(e.message ?: "Unknown torrent error")
            }
            throw e
        }
    }

    actual fun stopStream() {
        Log.d(TAG, "stopStream: detach active stream")
        detachActiveStream()?.let(::scheduleIdleDrop)
    }

    actual fun shutdown() {
        Log.d(TAG, "shutdown: stopping engine")
        val hash = detachActiveStream()
        val idleHashes = cancelScheduledIdleDrops()
        val warmup = synchronized(lifecycleLock) {
            val job = warmupJob
            warmupJob = null
            job
        }
        warmup?.cancel()
        scope.launch {
            try {
                warmup?.join()
            } catch (_: CancellationException) {
            }

            (listOfNotNull(hash) + idleHashes)
                .distinctBy { hashKey(it) }
                .forEach {
                    try {
                        api.dropTorrent(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error dropping torrent", e)
                    }
                }

            try {
                binary.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping TorrServer", e)
            }
        }
    }

    private data class DetachedStream(
        val generation: Long,
        val hash: String?,
        val statsJob: Job?,
    )

    private data class ActiveFileSelection(
        val requestedIdx: Int?,
        val requestedFilename: String?,
        val resolvedIdx: Int?,
    )

    private fun beginStreamGeneration(): DetachedStream {
        val detached = synchronized(lifecycleLock) {
            streamGeneration += 1
            val detached = DetachedStream(
                generation = streamGeneration,
                hash = currentHash,
                statsJob = statsJob,
            )
            currentHash = null
            statsJob = null
            detached
        }
        detached.statsJob?.cancel()
        return detached
    }

    private fun detachActiveStream(): String? {
        val detached = beginStreamGeneration()
        _state.value = P2pStreamingState.Idle
        return detached.hash
    }

    private suspend fun awaitWarmup() {
        val job = synchronized(lifecycleLock) { warmupJob?.takeIf { it.isActive } }
        job?.join()
    }

    private fun attachTorrentIfCurrent(generation: Long, hash: String): Boolean =
        synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized false
            currentHash = hash
            true
        }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun isHashCurrent(hash: String): Boolean =
        synchronized(lifecycleLock) { hashMatches(currentHash, hash) }

    private fun scheduleIdleDrop(hash: String, delayMs: Long = IDLE_TORRENT_TTL_MS) {
        val key = hashKey(hash)
        if (key.isBlank()) return
        Log.d(TAG, "idleDrop: scheduling hash=$hash in ${delayMs}ms")
        val job = scope.launch {
            delay(delayMs)
            val shouldDrop = synchronized(lifecycleLock) {
                if (hashMatches(currentHash, hash)) {
                    idleDropJobs.remove(key)
                    false
                } else {
                    idleDropJobs.remove(key)
                    true
                }
            }
            if (shouldDrop) {
                Log.d(TAG, "idleDrop: dropping hash=$hash after idle ttl")
                api.dropTorrent(hash)
            } else {
                Log.d(TAG, "idleDrop: keeping hash=$hash because it is current again")
            }
        }
        synchronized(lifecycleLock) {
            if (hashMatches(currentHash, hash)) {
                Log.d(TAG, "idleDrop: skip schedule for current hash=$hash")
                job.cancel()
                return
            }
            idleDropJobs.remove(key)?.cancel()
            idleDropJobs[key] = job
        }
    }

    private fun cancelIdleDrop(hash: String) {
        synchronized(lifecycleLock) {
            idleDropJobs.remove(hashKey(hash))?.let {
                Log.d(TAG, "idleDrop: cancelled hash=$hash")
                it.cancel()
            }
        }
    }

    private fun cancelScheduledIdleDrops(): List<String> =
        synchronized(lifecycleLock) {
            val hashes = idleDropJobs.keys.toList()
            idleDropJobs.values.forEach { it.cancel() }
            idleDropJobs.clear()
            hashes
        }

    private fun hashMatches(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return hashKey(left) == hashKey(right)
    }

    private fun hashKey(hash: String): String =
        hash.trim().lowercase(Locale.US)

    private fun buildMagnetUri(
        infoHash: String,
        magnetUri: String?,
        extraTrackers: List<String>,
    ): String {
        val parsedMagnet = parseMagnetUri(magnetUri)
        val trackers = (DEFAULT_TRACKERS + parsedMagnet.trackers + extraTrackers)
            .asSequence()
            .mapNotNull(::normalizeTracker)
            .distinctBy { it.lowercase(Locale.US) }
            .toList()

        return buildString {
            append("magnet:?xt=urn:btih:")
            append(infoHash.trim())
            parsedMagnet.passthroughParams.forEach { param ->
                append('&')
                append(param)
            }
            trackers.forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, "UTF-8"))
            }
        }
    }

    private data class ParsedMagnet(
        val trackers: List<String>,
        val passthroughParams: List<String>,
    )

    private fun parseMagnetUri(magnetUri: String?): ParsedMagnet {
        val raw = magnetUri
            ?.trim()
            ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: return ParsedMagnet(trackers = emptyList(), passthroughParams = emptyList())
        val query = raw.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return ParsedMagnet(trackers = emptyList(), passthroughParams = emptyList())

        val trackers = mutableListOf<String>()
        val passthroughParams = mutableListOf<String>()
        query.split('&')
            .filter { it.isNotBlank() }
            .forEach { param ->
                val key = param.substringBefore('=').lowercase(Locale.US)
                when (key) {
                    "tr" -> trackers += decodeQueryValue(param.substringAfter('=', missingDelimiterValue = ""))
                    "xt" -> Unit
                    else -> passthroughParams += param
                }
            }
        return ParsedMagnet(
            trackers = trackers,
            passthroughParams = passthroughParams.distinct(),
        )
    }

    private fun decodeQueryValue(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrDefault(value)

    private fun normalizeTracker(value: String): String? =
        value
            .trim()
            .removePrefix("tracker:")
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun summarizeMagnet(magnetLink: String): String {
        val query = magnetLink.substringAfter('?', missingDelimiterValue = "")
        val params = query.split('&').filter { it.isNotBlank() }
        val xt = params.firstOrNull { it.startsWith("xt=", ignoreCase = true) }
            ?.substringAfter('=')
            .orEmpty()
        val dn = params.firstOrNull { it.startsWith("dn=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let(::decodeQueryValue)
            .orEmpty()
        val trackerCount = params.count { it.startsWith("tr=", ignoreCase = true) }
        return "xt=$xt dn=$dn trackers=$trackerCount length=${magnetLink.length}"
    }

    private fun summarizeStreamUrl(streamUrl: String): String {
        val index = streamUrl.substringAfter("index=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?: "?"
        val fileIdx = streamUrl.substringAfter("fileIdx=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?: "?"
        val filename = streamUrl.substringAfter("filename=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?.let(::decodeQueryValue)
            .orEmpty()
        return "index=$index fileIdx=$fileIdx filename=$filename length=${streamUrl.length}"
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        val requestedName = filename?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedIdx != null) {
            val torrServerIndex = requestedIdx + 1
            Log.d(
                TAG,
                "resolveFileIndex: requested fileIdx=$requestedIdx mapsToTorrServerId=$torrServerIndex " +
                    "filename=${requestedName.orEmpty()}",
            )

            if (requestedName != null) {
                val files = waitForTorrentFiles(
                    hash = hash,
                    timeoutMs = FILE_INDEX_FAST_VALIDATION_TIMEOUT_MS,
                    reason = "fast-validation",
                )
                if (files.isNotEmpty()) {
                    logTorrentFiles(
                        label = "fast-validation",
                        files = files,
                        requestedIdx = requestedIdx,
                    )
                    resolveByFilename(files, requestedName)?.let { match ->
                        Log.d(
                            TAG,
                            "resolveFileIndex: filename match overrides requested index " +
                                "requestedIdx=$requestedIdx requestedTorrServerId=$torrServerIndex " +
                                "matchedId=${match.id} path=${match.path}",
                        )
                        return match.id
                    }

                    val requestedIdMatch = files.firstOrNull { it.id == torrServerIndex }
                    if (requestedIdMatch != null) {
                        Log.d(
                            TAG,
                            "resolveFileIndex: using requested TorrServer id=$torrServerIndex " +
                                "path=${requestedIdMatch.path} size=${formatBytes(requestedIdMatch.length)} " +
                                "because filename=$requestedName did not match metadata",
                        )
                        return torrServerIndex
                    }

                    val positionalFile = files.getOrNull(requestedIdx)
                    if (positionalFile != null) {
                        Log.w(
                            TAG,
                            "resolveFileIndex: TorrServer id=$torrServerIndex missing; using positional file " +
                                "position=$requestedIdx id=${positionalFile.id} path=${positionalFile.path}",
                        )
                        return positionalFile.id
                    }

                    Log.w(
                        TAG,
                        "resolveFileIndex: requestedIdx=$requestedIdx is outside metadata files=${files.size}; " +
                            "falling back to TorrServer id=$torrServerIndex",
                    )
                } else {
                    Log.w(
                        TAG,
                        "resolveFileIndex: no metadata within ${FILE_INDEX_FAST_VALIDATION_TIMEOUT_MS}ms; " +
                            "using requested TorrServer id=$torrServerIndex",
                    )
                }
            } else {
                Log.d(TAG, "resolveFileIndex: no filename to validate; using requested TorrServer id=$torrServerIndex")
            }
            return torrServerIndex
        }

        Log.d(TAG, "resolveFileIndex: no requested fileIdx; waiting for metadata filename=${requestedName.orEmpty()}")
        val files = waitForTorrentFiles(
            hash = hash,
            timeoutMs = FILE_INDEX_METADATA_TIMEOUT_MS,
            reason = "full-resolution",
        )

        if (files.isEmpty()) {
            Log.w(TAG, "resolveFileIndex: no files after metadata timeout; guessing TorrServer id=1")
            return 1
        }
        logTorrentFiles(label = "full-resolution", files = files, requestedIdx = requestedIdx)

        if (requestedName != null) {
            resolveByFilename(files, requestedName)?.let { match ->
                Log.d(TAG, "resolveFileIndex: filename match id=${match.id} path=${match.path}")
                return match.id
            }
        }

        val videoFile = files
            .filter { file ->
                val ext = file.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        val result = videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
        val resultFile = files.firstOrNull { it.id == result }
        Log.d(
            TAG,
            "resolveFileIndex: largest video fallback id=$result " +
                "path=${resultFile?.path.orEmpty()} size=${resultFile?.let { formatBytes(it.length) }.orEmpty()}",
        )
        return result
    }

    private suspend fun waitForTorrentFiles(
        hash: String,
        timeoutMs: Long,
        reason: String,
    ): List<TorrServerFile> {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            attempt += 1
            val stats = api.getTorrentStats(hash)
            val files = stats?.files.orEmpty()
            if (files.isNotEmpty()) {
                Log.d(
                    TAG,
                    "metadata[$reason]: files=${files.size} after ${System.currentTimeMillis() - startedAt}ms " +
                        "attempt=$attempt peers=${stats?.peers ?: 0} seeds=${stats?.seeds ?: 0} " +
                        "download=${stats?.downloadSpeed ?: 0}Bps preload=${stats?.preloadedBytes ?: 0}",
                )
                return files
            }
            Log.d(TAG, "metadata[$reason]: waiting attempt=$attempt elapsed=${System.currentTimeMillis() - startedAt}ms")
            delay(FILE_INDEX_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "metadata[$reason]: unavailable after ${System.currentTimeMillis() - startedAt}ms")
        return emptyList()
    }

    private fun resolveByFilename(files: List<TorrServerFile>, filename: String): TorrServerFile? {
        val name = filename.trim()
        val exactBasename = files.firstOrNull { file ->
            file.path.substringAfterLast('/').equals(name, ignoreCase = true)
        }
        if (exactBasename != null) {
            Log.d(TAG, "resolveByFilename: exact basename match filename=$name id=${exactBasename.id}")
            return exactBasename
        }

        val exactPath = files.firstOrNull { file ->
            file.path.equals(name, ignoreCase = true)
        }
        if (exactPath != null) {
            Log.d(TAG, "resolveByFilename: exact path match filename=$name id=${exactPath.id}")
            return exactPath
        }

        val contains = files.firstOrNull { file ->
            file.path.contains(name, ignoreCase = true)
        }
        if (contains != null) {
            Log.d(TAG, "resolveByFilename: contains match filename=$name id=${contains.id}")
            return contains
        }

        Log.w(TAG, "resolveByFilename: no match for filename=$name")
        return null
    }

    private fun logTorrentFiles(
        label: String,
        files: List<TorrServerFile>,
        requestedIdx: Int?,
    ) {
        Log.d(TAG, "files[$label]: total=${files.size} requestedIdx=${requestedIdx ?: -1}")
        val selectedPositions = buildSet {
            addAll(0 until minOf(files.size, 25))
            requestedIdx?.let { idx ->
                add(idx)
                add(idx - 2)
                add(idx - 1)
                add(idx + 1)
                add(idx + 2)
            }
            files.indices.maxByOrNull { files[it].length }?.let(::add)
            add(files.lastIndex)
        }.filter { it in files.indices }.sorted()

        selectedPositions.forEach { position ->
            val file = files[position]
            val requestedMarker = if (
                position == requestedIdx ||
                file.id == requestedIdx?.plus(1) ||
                file.index == requestedIdx
            ) {
                " requested"
            } else {
                ""
            }
            Log.d(
                TAG,
                "files[$label]: pos=$position id=${file.id} index=${file.index}$requestedMarker " +
                    "size=${formatBytes(file.length)} path=${file.path}",
            )
        }

        val omitted = files.size - selectedPositions.size
        if (omitted > 0) {
            Log.d(TAG, "files[$label]: omitted=$omitted middle entries")
        }
    }

    private fun logStreamingFileAudit(
        files: List<TorrServerFile>,
        fileSelection: ActiveFileSelection,
    ) {
        logTorrentFiles(
            label = "streaming-audit",
            files = files,
            requestedIdx = fileSelection.requestedIdx,
        )

        val selected = fileSelection.resolvedIdx?.let { resolvedIdx ->
            files.firstOrNull { it.id == resolvedIdx }
        }
        val filename = fileSelection.requestedFilename?.trim()?.takeIf { it.isNotEmpty() }
        val filenameMatch = filename?.let { resolveByFilename(files, it) }
        val rawIndexMatch = fileSelection.requestedIdx?.let { requestedIdx ->
            files.firstOrNull { it.index == requestedIdx }
        }
        Log.d(
            TAG,
            "streaming-audit: resolvedId=${fileSelection.resolvedIdx} " +
                "selectedPath=${selected?.path.orEmpty()} selectedSize=${selected?.let { formatBytes(it.length) }.orEmpty()} " +
                "requestedIdx=${fileSelection.requestedIdx ?: -1} requestedFilename=${filename.orEmpty()} " +
                "filenameMatchId=${filenameMatch?.id ?: -1} filenameMatchPath=${filenameMatch?.path.orEmpty()} " +
                "rawIndexMatchId=${rawIndexMatch?.id ?: -1} rawIndexMatchPath=${rawIndexMatch?.path.orEmpty()}",
        )

        if (
            fileSelection.resolvedIdx != null &&
            filenameMatch != null &&
            filenameMatch.id != fileSelection.resolvedIdx
        ) {
            Log.w(
                TAG,
                "streaming-audit: FILE_SELECTION_MISMATCH resolvedId=${fileSelection.resolvedIdx} " +
                    "selectedPath=${selected?.path.orEmpty()} filenameMatchId=${filenameMatch.id} " +
                    "filenameMatchPath=${filenameMatch.path}",
            )
        }
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1_073_741_824L -> "${"%.2f".format(Locale.US, bytes / 1_073_741_824.0)}GB"
            bytes >= 1_048_576L -> "${"%.1f".format(Locale.US, bytes / 1_048_576.0)}MB"
            bytes >= 1_024L -> "${bytes / 1_024}KB"
            else -> "${bytes}B"
        }

    private fun startStatsPolling(
        hash: String,
        generation: Long,
        fileSelection: ActiveFileSelection,
    ) {
        val job = scope.launch {
            var loggedMetadataAudit = false
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                try {
                    val stats = api.getTorrentStats(hash)
                    if (!loggedMetadataAudit && stats?.files?.isNotEmpty() == true) {
                        loggedMetadataAudit = true
                        logStreamingFileAudit(
                            files = stats.files,
                            fileSelection = fileSelection,
                        )
                    }
                    val currentState = _state.value
                    if (
                        stats != null &&
                        currentState is P2pStreamingState.Streaming &&
                        isCurrentGeneration(generation)
                    ) {
                        _state.value = currentState.copy(
                            downloadSpeed = stats.downloadSpeed,
                            uploadSpeed = stats.uploadSpeed,
                            peers = stats.peers,
                            seeds = stats.seeds,
                            preloadedBytes = stats.preloadedBytes,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error", e)
                }
                delay(1_000L)
            }
        }
        val previousJob = synchronized(lifecycleLock) {
            if (streamGeneration == generation && hashMatches(currentHash, hash)) {
                val previous = statsJob
                statsJob = job
                previous
            } else {
                job.cancel()
                null
            }
        }
        previousJob?.cancel()
    }

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "https://torrent.tracker.durukanbal.com:443/announce",
        "udp://wepzone.net:6969/announce",
        "udp://tracker.wepzone.net:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.t-1.org:6969/announce",
        "udp://tracker.darkness.services:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://t.overflow.biz:6969/announce",
        "udp://open.dstud.io:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://bittorrent-tracker.e-n-c-r-y-p-t.net:1337/announce",
        "https://tracker.zhuqiy.com:443/announce",
        "https://tracker.pmman.tech:443/announce",
        "https://tracker.moeblog.cn:443/announce",
        "https://tracker.bt4g.com:443/announce",
    )

    private class TorrServerBinary {
        private var context: Context? = null
        private var process: Process? = null
        private val startMutex = Mutex()
        private val healthClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val baseUrl: String get() = "http://127.0.0.1:$PORT"

        fun initialize(context: Context) {
            this.context = context.applicationContext
        }

        suspend fun start() = startMutex.withLock {
            withContext(Dispatchers.IO) {
                if (isRunning()) {
                    Log.d(TAG, "TorrServer already running")
                    return@withContext
                }

                killOrphanedProcess()

                val ctx = requireContext()
                val binaryFile = File(ctx.applicationInfo.nativeLibraryDir, "libtorrserver.so")
                if (!binaryFile.exists()) {
                    throw P2pStreamingException("TorrServer binary not found at ${binaryFile.absolutePath}")
                }

                if (!binaryFile.canExecute()) {
                    binaryFile.setExecutable(true)
                }

                val configDir = File(ctx.filesDir, "torrserver").also { it.mkdirs() }
                val processBuilder = ProcessBuilder(
                    binaryFile.absolutePath,
                    "--port",
                    PORT.toString(),
                    "--path",
                    configDir.absolutePath,
                )
                processBuilder.directory(configDir)
                processBuilder.redirectErrorStream(true)

                Log.d(TAG, "Starting TorrServer on port $PORT from ${binaryFile.absolutePath}")
                process = processBuilder.start()

                val proc = process!!
                Thread {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            Log.d(TAG, "[server] $line")
                        }
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (isRunning()) {
                        Log.d(TAG, "TorrServer started successfully")
                        return@withContext
                    }
                    if (!isProcessAlive(process)) {
                        val exitCode = process?.exitValue() ?: -1
                        process = null
                        throw P2pStreamingException("TorrServer process died on startup (exit code $exitCode)")
                    }
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }

                stop()
                throw P2pStreamingException("TorrServer failed to start within ${STARTUP_TIMEOUT_MS / 1000}s")
            }
        }

        fun isRunning(): Boolean {
            return try {
                val request = Request.Builder().url("$baseUrl/echo").build()
                healthClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }

        fun stop() {
            try {
                val request = Request.Builder().url("$baseUrl/shutdown").build()
                healthClient.newCall(request).execute().close()
            } catch (_: Exception) {
            }

            process?.let { proc ->
                try {
                    Thread.sleep(3_000L)
                    if (isProcessAlive(proc)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    proc.destroyForcibly()
                }
            }
            process = null
            Log.d(TAG, "TorrServer stopped")
        }

        private fun killOrphanedProcess() {
            try {
                val request = Request.Builder().url("$baseUrl/shutdown").build()
                healthClient.newCall(request).execute().close()
                Thread.sleep(1_000L)
                Log.d(TAG, "Shut down orphaned TorrServer instance")
            } catch (_: Exception) {
            }
        }

        private fun isProcessAlive(proc: Process?): Boolean {
            if (proc == null) return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun requireContext(): Context =
            context ?: throw P2pStreamingException("P2P streaming engine is not initialized")

        companion object {
            const val PORT = 8091
            private const val STARTUP_TIMEOUT_MS = 15_000L
            private const val HEALTH_CHECK_INTERVAL_MS = 200L
        }
    }

    private data class TorrServerFile(
        val id: Int,
        val index: Int,
        val path: String,
        val length: Long,
    )

    private data class TorrServerStreamSelector(
        val legacyIndex: Int?,
        val fileIdx: Int?,
        val filename: String?,
    )

    private data class TorrServerStats(
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val preloadedBytes: Long,
        val loadedSize: Long,
        val torrentSize: Long,
        val files: List<TorrServerFile>,
    )

    private class TorrServerApi(
        private val binary: TorrServerBinary,
    ) {
        private val settingsMutex = Mutex()
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val baseUrl: String get() = binary.baseUrl

        suspend fun ensureStreamingSettings(): Boolean = settingsMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val settings = getSettings() ?: return@withContext false
                    val beforeSummary = summarizeSettings(settings)
                    val changes = mutableListOf<String>()

                    putIfDifferent(settings, "CacheSize", STREAMING_CACHE_SIZE_BYTES, changes)
                    putIfDifferent(settings, "ConnectionsLimit", STREAMING_CONNECTION_LIMIT, changes)
                    putIfDifferent(settings, "TorrentDisconnectTimeout", STREAMING_DISCONNECT_TIMEOUT_SECONDS, changes)
                    putIfDifferent(settings, "ReaderReadAHead", STREAMING_READ_AHEAD_PERCENT, changes)
                    putIfDifferent(settings, "PreloadCache", STREAMING_PRELOAD_CACHE_PERCENT, changes)
                    putIfDifferent(settings, "ResponsiveMode", true, changes)
                    putIfDifferent(settings, "DisableDHT", false, changes)
                    putIfDifferent(settings, "DisablePEX", false, changes)
                    putIfDifferent(settings, "DisableTCP", false, changes)
                    putIfDifferent(settings, "DisableUTP", false, changes)
                    putIfDifferent(settings, "DisableUpload", false, changes)
                    putIfDifferent(settings, "ForceEncrypt", false, changes)
                    putIfDifferent(settings, "DownloadRateLimit", 0, changes)
                    putIfDifferent(settings, "UploadRateLimit", 0, changes)
                    putIfDifferent(settings, "RetrackersMode", 1, changes)
                    putIfDifferent(settings, "EnableLPD", true, changes)
                    putIfDifferent(settings, "LPDIPv6", false, changes)
                    putIfDifferent(settings, "StoreSettingsInJson", true, changes)

                    if (changes.isEmpty()) {
                        Log.d(TAG, "streaming-settings: already tuned $beforeSummary")
                        return@withContext true
                    }

                    Log.d(
                        TAG,
                        "streaming-settings: applying ${changes.joinToString()} from $beforeSummary",
                    )
                    val body = JSONObject().apply {
                        put("action", "set")
                        put("sets", settings)
                    }
                    val request = Request.Builder()
                        .url("$baseUrl/settings")
                        .post(body.toString().toRequestBody(JSON_TYPE))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "streaming-settings: set failed code=${response.code}")
                            return@withContext false
                        }
                    }
                    Log.d(TAG, "streaming-settings: applied ${summarizeSettings(settings)}")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "streaming-settings: failed", e)
                    false
                }
            }
        }

        private fun getSettings(): JSONObject? {
            val body = JSONObject().apply {
                put("action", "get")
            }
            val request = Request.Builder()
                .url("$baseUrl/settings")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "streaming-settings: get failed code=${response.code}")
                    null
                } else {
                    JSONObject(response.body?.string()?.takeIf { it.isNotBlank() } ?: "{}")
                }
            }
        }

        private fun putIfDifferent(
            settings: JSONObject,
            key: String,
            desiredValue: Int,
            changes: MutableList<String>,
        ) {
            val hasKey = settings.has(key)
            val currentValue = settings.optInt(key, Int.MIN_VALUE)
            if (!hasKey || currentValue != desiredValue) {
                settings.put(key, desiredValue)
                changes += "$key=${formatSettingsValue(currentValue, hasKey)}->$desiredValue"
            }
        }

        private fun putIfDifferent(
            settings: JSONObject,
            key: String,
            desiredValue: Long,
            changes: MutableList<String>,
        ) {
            val hasKey = settings.has(key)
            val currentValue = settings.optLong(key, Long.MIN_VALUE)
            if (!hasKey || currentValue != desiredValue) {
                settings.put(key, desiredValue)
                changes += "$key=${formatSettingsValue(currentValue, hasKey)}->$desiredValue"
            }
        }

        private fun putIfDifferent(
            settings: JSONObject,
            key: String,
            desiredValue: Boolean,
            changes: MutableList<String>,
        ) {
            val hasKey = settings.has(key)
            val currentValue = settings.optBoolean(key, !desiredValue)
            if (!hasKey || currentValue != desiredValue) {
                settings.put(key, desiredValue)
                changes += "$key=${if (hasKey) currentValue else "unset"}->$desiredValue"
            }
        }

        private fun formatSettingsValue(value: Int, hasKey: Boolean): String =
            if (hasKey) value.toString() else "unset"

        private fun formatSettingsValue(value: Long, hasKey: Boolean): String =
            if (hasKey) value.toString() else "unset"

        private fun summarizeSettings(settings: JSONObject): String =
            "cache=${formatBytes(settings.optLong("CacheSize", 0))} " +
                "connections=${settings.optInt("ConnectionsLimit", 0)} " +
                "readAhead=${settings.optInt("ReaderReadAHead", 0)} " +
                "preload=${settings.optInt("PreloadCache", 0)} " +
                "responsive=${settings.optBoolean("ResponsiveMode", false)} " +
                "dht=${!settings.optBoolean("DisableDHT", false)} " +
                "pex=${!settings.optBoolean("DisablePEX", false)} " +
                "tcp=${!settings.optBoolean("DisableTCP", false)} " +
                "utp=${!settings.optBoolean("DisableUTP", false)} " +
                "rate=${settings.optInt("DownloadRateLimit", 0)}/${settings.optInt("UploadRateLimit", 0)}KBps"

        suspend fun addTorrent(magnetLink: String, title: String? = null): String? = withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("action", "add")
                put("link", magnetLink)
                put("save_to_db", false)
                if (title != null) put("title", title)
            }

            val request = Request.Builder()
                .url("$baseUrl/torrents")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "addTorrent failed: ${response.code}")
                        return@withContext null
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val hash = json.optString("hash", "")
                    Log.d(TAG, "Torrent added: $hash")
                    hash.ifEmpty { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addTorrent error", e)
                null
            }
        }

        suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("action", "get")
                put("hash", hash)
            }

            val request = Request.Builder()
                .url("$baseUrl/torrents")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val json = JSONObject(response.body?.string() ?: "{}")

                    val files = mutableListOf<TorrServerFile>()
                    val fileList = json.optJSONArray("file_stats") ?: JSONArray()
                    for (i in 0 until fileList.length()) {
                        val file = fileList.getJSONObject(i)
                        files.add(
                            TorrServerFile(
                                id = file.optInt("id", i + 1),
                                index = file.optInt("index", i),
                                path = file.optString("path", ""),
                                length = file.optLong("length", 0),
                            ),
                        )
                    }

                    TorrServerStats(
                        downloadSpeed = json.optLong("download_speed", 0),
                        uploadSpeed = json.optLong("upload_speed", 0),
                        peers = json.optInt("active_peers", 0),
                        seeds = json.optInt("connected_seeders", 0),
                        preloadedBytes = json.optLong("preloaded_bytes", 0),
                        loadedSize = json.optLong("loaded_size", 0),
                        torrentSize = json.optLong("torrent_size", 0),
                        files = files,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "getTorrentStats error", e)
                null
            }
        }

        suspend fun dropTorrent(hash: String) = withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("action", "drop")
                put("hash", hash)
            }

            val request = Request.Builder()
                .url("$baseUrl/torrents")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            try {
                client.newCall(request).execute().close()
                Log.d(TAG, "Torrent dropped: $hash")
            } catch (e: Exception) {
                Log.w(TAG, "dropTorrent error", e)
            }
        }

        fun getStreamUrl(
            magnetLink: String,
            selector: TorrServerStreamSelector,
        ): String {
            val params = mutableListOf(
                "link=${URLEncoder.encode(magnetLink, "UTF-8")}",
                "play",
            )
            selector.legacyIndex?.let { params += "index=$it" }
            selector.fileIdx?.let { params += "fileIdx=$it" }
            selector.filename
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { params += "filename=${URLEncoder.encode(it, "UTF-8")}" }
            return "$baseUrl/stream?${params.joinToString("&")}"
        }
    }

    private val JSON_TYPE = "application/json".toMediaType()
}
