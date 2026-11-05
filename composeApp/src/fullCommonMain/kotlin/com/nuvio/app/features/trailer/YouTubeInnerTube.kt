package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.YouTubeMusicWebClient
import com.nuvio.app.features.youtube.YouTubeWebClient
import com.nuvio.app.features.youtube.YouTubeWebShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * fork:innertube
 *
 * YouTube search and YouTube Music requests, written as extensions of [InAppYouTubeExtractor] so they
 * ride on its InnerTube session (API key and visitor data from the watch page, with the extractor's
 * caching, 429 back-off and keyless fallback) without living inside it. The only thing the extractor
 * itself carries is [InAppYouTubeExtractor.innerTubeSession], a three-line accessor.
 *
 * Users: the "sharper trailer" search (SharpestTrailerResolver) and the music feature
 * (MusicResolver, MusicMetadataClient), both through [TrailerPlaybackResolver].
 */

/** Stable, always-available video used only to obtain a watch config when search runs first. */
internal const val INNERTUBE_SEED_VIDEO_ID = "jNQXAC9IVRw"

/** What the extractor hands out: the session, not the video. [apiKey] is null for a keyless session. */
internal data class InnerTubeSession(val apiKey: String?, val visitorData: String?)

private const val INNERTUBE_TIMEOUT_MS = 30_000L

/**
 * The web client version only has to look current. Search used to take it from the watch page; the
 * extractor does not expose that, so a fixed value is sent. If YouTube ever starts refusing it,
 * bump this constant.
 */
private const val WEB_CLIENT_VERSION = "2.20260708.00.00"

/** YouTube Music's web client (WEB_REMIX). The version only has to look current. */
private const val MUSIC_CLIENT_ID = "67"
private const val MUSIC_CLIENT_VERSION = "1.20260707.12.00"

private val innerTubeLog = Logger.withTag(TRAILER_EXTRACTOR_TAG)

/**
 * Searches YouTube for videos matching [query]. Best effort: any failure is an empty list, so a
 * trailer still plays from the listed uploads when search is unavailable. Quality is not in search
 * results; callers resolve the hits they care about and compare heights.
 */
internal suspend fun InAppYouTubeExtractor.searchVideos(query: String): List<YouTubeSearchHit> =
    withContext(Dispatchers.Default) {
        runCatching {
            // fork:youtube-pause - a search is only worth sending while YouTube is answering us, or when someone waits on it.
            val caller = com.nuvio.app.features.music.youTubeCaller()
            if (!com.nuvio.app.features.music.mayAskYouTube(com.nuvio.app.features.music.MusicRateLimit.isBlocked(), caller?.urgent == true)) {
                innerTubeLog.d { "[${caller?.label ?: "other"}] YouTube search \"$query\" skipped: paused after a refusal" }
                return@runCatching emptyList<YouTubeSearchHit>()
            }
            withTimeout(INNERTUBE_TIMEOUT_MS) {
                val session = innerTubeSession()
                val headers = buildMap {
                    putAll(TrailerExtractionPlatform.defaultHeaders)
                    put("content-type", "application/json")
                    put("origin", "https://www.youtube.com")
                    put("x-youtube-client-name", "1")
                    put("x-youtube-client-version", WEB_CLIENT_VERSION)
                    if (!session.visitorData.isNullOrBlank()) put("x-goog-visitor-id", session.visitorData)
                }
                val payload = innerTubePayload(
                    mapOf(
                        "query" to query,
                        // Restricts results to videos (no channels, playlists or shorts shelves).
                        "params" to "EgIQAQ==",
                    ),
                    clientName = "WEB",
                    clientVersion = WEB_CLIENT_VERSION,
                )
                val response = TrailerExtractionPlatform.performRequest(
                    url = "https://www.youtube.com/youtubei/v1/search" + keyQuery(session),
                    method = "POST",
                    headers = headers,
                    body = payload.toString(),
                    timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
                )
                if (!response.ok) {
                    com.nuvio.app.features.music.MusicRateLimit.noteRefusal(response.status, response.body)
                    innerTubeLog.w { "YouTube search failed (${response.status}) for \"$query\": ${response.body.take(160)}" }
                    return@withTimeout emptyList()
                }
                parseYouTubeSearchHits(response.body).also {
                    innerTubeLog.i { "YouTube search \"$query\": ${it.size} videos" }
                }
            }
        }.onFailure {
            // fork:music-home - a search the caller gave up on (typing moved on) is not a failure: when the caller's job is
            // cancelled, pass that on, so nothing upstream takes "no answer" for "nothing found" and keeps it. A timeout
            // still answers null as before.
            ensureActive()
            innerTubeLog.w { "YouTube search failed for \"$query\": ${it.message}" }
        }.getOrDefault(emptyList())
    }

/**
 * POSTs [params] to YouTube Music's InnerTube [endpoint] ("search", "browse", "next") as the
 * WEB_REMIX client and returns the response body, or null on any failure.
 */
internal suspend fun InAppYouTubeExtractor.musicRequest(endpoint: String, params: Map<String, Any?>): String? =
    withContext(Dispatchers.Default) {
        runCatching {
            if (com.nuvio.app.features.music.MusicRateLimit.isBlocked()) {
                return@runCatching null.also { innerTubeLog.d { "YouTube Music $endpoint skipped: paused after a refusal" } }
            }
            withTimeout(INNERTUBE_TIMEOUT_MS) {
                // fork:ytmusic-web — sent as music.youtube.com sends it (see YouTubeMusicWebClient), with a visitor
                // id of its own; the watch page's id is only the fallback when none can be minted.
                val visitor = MusicVisitorId.current() ?: innerTubeSession().visitorData
                var response = postMusic(endpoint, params, visitor)
                if (!response.ok && YouTubeMusicWebClient.isRefusal(response.status, response.body)) {
                    // As BitChord does: a refusal can mean the visitor id is burned, so take a fresh one once and ask once more.
                    val fresh = MusicVisitorId.remint()
                    if (fresh != null && fresh != visitor) {
                        innerTubeLog.w { "YouTube Music $endpoint refused (${response.status}); asking once more with a fresh visitor id" }
                        response = postMusic(endpoint, params, fresh)
                        if (response.ok) innerTubeLog.i { "YouTube Music $endpoint answered with the fresh visitor id" }
                    }
                }
                if (!response.ok) {
                    innerTubeLog.w { "YouTube Music $endpoint failed (${response.status}): ${response.body.take(160)}" }
                    if (com.nuvio.app.features.music.MusicRateLimit.noteRefusal(response.status, response.body)) {
                        innerTubeLog.w { "YouTube Music is limiting requests: pausing them for a short while" }
                    }
                    return@withTimeout null
                }
                response.body
            }
        }.onFailure {
            // fork:music-home - cancelled by the caller (typing moved on): not a failure, and must not be cached as
            // "nothing found" by MusicMetadataClient, which kept such misses for three minutes. A timeout still answers null.
            ensureActive()
            innerTubeLog.w { "YouTube Music $endpoint failed: ${it.message}" }
        }.getOrNull()
    }

private suspend fun postMusic(endpoint: String, params: Map<String, Any?>, visitorData: String?): TrailerRequestResponse =
    TrailerExtractionPlatform.performRequest(
        url = YouTubeMusicWebClient.url(endpoint),
        method = "POST",
        headers = YouTubeMusicWebClient.headers(MUSIC_CLIENT_ID, MUSIC_CLIENT_VERSION, visitorData),
        body = innerTubePayload(
            params,
            clientName = "WEB_REMIX",
            clientVersion = MUSIC_CLIENT_VERSION,
            visitorData = visitorData,
            webContext = true,
        ).toString(),
        timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
    )

/**
 * fork:ytmusic-web — YouTube Music's own visitor id, minted from `sw.js_data` on first use and kept for the
 * session. [remint] replaces it, at most once per [YouTubeMusicWebClient.REMINT_MIN_INTERVAL_MS].
 */
private object MusicVisitorId {
    private val lock = Mutex()
    private var id: String? = null
    private var mintedAtMs = 0L
    private var tried = false

    suspend fun current(): String? = lock.withLock {
        if (id == null && !tried) {
            tried = true
            mintLocked()
        }
        id
    }

    /** A fresh id, or null when one was taken too recently or none could be minted. */
    suspend fun remint(): String? = lock.withLock {
        if (!YouTubeMusicWebClient.mayRemint(mintedAtMs, com.nuvio.app.features.streams.epochMs())) return@withLock null
        mintLocked()
        id
    }

    private suspend fun mintLocked() {
        mintedAtMs = com.nuvio.app.features.streams.epochMs()
        val minted = runCatching {
            val response = TrailerExtractionPlatform.performRequest(
                url = YouTubeMusicWebClient.VISITOR_URL,
                method = "GET",
                headers = mapOf(
                    "user-agent" to YouTubeMusicWebClient.USER_AGENT,
                    "accept-language" to "en-US,en;q=0.9",
                ),
                body = null,
                timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
            )
            if (response.ok) YouTubeMusicWebClient.visitorFromSwJsData(response.body) else null
        }.getOrNull()
        if (minted != null) {
            id = minted
            innerTubeLog.i { "YouTube Music visitor id minted" }
        } else {
            innerTubeLog.w { "YouTube Music visitor id could not be minted; using the watch page's" }
        }
    }
}

/**
 * fork:youtube-mode - POSTs [params] to youtube.com's InnerTube [endpoint] ("browse",
 * "account/accounts_list") as the WEB client, signed as the account from Settings > YouTube account.
 *
 * [pageId] acts as one of the account's brand channels. Without it YouTube answers for the Google
 * account's own profile, which for many people has no channel at all (their channel is a brand
 * channel), so its subscriptions are empty or long forgotten. The website picks the channel the same
 * way: the `x-goog-pageid` header plus `onBehalfOfUser` in the request context.
 *
 * fork:youtube-web — sent as youtube.com sends it (see [YouTubeWebClient]): a desktop browser, the signed-in
 * page's own visitor id, client version and account index from [shell], no API key. A refusal no longer
 * pauses music (YouTube refuses per kind of request, not per address; see YouTubeMusicWebClient); the tab
 * handles its own.
 *
 * Returns the response; status 0 when the request never got an answer.
 */
internal suspend fun InAppYouTubeExtractor.webRequest(
    endpoint: String,
    params: Map<String, Any?>,
    accountHeaders: Map<String, String>,
    pageId: String?,
    shell: YouTubeWebShell?,
): TrailerRequestResponse =
    withContext(Dispatchers.Default) {
        runCatching {
            withTimeout(INNERTUBE_TIMEOUT_MS) {
                // The anonymous watch-page id only when the signed-in page could not be read.
                val visitorData = shell?.visitorData ?: innerTubeSession().visitorData
                val headers = buildMap {
                    putAll(accountHeaders)
                    putAll(YouTubeWebClient.headers(shell?.copy(visitorData = visitorData)))
                    if (!pageId.isNullOrBlank()) put("x-goog-pageid", pageId)
                }
                val payload = innerTubePayload(
                    params,
                    clientName = "WEB",
                    clientVersion = shell?.clientVersion ?: YouTubeWebClient.FALLBACK_CLIENT_VERSION,
                    onBehalfOfUser = pageId?.takeIf { it.isNotBlank() },
                    visitorData = visitorData,
                    webContext = true,
                )
                val response = TrailerExtractionPlatform.performRequest(
                    url = YouTubeWebClient.url(endpoint),
                    method = "POST",
                    headers = headers,
                    body = payload.toString(),
                    timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
                )
                if (!response.ok) {
                    innerTubeLog.w { "YouTube $endpoint failed (${response.status}): ${response.body.take(160)}" }
                }
                response
            }
        }.onFailure {
            innerTubeLog.w { "YouTube $endpoint failed: ${it.message}" }
        }.getOrDefault(TrailerRequestResponse(ok = false, status = 0, statusText = "", url = "", body = ""))
    }

/** `?key=...` when the session has a key, else `?` alone so a following `&prettyPrint=false` still parses. */
private fun keyQuery(session: InnerTubeSession): String {
    val key = session.apiKey?.takeIf { it.isNotBlank() }
    return if (key != null) "?key=${percentEncode(key)}" else "?"
}

private fun percentEncode(value: String): String =
    value
        .replace("%", "%25")
        .replace("+", "%2B")
        .replace(" ", "%20")
        .replace("&", "%26")
        .replace("=", "%3D")

private fun innerTubePayload(
    params: Map<String, Any?>,
    clientName: String,
    clientVersion: String,
    onBehalfOfUser: String? = null,
    visitorData: String? = null,
    /** fork:ytmusic-web — the context fields the site itself sends (`user.lockedSafetyMode`, `request.useSsl`). */
    webContext: Boolean = false,
): JsonObject {
    val fields = LinkedHashMap<String, JsonElement>()
    params.forEach { (key, value) -> if (value != null) fields[key] = toJsonElement(value) }
    val client = buildMap<String, JsonElement> {
        put("clientName", JsonPrimitive(clientName))
        put("clientVersion", JsonPrimitive(clientVersion))
        put("hl", JsonPrimitive("en"))
        put("gl", JsonPrimitive("US"))
        if (!visitorData.isNullOrBlank()) put("visitorData", JsonPrimitive(visitorData))
    }
    val user = buildMap<String, JsonElement> {
        onBehalfOfUser?.let { put("onBehalfOfUser", JsonPrimitive(it)) }
        if (webContext) put("lockedSafetyMode", JsonPrimitive(false))
    }
    fields["context"] = JsonObject(
        buildMap {
            if (user.isNotEmpty()) put("user", JsonObject(user))
            put("client", JsonObject(client))
            if (webContext) put("request", JsonObject(mapOf("useSsl" to JsonPrimitive(true))))
        },
    )
    return JsonObject(fields)
}

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries
            .filter { it.key != null && it.value != null }
            .associate { it.key.toString() to toJsonElement(it.value) },
    )
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}
