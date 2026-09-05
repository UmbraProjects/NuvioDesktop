package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val TRAILER_EXTRACTOR_TAG = "InAppYouTubeExtractor"
internal const val TRAILER_REQUEST_TIMEOUT_MS = 20_000L

private const val EXTRACTOR_TIMEOUT_MS = 30_000L

/**
 * How long a cached watch config is trusted. `visitorData` is a session token that YouTube
 * eventually retires; when it does, every client answers LOGIN_REQUIRED. Three hours keeps
 * a browsing session on one fetch while staying well inside that lifetime, and a stale token
 * is recovered by the one-shot refresh retry rather than by this bound.
 */
private const val WATCH_CONFIG_TTL_MS = 3L * 60L * 60L * 1000L
private const val PREFERRED_SEPARATE_CLIENT = "visionos"

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

private data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: JsonObject,
    val priority: Int,
)

private data class WatchConfig(
    val apiKey: String?,
    val visitorData: String?,
)

internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val height: Int,
    val fps: Int,
    val bitrate: Double,
    val ext: String,
)

private data class ManifestBestVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
)

internal data class ManifestCandidate(
    val client: String,
    val priority: Int,
    val manifestUrl: String,
    val selectedVariantUrl: String,
    val height: Int,
    val bandwidth: Long,
)

internal data class TrailerRequestResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
)

private val JSON = Json { ignoreUnknownKeys = true }

private val CLIENTS = listOf(
    // VISIONOS must stay first: it is the only client whose media URLs are unrestricted.
    // ANDROID and IOS below still return OK with a full format list, but their URLs 403 an
    // open-ended `Range: bytes=<pos>-` (which is exactly what ffmpeg/mpv opens with) and serve
    // only the first ~63 seconds of media before answering 403 to everything beyond. VISIONOS
    // URLs answer 206 to an open-ended range, stream to their full `clen`, and come with an
    // HLS manifest. The two below are kept purely as fallbacks.
    //
    // This replaced ANDROID_VR, which now returns `playabilityStatus=LOGIN_REQUIRED` with no
    // `streamingData` at all. Matches NuvioTV, which made the same switch.
    YouTubeClient(
        key = "visionos",
        id = "101",
        version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        context = jsonObjectOf(
            "clientName" to "VISIONOS",
            "clientVersion" to "1.02",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 0,
    ),
    YouTubeClient(
        key = "android",
        id = "3",
        version = "20.10.35",
        userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
        context = jsonObjectOf(
            "clientName" to "ANDROID",
            "clientVersion" to "20.10.35",
            "osName" to "Android",
            "osVersion" to "14",
            "platform" to "MOBILE",
            "androidSdkVersion" to 34,
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 1,
    ),
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "20.10.1",
        userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
        context = jsonObjectOf(
            "clientName" to "IOS",
            "clientVersion" to "20.10.1",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "17.4.0.21E219",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 2,
    ),
)

class InAppYouTubeExtractor {
    private val log = Logger.withTag(TRAILER_EXTRACTOR_TAG)

    /**
     * `INNERTUBE_API_KEY` and `visitorData` identify the *session*, not the video, so one watch
     * page serves every later extraction. Caching it removes a full page fetch (~1.3MB) from
     * each trailer resolve — which matters most while browsing, where hovering a row resolves
     * one trailer per poster.
     */
    private var cachedConfig: CachedWatchConfig? = null
    private val configMutex = Mutex()

    private data class CachedWatchConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAtMs: Long,
    )

    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerResolution = withContext(Dispatchers.Default) {
        if (youtubeUrl.isBlank()) return@withContext TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)

        val hadCachedConfig = configMutex.withLock { cachedConfig != null }
        val first = attemptExtraction(youtubeUrl, forceRefreshConfig = false)

        // A session that has expired server-side is indistinguishable from an unplayable video:
        // every client answers LOGIN_REQUIRED with no `streamingData`. Retry once against a
        // freshly fetched watch config before believing the verdict. Skipped when this attempt
        // already fetched a config, and when YouTube gave a definitive reason a new session
        // cannot change, so the common failure paths still cost a single pass.
        if (first is TrailerResolution.Available || !hadCachedConfig || !first.warrantsFreshConfig()) {
            return@withContext first
        }
        log.i { "Retrying extraction with a fresh watch config" }
        attemptExtraction(youtubeUrl, forceRefreshConfig = true)
    }

    private suspend fun attemptExtraction(youtubeUrl: String, forceRefreshConfig: Boolean): TrailerResolution =
        runCatching {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractPlaybackSourceInternal(youtubeUrl, forceRefreshConfig)
            }
        }.onFailure {
            log.w { "Trailer extractor failed for $youtubeUrl: ${it.message}" }
        }.getOrNull() ?: TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)

    private fun TrailerResolution.warrantsFreshConfig(): Boolean =
        this is TrailerResolution.Unavailable &&
            (reason == TrailerUnavailableReason.UNKNOWN || reason == TrailerUnavailableReason.AGE_RESTRICTED)

    /**
     * Returns the cached watch config, fetching one only when absent, older than
     * [WATCH_CONFIG_TTL_MS], or when [forceRefresh] is set. The fetch happens under the mutex so
     * a burst of concurrent resolves triggers a single page load rather than one each.
     *
     * The requested video's own watch page is used, so the first extraction costs exactly what
     * it always did and no placeholder video ID is needed.
     */
    private suspend fun ensureWatchConfig(videoId: String, forceRefresh: Boolean): CachedWatchConfig =
        configMutex.withLock {
            if (!forceRefresh) {
                cachedConfig?.takeIf { !it.isStale() }?.let { return@withLock it }
            }

            val watchResponse = TrailerExtractionPlatform.performRequest(
                url = "https://www.youtube.com/watch?v=$videoId&hl=en",
                method = "GET",
                headers = TrailerExtractionPlatform.defaultHeaders,
                body = null,
                timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
            )
            if (!watchResponse.ok) {
                // A stale config still usually works; failing outright would turn a transient
                // network blip into an unplayable trailer.
                cachedConfig?.let { stale ->
                    log.w { "Watch page failed (${watchResponse.status}), reusing cached config" }
                    return@withLock stale
                }
                throw IllegalStateException("Failed to fetch watch page (${watchResponse.status})")
            }

            val parsed = getWatchConfig(watchResponse.body)
            val apiKey = parsed.apiKey
                ?: throw IllegalStateException("Unable to extract INNERTUBE_API_KEY")
            CachedWatchConfig(
                apiKey = apiKey,
                visitorData = parsed.visitorData,
                fetchedAtMs = LibraryClock.nowEpochMs(),
            ).also {
                cachedConfig = it
                log.i { "Watch config cached (visitorData=${!it.visitorData.isNullOrBlank()})" }
            }
        }

    private fun CachedWatchConfig.isStale(): Boolean =
        LibraryClock.nowEpochMs() - fetchedAtMs > WATCH_CONFIG_TTL_MS

    private suspend fun extractPlaybackSourceInternal(
        youtubeUrl: String,
        forceRefreshConfig: Boolean,
    ): TrailerResolution {
        val videoId = extractVideoId(youtubeUrl)
            ?: return TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)

        val watchConfig = ensureWatchConfig(videoId, forceRefreshConfig)
        val apiKey = watchConfig.apiKey

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        // Kept for messaging only: when every client comes back with no streamingData, this is
        // YouTube's own explanation (region block, age gate, removed video, ...), read from
        // whichever client responded last.
        var lastPlayabilityStatus: JsonObject? = null

        for (client in CLIENTS) {
            runCatching {
                val playerResponse = fetchPlayerResponse(
                    apiKey = apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = watchConfig.visitorData,
                )

                val streamingData = playerResponse.objectValue("streamingData")
                if (streamingData == null) {
                    lastPlayabilityStatus = playerResponse.objectValue("playabilityStatus")
                        ?: lastPlayabilityStatus
                    return@runCatching
                }
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }

                for (format in streamingData.listObjectValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (
                        format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                            ?: 0.0
                        ).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate")
                        ?: format.numberValue("averageBitrate")
                        ?: 0.0

                    progressive += StreamCandidate(
                        client = client.key,
                        priority = client.priority,
                        url = url,
                        score = videoScore(height, fps, bitrate),
                        hasN = hasNParam(url),
                        height = height,
                        fps = fps,
                        bitrate = bitrate,
                        ext = if (mimeType.contains("webm")) "webm" else "mp4",
                    )
                }

                for (format in streamingData.listObjectValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    val hasVideo = mimeType.contains("video/")
                    val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")

                    if (hasVideo) {
                        val height = (
                            format.numberValue("height")
                                ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                                ?: 0.0
                            ).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            height = height,
                            fps = fps,
                            bitrate = bitrate,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4",
                        )
                    } else if (hasAudio) {
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0
                        val audioSampleRate = format.numberValue("audioSampleRate") ?: 0.0

                        adaptiveAudio += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = audioScore(bitrate, audioSampleRate),
                            hasN = hasNParam(url),
                            height = 0,
                            fps = 0,
                            bitrate = bitrate,
                            ext = if (mimeType.contains("webm")) "webm" else "m4a",
                        )
                    }
                }
            }
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            val reason = classifyUnavailability(lastPlayabilityStatus)
            log.i { "No streams for $videoId: playabilityStatus=$lastPlayabilityStatus reason=$reason" }
            return TrailerResolution.Unavailable(reason)
        }

        var bestManifest: ManifestCandidate? = null
        for ((clientKey, priority, manifestUrl) in manifestUrls) {
            runCatching {
                val variant = parseHlsManifest(manifestUrl) ?: return@runCatching
                val candidate = ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth,
                )
                if (
                    bestManifest == null ||
                    candidate.height > bestManifest.height ||
                    (candidate.height == bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)
                ) {
                    bestManifest = candidate
                }
            }
        }

        val bestProgressive = sortCandidates(progressive).firstOrNull()
        val bestVideo = pickBestVideoForClient(
            items = adaptiveVideo,
            clientKey = PREFERRED_SEPARATE_CLIENT,
            preferredHeights = TrailerExtractionPlatform.preferredSeparateVideoHeights,
            preferClient = TrailerExtractionPlatform.preferSeparateVideoClient,
        )
        val bestAudio = pickBestForClient(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)

        log.i {
            "Selected trailer streams adaptive=${bestVideo?.height ?: 0}p/${bestVideo?.fps ?: 0}fps " +
                "progressive=${bestProgressive?.height ?: 0}p manifest=${bestManifest?.height ?: 0}p " +
                "separateAudio=${bestAudio != null}"
        }

        val source = TrailerExtractionPlatform.buildPlaybackSource(
            bestManifest = bestManifest,
            bestProgressive = bestProgressive,
            bestVideo = bestVideo,
            bestAudio = bestAudio,
        )
        return source?.let { TrailerResolution.Available(it) }
            ?: TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
    }

    /**
     * Reads YouTube's own explanation for why a video has no streamable formats. The reason
     * text is a fixed English phrase (all clients request `hl=en`), so substring matching is
     * reliable — e.g. region blocks always read "not available in your country".
     */
    private fun classifyUnavailability(status: JsonObject?): TrailerUnavailableReason {
        val playabilityText = listOfNotNull(
            status?.stringValue("reason"),
            status?.objectValue("errorScreen")
                ?.objectValue("playerErrorMessageRenderer")
                ?.objectValue("subreason")
                ?.stringValue("simpleText"),
        ).joinToString(" ").lowercase()

        return when {
            playabilityText.contains("country") || playabilityText.contains("region") ->
                TrailerUnavailableReason.REGION_BLOCKED
            status?.stringValue("status").equals("LOGIN_REQUIRED", ignoreCase = true) ||
                playabilityText.contains("sign in to confirm") ->
                TrailerUnavailableReason.AGE_RESTRICTED
            playabilityText.contains("removed") || playabilityText.contains("private") ||
                playabilityText.contains("no longer available") ->
                TrailerUnavailableReason.REMOVED_OR_PRIVATE
            else -> TrailerUnavailableReason.UNKNOWN
        }
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
    ): JsonObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${encodeUrlComponent(apiKey)}"

        val headers = buildMap {
            putAll(TrailerExtractionPlatform.defaultHeaders)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }

        val payload = jsonObjectOf(
            "videoId" to videoId,
            "contentCheckOk" to true,
            "racyCheckOk" to true,
            "context" to jsonObjectOf("client" to client.context),
            "playbackContext" to jsonObjectOf(
                "contentPlaybackContext" to jsonObjectOf("html5Preference" to "HTML5_PREF_WANTS"),
            ),
        )

        val response = TrailerExtractionPlatform.performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payload.toString(),
            timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
        )

        if (!response.ok) {
            val preview = response.body.take(200)
            throw IllegalStateException("player API ${client.key} failed (${response.status}): $preview")
        }

        val parsed = JSON.parseToJsonElement(response.body)
        return parsed as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = TrailerExtractionPlatform.performRequest(
            url = manifestUrl,
            method = "GET",
            headers = TrailerExtractionPlatform.defaultHeaders,
            body = null,
            timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestVariant: ManifestBestVariant? = null
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(index + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val resolution = attrs["RESOLUTION"].orEmpty()
            val (width, height) = parseResolution(resolution)
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L

            val candidate = ManifestBestVariant(
                url = absolutizeUrl(manifestUrl, nextLine),
                width = width,
                height = height,
                bandwidth = bandwidth,
            )

            if (
                bestVariant == null ||
                candidate.height > bestVariant.height ||
                (candidate.height == bestVariant.height && candidate.bandwidth > bestVariant.bandwidth) ||
                (
                    candidate.height == bestVariant.height &&
                        candidate.bandwidth == bestVariant.bandwidth &&
                        candidate.width > bestVariant.width
                    )
            ) {
                bestVariant = candidate
            }
        }

        return bestVariant
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val parsed = parseUrl(trimmed) ?: return null

        if (parsed.host.endsWith("youtu.be")) {
            val id = parsed.pathSegments.firstOrNull()
            if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) {
                return id
            }
        }

        val queryId = parsed.query["v"]?.firstOrNull()
        if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
            return queryId
        }

        if (parsed.pathSegments.size >= 2) {
            val first = parsed.pathSegments[0]
            val second = parsed.pathSegments[1]
            if ((first == "embed" || first == "shorts" || first == "live") && VIDEO_ID_REGEX.matches(second)) {
                return second
            }
        }

        return null
    }

    private fun getWatchConfig(html: String): WatchConfig {
        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(apiKey = apiKey, visitorData = visitorData)
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') {
                    inKey = false
                } else {
                    key.append(ch)
                }
                continue
            }

            if (ch == '"') {
                inQuote = !inQuote
                continue
            }

            if (ch == ',' && !inQuote) {
                val parsedKey = key.toString().trim()
                if (parsedKey.isNotEmpty()) {
                    out[parsedKey] = value.toString().trim()
                }
                key.clear()
                value.clear()
                inKey = true
                continue
            }

            value.append(ch)
        }

        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) {
            out[lastKey] = value.toString().trim()
        }

        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        if (parts.size != 2) return 0 to 0
        val width = parts[0].toIntOrNull() ?: 0
        val height = parts[1].toIntOrNull() ?: 0
        return width to height
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return QUALITY_LABEL_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean {
        return parseUrl(url)?.query?.get("n")?.firstOrNull()?.isNotBlank() == true
    }

    private fun videoScore(height: Int, fps: Int, bitrate: Double): Double {
        return height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    }

    private fun audioScore(bitrate: Double, audioSampleRate: Double): Double {
        return bitrate * 1_000_000.0 + audioSampleRate
    }

    private fun sortCandidates(items: List<StreamCandidate>): List<StreamCandidate> {
        return items.sortedWith(
            compareBy<StreamCandidate> { if (it.hasN) 1 else 0 }
                .thenByDescending { it.score }
                .thenBy { containerPreference(it.ext) }
                .thenBy { it.priority },
        )
    }

    private fun pickBestForClient(items: List<StreamCandidate>, clientKey: String): StreamCandidate? {
        // An unresolved YouTube `n` parameter rate-limits direct media URLs. Prefer an
        // unthrottled response from any client before applying the client preference.
        val unthrottled = items.filterNot { it.hasN }
        val pool = unthrottled.ifEmpty { items }
        val sameClient = pool.filter { it.client == clientKey }
        if (sameClient.isNotEmpty()) {
            return sortCandidates(sameClient).firstOrNull()
        }
        return sortCandidates(pool).firstOrNull()
    }

    private fun pickBestVideoForClient(
        items: List<StreamCandidate>,
        clientKey: String,
        preferredHeights: List<Int>,
        preferClient: Boolean,
    ): StreamCandidate? {
        val availableHeights = items.map { it.height }.filter { it > 0 }.distinct()
        val targetHeight = preferredHeights.firstOrNull { it in availableHeights }
            ?: availableHeights.filter { it < (preferredHeights.lastOrNull() ?: 1080) }.maxOrNull()
            ?: availableHeights.maxOrNull()
            ?: return null
        val resolutionPool = items.filter { it.height == targetHeight }
        // At accelerated playback, 60 fps content makes the decoder/render path process
        // 120 frames per wall-clock second. Prefer the best <=30 fps encode at the same
        // resolution; retain 60 fps only when it is the sole option.
        val frameRatePool = resolutionPool.filter { it.fps in 1..30 }.ifEmpty { resolutionPool }
        val unthrottled = frameRatePool.filterNot { it.hasN }
        val networkPool = unthrottled.ifEmpty { frameRatePool }
        val clientPool = if (preferClient) {
            networkPool.filter { it.client == clientKey }.ifEmpty { networkPool }
        } else {
            networkPool
        }
        // At a fixed resolution/frame-rate class, bitrate is the best useful proxy for
        // visual quality. This deliberately beats client and container preferences.
        return clientPool.sortedWith(
            compareByDescending<StreamCandidate> { it.bitrate }
                .thenBy { containerPreference(it.ext) }
                .thenBy { it.priority },
        ).firstOrNull()
    }

    private fun containerPreference(ext: String): Int {
        return when (ext.lowercase()) {
            "mp4", "m4a" -> 0
            "webm" -> 1
            else -> 2
        }
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative
        }
        if (maybeRelative.startsWith('/')) {
            val scheme = baseUrl.substringBefore("://", "https")
            val host = baseUrl.substringAfter("://", "").substringBefore('/')
            return if (host.isNotBlank()) "$scheme://$host$maybeRelative" else maybeRelative
        }
        val baseDir = baseUrl.substringBeforeLast('/', missingDelimiterValue = baseUrl)
        return "$baseDir/$maybeRelative"
    }

    private fun encodeUrlComponent(value: String): String {
        return value
            .replace("%", "%25")
            .replace("+", "%2B")
            .replace(" ", "%20")
            .replace("&", "%26")
            .replace("=", "%3D")
    }
}

private data class ParsedUrl(
    val host: String,
    val pathSegments: List<String>,
    val query: Map<String, List<String>>,
)

private fun parseUrl(input: String): ParsedUrl? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }

    val withoutFragment = normalized.substringBefore('#')
    val withoutScheme = withoutFragment.substringAfter("://", withoutFragment)
    val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    if (host.isBlank()) return null

    val pathAndQuery = withoutScheme.removePrefix(host)
    val path = when {
        pathAndQuery.startsWith("/") -> pathAndQuery.substringBefore('?')
        pathAndQuery.startsWith("?") || pathAndQuery.isBlank() -> "/"
        else -> "/${pathAndQuery.substringBefore('?')}"
    }
    val queryString = withoutFragment.substringAfter('?', "")
    val query = LinkedHashMap<String, MutableList<String>>()
    queryString.split('&')
        .filter { it.isNotBlank() }
        .forEach { pair ->
            val key = pair.substringBefore('=').trim()
            if (key.isBlank()) return@forEach
            val value = pair.substringAfter('=', "")
            query.getOrPut(key) { mutableListOf() }.add(value)
        }

    return ParsedUrl(
        host = host,
        pathSegments = path.trim('/').split('/').filter { it.isNotBlank() },
        query = query,
    )
}

private fun JsonObject.objectValue(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.listObjectValue(key: String): List<JsonObject> {
    return (this[key] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
}

private fun JsonObject.stringValue(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.toString().trim('"')
}

private fun JsonObject.numberValue(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.toString().trim('"').toDoubleOrNull()
}

private fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val mapped = LinkedHashMap<String, JsonElement>()
    pairs.forEach { (key, value) ->
        value?.let { mapped[key] = toJsonElement(it) }
    }
    return JsonObject(mapped)
}

private fun toJsonElement(value: Any): JsonElement {
    return when (value) {
        is JsonElement -> value
        is JsonObject -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> {
            val map = LinkedHashMap<String, JsonElement>()
            value.forEach { (key, nestedValue) ->
                val parsedKey = key?.toString() ?: return@forEach
                if (nestedValue != null) {
                    map[parsedKey] = toJsonElement(nestedValue)
                }
            }
            JsonObject(map)
        }
        is List<*> -> JsonArray(value.mapNotNull { it?.let(::toJsonElement) })
        else -> JsonPrimitive(value.toString())
    }
}
