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
import kotlin.concurrent.Volatile
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
import kotlinx.serialization.json.booleanOrNull
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

/** Whether a marker file of this name sits in the data folder (experiments without a setting). */
internal expect fun usenetMarker(name: String): Boolean

/**
 * A seek cancels the articles still downloading for the old position. The engine's own default
 * closes the connection of any with more than 64 KiB left (it measured faster seeks that way, in a
 * simulation without TLS); on a real TLS provider every closed socket costs a reconnect. Measured
 * on the TV, Nov 17 (same film, alternating seeks): finishing articles up to 1 MiB instead took the
 * median seek from 2.85 s to 1.75 s, reopened 0 connections instead of 111 and had no provider
 * errors. So Nuvio asks for 1 MiB; a `usenet-no-drain-seek` file in the data folder restores the
 * engine's default for comparison.
 */
internal const val USENET_NO_DRAIN_MARKER = "usenet-no-drain-seek"
private const val USENET_DRAIN_BYTES = 1L shl 20

internal fun usenetSeekDrain(): Boolean = !usenetMarker(USENET_NO_DRAIN_MARKER)

/**
 * Experiment: a `usenet-pipeline-1` file queues one body per connection instead of the profile's
 * four, so a seek's first article never waits behind another (simulated: 24 MiB after a seek in
 * 1.57 s instead of 1.92 s). Only with [PIPELINE_ONE_MIN_CONNECTIONS] or more connections: with
 * fewer, one body per connection cannot keep a 4K stream fed.
 */
internal const val USENET_PIPELINE_ONE_MARKER = "usenet-pipeline-1"
internal const val PIPELINE_ONE_MIN_CONNECTIONS = 24

/**
 * Experiment: a `usenet-readahead-ramp` file makes each player request start with 4 articles in
 * flight and double as it is read, instead of the whole window at once. Simulated: the first 3 MB
 * after a seek in 1.12 s instead of 1.57 s, the first 6 MB in about 2.1 s instead of 1.57 s.
 */
internal const val USENET_RAMP_MARKER = "usenet-readahead-ramp"

/**
 * The engine locks a file's fixed article grid (as AIOStreams does) so a seek computes its article
 * instead of guessing from the NZB's encoded sizes, each wrong guess a provider round trip. A
 * `usenet-no-grid-lock` file turns that off, to compare the "wrong guesses" in UsenetSeek lines.
 */
internal const val USENET_NO_GRID_MARKER = "usenet-no-grid-lock"

/**
 * Experiment for lines faster than one stream fills (~30 MB/s measured on a ~400 Mbit line): a
 * `usenet-wide-window` file lets a stream keep 64 articles / 48 MiB in flight instead of 32 / 32.
 * Needs the Throughput profile, whose article memory (128 MiB) allows 48 MiB of read-ahead.
 */
internal const val USENET_WIDE_MARKER = "usenet-wide-window"
private const val WIDE_READ_AHEAD_ARTICLES = 64
private const val WIDE_READ_AHEAD_MIB = 48
private const val USENET_RAMP_FIRST_ARTICLES = 4

internal fun usenetPipelineOne(connections: Int): Boolean =
    connections >= PIPELINE_ONE_MIN_CONNECTIONS && usenetMarker(USENET_PIPELINE_ONE_MARKER)

/** The connections the first (preferred) server in [servers] allows, from its "/N" suffix. */
internal fun usenetPreferredConnections(servers: List<String>): Int =
    servers.firstOrNull()?.substringBefore('?')?.substringAfterLast('/')?.toIntOrNull() ?: 0

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
 * Not ported from the TV: result prefetching and pre-warming and startup diagnostics; Nuvio's
 * own usage counts are [UsenetStats]. Provider credentials only ever travel in the session
 * request, never in arguments, files or logs.
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
    @Volatile private var processProfile: String? = null
    @Volatile private var endpoint: String? = null
    @Volatile private var token: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var playbackUrl: String? = null
    @Volatile private var liveSessionProviders: Pair<String, List<String>>? = null
    private var idleStop: Job? = null

    val isInstalled: Boolean get() = usenetEngineExecutable() != null

    fun isSessionUrl(url: String?): Boolean = url != null && UsenetEngineUrls.isSession(url)

    /** True when [stream]'s release already failed to open this run (see [UsenetFailedReleases]). */
    fun hasFailed(stream: StreamItem): Boolean =
        UsenetFailedReleases.hasFailed(stream) || UsenetCompressedReleases.skipped(stream)

    /**
     * Opens [stream]'s release in the engine and returns it ready to play: its url is the
     * engine's local link, and the files the release carries as subtitles come with it.
     */
    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?): StreamItem = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                open(stream, season, episode)
            } catch (first: UsenetPreparationException) {
                // Another upload of the same release ("Show each release once" hid it) can have the
                // articles this one lacks. A release that is unplayable as such (a password, a
                // compressed 7z over the limit) is the same in every upload: not retried.
                if (!usenetIncompleteUpload(first)) throw first
                for (alternate in stream.usenetAlternates) {
                    val other = stream.copy(nzbUrl = alternate, usenetAlternates = emptyList())
                    if (UsenetFailedReleases.hasFailed(other)) continue
                    log.i { "Usenet: trying another upload of \"${stream.name}\"" }
                    try {
                        return@withLock open(other, season, episode)
                    } catch (e: UsenetPreparationException) {
                        if (!usenetIncompleteUpload(e)) throw e
                    }
                }
                throw first
            }
        }
    }

    /** Releases the session behind [url], when it is still the one playing. */
    fun release(url: String?) {
        if (url == null || !UsenetEngineUrls.isSession(url)) return
        scope.launch { mutex.withLock { if (url == playbackUrl) releaseSessionLocked() } }
    }

    /**
     * The engine's counters for the session playing [url] (GET /sessions/<id>/diagnostics), or
     * null when it is not the current session or the engine does not answer within a second.
     * Never waits for the session lock, so a long open cannot hold it up; the answer holds only
     * counts and timings, no URLs, names or credentials.
     */
    suspend fun diagnostics(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val id = sessionId ?: return@withContext null
        val base = endpoint ?: return@withContext null
        if (url != playbackUrl) return@withContext null
        val request = HttpRequest.newBuilder(URI("$base/sessions/$id/diagnostics"))
            .timeout(Duration.ofSeconds(1))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        runCatching {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() in 200..299) json.parseToJsonElement(response.body()).jsonObject else null
        }.getOrNull()?.also { UsenetStats.sessionCounters(id, it) }
    }

    /** Live session snapshot (providers, completion, TTFB, NNTP errors) for mpv's diagnostics overlay. */
    suspend fun liveStats(url: String): UsenetLiveStats? {
        val raw = diagnostics(url) ?: return null
        val names = liveSessionProviders?.takeIf { it.first == url }?.second.orEmpty()
        return UsenetLiveStats.fromDiagnostics(raw, names)
    }

    /** A closing session's last counters for Usenet info; the engine forgets them on DELETE. */
    private suspend fun recordFinalCounters(id: String) {
        val base = endpoint ?: return
        val request = HttpRequest.newBuilder(URI("$base/sessions/$id/diagnostics"))
            .timeout(Duration.ofSeconds(1))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        runCatching {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() in 200..299) {
                UsenetStats.sessionCounters(id, json.parseToJsonElement(response.body()).jsonObject)
            }
        }
        UsenetStats.sessionClosed(id)
    }

    /** The performance profile the running engine was started with. */
    val runningProfile: String? get() = processProfile

    /** New tuning applies to the next session; an idle engine restarts with the new memory target. */
    fun settingsChanged() {
        scope.launch { mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    /** Stops the child process immediately on application exit or OS shutdown. */
    fun shutdown() {
        val child = process
        if (child != null) {
            runCatching { child.outputStream.close() }
            runCatching { child.destroyForcibly() }
        }
    }

    private suspend fun open(stream: StreamItem, season: Int?, episode: Int?): StreamItem {
        val sources = UsenetSettingsRepository.sources
        val configuration = UsenetSettingsRepository.engine
        val nzbUrl = requireNotNull(stream.nzbUrl) { "Not a Usenet stream" }
        val addonServers = stream.usenetServers?.takeIf { it.isNotEmpty() }
        val enabledProviders = sources.providers.filter { it.enabled }
        val servers = addonServers ?: sources.servers()
        // Usenet info names the engine's server indexes by provider; an addon's own servers are not ours.
        val providerIds = if (addonServers != null) emptyList() else enabledProviders.map { it.id }
        val providerNames = if (addonServers != null) {
            addonServers.map(::usenetServerDisplayName)
        } else {
            enabledProviders.map { it.name.takeIf { n -> n.isNotBlank() } ?: it.host }
        }
        val cachedNzb = configuration.cacheNzb && UsenetKeptNzbs.isKept(nzbUrl)
        if (servers.isEmpty()) {
            throw UsenetPreparationException("Add a Usenet provider in Settings > Usenet", UsenetPreparationException.Scope.PROVIDER)
        }
        start(configuration)
        val previousId = sessionId
        val pipelineOne = usenetPipelineOne(
            usenetPreferredConnections(servers).let { if (configuration.maxConnections > 0) minOf(it, configuration.maxConnections) else it },
        )
        UsenetSeekProbe.pipelineOne = pipelineOne
        val ramp = usenetMarker(USENET_RAMP_MARKER)
        UsenetSeekProbe.ramp = ramp
        val noGrid = usenetMarker(USENET_NO_GRID_MARKER)
        UsenetSeekProbe.noGrid = noGrid
        val wide = usenetMarker(USENET_WIDE_MARKER)
        UsenetSeekProbe.wide = wide
        try {
            val request = buildJsonObject {
                put("keepSessionId", previousId ?: "")
                put("cacheScope", "unscoped")
                put("legacyCacheScope", "unscoped")
                put("nzbUrl", nzbUrl)
                // An addon's row can carry "{{password}}" in its name too.
                (stream.usenetPassword ?: usenetNamePassword(stream.behaviorHints.filename ?: stream.title.orEmpty()).second)
                    ?.let { put("password", it) }
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
                    put("readAhead", if (wide) maxOf(configuration.readAhead, WIDE_READ_AHEAD_ARTICLES) else configuration.readAhead)
                    put("readAheadMiB", if (wide) WIDE_READ_AHEAD_MIB else 0)
                    put("maxConnections", configuration.maxConnections)
                    put("fastMkvStartup", configuration.fastMkvStartup)
                    put("fastNzbFetch", configuration.fastNzbFetch)
                    put("cacheNzb", configuration.cacheNzb)
                    put("allowPrivateNetwork", configuration.allowPrivateNetwork)
                    put("holeFilling", configuration.holeFilling)
                    put("maxMissingArticles", configuration.maxMissingArticles)
                    put("maxConsecutiveMissing", configuration.maxConsecutiveMissing)
                    put("maxCompressedBytes", configuration.maxCompressedBytes)
                    put("abortDrainBytes", if (usenetSeekDrain()) USENET_DRAIN_BYTES else 0L)
                    put("pipeline", if (pipelineOne) 1 else 0)
                    put("readAheadRamp", if (ramp) USENET_RAMP_FIRST_ARTICLES else 0)
                    put("noPartGrid", noGrid)
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
            liveSessionProviders = url to providerNames
            previousId?.let(::retireSession)
            UsenetStats.sessionOpened(newId, providerIds)
            UsenetFailedReleases.forget(stream)
            if (result["compressed7z"]?.jsonPrimitive?.booleanOrNull == true) {
                UsenetCompressedReleases.record(nzbUrl)
            }
            val nzbCache = (result["startup"] as? JsonObject)?.get("nzbCache") as? JsonObject
            log.i {
                "NZB " + when (nzbCache?.get("lookup")?.jsonPrimitive?.contentOrNull) {
                    "hit" -> "from the cache (no indexer download)"
                    "miss" -> "downloaded from the indexer (cache: " +
                        (nzbCache["reason"]?.jsonPrimitive?.contentOrNull ?: "miss") +
                        (nzbCache["write"]?.jsonPrimitive?.contentOrNull?.let { ", $it" } ?: "") + ")"
                    else -> "downloaded from the indexer (Cache NZB files off)"
                } + (if (usenetSeekDrain()) "" else "; seek drain off (usenet-no-drain-seek)") +
                    (if (pipelineOne) "; pipeline 1 (usenet-pipeline-1)" else "") +
                    (if (ramp) "; read-ahead ramp (usenet-readahead-ramp)" else "") +
                    (if (noGrid) "; grid lock off (usenet-no-grid-lock)" else "") +
                    if (wide) "; wide window (usenet-wide-window)" else ""
            }
            if (configuration.cacheNzb) {
                UsenetKeptNzbs.remember(nzbUrl, result["nzbCacheKey"]?.jsonPrimitive?.contentOrNull)
                // Rows listed before this play still read ⚡: switch them to 📖 where they are.
                if (UsenetKeptNzbs.isKept(nzbUrl)) UsenetKeptRows.mark(nzbUrl)
            }
            UsenetStats.opened(nzbUrl, grabbed = nzbCache?.get("lookup")?.jsonPrimitive?.contentOrNull != "hit", UsenetOpenOutcome.PLAYED)
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
            val preparation = e as? UsenetPreparationException
            // The engine's message names the archive problem ("encrypted 7z needs a password"); it
            // never holds URLs or credentials (the engine only passes its own errors through).
            log.i {
                "Usenet open failed for \"${stream.name}\": ${preparation?.failure ?: e.javaClass.simpleName}" +
                    (preparation?.message?.take(160)?.let { " ($it)" } ?: "")
            }
            if (preparation?.releaseUnplayable == true) {
                UsenetFailedReleases.record(stream, preparation.failure ?: "unplayable")
            }
            // The release's fault, so its indexer's; a provider or engine failure is neither.
            // Still record missing articles / provider errors on the providers that answered, since
            // failed opens never reach sessionOpened() and would otherwise leave providers at 0%.
            if (preparation != null && preparation.scope == UsenetPreparationException.Scope.SOURCE) {
                val incomplete = usenetIncompleteUpload(preparation)
                UsenetStats.opened(
                    nzbUrl,
                    grabbed = !cachedNzb,
                    outcome = if (incomplete) UsenetOpenOutcome.INCOMPLETE else UsenetOpenOutcome.FAILED,
                )
                if (incomplete) {
                    UsenetStats.providerOpenFailed(providerIds, incomplete = true)
                }
            } else if (preparation != null && preparation.scope == UsenetPreparationException.Scope.PROVIDER) {
                UsenetStats.providerOpenFailed(providerIds, incomplete = false)
            }
            if (preparation != null && usenetCompressedSkip(preparation)) {
                // Kept across restarts: it is skipped again next time, without asking the engine.
                UsenetCompressedReleases.record(nzbUrl)
            }
            when {
                // The release is bad, not the engine: keep it running so the next source opens at
                // once instead of waiting for a restart; it stops after the usual quiet period.
                preparation != null && preparation.scope != UsenetPreparationException.Scope.ENGINE &&
                    process?.isAlive == true && sessionId == previousId -> {
                    if (previousId == null) scheduleIdleStop()
                }
                previousId == null || sessionId != previousId || process?.isAlive != true -> stopLocked()
            }
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
            recordFinalCounters(id)
            runCatching { withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) } }
        }
    }

    private suspend fun releaseSessionLocked() {
        val id = sessionId
        sessionId = null
        playbackUrl = null
        liveSessionProviders = null
        if (id != null) {
            recordFinalCounters(id)
            val answer = runCatching { withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) } }.getOrNull()
            if (answer == null) { stopLocked(); return }
        }
        // Keeps the started engine across episode changes, without provider sockets or buffers.
        scheduleIdleStop()
    }

    private fun scheduleIdleStop() {
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
            releaseUnplayable = isUnplayableRelease(response.statusCode(), failure),
        )
    }

    private fun stopLocked() {
        idleStop?.cancel(); idleStop = null
        val child = process
        sessionId?.let(UsenetStats::sessionClosed)
        process = null; processProfile = null; endpoint = null; token = null; sessionId = null; playbackUrl = null; liveSessionProviders = null
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

    /** 422 is the engine's "this release cannot play"; a provider failure is not the release's. */
    internal fun isUnplayableRelease(status: Int, failure: String?): Boolean =
        status == 422 && failure != "provider" && failure != "configuration" &&
            failure?.startsWith("provider-") != true

    private val SESSION_ID = Regex("[0-9a-f]{48}")
    private const val IDLE_STOP_MS = 30_000L
}
