package com.nuvio.app.features.youtube

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.AddonSubtitle
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

/** The `params` that pick a channel page's Videos tab (newest uploads), as the website sends them. */
private const val CHANNEL_VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

internal actual suspend fun youTubeChannelVideos(channelId: String, continuation: String?, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        "browse",
        if (continuation != null) {
            mapOf("continuation" to continuation)
        } else {
            mapOf("browseId" to channelId, "params" to CHANNEL_VIDEOS_TAB_PARAMS)
        },
        pageId,
    )

internal actual suspend fun youTubeSetSubscribed(channelId: String, subscribed: Boolean, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
        mapOf("channelIds" to listOf(channelId)),
        pageId,
    )

internal actual suspend fun youTubeSetLiked(videoId: String, liked: Boolean, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        if (liked) "like/like" else "like/removelike",
        mapOf("target" to mapOf("videoId" to videoId)),
        pageId,
    )

internal actual suspend fun youTubeEditPlaylist(playlistId: String, videoId: String, add: Boolean, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        "browse/edit_playlist",
        mapOf(
            "playlistId" to playlistId,
            "actions" to listOf(
                if (add) {
                    mapOf("action" to "ACTION_ADD_VIDEO", "addedVideoId" to videoId)
                } else {
                    mapOf("action" to "ACTION_REMOVE_VIDEO_BY_VIDEO_ID", "removedVideoId" to videoId)
                },
            ),
        ),
        pageId,
    )

internal actual suspend fun youTubePlaylistOptions(videoId: String, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        "playlist/get_add_to_playlist",
        mapOf("videoIds" to listOf(videoId), "excludeWatchLater" to false),
        pageId,
    )

internal actual suspend fun youTubeNext(videoId: String?, continuation: String?, pageId: String?): YouTubeBrowseResult =
    signedWebRequest("next", mapOf("videoId" to videoId, "continuation" to continuation), pageId)

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
    var (status, body) = TrailerPlaybackResolver.webRequest(endpoint, params, accountHeaders, pageId)
    // Answered as signed out: the session may only be stale (the app was closed for a while), so
    // renew it once and ask again before calling the sign-in expired.
    if (status in 200..299 && youTubeAnsweredSignedOut(body) && YouTubeMusicAuth.renewNow()) {
        val retried = TrailerPlaybackResolver.webRequest(endpoint, params, YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN), pageId)
        status = retried.first
        body = retried.second
        if (status in 200..299 && !youTubeAnsweredSignedOut(body)) log.i { "$endpoint: signed in again after renewing the session" }
    }
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

/** At most this many tracks are fetched per video: the uploaded ones first, then the auto-generated. */
private const val MAX_CAPTION_TRACKS = 6

internal actual suspend fun youTubeCaptionSubtitles(videoId: String): List<AddonSubtitle> {
    val player = signedWebRequest(
        "player",
        mapOf("videoId" to videoId, "contentCheckOk" to true, "racyCheckOk" to true),
        YouTubeFeedRepository.channel.value.pageId,
    ) as? YouTubeBrowseResult.Ok ?: return emptyList()
    val tracks = parseYouTubeCaptionTracks(player.body).take(MAX_CAPTION_TRACKS)
    if (tracks.isEmpty()) {
        log.i { "$videoId: no subtitle tracks" }
        return emptyList()
    }
    val visitor = youTubeVisitorData(player.body)
    // Minted once, only when a track comes back empty without one.
    var token: String? = null
    var tokenAsked = false
    var usedToken = false
    val dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "nuvio-youtube-captions")
    runCatching { java.nio.file.Files.createDirectories(dir) }
    val subtitles = tracks.mapIndexedNotNull { index, track ->
        var vtt = fetchCaptionVtt(youTubeCaptionUrl(track.baseUrl, poToken = null))
        if (vtt == null) {
            if (!tokenAsked) {
                tokenAsked = true
                token = visitor?.let { YouTubeCaptionTokens.videoToken(videoId, it) }
            }
            token?.let { pot ->
                vtt = fetchCaptionVtt(youTubeCaptionUrl(track.baseUrl, pot))
                if (vtt != null) usedToken = true
            }
        }
        val text = vtt ?: return@mapIndexedNotNull null
        val file = dir.resolve("$videoId-$index.vtt")
        runCatching { java.nio.file.Files.writeString(file, text) }.getOrNull() ?: return@mapIndexedNotNull null
        AddonSubtitle(
            id = "youtube:$videoId:${track.vssId ?: index}",
            url = file.toString(),
            language = track.languageCode.substringBefore('-').lowercase(),
            display = track.label,
            addonName = "YouTube",
        )
    }
    log.i {
        "$videoId: ${subtitles.size} of ${tracks.size} subtitle tracks " +
            "(${if (usedToken) "with a PO token" else if (tokenAsked) "no PO token could be made" else "no token needed"})"
    }
    return subtitles
}

/** The WebVTT at [url], or null when YouTube answered nothing usable (the empty body of a missing PO token). */
private suspend fun fetchCaptionVtt(url: String): String? =
    runCatching {
        TrailerExtractionPlatform.performRequest(
            url = url,
            method = "GET",
            headers = mapOf("user-agent" to STATS_USER_AGENT, "accept-language" to "en-US,en;q=0.9"),
            body = null,
            timeoutMillis = 15_000L,
        )
    }.getOrNull()?.takeIf { it.ok }?.body?.takeIf { it.trimStart().startsWith("WEBVTT") && it.contains("-->") }

/**
 * PO tokens for subtitle requests, from the same BotGuard minter music uses (a headless Edge or
 * Chrome); its own instance, since music's is only built with the InnerTubeX option. Nothing starts
 * until a video's subtitles come back empty without a token.
 */
private object YouTubeCaptionTokens {
    private val browser: String? by lazy { com.nuvio.app.features.music.potoken.BrowserLocator.find() }

    private val generator by lazy {
        com.nuvio.app.features.music.potoken.PoTokenGenerator(
            createMinter = {
                com.nuvio.app.features.music.potoken.BotGuardMinter.create(requireNotNull(browser) { "No Edge or Chrome was found" })
            },
            available = { browser != null },
        )
    }

    /** A token bound to [videoId] (what the subtitle endpoint checks), or null when none can be made. */
    suspend fun videoToken(videoId: String, visitorData: String): String? {
        if (browser == null) {
            log.i { "subtitles need a PO token, but no Edge or Chrome was found to make one" }
            return null
        }
        return generator.tokens(videoId, visitorData)?.videoToken
    }
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
    withContext(YouTubeCaller("youtube tab", urgent = true, preferHdr = true)) {
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

    private const val HIDE_WATCHED_KEY = "hide_watched"

    actual fun loadHideWatched(): Boolean = store.getBoolean(HIDE_WATCHED_KEY) ?: false

    actual fun saveHideWatched(enabled: Boolean) = store.putBoolean(HIDE_WATCHED_KEY, enabled)

    private const val CHANNEL_CHOICE_KEY = "channel_page_id"

    actual fun loadChannelChoice(): String? = store.getString(CHANNEL_CHOICE_KEY)

    actual fun saveChannelChoice(choice: String?) = store.putString(CHANNEL_CHOICE_KEY, choice)
}
