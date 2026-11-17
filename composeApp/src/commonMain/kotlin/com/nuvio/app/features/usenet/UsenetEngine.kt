package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The engine executable shipped beside Nuvio.exe (or on PATH in a development run); null when missing. */
internal expect fun usenetEngineExecutable(): File?

/** Where the engine keeps parsed NZBs between sessions (Cache NZB files). */
internal expect fun usenetNzbCacheDir(): File

/**
 * The built-in Usenet engine (NuvioMedia/nuvio-usenet-engine), run as a child process: NuvioTV's
 * UsenetSidecar, ported. It starts on first use with a random control token over stdin, binds only
 * 127.0.0.1, and turns an NZB plus NNTP providers into an ordinary local HTTP link that mpv plays.
 * One session at a time: opening the next release retires the previous one, leaving the player
 * releases it, and the engine stops after a quiet half minute. Closing Nuvio closes its stdin,
 * which ends the engine too.
 *
 * Not ported from the TV: result prefetching and pre-warming, startup diagnostics and the usage
 * statistics. Provider credentials only ever travel in the session request, never in arguments,
 * files or logs.
 */
object UsenetEngine {
    private val log = Logger.withTag("UsenetEngine")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val reader = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-usenet-io").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var processProfile: String? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var sessionId: String? = null
    private var playbackUrl: String? = null
    private var idleStop: Job? = null

    val isInstalled: Boolean get() = usenetEngineExecutable() != null

    fun isSessionUrl(url: String?): Boolean = url != null && UsenetEngineUrls.isSession(url)

    /**
     * Opens [stream]'s release in the engine and returns it ready to play: its url is the
     * engine's local link, and the files the release carries as subtitles come with it.
     */
    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?): StreamItem = withContext(Dispatchers.IO) {
        mutex.withLock { open(stream, season, episode) }
    }

    /** Releases the session behind [url], when it is still the one playing. */
    fun release(url: String?) {
        if (url == null || !UsenetEngineUrls.isSession(url)) return
        scope.launch { mutex.withLock { if (url == playbackUrl) releaseSessionLocked() } }
    }

    /** New tuning applies to the next session; an idle engine restarts with the new memory target. */
    fun settingsChanged() {
        scope.launch { mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    private suspend fun open(stream: StreamItem, season: Int?, episode: Int?): StreamItem {
        val sources = UsenetSettingsRepository.sources
        val configuration = UsenetSettingsRepository.engine
        val nzbUrl = requireNotNull(stream.nzbUrl) { "Not a Usenet stream" }
        val servers = stream.usenetServers?.takeIf { it.isNotEmpty() } ?: sources.servers()
        if (servers.isEmpty()) {
            throw UsenetPreparationException("Add a Usenet provider in Settings > Usenet", UsenetPreparationException.Scope.PROVIDER)
        }
        start(configuration)
        val previousId = sessionId
        try {
            val request = buildJsonObject {
                put("keepSessionId", previousId ?: "")
                put("cacheScope", "unscoped")
                put("legacyCacheScope", "unscoped")
                put("nzbUrl", nzbUrl)
                putJsonArray("servers") { servers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                stream.fileIdx?.let { put("fileIdx", it) }
                stream.fileMustInclude?.let { put("fileMustInclude", it) }
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
                putJsonObject("headers") {
                    nzbHeaders(stream, configuration).forEach { (name, value) -> put(name, value) }
                }
                putJsonObject("config") {
                    put("profile", configuration.profile)
                    put("readAhead", configuration.readAhead)
                    put("maxConnections", configuration.maxConnections)
                    put("fastMkvStartup", configuration.fastMkvStartup)
                    put("fastNzbFetch", configuration.fastNzbFetch)
                    put("cacheNzb", configuration.cacheNzb)
                    put("allowPrivateNetwork", configuration.allowPrivateNetwork)
                    put("holeFilling", configuration.holeFilling)
                    put("maxMissingArticles", configuration.maxMissingArticles)
                    put("maxConsecutiveMissing", configuration.maxConsecutiveMissing)
                }
            }
            val result = json.parseToJsonElement(control("/sessions", request.toString())).jsonObject
            val newId = result["id"]!!.jsonPrimitive.content
            val newPath = result["path"]!!.jsonPrimitive.content
            require(newId.matches(SESSION_ID) && newPath.startsWith("/stream/$newId/")) { "Unexpected engine answer" }
            val base = requireNotNull(endpoint)
            val url = UsenetEngineUrls.stream(base, newId, newPath)
            val subtitles = (result["subtitles"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val subtitleId = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.toIntOrNull() != null } ?: return@mapNotNull null
                val filename = item["filename"]?.jsonPrimitive?.contentOrNull.orEmpty()
                StreamSubtitle(
                    url = "$base/subtitle/$newId/$subtitleId/" + java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"),
                    language = item["lang"]?.jsonPrimitive?.contentOrNull ?: "und",
                    name = filename.takeIf { it.isNotBlank() },
                )
            }
            sessionId = newId
            playbackUrl = url
            previousId?.let(::retireSession)
            log.i { "Opened Usenet session for \"${stream.name}\"" }
            return stream.copy(
                url = url,
                externalUrl = null,
                infoHash = null,
                externalSubtitles = stream.externalSubtitles + subtitles,
                behaviorHints = stream.behaviorHints.copy(
                    proxyHeaders = null,
                    filename = result["filename"]?.jsonPrimitive?.contentOrNull ?: stream.behaviorHints.filename,
                    videoSize = result["size"]?.jsonPrimitive?.long ?: stream.behaviorHints.videoSize,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.i { "Usenet open failed for \"${stream.name}\": ${(e as? UsenetPreparationException)?.failure ?: e.javaClass.simpleName}" }
            if (previousId == null || sessionId != previousId || process?.isAlive != true) stopLocked()
            throw e
        }
    }

    /**
     * The NZB is downloaded with these; an addon's own User-Agent wins over the setting, and the
     * Generic setting adds none (the engine's Go client sends its own default).
     */
    private fun nzbHeaders(stream: StreamItem, configuration: UsenetConfiguration): Map<String, String> {
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val agent = configuration.userAgent
        return if (agent == null || headers.keys.any { it.equals(USER_AGENT, ignoreCase = true) }) headers
        else headers + (USER_AGENT to agent)
    }

    private fun retireSession(id: String) {
        scope.launch {
            runCatching { withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) } }
        }
    }

    private suspend fun releaseSessionLocked() {
        val id = sessionId
        sessionId = null
        playbackUrl = null
        if (id != null) {
            val answer = runCatching { withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) } }.getOrNull()
            if (answer == null) { stopLocked(); return }
        }
        // Keeps the started engine across episode changes, without provider sockets or buffers.
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(IDLE_STOP_MS)
            mutex.withLock { if (sessionId == null) stopLocked() }
        }
    }

    private fun start(configuration: UsenetConfiguration) {
        idleStop?.cancel(); idleStop = null
        if (process?.isAlive == true && endpoint != null &&
            (processProfile == configuration.profile || sessionId != null)
        ) {
            return
        }
        stopLocked()
        val binary = usenetEngineExecutable()
            ?: throw UsenetPreparationException("The Usenet engine is not installed with this build", UsenetPreparationException.Scope.ENGINE)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val child = ProcessBuilder(binary.absolutePath).start()
        process = child
        processProfile = configuration.profile
        reader.execute { drainQuietly(child.errorStream) }
        val cacheDir = usenetNzbCacheDir().apply { mkdirs() }
        val bootstrap = buildJsonObject {
            put("token", secret)
            put("nzbCacheDir", cacheDir.absolutePath)
            put("memoryMiB", when (configuration.profile) { "low-memory" -> 96; "throughput" -> 384; else -> 192 })
            // Go reads the Windows certificate store itself; no roots need to be passed.
            putJsonArray("certificates") {}
        }
        // Stdin stays open while the engine runs: its end (or Nuvio's) stops the engine.
        child.outputStream.write((bootstrap.toString() + "\n").toByteArray())
        child.outputStream.flush()
        val ready = reader.submit<String> {
            child.inputStream.bufferedReader().readLine() ?: throw IOException("Usenet engine exited before startup")
        }
        try {
            val record = json.parseToJsonElement(ready.get(15, TimeUnit.SECONDS)).jsonObject
            require(record["protocol"]?.jsonPrimitive?.int == 1)
            val port = record["port"]!!.jsonPrimitive.int
            require(port in 1..65535)
            endpoint = "http://127.0.0.1:$port"
            token = secret
            UsenetEngineUrls.remember(port)
            log.i { "Usenet engine started on 127.0.0.1:$port" }
        } catch (e: Exception) {
            ready.cancel(true)
            stopLocked()
            throw UsenetPreparationException("The Usenet engine did not start", UsenetPreparationException.Scope.ENGINE)
        }
    }

    private suspend fun control(path: String, payload: String?, delete: Boolean = false): String {
        val base = endpoint ?: throw UsenetPreparationException("The Usenet engine is not running", UsenetPreparationException.Scope.ENGINE)
        val builder = HttpRequest.newBuilder(URI("$base$path"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $token")
        if (delete) builder.DELETE()
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.orEmpty()))
        val response = try {
            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UsenetPreparationException("Could not reach the Usenet engine", UsenetPreparationException.Scope.ENGINE)
        }
        val text = response.body().orEmpty()
        if (response.statusCode() in 200..299) return text
        val failure = response.headers().firstValue("X-Usenet-Failure").orElse(null)
        val scope = when (failure) {
            "configuration" -> UsenetPreparationException.Scope.ENGINE
            "provider" -> UsenetPreparationException.Scope.PROVIDER
            else -> UsenetPreparationException.Scope.SOURCE
        }
        throw UsenetPreparationException(
            text.take(240).ifBlank { "Usenet engine error ${response.statusCode()}" },
            scope,
            failure,
            providers = response.headers().firstValue("X-Usenet-Providers").orElse(null),
        )
    }

    private fun stopLocked() {
        idleStop?.cancel(); idleStop = null
        val child = process
        process = null; processProfile = null; endpoint = null; token = null; sessionId = null; playbackUrl = null
        if (child != null) {
            runCatching { child.outputStream.close() }
            runCatching {
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
            }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        }
    }

    private fun drainQuietly(input: InputStream) {
        runCatching { input.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) Unit } }
    }

    private val SESSION_ID = Regex("[0-9a-f]{48}")
    private const val IDLE_STOP_MS = 30_000L
}
