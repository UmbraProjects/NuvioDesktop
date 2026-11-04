package com.nuvio.app.features.youtube

/**
 * fork:youtube-web — how the YouTube tab's signed requests (WEB client, www.youtube.com) are made to
 * look like the site's own. The same lessons as YouTube Music (`YouTubeMusicWebClient`), plus the
 * signed-in ones from BitChord's `Innertube.SessionScope`:
 *
 * - One browser identity for the whole signed-in session. The tab's browse requests went out with an
 *   Android TV user agent while its stats pings already claimed a desktop Chrome, so one session
 *   showed two devices.
 * - The signed-in page's own visitor id, client version and account index (`VISITOR_DATA`,
 *   `INNERTUBE_CLIENT_VERSION`, `SESSION_INDEX` in its `ytcfg`). The tab paired the account's cookies
 *   with the trailer extractor's anonymous visitor id (BitChord: "a session-bound id outranks an
 *   anonymous one") and claimed client 2.20260708 while the site was on 2.20261030.
 * - The page's `LOGGED_IN` says whether the cookies are still a session, before any feed is asked.
 * - No API key; Origin, X-Origin and Referer of www.youtube.com; the visitor id in the context too.
 */
internal object YouTubeWebClient {
    const val ORIGIN = "https://www.youtube.com"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36"

    /** Used only until the signed-in page has been read. */
    const val FALLBACK_CLIENT_VERSION = "2.20261030.02.00"

    /** The signed-in page is read again after this long (a new visitor id or client version is rare). */
    const val SHELL_TTL_MS = 30 * 60_000L

    fun url(endpoint: String): String = "$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false"

    /** Headers for a signed WEB request, on top of the account's cookie and SAPISIDHASH. */
    fun headers(shell: YouTubeWebShell?): Map<String, String> = buildMap {
        put("accept-language", "en-US,en;q=0.9")
        put("user-agent", USER_AGENT)
        put("content-type", "application/json")
        put("origin", ORIGIN)
        put("x-origin", ORIGIN)
        put("referer", "$ORIGIN/")
        put("x-youtube-client-name", "1")
        put("x-youtube-client-version", shell?.clientVersion ?: FALLBACK_CLIENT_VERSION)
        shell?.visitorData?.let { put("x-goog-visitor-id", it) }
        // Which Google account in the cookie jar; the page knows, "0" is the first.
        put("x-goog-authuser", shell?.sessionIndex ?: "0")
    }

    /** Headers for reading the signed-in page itself. */
    fun pageHeaders(): Map<String, String> = mapOf(
        "user-agent" to USER_AGENT,
        "accept-language" to "en-US,en;q=0.9",
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    /** Google's "Sorry..." page or a 429: slow down, the sign-in is not the problem. */
    fun isRefusal(status: Int, body: String): Boolean =
        status == 429 || (status == 403 && body.contains("Sorry", ignoreCase = true))

    private val LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
    private val SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")

    /**
     * What the signed-in page says about the session, or null when it is not the YouTube page at all
     * (a consent page, an error page): then nothing is known and nothing is concluded from it.
     */
    fun parseShell(html: String): YouTubeWebShell? {
        val loggedIn = LOGGED_IN.find(html)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        val visitor = VISITOR_DATA.find(html)?.groupValues?.get(1)
        val version = CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (loggedIn == null && visitor == null && version == null) return null
        return YouTubeWebShell(
            loggedIn = loggedIn,
            visitorData = visitor,
            clientVersion = version,
            sessionIndex = SESSION_INDEX.find(html)?.groupValues?.get(1),
        )
    }
}

/** What youtube.com's own page said about the signed-in session. */
internal data class YouTubeWebShell(
    /** Null when the page did not say. */
    val loggedIn: Boolean?,
    val visitorData: String?,
    val clientVersion: String?,
    /** Which account in the cookie jar ("0" is the first). */
    val sessionIndex: String?,
)
