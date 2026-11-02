package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * fork:ytmusic-web — what a YouTube Music (WEB_REMIX) request has to look like to be answered like the
 * music.youtube.com page's own.
 *
 * The HTPC logs show Google's "Sorry..." page (HTTP 403) on YouTube Music browse, search and next while
 * InnerTubeX's player requests from the same machine, in the same second, were answered (Nov 2 15:24:05
 * and 15:53:23), so the block is on how those requests looked, not on the address. They went out as
 * WEB_REMIX with an Android TV user agent, the watch page's API key, the watch page's visitor id in a
 * header only, and no Referer. BitChord's `Innertube.postMusic` and yt-dlp's `_call_api` send what the
 * site sends instead: a desktop browser user agent, Origin / X-Origin / Referer of music.youtube.com,
 * the visitor id in the header and in the context, `request.useSsl` and no API key.
 *
 * The visitor id is minted for YouTube Music from `sw.js_data`, the service-worker bootstrap the web
 * player loads first, as BitChord does. A refusal that accuses us of being a bot can mean the id is
 * burned while everything else is fine, so BitChord takes a fresh one once and asks once more; so
 * does [com.nuvio.app.features.trailer.musicRequest].
 */
internal object YouTubeMusicWebClient {
    const val ORIGIN = "https://music.youtube.com"

    /** The browser the request claims to be; WEB_REMIX is the desktop site's client. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36"

    const val VISITOR_URL = "https://www.youtube.com/sw.js_data"

    /** A fresh visitor id is taken at most this often, however many refusals come in. */
    const val REMINT_MIN_INTERVAL_MS = 10 * 60_000L

    /** Headers for a WEB_REMIX request, as music.youtube.com sends them. */
    fun headers(clientId: String, clientVersion: String, visitorData: String?): Map<String, String> = buildMap {
        put("accept-language", "en-US,en;q=0.9")
        put("user-agent", USER_AGENT)
        put("content-type", "application/json")
        put("origin", ORIGIN)
        put("x-origin", ORIGIN)
        put("referer", "$ORIGIN/")
        put("x-youtube-client-name", clientId)
        put("x-youtube-client-version", clientVersion)
        if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
    }

    /** The endpoint URL: no API key (neither the site nor BitChord nor yt-dlp sends one). */
    fun url(endpoint: String): String = "$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false"

    /** True for the answers that mean "slow down": Google's "Sorry..." page or a 429. */
    fun isRefusal(status: Int, body: String): Boolean =
        status == 429 || (status == 403 && body.contains("Sorry", ignoreCase = true))

    /** Whether a refusal is worth one fresh visitor id and one more ask. */
    fun mayRemint(lastMintMs: Long, now: Long): Boolean = lastMintMs == 0L || now - lastMintMs >= REMINT_MIN_INTERVAL_MS

    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    /**
     * The visitor id in a `sw.js_data` answer, or null. The body is JSON behind Google's `)]}'` guard,
     * nested arrays all the way down, so the id is found by its shape rather than by a path.
     */
    fun visitorFromSwJsData(body: String): String? {
        val json = body.substringAfter("\n", body.removePrefix(")]}'")).trim()
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        return findVisitor(root)
    }

    private fun findVisitor(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitor(it) }
        is JsonObject -> element.values.firstNotNullOfOrNull { findVisitor(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { element.isString && VISITOR_DATA.matches(it) }
    }
}
