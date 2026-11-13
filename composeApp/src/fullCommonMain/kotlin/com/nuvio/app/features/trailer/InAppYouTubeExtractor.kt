package com.nuvio.app.features.trailer

import com.nuvio.app.features.preroll.StreamLoudnessBook
import com.nuvio.app.features.preroll.streamUrls
import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicRateLimit
import com.nuvio.app.features.music.mayAskYouTube
import com.nuvio.app.features.music.playerClientsToTry
import com.nuvio.app.features.music.youTubeCaller
import com.nuvio.app.features.music.youTubeCallerLabel
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

/** Lower-case fragments of the messages YouTube shows for an age-gated video. */
private val AGE_GATE_PHRASES = listOf("your age", "age-restricted", "age restricted", "inappropriate for some users")

/**
 * How long a cached watch config is trusted. `visitorData` is a session token that YouTube
 * eventually retires; when it does, every client answers LOGIN_REQUIRED. Three hours keeps
 * a browsing session on one fetch while staying well inside that lifetime, and a stale token
 * is recovered by the one-shot refresh retry rather than by this bound.
 */
private const val WATCH_CONFIG_TTL_MS = 3L * 60L * 60L * 1000L

/**
 * How long the watch page is left alone after it answers 429. A 429 there means YouTube is
 * throttling this IP's page loads; retrying on every hero rotation is what keeps it throttled.
 * The player API is a separate endpoint and usually still answers, so extraction carries on
 * with a keyless session meanwhile.
 */
internal const val WATCH_PAGE_BACKOFF_MS = 10L * 60L * 1000L
private const val PREFERRED_SEPARATE_CLIENT = "visionos"

/** YouTube Premium's enhanced-bitrate formats (356 adaptive VP9 1080p; 616 the HLS one), unplayable without Premium. */
private val PREMIUM_ITAGS = setOf(356, 616)

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
    /** 0 when YouTube sent no width (audio, or an old format entry). */
    val width: Int = 0,
    /**
     * Audio only: whether this is the video's original language track. Null when YouTube sent no
     * `audioTrack` (a single-language video, where every audio format is the original).
     */
    val isOriginalAudio: Boolean? = null,
    /** Audio only: YouTube's `loudnessDb` for this format (how far above its reference it is), when it sent one. */
    val loudnessDb: Double? = null,
    /** Audio only: the language track this format belongs to (`audioTrack.id`, "en.4"); null on a one-language video. */
    val audioTrackId: String? = null,
    /** Audio only: that track's name as YouTube shows it ("English (United States) original"). */
    val audioTrackName: String? = null,
    /** Video only: an HDR encode (PQ or HLG; VP9 profile 2 or AV1), listed beside the SDR one of the same height. */
    val isHdr: Boolean = false,
)

/** Whether a streaming-data format is HDR: its `colorInfo` transfer is PQ or HLG, or YouTube labels it so ("2160p60 HDR"). */
internal fun isHdrYouTubeFormat(transferCharacteristics: String?, qualityLabel: String?): Boolean =
    transferCharacteristics?.let { "SMPTEST2084" in it || "ARIB_STD_B67" in it } == true ||
        qualityLabel?.contains("HDR", ignoreCase = true) == true

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
    val width: Int = 0,
)

internal data class TrailerRequestResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    /** fork:youtube-cookie-upkeep — the response's `Set-Cookie` headers, for the signed-in YouTube session. */
    val setCookies: List<String> = emptyList(),
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
        version = "21.26.364",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        context = jsonObjectOf(
            "clientName" to "ANDROID",
            "clientVersion" to "21.26.364",
            "osName" to "Android",
            "osVersion" to "11",
            "platform" to "MOBILE",
            "androidSdkVersion" to 30,
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 1,
    ),
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "21.26.4",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        context = jsonObjectOf(
            "clientName" to "IOS",
            "clientVersion" to "21.26.4",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 2,
    ),
)

// fork:ytmusic-login — clients for the signed-in retry. The mobile clients above are built for
// anonymous use and reject a request carrying account cookies, so a signed-in pass needs the
// TV clients, which accept them and are the ones that serve age-gated videos.
private val ACCOUNT_CLIENTS = listOf(
    YouTubeClient(
        key = "tv",
        id = "7",
        version = "7.20260707.07.00",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
            "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        context = jsonObjectOf(
            "clientName" to "TVHTML5",
            "clientVersion" to "7.20260707.07.00",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 0,
    ),
    // TVHTML5_SIMPLY_EMBEDDED_PLAYER is retired ("no longer supported in this application or
    // device"); TVHTML5_SIMPLY is the client that replaced it.
    YouTubeClient(
        key = "tv_simply",
        id = "75",
        version = "1.0",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        context = jsonObjectOf(
            "clientName" to "TVHTML5_SIMPLY",
            "clientVersion" to "1.0",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 1,
    ),
    // fork:yt-clients — yt-dlp's first-choice client (its defaults are tv_samsung, visionos, web) and one it treats
    // as needing no PO token: a Samsung Tizen smart-TV profile of TVHTML5. Only here, in the signed-in retry, and
    // last. Every client in a list costs one player request per resolve, so it is deliberately NOT in [CLIENTS]
    // until it has been shown to serve media URLs that last; see the po-token-minter / yt-dlp notes.
    YouTubeClient(
        key = "tv_samsung",
        id = "7",
        version = "5.20260707",
        userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) " +
            "Version/2.4.0 TV Safari/538.1",
        context = jsonObjectOf(
            "clientName" to "TVHTML5",
            "clientVersion" to "5.20260707",
            "deviceMake" to "Samsung",
            "deviceModel" to "UKS9800",
            "osName" to "Tizen",
            "osVersion" to "2.4.0",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 2,
    ),
)

internal typealias TrailerRequester = suspend (
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String?,
    timeoutMillis: Long,
) -> TrailerRequestResponse

class InAppYouTubeExtractor internal constructor(
    private val performRequest: TrailerRequester,
    private val nowMs: () -> Long,
) {
    constructor() : this(TrailerExtractionPlatform::performRequest, LibraryClock::nowEpochMs) {
        accountHeaders = YouTubeMusicAuth::authHeaders
    }

    /**
     * fork:ytmusic-login — headers that make a request to the given origin act as the signed-in
     * Google account, or empty when nobody is signed in. A property rather than a constructor
     * parameter so the existing `InAppYouTubeExtractor(::request) { nowMs }` call shape keeps
     * working; tests leave the default (no account) so they never touch the stored session.
     */
    internal var accountHeaders: (origin: String) -> Map<String, String> = { emptyMap() }

    private val log = Logger.withTag(TRAILER_EXTRACTOR_TAG)

    /**
     * `INNERTUBE_API_KEY` and `visitorData` identify the *session*, not the video, so one watch
     * page serves every later extraction. Caching it removes a full page fetch (~1.3MB) from
     * each trailer resolve — which matters most while browsing, where hovering a row resolves
     * one trailer per poster.
     */
    private var cachedConfig: CachedWatchConfig? = null
    private val configMutex = Mutex()

    /** Epoch ms before which the watch page is not requested; set when it answers 429. */
    private var watchPageBackoffUntilMs = 0L

    /** [apiKey] is null for a keyless session bootstrapped from the player API. */
    private data class CachedWatchConfig(
        val apiKey: String?,
        val visitorData: String?,
        val fetchedAtMs: Long,
    )

    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerResolution = extract(youtubeUrl, singleUrl = false)

    /**
     * One URL carrying both video and audio, for a stream that is only a YouTube id (upstream
     * 94976222d): the HLS master playlist (every quality, audio as a rendition), or a progressive
     * file when there is none. The adaptive formats are skipped because their audio is separate.
     */
    suspend fun extractSingleUrl(youtubeUrl: String): String? =
        (extract(youtubeUrl, singleUrl = true) as? TrailerResolution.Available)?.source?.videoUrl

    /**
     * fork:loudness - YouTube's overall `loudnessDb` for [videoId] from one player request (the first client only), or null.
     * Nothing is asked while YouTube is refusing this address: the figure is a nicety, the stream plays without it.
     */
    suspend fun loudnessDbOf(videoId: String): Double? = withContext(Dispatchers.Default) {
        if (videoId.length != 11 || MusicRateLimit.isBlocked()) return@withContext null
        try {
            val config = ensureWatchConfig(videoId, forceRefresh = false)
            val response = fetchPlayerResponse(config.apiKey, videoId, CLIENTS.first(), config.visitorData)
            response.objectValue("playerConfig")?.objectValue("audioConfig")?.numberValue("loudnessDb")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "loudness lookup for $videoId failed: ${e.message}" }
            null
        }
    }

    /** fork:innertube - the shared session for the search / YouTube Music requests in YouTubeInnerTube.kt. */
    internal suspend fun innerTubeSession(): InnerTubeSession =
        ensureWatchConfig(INNERTUBE_SEED_VIDEO_ID, forceRefresh = false).let { InnerTubeSession(it.apiKey, it.visitorData) }

    private suspend fun extract(youtubeUrl: String, singleUrl: Boolean): TrailerResolution = withContext(Dispatchers.Default) {
        if (youtubeUrl.isBlank()) return@withContext TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        // fork:youtube-pause - while YouTube is refusing this address, only a lookup someone waits on goes out.
        val caller = youTubeCaller()
        if (!mayAskYouTube(paused = MusicRateLimit.isBlocked(), urgent = caller?.urgent == true)) {
            log.d { "[${caller?.label ?: "other"}] skipped $youtubeUrl: paused for ${MusicRateLimit.secondsLeft()}s after a refusal" }
            return@withContext TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        }

        val hadCachedConfig = configMutex.withLock { cachedConfig != null }
        val first = attemptExtraction(youtubeUrl, forceRefreshConfig = false, singleUrl = singleUrl)

        // A session that has expired server-side is indistinguishable from an unplayable video:
        // every client answers LOGIN_REQUIRED with no `streamingData`. Retry once against a
        // freshly fetched watch config before believing the verdict. Skipped when this attempt
        // already fetched a config, and when YouTube gave a definitive reason a new session
        // cannot change, so the common failure paths still cost a single pass.
        val anonymous =
            if (first is TrailerResolution.Available || !hadCachedConfig || !first.warrantsFreshConfig() || MusicRateLimit.isBlocked()) {
                first
            } else {
                log.i { "Retrying extraction with a fresh watch config" }
                attemptExtraction(youtubeUrl, forceRefreshConfig = true, singleUrl = singleUrl)
            }

        // fork:ytmusic-login — a video YouTube gates behind a sign-in (age restriction, "confirm
        // you're not a bot") is retried once as the signed-in Google account. Anonymous stays the
        // default so the account's cookies only ever leave the machine for videos that need them.
        val needsAccount = anonymous is TrailerResolution.Unavailable &&
            anonymous.reason == TrailerUnavailableReason.AGE_RESTRICTED
        if (needsAccount && !MusicRateLimit.isBlocked() && accountHeaders(YOUTUBE_ORIGIN).isNotEmpty()) {
            log.i { "Retrying login-gated video as the signed-in account" }
            val signedIn = attemptExtraction(youtubeUrl, forceRefreshConfig = false, singleUrl = singleUrl, useAccount = true)
            // fork:youtube-unplayable - the TV clients answer an age-gated video with "The page needs to be
            // reloaded" (UNKNOWN); the anonymous verdict is the one that says why.
            return@withContext if (signedIn is TrailerResolution.Unavailable && signedIn.reason == TrailerUnavailableReason.UNKNOWN) {
                anonymous
            } else {
                signedIn
            }
        }
        anonymous
    }

    /**
     * fork:music-resolver — drops the cached watch config so the next extraction fetches a fresh
     * session. For a caller that has just proved the URLs it was given are dead: YouTube issues
     * URLs that serve nothing to a session it has reservations about, and a stale `visitorData`
     * is one way to be that session.
     */
    suspend fun forgetSession() {
        configMutex.withLock { cachedConfig = null }
    }

    private suspend fun attemptExtraction(
        youtubeUrl: String,
        forceRefreshConfig: Boolean,
        singleUrl: Boolean,
        useAccount: Boolean = false,
    ): TrailerResolution =
        try {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractPlaybackSourceInternal(youtubeUrl, forceRefreshConfig, singleUrl, useAccount)
            }
        } catch (error: Throwable) {
            // The caller giving up is not a verdict on the video: rethrow, so nothing caches it as unavailable and
            // the fresh-config retry above does not run in a cancelled coroutine. Our own timeout is a failure.
            if (error is kotlinx.coroutines.CancellationException &&
                error !is kotlinx.coroutines.TimeoutCancellationException
            ) {
                throw error
            }
            log.w { "[${youTubeCallerLabel()}] extractor failed for $youtubeUrl: ${error.message}" }
            TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        }

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
     *
     * When the page is unusable — throttled (429), any other error, or a page without the key
     * such as a consent interstitial — extraction falls back to a keyless session from
     * [bootstrapKeylessConfig] rather than failing.
     */
    private suspend fun ensureWatchConfig(videoId: String, forceRefresh: Boolean): CachedWatchConfig =
        configMutex.withLock {
            if (!forceRefresh) {
                cachedConfig?.takeIf { !it.isStale() }?.let { return@withLock it }
            }

            if (nowMs() < watchPageBackoffUntilMs) {
                // A forced refresh still deserves a new session; it just can't come from the page.
                if (!forceRefresh) cachedConfig?.let { return@withLock it }
                return@withLock bootstrapKeylessConfig(videoId)
            }

            val watchResponse = performRequest(
                "https://www.youtube.com/watch?v=$videoId&hl=en",
                "GET",
                TrailerExtractionPlatform.defaultHeaders,
                null,
                TRAILER_REQUEST_TIMEOUT_MS,
            )
            if (!watchResponse.ok) {
                if (watchResponse.status == 429) {
                    watchPageBackoffUntilMs = nowMs() + WATCH_PAGE_BACKOFF_MS
                    log.w { "Watch page throttled (429); skipping it for ${WATCH_PAGE_BACKOFF_MS / 60_000} min" }
                }
                // A stale config still usually works; failing outright would turn a transient
                // network blip into an unplayable trailer.
                cachedConfig?.let { stale ->
                    log.w { "Watch page failed (${watchResponse.status}), reusing cached config" }
                    return@withLock stale
                }
                log.w { "Watch page failed (${watchResponse.status}), using a keyless session" }
                return@withLock bootstrapKeylessConfig(videoId)
            }

            val parsed = getWatchConfig(watchResponse.body)
            val apiKey = parsed.apiKey ?: run {
                log.w { "Watch page had no INNERTUBE_API_KEY, using a keyless session" }
                return@withLock bootstrapKeylessConfig(videoId)
            }
            CachedWatchConfig(
                apiKey = apiKey,
                visitorData = parsed.visitorData,
                fetchedAtMs = nowMs(),
            ).also {
                cachedConfig = it
                log.i { "Watch config cached (visitorData=${!it.visitorData.isNullOrBlank()})" }
            }
        }

    /**
     * Builds a session without the watch page. The player API needs no key, but a request
     * without `visitorData` answers LOGIN_REQUIRED — while still issuing a fresh `visitorData`
     * in `responseContext`. One throwaway player call therefore yields a session that every
     * client then accepts. Caller holds [configMutex].
     */
    private suspend fun bootstrapKeylessConfig(videoId: String): CachedWatchConfig {
        val response = fetchPlayerResponse(
            apiKey = null,
            videoId = videoId,
            client = CLIENTS.first(),
            visitorData = null,
        )
        val visitorData = response.objectValue("responseContext")?.stringValue("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Unable to bootstrap a keyless YouTube session")
        return CachedWatchConfig(
            apiKey = null,
            visitorData = visitorData,
            fetchedAtMs = nowMs(),
        ).also {
            cachedConfig = it
            log.i { "Keyless watch config cached" }
        }
    }

    private fun CachedWatchConfig.isStale(): Boolean =
        nowMs() - fetchedAtMs > WATCH_CONFIG_TTL_MS

    private suspend fun extractPlaybackSourceInternal(
        youtubeUrl: String,
        forceRefreshConfig: Boolean,
        singleUrl: Boolean = false,
        useAccount: Boolean = false,
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
        // fork:loudness - the video's overall loudness from the player config, for a stream with no per-format figure.
        var playerLoudnessDb: Double? = null

        val callerLabel = youTubeCallerLabel()
        val clients = if (useAccount) ACCOUNT_CLIENTS else CLIENTS
        // fork:youtube-parallel-tab-only - the YouTube tab asks every client at once (it saves ~0.3 s a start). Trailers and
        // music keep asking one after another: a burst of different client identities from one address is what YouTube
        // answers with a bot check, and one at a time lets the first refusal stop the rest (the pause below).
        val parallelClients = com.nuvio.app.features.music.youTubeCaller()?.parallelClients == true
        val parallelAnswers = if (parallelClients) {
            // fork:youtube-pause - while YouTube is refusing this address the other clients would only lengthen the block.
            val clientsToAsk = clients.take(playerClientsToTry(MusicRateLimit.isBlocked(), clients.size))
            if (clientsToAsk.size < clients.size) {
                log.i { "[$callerLabel] YouTube is refusing requests; asking only player client ${clientsToAsk.firstOrNull()?.key} for $videoId" }
            }
            coroutineScope {
                clientsToAsk.map { client ->
                    async {
                        client to runCatching {
                            fetchPlayerResponse(
                                apiKey = apiKey,
                                videoId = videoId,
                                client = client,
                                visitorData = watchConfig.visitorData,
                                useAccount = useAccount,
                            )
                        }
                    }
                }.awaitAll()
            }
        } else {
            null
        }
        for ((clientIndex, client) in clients.withIndex()) {
            val answer = if (parallelAnswers != null) {
                (parallelAnswers.getOrNull(clientIndex) ?: break).second
            } else {
                // fork:youtube-pause - once YouTube is refusing this address the remaining clients would only lengthen the block.
                if (clientIndex >= playerClientsToTry(MusicRateLimit.isBlocked(), clients.size)) {
                    log.i { "[$callerLabel] YouTube is refusing requests; not trying player client ${client.key} for $videoId" }
                    break
                }
                runCatching {
                    fetchPlayerResponse(
                        apiKey = apiKey,
                        videoId = videoId,
                        client = client,
                        visitorData = watchConfig.visitorData,
                        useAccount = useAccount,
                    )
                }
            }
            runCatching {
                val playerResponse = answer.getOrThrow()

                val streamingData = playerResponse.objectValue("streamingData")
                if (streamingData == null) {
                    if (useAccount) {
                        val status = playerResponse.objectValue("playabilityStatus")
                        log.i {
                            "Signed-in ${client.key} for $videoId: ${status?.stringValue("status")} " +
                                "${status?.stringValue("reason")}"
                        }
                    }
                    lastPlayabilityStatus = playerResponse.objectValue("playabilityStatus")
                        ?: lastPlayabilityStatus
                    return@runCatching
                }
                if (playerLoudnessDb == null) {
                    playerLoudnessDb = playerResponse.objectValue("playerConfig")
                        ?.objectValue("audioConfig")
                        ?.numberValue("loudnessDb")
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
                        width = (format.numberValue("width") ?: 0.0).toInt(),
                    )
                }

                for (format in streamingData.listObjectValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    // YouTube Premium's enhanced-bitrate 1080p is listed to everyone and scores highest on bitrate, but
                    // without Premium its URL serves the start and then refuses the rest (a seek, or playing on): the
                    // video hung at the same point every time while others opened at once (2026-10-31).
                    if (isPremiumFormat(format)) {
                        log.i { "skipping Premium format itag=${format.numberValue("itag")?.toInt()} for $videoId (${client.key})" }
                        continue
                    }
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
                            width = (format.numberValue("width") ?: 0.0).toInt(),
                            isHdr = isHdrYouTubeFormat(
                                (format["colorInfo"] as? JsonObject)?.stringValue("transferCharacteristics"),
                                format.stringValue("qualityLabel"),
                            ),
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
                            isOriginalAudio = format.isOriginalAudioTrack(url),
                            loudnessDb = format.numberValue("loudnessDb"),
                            audioTrackId = format.objectValue("audioTrack")?.stringValue("id"),
                            audioTrackName = format.objectValue("audioTrack")?.stringValue("displayName"),
                        )
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                log.i { "[$callerLabel] player client ${client.key} failed for $videoId (account=$useAccount): ${error.message}" }
            }
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            val reason = classifyUnavailability(lastPlayabilityStatus)
            log.i { "[$callerLabel] no streams for $videoId: playabilityStatus=$lastPlayabilityStatus reason=$reason" }
            return TrailerResolution.Unavailable(reason, youTubeReason = lastPlayabilityStatus?.stringValue("reason"))
        }

        var bestManifest: ManifestCandidate? = null
        // fork:youtube-quick-start - a caller that plays the adaptive pair keeps the manifest only as a fallback URL, so its
        // ladder is not fetched (one more round trip before playback); the quality figure then comes from the adaptive video.
        val skipLadder = com.nuvio.app.features.music.youTubeCaller()?.skipManifestLadder == true &&
            !singleUrl && adaptiveVideo.isNotEmpty() && adaptiveAudio.isNotEmpty()
        if (skipLadder) {
            manifestUrls.minByOrNull { it.second }?.let { (clientKey, priority, manifestUrl) ->
                bestManifest = ManifestCandidate(clientKey, priority, manifestUrl, selectedVariantUrl = manifestUrl, height = 0, bandwidth = 0L)
            }
        }
        val ladders = when {
            skipLadder -> emptyList()
            // Trailers and music read the ladders one after another, as before; only the YouTube tab reads them at once.
            com.nuvio.app.features.music.youTubeCaller()?.parallelClients == true -> coroutineScope {
                manifestUrls.map { (_, _, manifestUrl) -> async { runCatching { parseHlsManifest(manifestUrl) }.getOrNull() } }.awaitAll()
            }
            else -> manifestUrls.map { (_, _, manifestUrl) -> runCatching { parseHlsManifest(manifestUrl) }.getOrNull() }
        }
        for ((index, variant) in ladders.withIndex()) {
            val (clientKey, priority, manifestUrl) = manifestUrls[index]
            runCatching {
                variant ?: return@runCatching
                val candidate = ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth,
                    width = variant.width,
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
        if (singleUrl) {
            // The master playlist, not the best variant: variant playlists carry video only, with
            // audio as a rendition only the master links.
            val single = bestManifest?.manifestUrl?.takeIf { it.isNotBlank() } ?: bestProgressive?.url
            log.i { "Selected single-url stream manifest=${bestManifest != null} progressive=${bestProgressive?.height ?: 0}p" }
            return single?.let {
                val singleSource = TrailerPlaybackSource(videoUrl = it)
                StreamLoudnessBook.record(singleSource.streamUrls(), playerLoudnessDb)
                TrailerResolution.Available(singleSource)
            } ?: TrailerResolution.Unavailable(TrailerUnavailableReason.UNKNOWN)
        }
        // Only for whoever asked for it (the YouTube tab's videos): trailers and music keep choosing as before.
        val preferHdr = com.nuvio.app.features.music.youTubeCaller()?.preferHdr == true
        val bestVideo = pickBestVideoForClient(
            items = adaptiveVideo,
            clientKey = PREFERRED_SEPARATE_CLIENT,
            preferredHeights = TrailerExtractionPlatform.preferredSeparateVideoHeights,
            preferClient = TrailerExtractionPlatform.preferSeparateVideoClient,
            preferHdr = preferHdr,
            maxShortSide = com.nuvio.app.features.music.youTubeCaller()?.maxShortSide,
        )
        // Multi-language uploads (studio trailers, and anything YouTube has auto-dubbed) carry one
        // audio format per language, and the highest-bitrate one was as likely to be a dub as the
        // original: The Housemaid (2025) played in another language. Only the original track
        // competes when YouTube says which one that is.
        val originalAudio = adaptiveAudio.filter { it.isOriginalAudio == true }
        val bestAudio = pickBestForClient(originalAudio.ifEmpty { adaptiveAudio }, PREFERRED_SEPARATE_CLIENT)
        // fork:youtube-languages - the other language tracks (dubs), for a caller that asked for them: the
        // best of each from the same client as the original, so they carry the same session.
        val otherAudioTracks = if (com.nuvio.app.features.music.youTubeCaller()?.allAudioTracks == true && bestAudio != null) {
            adaptiveAudio
                .filter { it.client == bestAudio.client && it.audioTrackId != null && it.audioTrackId != bestAudio.audioTrackId }
                .groupBy { it.audioTrackId }
                .mapNotNull { (trackId, formats) ->
                    val best = formats.maxByOrNull { it.score } ?: return@mapNotNull null
                    TrailerAudioTrack(
                        url = best.url,
                        language = trackId.orEmpty().substringBefore('.').substringBefore('-').lowercase(),
                        title = best.audioTrackName ?: trackId.orEmpty(),
                    )
                }
                .sortedBy { it.title }
        } else {
            emptyList()
        }

        log.i {
            "Selected trailer streams adaptive=${bestVideo?.height ?: 0}p/${bestVideo?.fps ?: 0}fps" +
                (if (preferHdr) " hdr=${bestVideo?.isHdr == true} (offered=${adaptiveVideo.any { it.isHdr }})" else "") + " " +
                "progressive=${bestProgressive?.height ?: 0}p manifest=${bestManifest?.height ?: 0}p " +
                "separateAudio=${bestAudio != null} videoClient=${bestVideo?.client} audioClient=${bestAudio?.client} " +
                "manifestClient=${bestManifest?.client}" +
                (if (otherAudioTracks.isNotEmpty()) " otherLanguages=${otherAudioTracks.size}" else "")
        }

        val source = TrailerExtractionPlatform.buildPlaybackSource(
            bestManifest = bestManifest,
            bestProgressive = bestProgressive,
            bestVideo = bestVideo,
            bestAudio = bestAudio,
        )
        // fork:trailer-quality - the upload's real ceiling (the HLS ladder often tops out above the
        // adaptive rendition picked for playback), and whether the streams came from the client whose
        // URLs are not cut off, so uploads of the same trailer can be compared.
        // Measured on the short side, so a vertical 1080x1920 upload counts as 1080p, not 1920p, and
        // it is flagged: cropped to fill a wide hero it shows only a soft middle slice.
        val dimensions = listOfNotNull(
            bestManifest?.let { it.width to it.height },
            bestVideo?.let { it.width to it.height },
            bestProgressive?.let { it.width to it.height },
        )
        val sharpest = dimensions.maxOfOrNull { (w, h) -> if (w > 0) minOf(w, h) else h } ?: 0
        val portrait = dimensions.any { (w, h) -> w in 1 until h }
        val reliable = bestVideo?.client == PREFERRED_SEPARATE_CLIENT || bestManifest?.client == PREFERRED_SEPARATE_CLIENT
        return source?.let {
            val finished = it.copy(
                heightPx = sharpest,
                reliable = reliable,
                portrait = portrait,
                // Only beside a separate original track: a manifest or progressive stream has its own audio.
                extraAudioTracks = if (it.audioUrl != null && it.audioUrl == bestAudio?.url) otherAudioTracks else emptyList(),
            )
            // fork:loudness - what YouTube says the audio's loudness is, kept for the player to plan its gain from.
            StreamLoudnessBook.record(finished.streamUrls(), bestAudio?.loudnessDb ?: playerLoudnessDb)
            TrailerResolution.Available(finished)
        }
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
                playabilityText.contains("sign in to confirm") ||
                // fork:ytmusic-login — the other ways YouTube words an age gate; all of them are
                // cleared by a signed-in account, so they take the same retry.
                status?.stringValue("status").let {
                    it.equals("AGE_CHECK_REQUIRED", ignoreCase = true) ||
                        it.equals("AGE_VERIFICATION_REQUIRED", ignoreCase = true)
                } ||
                AGE_GATE_PHRASES.any { playabilityText.contains(it) } ->
                TrailerUnavailableReason.AGE_RESTRICTED
            playabilityText.contains("removed") || playabilityText.contains("private") ||
                playabilityText.contains("no longer available") ->
                TrailerUnavailableReason.REMOVED_OR_PRIVATE
            else -> TrailerUnavailableReason.UNKNOWN
        }
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String?,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        useAccount: Boolean = false,
    ): JsonObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player" +
            apiKey?.let { "?key=${encodeUrlComponent(it)}" }.orEmpty()

        val accountHeadersForRequest = if (useAccount) accountHeaders(YOUTUBE_ORIGIN) else emptyMap()
        val headers = buildMap {
            putAll(TrailerExtractionPlatform.defaultHeaders)
            put("content-type", "application/json")
            put("origin", YOUTUBE_ORIGIN)
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
            // fork:ytmusic-login — cookie + SAPISIDHASH for the signed-in account (see the
            // retry in extractPlaybackSource). Empty when nobody is signed in.
            putAll(accountHeadersForRequest)
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

        val response = performRequest(endpoint, "POST", headers, payload.toString(), TRAILER_REQUEST_TIMEOUT_MS)

        if (accountHeadersForRequest.isNotEmpty()) {
            // The saved session is kept either way; the settings page just shows a "sign in again" hint.
            if (response.status == 401 || response.status == 403) YouTubeMusicAuth.onSessionRejected()
            else if (response.ok) YouTubeMusicAuth.onSessionAccepted()
        }

        if (!response.ok) {
            MusicRateLimit.noteRefusal(response.status, response.body) // fork:youtube-pause
            val preview = response.body.take(200)
            throw IllegalStateException("player API ${client.key} failed (${response.status}): $preview")
        }

        val parsed = JSON.parseToJsonElement(response.body)
        return parsed as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = performRequest(
            manifestUrl,
            "GET",
            TrailerExtractionPlatform.defaultHeaders,
            null,
            TRAILER_REQUEST_TIMEOUT_MS,
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

    /** YouTube Premium's enhanced-bitrate formats: itag 356 (VP9 1080p) and anything labelled Premium. */
    private fun isPremiumFormat(format: JsonObject): Boolean =
        format.numberValue("itag")?.toInt() in PREMIUM_ITAGS ||
            format.stringValue("qualityLabel")?.contains("premium", ignoreCase = true) == true

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
        preferHdr: Boolean = false,
        maxShortSide: Int? = null,
    ): StreamCandidate? {
        val availableHeights = items.map { it.height }.filter { it > 0 }.distinct()
        // The sharpest encode up to [maxShortSide], measured on the short side so a 3840x1600 scope upload
        // or a vertical 2160x3840 one counts as 4K (the height list below only knows 16:9 heights). An
        // upload with nothing that small plays its smallest.
        val cappedHeight = maxShortSide?.let { cap ->
            items.filter { minOf(it.width.takeIf { w -> w > 0 } ?: it.height, it.height) in 1..cap }.maxOfOrNull { it.height }
                ?: availableHeights.minOrNull()
        }
        val targetHeight = cappedHeight
            ?: preferredHeights.firstOrNull { it in availableHeights }
            ?: availableHeights.filter { it < (preferredHeights.lastOrNull() ?: 1080) }.maxOrNull()
            ?: availableHeights.maxOrNull()
            ?: return null
        val resolutionPool = items.filter { it.height == targetHeight }
        // An HDR upload lists its HDR encode beside an SDR one at each height. Asked for HDR, only
        // the HDR one competes, and before the frame-rate rule below: HDR uploads are mostly 60 fps,
        // so that rule alone handed every one of them the 30 fps SDR encode (Nov 2).
        val dynamicRangePool = if (preferHdr) resolutionPool.filter { it.isHdr }.ifEmpty { resolutionPool } else resolutionPool
        // At accelerated playback, 60 fps content makes the decoder/render path process
        // 120 frames per wall-clock second. Prefer the best <=30 fps encode at the same
        // resolution; retain 60 fps only when it is the sole option.
        val frameRatePool = dynamicRangePool.filter { it.fps in 1..30 }.ifEmpty { dynamicRangePool }
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

/**
 * Whether an audio format is the original language. YouTube marks it in `audioTrack.audioIsDefault`
 * and in the URL's `xtags` (`acont=original` against `acont=dubbed`/`dubbed-auto`); null when
 * neither says, which is a video with only one audio language.
 */
private fun JsonObject.isOriginalAudioTrack(url: String): Boolean? {
    val track = objectValue("audioTrack")
    (track?.get("audioIsDefault") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
    val xtags = Regex("""[?&]xtags=([^&]*)""").find(url)?.groupValues?.get(1)
        ?.replace("%3D", "=", ignoreCase = true)
        ?.replace("%3A", ":", ignoreCase = true)
        ?: return if (track != null) false else null
    return when {
        "acont=original" in xtags -> true
        "acont=dubbed" in xtags || "acont=descriptive" in xtags -> false
        else -> null
    }
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
