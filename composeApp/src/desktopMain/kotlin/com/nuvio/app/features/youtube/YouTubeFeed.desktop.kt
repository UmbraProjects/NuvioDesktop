package com.nuvio.app.features.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.AddonSubtitle
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

internal actual suspend fun youTubeCreateComment(params: String, text: String, reply: Boolean, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        if (reply) "comment/create_comment_reply" else "comment/create_comment",
        mapOf("commentText" to text, (if (reply) "createReplyParams" else "createCommentParams") to params),
        pageId,
    )

internal actual suspend fun youTubePerformCommentAction(action: String, pageId: String?): YouTubeBrowseResult =
    signedWebRequest("comment/perform_comment_action", mapOf("actions" to listOf(action)), pageId)

internal actual suspend fun youTubeSendFeedback(token: String, pageId: String?): YouTubeBrowseResult =
    signedWebRequest(
        "feedback",
        mapOf("feedbackTokens" to listOf(token), "isFeedbackTokenUnencrypted" to false, "shouldMerge" to false),
        pageId,
    )

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
            if (response.ok) YouTubeWebClient.parseShell(response.body) else null
        }.getOrNull()?.let { page -> page.copy(signatureTimestamp = signatureTimestampFor(page.playerJsUrl)) }
        log.i {
            "signed-in page: loggedIn=${read?.loggedIn} visitorData=${read?.visitorData != null} " +
                "client=${read?.clientVersion} authUser=${read?.sessionIndex} sts=${read?.signatureTimestamp}" +
                if (read == null) " (could not read it)" else ""
        }
        shell = read
        // The cookie may have just been refreshed by the page's answer; key the cache by what is saved now.
        forCookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: cookie
        // A page that could not be read is tried again in two minutes rather than half an hour.
        readAtMs = if (read == null) now - YouTubeWebClient.SHELL_TTL_MS + 120_000L else now
        read
    }

    suspend fun forget() = lock.withLock { readAtMs = 0L }

    /** The last player script read and its signatureTimestamp: the script only changes every few weeks. */
    private var playerSts: Pair<String, Int>? = null

    /** The signatureTimestamp of the player script at [url] (about 2.5 MB, read once per version). */
    private suspend fun signatureTimestampFor(url: String?): Int? {
        url ?: return null
        playerSts?.takeIf { it.first == url }?.let { return it.second }
        val sts = runCatching {
            val response = TrailerExtractionPlatform.performRequest(
                url = url,
                method = "GET",
                headers = YouTubeWebClient.pageHeaders(),
                body = null,
                timeoutMillis = 15_000L,
            )
            if (response.ok) YouTubeWebClient.signatureTimestamp(response.body) else null
        }.getOrNull()
        if (sts == null) log.w { "player script: no signatureTimestamp found in $url" }
        else playerSts = url to sts
        return sts
    }

    /** The signatureTimestamp for the saved sign-in's page, or null when unknown. */
    suspend fun signatureTimestamp(): Int? =
        YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"]?.let { get(it)?.signatureTimestamp }
}

/** One signed request with the cookies as saved now (they may just have been refreshed; the request layer keeps refreshes). */
private suspend fun send(endpoint: String, params: Map<String, Any?>, pageId: String?, shell: YouTubeWebShell?) =
    TrailerPlaybackResolver.webRequest(endpoint, params, YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN), pageId, shell)

private const val EXPIRED_MESSAGE =
    "YouTube no longer accepts the saved sign-in (it answered as signed out). Sign in again under Settings > YouTube account."

private suspend fun signedWebRequest(endpoint: String, params: Map<String, Any?>, pageId: String?): YouTubeBrowseResult {
    var cookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: return YouTubeBrowseResult.SignedOut
    var shell = SignedInShell.get(cookie)
    if (shell?.loggedIn == false && YouTubeMusicAuth.renewNow()) {
        // A session gone stale while the app was closed: renewed through the sign-in's browser profile, read again.
        cookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: cookie
        shell = SignedInShell.get(cookie, fresh = true)
    }
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
    var status = response.status
    var body = response.body
    // Answered as signed out: the session may only be stale (the app was closed for a while), so
    // renew it once through the sign-in's browser profile and ask again before calling it expired.
    if (status in 200..299 && youTubeAnsweredSignedOut(body) && YouTubeMusicAuth.renewNow()) {
        cookie = YouTubeMusicAuth.authHeaders(YOUTUBE_ORIGIN)["cookie"] ?: cookie
        shell = SignedInShell.get(cookie, fresh = true)
        val retried = send(endpoint, params, pageId, shell)
        status = retried.status
        body = retried.body
        if (status in 200..299 && !youTubeAnsweredSignedOut(body)) log.i { "$endpoint: signed in again after renewing the session" }
    }
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

/**
 * The signed WEB `player` answer for a video, asked once and shared for a couple of minutes: the
 * watch-time pings, the subtitle tracks and the seek previews all read it as playback starts, and
 * each used to ask YouTube for it again. Its streams are not used (they came from the extractor).
 */
private object WebPlayerAnswers {
    private const val KEEP_MS = 2 * 60_000L
    private val lock = Mutex()
    private val answers = HashMap<String, Pair<Long, CompletableDeferred<YouTubeBrowseResult>>>()

    suspend fun get(videoId: String, pageId: String?): YouTubeBrowseResult {
        val key = "$videoId|${pageId.orEmpty()}"
        val (answer, asking) = lock.withLock {
            val now = System.currentTimeMillis()
            answers.entries.removeAll { now - it.value.first > KEEP_MS }
            answers[key]?.let { it.second to false }
                ?: (CompletableDeferred<YouTubeBrowseResult>().also { answers[key] = now to it } to true)
        }
        if (asking) {
            try {
                // As youtube.com asks: with the player script's signatureTimestamp. Without it some accounts get a
                // refusal back, with no playbackTracking, so nothing reaches the watch history.
                val sts = SignedInShell.signatureTimestamp()
                val contentPlaybackContext = buildMap<String, Any?> {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (sts != null) put("signatureTimestamp", sts)
                }
                val result = signedWebRequest(
                    "player",
                    mapOf(
                        "videoId" to videoId,
                        "contentCheckOk" to true,
                        "racyCheckOk" to true,
                        "playbackContext" to mapOf("contentPlaybackContext" to contentPlaybackContext),
                    ),
                    pageId,
                )
                // A failure is not kept: the next reader asks again.
                if (result !is YouTubeBrowseResult.Ok) lock.withLock { answers.remove(key) }
                answer.complete(result)
            } catch (error: Throwable) {
                lock.withLock { answers.remove(key) }
                answer.complete(YouTubeBrowseResult.Failed("Couldn't reach YouTube."))
                throw error
            }
        }
        return answer.await()
    }
}

internal actual suspend fun youTubeStoryboard(videoId: String): YouTubeStoryboard? {
    val player = WebPlayerAnswers.get(videoId, YouTubeFeedRepository.channel.value.pageId) as? YouTubeBrowseResult.Ok
        ?: return null
    return parseYouTubeStoryboard(player.body).also { board ->
        log.i { "$videoId: ${board?.let { "storyboard ${it.width}x${it.height}, ${it.count} frames every ${it.intervalMs} ms" } ?: "no storyboard"}" }
    }
}

internal actual suspend fun youTubePlaybackTracking(videoId: String, pageId: String?): YouTubeTrackingUrls? {
    val result = WebPlayerAnswers.get(videoId, pageId) as? YouTubeBrowseResult.Ok ?: return null
    // Only the stats URLs are wanted here; the streams came from the extractor.
    val tracking = runCatching { Json.parseToJsonElement(result.body).jsonObject["playbackTracking"]?.jsonObject }
        .getOrNull()
    if (tracking == null) {
        // The answer says why (usually a refusal: LOGIN_REQUIRED "...not a bot", UNPLAYABLE ...).
        log.w { "$videoId: player answer has no playbackTracking: ${YouTubeWebClient.playabilitySummary(result.body)}" }
        return null
    }
    fun url(key: String) = tracking[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.contentOrNull
    val playback = url("videostatsPlaybackUrl") ?: return null
    val watchtime = url("videostatsWatchtimeUrl") ?: return null
    return YouTubeTrackingUrls(playbackUrl = playback, watchtimeUrl = watchtime)
}

/** At most this many tracks are fetched per video: the uploaded ones first, then the auto-generated. */
private const val MAX_CAPTION_TRACKS = 6

internal actual suspend fun youTubeCaptionSubtitles(videoId: String): List<AddonSubtitle> {
    val player = WebPlayerAnswers.get(videoId, YouTubeFeedRepository.channel.value.pageId) as? YouTubeBrowseResult.Ok
        ?: return emptyList()
    // fork:youtube-languages - every track with All languages on (a video subtitled in many languages).
    val tracks = parseYouTubeCaptionTracks(player.body)
        .let { if (YouTubeFeedRepository.allLanguages.value) it else it.take(MAX_CAPTION_TRACKS) }
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
        // Auto-generated tracks roll like karaoke; shown as ordinary subtitles instead.
        val text = vtt?.let(::plainYouTubeCaptions) ?: return@mapIndexedNotNull null
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
        ).also { YouTubeMusicAuth.applySetCookies(it.setCookies) }.status
    }.getOrDefault(0)
}

/** The stats endpoints expect the desktop web player, which is the client the `player` request named; the same browser as every signed request. */
private const val STATS_USER_AGENT = YouTubeWebClient.USER_AGENT

internal actual suspend fun youTubeOpenVideo(videoId: String, ahead: Boolean): TrailerPlaybackSource? =
    // Ahead of a click (the card under the cursor): not a lookup anyone waits on yet, so it waits out a pause.
    withContext(
        YouTubeCaller(
            label = if (ahead) "youtube tab ahead" else "youtube tab",
            urgent = !ahead,
            preferHdr = true,
            skipManifestLadder = true,
            parallelClients = true,
            maxShortSide = YouTubeFeedRepository.maxQuality.value,
            allAudioTracks = YouTubeFeedRepository.allLanguages.value,
        ),
    ) {
        // fork:youtube-languages - any dubs ride along in the audio source string for the player to open.
        MusicResolver.openVideo(videoId)?.let { source ->
            if (source.extraAudioTracks.isEmpty()) {
                source
            } else {
                log.i { "$videoId: ${source.extraAudioTracks.size} more audio languages (${source.extraAudioTracks.joinToString { it.language }})" }
                source.copy(audioUrl = com.nuvio.app.features.trailer.encodePlayerAudioSources(source.audioUrl, source.extraAudioTracks))
            }
        }
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

    actual fun loadSearchHistory(profileId: Int): String? = store.getString("search_history_$profileId")

    actual fun saveSearchHistory(profileId: Int, payload: String?) = store.putString("search_history_$profileId", payload)

    private const val AUTOPLAY_KEY = "autoplay"

    actual fun loadAutoplay(): Boolean = store.getBoolean(AUTOPLAY_KEY) ?: false

    actual fun saveAutoplay(enabled: Boolean) = store.putBoolean(AUTOPLAY_KEY, enabled)

    private const val HIDE_STREAMS_KEY = "hide_streams"

    actual fun loadHideStreams(): Boolean = store.getBoolean(HIDE_STREAMS_KEY) ?: false

    actual fun saveHideStreams(enabled: Boolean) = store.putBoolean(HIDE_STREAMS_KEY, enabled)

    private const val SKY_BACKGROUND_KEY = "sky_background"

    actual fun loadSkyBackground(): Boolean = store.getBoolean(SKY_BACKGROUND_KEY) ?: false

    actual fun saveSkyBackground(enabled: Boolean) = store.putBoolean(SKY_BACKGROUND_KEY, enabled)

    private const val MAX_QUALITY_KEY = "max_quality"

    actual fun loadMaxQuality(): Int? = store.getString(MAX_QUALITY_KEY)?.toIntOrNull()

    actual fun saveMaxQuality(shortSide: Int) = store.putString(MAX_QUALITY_KEY, shortSide.toString())

    private const val OPEN_WINDOWED_KEY = "open_windowed"

    actual fun loadOpenWindowed(): Boolean = store.getBoolean(OPEN_WINDOWED_KEY) ?: true

    actual fun saveOpenWindowed(windowed: Boolean) = store.putBoolean(OPEN_WINDOWED_KEY, windowed)

    private const val MINI_PLAYER_ON_BACK_KEY = "mini_player_on_back"

    actual fun loadMiniPlayerOnBack(): Boolean = store.getBoolean(MINI_PLAYER_ON_BACK_KEY) ?: true

    actual fun saveMiniPlayerOnBack(enabled: Boolean) = store.putBoolean(MINI_PLAYER_ON_BACK_KEY, enabled)

    private const val THEATER_MODE_KEY = "theater_mode"

    actual fun loadTheaterMode(): Boolean = store.getBoolean(THEATER_MODE_KEY) ?: false

    actual fun saveTheaterMode(theater: Boolean) = store.putBoolean(THEATER_MODE_KEY, theater)

    private const val ALL_LANGUAGES_KEY = "all_languages"

    actual fun loadAllLanguages(): Boolean = store.getBoolean(ALL_LANGUAGES_KEY) ?: false

    actual fun saveAllLanguages(enabled: Boolean) = store.putBoolean(ALL_LANGUAGES_KEY, enabled)

    private const val WATCHED_DATE_MODE_KEY = "watched_date_mode"

    actual fun loadWatchedDateMode(): String? = store.getString(WATCHED_DATE_MODE_KEY)

    actual fun saveWatchedDateMode(mode: String) = store.putString(WATCHED_DATE_MODE_KEY, mode)

    private const val DATE_FORMAT_KEY = "date_format"

    actual fun loadDateFormat(): String? = store.getString(DATE_FORMAT_KEY)

    actual fun saveDateFormat(format: String) = store.putString(DATE_FORMAT_KEY, format)

    private const val CHANNEL_CHOICE_KEY = "channel_page_id"

    actual fun loadChannelChoice(): String? = store.getString(CHANNEL_CHOICE_KEY)

    actual fun saveChannelChoice(choice: String?) = store.putString(CHANNEL_CHOICE_KEY, choice)
}
