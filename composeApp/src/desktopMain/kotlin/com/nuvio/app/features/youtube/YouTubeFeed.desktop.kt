package com.nuvio.app.features.youtube

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.music.MusicResolver
import com.nuvio.app.features.music.YouTubeCaller
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

private val log = co.touchlab.kermit.Logger.withTag("YouTubeFeed")

internal actual suspend fun youTubeBrowse(browseId: String?, continuation: String?, pageId: String?): YouTubeBrowseResult =
    signedWebRequest("browse", mapOf("browseId" to browseId, "continuation" to continuation), pageId)

internal actual suspend fun youTubeAccountsList(): YouTubeBrowseResult =
    signedWebRequest(
        "account/accounts_list",
        mapOf(
            "requestType" to "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER",
            "callCircumstance" to "SWITCHING_USERS_FULL",
        ),
        pageId = null,
    )

/**
 * fork:youtube-web — what youtube.com's own page says about the saved session (see [YouTubeWebClient]),
 * read with the account's cookies at most every [YouTubeWebClient.SHELL_TTL_MS] and again after a refusal.
 */
private object SignedInShell {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var shell: YouTubeWebShell? = null
    private var forCookie: String? = null
    private var readAtMs = 0L

    suspend fun get(cookie: String, fresh: Boolean = false): YouTubeWebShell? = lock.withLock {
        val now = System.currentTimeMillis()
        if (!fresh && forCookie == cookie && now - readAtMs < YouTubeWebClient.SHELL_TTL_MS) return@withLock shell
        val read = runCatching {
            val response = TrailerExtractionPlatform.performRequest(
                url = "$YOUTUBE_ORIGIN/",
                method = "GET",
                headers = YouTubeWebClient.pageHeaders() + ("cookie" to cookie),
                body = null,
                timeoutMillis = 15_000L,
            )
            YouTubeMusicAuth.applySetCookies(response.setCookies)
            if (response.ok) YouTubeWebClient.parseShell(response.body) else null
        }.getOrNull()
        log.i {
            "signed-in page: loggedIn=${read?.loggedIn} visitorData=${read?.visitorData != null} " +
                "client=${read?.clientVersion} authUser=${read?.sessionIndex}" + if (read == null) " (could not read it)" else ""
        }
        shell = read
        // The cookie may have just been refreshed by the page's answer; key the cache by what is saved now.
        forCookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: cookie
        // A page that could not be read is tried again in two minutes rather than half an hour.
        readAtMs = if (read == null) now - YouTubeWebClient.SHELL_TTL_MS + 120_000L else now
        read
    }

    suspend fun forget() = lock.withLock { readAtMs = 0L }
}

/** One signed request: the cookies as saved now (they may just have been refreshed), and their refreshes kept. */
private suspend fun send(endpoint: String, params: Map<String, Any?>, pageId: String?, shell: YouTubeWebShell?) =
    TrailerPlaybackResolver.webRequest(endpoint, params, YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN), pageId, shell)
        .also { YouTubeMusicAuth.applySetCookies(it.setCookies) }

private const val EXPIRED_MESSAGE =
    "YouTube no longer accepts the saved sign-in (it answered as signed out). Sign in again under Settings > YouTube account."

private suspend fun signedWebRequest(endpoint: String, params: Map<String, Any?>, pageId: String?): YouTubeBrowseResult {
    val cookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: return YouTubeBrowseResult.SignedOut
    var shell = SignedInShell.get(cookie)
    if (shell?.loggedIn == false) {
        // The page itself says the cookies are no longer a session: no feed request can help.
        YouTubeMusicAuth.onSessionRejected()
        log.w { "$endpoint: youtube.com says the saved sign-in is signed out" }
        return YouTubeBrowseResult.Failed(EXPIRED_MESSAGE)
    }
    var response = send(endpoint, params, pageId, shell)
    if (YouTubeWebClient.isRefusal(response.status, response.body)) {
        // As for YouTube Music: a fresh visitor id once (re-read from the page), then one more ask.
        log.w { "$endpoint refused (${response.status}); asking once more with a fresh read of the signed-in page" }
        shell = SignedInShell.get(cookie, fresh = true)
        if (shell?.loggedIn != false) response = send(endpoint, params, pageId, shell)
    }
    val status = response.status
    val body = response.body
    return when {
        YouTubeWebClient.isRefusal(status, body) -> {
            SignedInShell.forget()
            YouTubeBrowseResult.Failed("YouTube is limiting requests right now. Try again in a few minutes.")
        }
        // fork:youtube-signin-expired — Google answers cookies it no longer accepts with 200 and the signed-out page.
        status in 200..299 && youTubeAnsweredSignedOut(body) -> {
            YouTubeMusicAuth.onSessionRejected()
            log.w { "$endpoint: YouTube answered as signed out; the saved sign-in has expired" }
            SignedInShell.forget()
            YouTubeBrowseResult.Failed(EXPIRED_MESSAGE)
        }
        status in 200..299 -> {
            YouTubeMusicAuth.onSessionAccepted()
            YouTubeBrowseResult.Ok(body)
        }
        status == 401 || status == 403 -> {
            YouTubeMusicAuth.onSessionRejected()
            YouTubeBrowseResult.Failed("YouTube refused the saved sign-in. Sign in again under Settings > YouTube account.")
        }
        status == 429 -> YouTubeBrowseResult.Failed("YouTube is limiting requests right now. Try again in a few minutes.")
        status == 0 -> YouTubeBrowseResult.Failed("Couldn't reach YouTube.")
        else -> YouTubeBrowseResult.Failed("YouTube answered with an error ($status).")
    }
}

internal actual suspend fun youTubePlaybackTracking(videoId: String, pageId: String?): YouTubeTrackingUrls? {
    val result = signedWebRequest(
        "player",
        mapOf(
            "videoId" to videoId,
            "contentCheckOk" to true,
            "racyCheckOk" to true,
            "playbackContext" to mapOf("contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS")),
        ),
        pageId,
    ) as? YouTubeBrowseResult.Ok ?: return null
    // Only the stats URLs are wanted here; the streams came from the extractor.
    val tracking = runCatching { Json.parseToJsonElement(result.body).jsonObject["playbackTracking"]?.jsonObject }
        .getOrNull() ?: return null
    fun url(key: String) = tracking[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.contentOrNull
    val playback = url("videostatsPlaybackUrl") ?: return null
    val watchtime = url("videostatsWatchtimeUrl") ?: return null
    return YouTubeTrackingUrls(playbackUrl = playback, watchtimeUrl = watchtime)
}

internal actual suspend fun youTubeStatsPing(url: String): Boolean {
    val cookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: return false
    return runCatching {
        TrailerExtractionPlatform.performRequest(
            url = url,
            method = "GET",
            headers = mapOf(
                "cookie" to cookie,
                "user-agent" to STATS_USER_AGENT,
                "origin" to YOUTUBE_ORIGIN,
                "referer" to "$YOUTUBE_ORIGIN/",
                "accept-language" to "en-US,en;q=0.9",
            ),
            body = null,
            timeoutMillis = 15_000L,
        ).also { YouTubeMusicAuth.applySetCookies(it.setCookies) }.status in 200..299
    }.getOrDefault(false)
}

/** The stats endpoints expect the desktop web player, which is the client the `player` request named; the same browser as every signed request. */
private const val STATS_USER_AGENT = YouTubeWebClient.USER_AGENT

internal actual suspend fun youTubeOpenVideo(videoId: String): TrailerPlaybackSource? =
    withContext(YouTubeCaller("youtube tab", urgent = true)) {
        MusicResolver.openVideo(videoId)
    }

internal actual object YouTubeModeStorage {
    // Per account, like the YouTube sign-in it depends on.
    private val store = DesktopStorage.store("nuvio_youtube_mode")
    private const val TAB_ENABLED_KEY = "tab_enabled"

    actual fun loadTabEnabled(): Boolean = store.getBoolean(TAB_ENABLED_KEY) ?: false

    actual fun saveTabEnabled(enabled: Boolean) = store.putBoolean(TAB_ENABLED_KEY, enabled)

    private const val CHANNEL_CHOICE_KEY = "channel_page_id"

    actual fun loadChannelChoice(): String? = store.getString(CHANNEL_CHOICE_KEY)

    actual fun saveChannelChoice(choice: String?) = store.putString(CHANNEL_CHOICE_KEY, choice)
}
