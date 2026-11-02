package com.nuvio.app.features.youtube

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.music.MusicResolver
import com.nuvio.app.features.music.YouTubeCaller
import com.nuvio.app.features.trailer.TrailerExtractionPlatform
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

private val log = co.touchlab.kermit.Logger.withTag("YouTubeFeed")

internal actual suspend fun youTubeBrowse(browseId: String?, continuation: String?, pageId: String?): YouTubeBrowseResult =
    signedWebRequest("browse", mapOf("browseId" to browseId, "continuation" to continuation), pageId)

internal actual suspend fun youTubeSearch(query: String?, continuation: String?, pageId: String?): YouTubeBrowseResult =
    signedWebRequest("search", mapOf("query" to query, "continuation" to continuation), pageId)

internal actual suspend fun youTubeAccountsList(): YouTubeBrowseResult =
    signedWebRequest(
        "account/accounts_list",
        mapOf(
            "requestType" to "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER",
            "callCircumstance" to "SWITCHING_USERS_FULL",
        ),
        pageId = null,
    )

private suspend fun signedWebRequest(endpoint: String, params: Map<String, Any?>, pageId: String?): YouTubeBrowseResult {
    val accountHeaders = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)
    if (accountHeaders.isEmpty()) return YouTubeBrowseResult.SignedOut
    val (status, body) = TrailerPlaybackResolver.webRequest(endpoint, params, accountHeaders, pageId)
    return when {
        // fork:youtube-signin-expired — Google answers cookies it no longer accepts with 200 and the signed-out page.
        status in 200..299 && youTubeAnsweredSignedOut(body) -> {
            YouTubeMusicAuth.onSessionRejected()
            log.w { "$endpoint: YouTube answered as signed out; the saved sign-in has expired" }
            YouTubeBrowseResult.Failed(
                "YouTube no longer accepts the saved sign-in (it answered as signed out). Sign in again under Settings > YouTube account.",
            )
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

internal actual suspend fun youTubeStatsPing(url: String, pageId: String?): Int {
    // Signed like the `player` request that handed the URL out, brand channel included: with the
    // cookie alone YouTube took the pings (Nov 2) but nothing showed up as watched on the channel
    // the feeds act as.
    val accountHeaders = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)
    if (accountHeaders.isEmpty()) return 0
    return runCatching {
        TrailerExtractionPlatform.performRequest(
            url = url,
            method = "GET",
            headers = buildMap {
                putAll(accountHeaders)
                if (!pageId.isNullOrBlank()) put("x-goog-pageid", pageId)
                put("user-agent", STATS_USER_AGENT)
                put("origin", YOUTUBE_ORIGIN)
                put("referer", "$YOUTUBE_ORIGIN/")
                put("accept-language", "en-US,en;q=0.9")
            },
            body = null,
            timeoutMillis = 15_000L,
        ).status
    }.getOrDefault(0)
}

/** The stats endpoints expect the desktop web player, which is the client the `player` request named. */
private const val STATS_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

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

    private const val SPONSOR_AUTO_SKIP_KEY = "sponsor_auto_skip"

    actual fun loadSponsorAutoSkip(): Boolean? = store.getBoolean(SPONSOR_AUTO_SKIP_KEY)

    actual fun saveSponsorAutoSkip(enabled: Boolean) = store.putBoolean(SPONSOR_AUTO_SKIP_KEY, enabled)

    private const val DEARROW_KEY = "dearrow"

    actual fun loadDeArrow(): Boolean? = store.getBoolean(DEARROW_KEY)

    actual fun saveDeArrow(enabled: Boolean) = store.putBoolean(DEARROW_KEY, enabled)

    private const val CHANNEL_CHOICE_KEY = "channel_page_id"

    actual fun loadChannelChoice(): String? = store.getString(CHANNEL_CHOICE_KEY)

    actual fun saveChannelChoice(choice: String?) = store.putString(CHANNEL_CHOICE_KEY, choice)
}
