package com.nuvio.app.features.p2p

import com.nuvio.app.features.downloads.isSafeVideoDownloadReference
import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

actual object P2pStreamingEngine {
    private val log = Logger.withTag("P2pStreamingEngine")
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var currentHash: String? = null
    private var streamGeneration = 0L
    private val binary = TorrServerBinary()
    private val api = TorrServerApi(binary)

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching {
                    runBlocking { stopStreamNow(stopBinary = true) }
                }
            }.apply {
                name = "nuvio-torrserver-shutdown"
            },
        )
    }

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        stopStreamNow(stopBinary = false)
        val generation = nextStreamGeneration()
        val startupStartedAt = System.nanoTime()
        _state.value = P2pStreamingState.Connecting
        log.i {
            "P2P startup begin: hash=${request.infoHash.take(12)} " +
                "requestedFile=${request.fileIdx ?: "auto"}"
        }

        try {
            binary.start()
            ensureCurrentGeneration(generation)
            val serverReadyMs = elapsedMillis(startupStartedAt)

            api.ensurePerformanceDefaults()
            ensureCurrentGeneration(generation)
            val settingsReadyMs = elapsedMillis(startupStartedAt)

            val magnetLink = buildMagnetUri(request.infoHash, request.trackers)
            log.d { "Starting stream: $magnetLink" }

            val hash = api.addTorrent(magnetLink)
                ?: throw P2pStreamingException("Failed to add torrent")
            if (!attachTorrentIfCurrent(generation, hash)) {
                api.dropTorrent(hash)
                throw CancellationException("P2P stream start was cancelled")
            }
            val torrentAddedMs = elapsedMillis(startupStartedAt)

            val resolvedIdx = resolveFileIndex(
                hash = hash,
                requestedIdx = request.fileIdx,
                filename = request.filename,
            )
            ensureCurrentGeneration(generation)

            val streamUrl = api.getStreamUrl(magnetLink, resolvedIdx)
            log.d { "Stream URL: $streamUrl" }
            val readyMs = elapsedMillis(startupStartedAt)
            log.i {
                "P2P startup ready: hash=${hash.take(12)} file=$resolvedIdx total=${readyMs}ms " +
                    "server=${serverReadyMs}ms settings=${settingsReadyMs - serverReadyMs}ms " +
                    "add=${torrentAddedMs - settingsReadyMs}ms metadata=${readyMs - torrentAddedMs}ms"
            }

            startStatsPolling(hash, generation, startupStartedAt)

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

            streamUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrentGeneration(generation)) {
                _state.value = P2pStreamingState.Error(e.message ?: "Unknown torrent error")
            }
            throw e
        }
    }

    actual fun stopStream() {
        scheduleStop(stopBinary = false)
    }

    actual fun shutdown() {
        scheduleStop(stopBinary = true)
    }

    private fun scheduleStop(stopBinary: Boolean) {
        val hash = detachActiveStream()
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(hash, stopBinary)
        }
    }

    private suspend fun stopStreamNow(stopBinary: Boolean) {
        cleanupJob?.join()
        val hash = detachActiveStream()
        cleanupDetachedStream(hash, stopBinary)
    }

    private fun detachActiveStream(): String? {
        val detached = synchronized(lifecycleLock) {
            streamGeneration += 1
            val hash = currentHash
            val job = statsJob
            currentHash = null
            statsJob = null
            hash to job
        }
        detached.second?.cancel()
        _state.value = P2pStreamingState.Idle
        return detached.first
    }

    private suspend fun cleanupDetachedStream(hash: String?, stopBinary: Boolean) {
        if (stopBinary) {
            hash?.let {
                try {
                    api.dropTorrent(it)
                } catch (e: Exception) {
                    log.w(e) { "Error dropping torrent" }
                }
            }
        } else if (hash != null) {
            log.d {
                "Released active stream ${hash.take(12)}; TorrServer will retain it for " +
                    "$NUVIO_TORRENT_RETENTION_SECONDS seconds"
            }
        }

        if (stopBinary) {
            try {
                binary.stop()
            } catch (e: Exception) {
                log.w(e) { "Error stopping TorrServer" }
            }
        }
    }

    private fun nextStreamGeneration(): Long =
        synchronized(lifecycleLock) {
            streamGeneration += 1
            streamGeneration
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

    private fun buildMagnetUri(infoHash: String, extraTrackers: List<String>): String {
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        val trackerParams = trackers.joinToString("") { "&tr=$it" }
        return "magnet:?xt=urn:btih:$infoHash$trackerParams"
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        val deadline = System.currentTimeMillis() + 15_000L
        var files: List<TorrServerFile> = emptyList()

        while (System.currentTimeMillis() < deadline) {
            files = api.getTorrentStats(hash)?.files ?: emptyList()
            if (files.isNotEmpty()) break
            log.d { "Waiting for torrent metadata..." }
            delay(1_000L)
        }

        if (files.isEmpty()) {
            throw P2pStreamingException(
                "Torrent metadata was unavailable, so a safe video file could not be verified",
            )
        }

        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            val exact = files.firstOrNull { file ->
                file.path.isSafeVideoDownloadReference() &&
                    file.path.substringAfterLast('/').equals(name, ignoreCase = true)
            }
            if (exact != null) {
                log.d { "File resolved by exact filename match: ${exact.path} -> id=${exact.id}" }
                return exact.id
            }

            val contains = files.firstOrNull { file ->
                file.path.isSafeVideoDownloadReference() &&
                    file.path.contains(name, ignoreCase = true)
            }
            if (contains != null) {
                log.d { "File resolved by filename contains match: ${contains.path} -> id=${contains.id}" }
                return contains.id
            }
        }

        if (requestedIdx != null) {
            val torrServerIndex = requestedIdx + 1
            if (files.any { it.id == torrServerIndex && it.path.isSafeVideoDownloadReference() }) {
                log.d { "File resolved by ID offset: id=$torrServerIndex" }
                return torrServerIndex
            }
        }

        if (requestedIdx != null && requestedIdx in files.indices) {
            val positionalFile = files[requestedIdx]
            if (positionalFile.path.isSafeVideoDownloadReference()) {
                log.d { "File resolved by positional index: [$requestedIdx] -> ${positionalFile.path} (id=${positionalFile.id})" }
                return positionalFile.id
            }
        }

        val videoFile = files
            .filter { file -> file.path.isSafeVideoDownloadReference() }
            .maxByOrNull { it.length }

        val result = videoFile?.id
            ?: throw P2pStreamingException("Torrent contains no supported video files")
        log.d { "File resolved by largest video fallback: id=$result" }
        return result
    }

    private fun startStatsPolling(hash: String, generation: Long, startupStartedAt: Long) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var loggedFirstStats = false
            var loggedFirstTransfer = false
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                try {
                    val stats = api.getTorrentStats(hash)
                    val currentState = _state.value
                    if (
                        stats != null &&
                        currentState is P2pStreamingState.Streaming &&
                        isCurrentGeneration(generation)
                    ) {
                        if (!loggedFirstStats) {
                            loggedFirstStats = true
                            log.i {
                                "P2P first stats: hash=${hash.take(12)} after=${elapsedMillis(startupStartedAt)}ms " +
                                    "peers=${stats.peers} seeds=${stats.seeds} speed=${stats.downloadSpeed}B/s"
                            }
                        }
                        if (!loggedFirstTransfer && (stats.downloadSpeed > 0L || stats.loadedSize > 0L)) {
                            loggedFirstTransfer = true
                            log.i {
                                "P2P first transfer: hash=${hash.take(12)} after=${elapsedMillis(startupStartedAt)}ms " +
                                    "peers=${stats.peers} speed=${stats.downloadSpeed}B/s loaded=${stats.loadedSize}"
                            }
                        }
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
                    log.w(e) { "Stats polling error" }
                }
                delay(1_000L)
            }
        }
    }

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    )

    private class TorrServerBinary {
        private val log = Logger.withTag("TorrServerBinary")
        private val healthClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        private var process: Process? = null
        private var resolvedBinaryFile: File? = null

        val baseUrl: String get() = "http://127.0.0.1:$PORT"

        suspend fun start() = withContext(Dispatchers.IO) {
            if (isRunning()) {
                log.d { "TorrServer already running" }
                return@withContext
            }

            killOrphanedProcess()

            val binaryFile = resolveBinaryFile()
            if (!binaryFile.canExecute()) {
                binaryFile.setExecutable(true)
            }

            val configDir = DesktopStorage.rootDir.resolve("torrserver").toFile().also { it.mkdirs() }
            val processBuilder = ProcessBuilder(
                binaryFile.absolutePath,
                "--port",
                PORT.toString(),
                "--ip",
                "127.0.0.1",
                "--path",
                configDir.absolutePath,
            )
            processBuilder.directory(configDir)
            processBuilder.redirectErrorStream(true)

            log.d { "Starting TorrServer on port $PORT from ${binaryFile.absolutePath}" }
            process = processBuilder.start()

            val proc = process!!
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        log.d { "[server] $line" }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "nuvio-torrserver-output"
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (isRunning()) {
                    log.d { "TorrServer started successfully" }
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

        fun isRunning(): Boolean =
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/echo"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val response = healthClient.send(request, HttpResponse.BodyHandlers.discarding())
                response.statusCode() in 200..299
            } catch (_: Exception) {
                false
            }

        fun stop() {
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/shutdown"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                healthClient.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (_: Exception) {
            }

            process?.let { proc ->
                try {
                    if (!proc.waitFor(3_000L, TimeUnit.MILLISECONDS) && isProcessAlive(proc)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    proc.destroyForcibly()
                }
            }
            process = null
            log.d { "TorrServer stopped" }
        }

        private fun killOrphanedProcess() {
            try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/shutdown"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                healthClient.send(request, HttpResponse.BodyHandlers.discarding())
                Thread.sleep(1_000L)
                log.d { "Shut down orphaned TorrServer instance" }
            } catch (_: Exception) {
            }
        }

        private fun isProcessAlive(proc: Process?): Boolean =
            proc?.isAlive == true

        private fun resolveBinaryFile(): File {
            resolvedBinaryFile
                ?.takeIf(File::exists)
                ?.let { return it }

            configuredBinaryPath()?.let { configured ->
                val file = File(configured)
                if (file.exists()) return file.also { resolvedBinaryFile = it }
                throw P2pStreamingException("Configured TorrServer binary was not found at ${file.absolutePath}")
            }

            val platform = DesktopTorrServerPlatform.current()
            localBinaryCandidates(platform)
                .firstOrNull(File::exists)
                ?.let { return it.also { resolved -> resolvedBinaryFile = resolved } }

            extractBundledBinary(platform)?.let { return it.also { resolved -> resolvedBinaryFile = resolved } }

            throw P2pStreamingException(
                "TorrServer desktop binary not found for ${platform.resourceDir}. " +
                    "Set -Dnuvio.torrserver.binary=/absolute/path/to/TorrServer or " +
                    "NUVIO_TORRSERVER_BINARY, or bundle /torrserver/${platform.resourceDir}/${platform.binaryName}.",
            )
        }

        private fun configuredBinaryPath(): String? =
            System.getProperty("nuvio.torrserver.binary")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("NUVIO_TORRSERVER_BINARY")?.takeIf { it.isNotBlank() }

        private fun localBinaryCandidates(platform: DesktopTorrServerPlatform): List<File> =
            listOf(
                File("composeApp/build/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
                File("build/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
                File("composeApp/src/desktopMain/native/torrserver/${platform.resourceDir}/${platform.binaryName}"),
                File("composeApp/src/desktopMain/resources/torrserver/${platform.resourceDir}/${platform.binaryName}"),
                File("vendor/TorrServer/dist/${platform.distFileName}"),
                File("vendor/TorrServer/dist/${platform.binaryName}"),
            )

        private fun extractBundledBinary(platform: DesktopTorrServerPlatform): File? {
            val resource = "/torrserver/${platform.resourceDir}/${platform.binaryName}"
            val digest = P2pStreamingEngine::class.java.getResourceAsStream(resource)?.use(::sha256) ?: return null
            cleanupLegacyTempBinaries(platform)

            val dir = DesktopStorage.rootDir.resolve("torrserver/bin/${platform.resourceDir}").toFile().apply {
                check(mkdirs() || isDirectory) { "Could not create TorrServer binary directory: $absolutePath" }
            }
            val file = File(dir, contentAddressedBinaryName(platform.binaryName, digest))
            if (!file.isFile) {
                installBundledBinary(resource, file)
            }
            file.setExecutable(true)
            cleanupOutdatedBundledBinaries(dir, file)
            return file
        }

        private fun installBundledBinary(resource: String, destination: File) {
            val source = P2pStreamingEngine::class.java.getResourceAsStream(resource)
                ?: throw P2pStreamingException("Bundled TorrServer resource disappeared: $resource")
            val temporary = Files.createTempFile(destination.parentFile.toPath(), ".TorrServer-", ".tmp")
            try {
                source.use { input ->
                    Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
                }
                try {
                    Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (error: Exception) {
                    // Another Nuvio instance may have installed the same content-addressed file first.
                    if (!destination.isFile) throw error
                }
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
        }

        private fun cleanupLegacyTempBinaries(platform: DesktopTorrServerPlatform) {
            val legacyDir = File(System.getProperty("java.io.tmpdir"), "nuvio-torrserver/${platform.resourceDir}")
            legacyDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.startsWith("TorrServer-") }
                ?.forEach { stale ->
                    runCatching { Files.deleteIfExists(stale.toPath()) }
                        .onFailure { error -> log.d(error) { "Could not remove legacy TorrServer binary ${stale.absolutePath}" } }
                }
        }

        private fun cleanupOutdatedBundledBinaries(directory: File, current: File) {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it != current && it.name.startsWith("TorrServer-") }
                ?.forEach { stale ->
                    runCatching { Files.deleteIfExists(stale.toPath()) }
                        .onFailure { error -> log.d(error) { "Could not remove outdated TorrServer binary ${stale.absolutePath}" } }
                }
        }

        private fun contentAddressedBinaryName(binaryName: String, digest: String): String {
            val extensionIndex = binaryName.lastIndexOf('.').takeIf { it > 0 } ?: binaryName.length
            return "${binaryName.substring(0, extensionIndex)}-$digest${binaryName.substring(extensionIndex)}"
        }

        private fun sha256(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        companion object {
            const val PORT = 8091
            private const val STARTUP_TIMEOUT_MS = 15_000L
            private const val HEALTH_CHECK_INTERVAL_MS = 200L
        }
    }

    private data class DesktopTorrServerPlatform(
        val resourceDir: String,
        val distFileName: String,
        val binaryName: String,
    ) {
        companion object {
            fun current(): DesktopTorrServerPlatform {
                val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
                val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
                val os = when {
                    osName.contains("mac") -> DesktopTorrServerOs(resourceName = "macos", distName = "darwin")
                    osName.contains("win") -> DesktopTorrServerOs(resourceName = "windows", distName = "windows")
                    osName.contains("linux") -> DesktopTorrServerOs(resourceName = "linux", distName = "linux")
                    else -> throw P2pStreamingException("Unsupported desktop OS for TorrServer: $osName")
                }
                val archName = when (arch) {
                    "aarch64", "arm64" -> "arm64"
                    "amd64", "x86_64", "x64" -> "amd64"
                    "x86", "i386", "i686" -> "386"
                    else -> throw P2pStreamingException("Unsupported desktop architecture for TorrServer: $arch")
                }
                val extension = if (os.distName == "windows") ".exe" else ""
                return DesktopTorrServerPlatform(
                    resourceDir = "${os.resourceName}-$archName",
                    distFileName = "TorrServer-${os.distName}-$archName$extension",
                    binaryName = "TorrServer$extension",
                )
            }
        }
    }

    private data class DesktopTorrServerOs(
        val resourceName: String,
        val distName: String,
    )

    private data class TorrServerFile(
        val id: Int,
        val path: String,
        val length: Long,
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
        private val log = Logger.withTag("TorrServerApi")
        private val json = Json { ignoreUnknownKeys = true }
        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private val baseUrl: String get() = binary.baseUrl

        private var performanceDefaultsApplied = false

        suspend fun ensurePerformanceDefaults() = withContext(Dispatchers.IO) {
            if (performanceDefaultsApplied) return@withContext

            try {
                val request = buildJsonObject { put("action", "get") }
                val current = postJson("/settings", request)
                    ?: throw P2pStreamingException("TorrServer settings were unavailable")
                val configured = current.withNuvioP2pPerformanceDefaults()

                if (configured != current) {
                    val update = buildJsonObject {
                        put("action", "set")
                        put("sets", configured)
                    }
                    postJson("/settings", update)
                        ?: throw P2pStreamingException("TorrServer rejected Nuvio performance defaults")
                    log.i {
                        "Applied TorrServer defaults: connections=$NUVIO_TORRENT_CONNECTION_LIMIT " +
                            "retention=${NUVIO_TORRENT_RETENTION_SECONDS}s"
                    }
                } else {
                    log.d { "TorrServer performance defaults already applied" }
                }
                performanceDefaultsApplied = true
            } catch (e: Exception) {
                // Performance tuning must not turn a playable torrent into a startup failure.
                log.w(e) { "Could not apply TorrServer performance defaults" }
            }
        }

        suspend fun addTorrent(magnetLink: String, title: String? = null): String? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "add")
                put("link", magnetLink)
                put("save_to_db", false)
                if (title != null) put("title", title)
            }

            try {
                val response = postJson("/torrents", body)
                if (response == null) {
                    log.e { "addTorrent failed" }
                    return@withContext null
                }
                val hash = response.stringOrNull("hash")
                log.d { "Torrent added: $hash" }
                hash?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                log.e(e) { "addTorrent error" }
                null
            }
        }

        suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "get")
                put("hash", hash)
            }

            try {
                val json = postJson("/torrents", body) ?: return@withContext null
                val files = json.arrayOrEmpty("file_stats").mapIndexed { index, file ->
                    val obj = file.jsonObject
                    TorrServerFile(
                        id = obj.intOrDefault("id", index + 1),
                        path = obj.stringOrNull("path").orEmpty(),
                        length = obj.longOrDefault("length", 0L),
                    )
                }

                TorrServerStats(
                    downloadSpeed = json.longOrDefault("download_speed", 0L),
                    uploadSpeed = json.longOrDefault("upload_speed", 0L),
                    peers = json.intOrDefault("active_peers", 0),
                    seeds = json.intOrDefault("connected_seeders", 0),
                    preloadedBytes = json.longOrDefault("preloaded_bytes", 0L),
                    loadedSize = json.longOrDefault("loaded_size", 0L),
                    torrentSize = json.longOrDefault("torrent_size", 0L),
                    files = files,
                )
            } catch (e: Exception) {
                log.w(e) { "getTorrentStats error" }
                null
            }
        }

        suspend fun dropTorrent(hash: String) = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("action", "drop")
                put("hash", hash)
            }

            try {
                postJson("/torrents", body)
                log.d { "Torrent dropped: $hash" }
            } catch (e: Exception) {
                log.w(e) { "dropTorrent error" }
            }
        }

        fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
            val encodedLink = URLEncoder.encode(magnetLink, Charsets.UTF_8.name())
            return "$baseUrl/stream?link=$encodedLink&index=$fileIdx&play"
        }

        private fun postJson(path: String, body: JsonObject): JsonObject? {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            val responseBody = response.body().orEmpty()
            return if (responseBody.isBlank()) {
                JsonObject(emptyMap())
            } else {
                json.parseToJsonElement(responseBody).jsonObject
            }
        }
    }
}

internal const val NUVIO_TORRENT_CONNECTION_LIMIT = 55
internal const val NUVIO_TORRENT_RETENTION_SECONDS = 10 * 60

internal fun JsonObject.withNuvioP2pPerformanceDefaults(): JsonObject =
    JsonObject(
        toMutableMap().apply {
            this["ConnectionsLimit"] = JsonPrimitive(NUVIO_TORRENT_CONNECTION_LIMIT)
            this["TorrentDisconnectTimeout"] = JsonPrimitive(NUVIO_TORRENT_RETENTION_SECONDS)
        },
    )

private fun elapsedMillis(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrDefault(key: String, default: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.longOrDefault(key: String, default: Long): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: default

private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    this[key]?.jsonArray ?: JsonArray(emptyList())
