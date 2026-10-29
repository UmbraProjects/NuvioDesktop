package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
private const val WEB_CLIENT_VERSION = "2.20260101.00.00"

/** YouTube Music's web client (WEB_REMIX). The version only has to look current. */
private const val MUSIC_CLIENT_ID = "67"
private const val MUSIC_CLIENT_VERSION = "1.20260101.01.00"

private val innerTubeLog = Logger.withTag(TRAILER_EXTRACTOR_TAG)

/**
 * Searches YouTube for videos matching [query]. Best effort: any failure is an empty list, so a
 * trailer still plays from the listed uploads when search is unavailable. Quality is not in search
 * results; callers resolve the hits they care about and compare heights.
 */
internal suspend fun InAppYouTubeExtractor.searchVideos(query: String): List<YouTubeSearchHit> =
    withContext(Dispatchers.Default) {
        runCatching {
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
                    innerTubeLog.w { "YouTube search failed (${response.status}) for \"$query\": ${response.body.take(160)}" }
                    return@withTimeout emptyList()
                }
                parseYouTubeSearchHits(response.body).also {
                    innerTubeLog.i { "YouTube search \"$query\": ${it.size} videos" }
                }
            }
        }.onFailure {
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
            withTimeout(INNERTUBE_TIMEOUT_MS) {
                val session = innerTubeSession()
                val headers = buildMap {
                    putAll(TrailerExtractionPlatform.defaultHeaders)
                    put("content-type", "application/json")
                    put("origin", "https://music.youtube.com")
                    put("x-youtube-client-name", MUSIC_CLIENT_ID)
                    put("x-youtube-client-version", MUSIC_CLIENT_VERSION)
                    if (!session.visitorData.isNullOrBlank()) put("x-goog-visitor-id", session.visitorData)
                }
                val payload = innerTubePayload(
                    params,
                    clientName = "WEB_REMIX",
                    clientVersion = MUSIC_CLIENT_VERSION,
                )
                val response = TrailerExtractionPlatform.performRequest(
                    url = "https://music.youtube.com/youtubei/v1/$endpoint" +
                        keyQuery(session) + "&prettyPrint=false",
                    method = "POST",
                    headers = headers,
                    body = payload.toString(),
                    timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
                )
                if (!response.ok) {
                    innerTubeLog.w { "YouTube Music $endpoint failed (${response.status}): ${response.body.take(160)}" }
                    return@withTimeout null
                }
                response.body
            }
        }.onFailure {
            innerTubeLog.w { "YouTube Music $endpoint failed: ${it.message}" }
        }.getOrNull()
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
): JsonObject {
    val fields = LinkedHashMap<String, JsonElement>()
    params.forEach { (key, value) -> if (value != null) fields[key] = toJsonElement(value) }
    fields["context"] = JsonObject(
        mapOf(
            "client" to JsonObject(
                mapOf(
                    "clientName" to JsonPrimitive(clientName),
                    "clientVersion" to JsonPrimitive(clientVersion),
                    "hl" to JsonPrimitive("en"),
                    "gl" to JsonPrimitive("US"),
                ),
            ),
        ),
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
