package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * fork:youtube-mode
 *
 * The YouTube tab's feeds. Requests go out as the account signed in under Settings > YouTube account
 * (the same Google session the trailers and YouTube Music use); without it YouTube answers the home
 * feed with an empty "search to get started" page and refuses the subscriptions feed outright.
 */

/** [inHeader]: has its own button in the tab header (the others are reached from a search or a channel). */
enum class YouTubeFeedKind(val browseId: String, val label: String, val inHeader: Boolean = true) {
    Home("FEwhat_to_watch", "Home"),
    Subscriptions("FEsubscriptions", "Subscriptions"),

    /** The account's watch history, newest first (what the website lists under History). */
    History("FEhistory", "History"),

    /** Results for [YouTubeFeedRepository.searchQuery]; not a browse feed, so it has no browse id. */
    Search("", "Search", inHeader = false),

    /** The Videos tab of [YouTubeFeedRepository.openChannel]; its browse id is the channel's. */
    Channel("", "Channel", inHeader = false),

    /** The videos of [YouTubeFeedRepository.openPlaylist], in its order; browsed as "VL" + its id. */
    Playlist("", "Playlist", inHeader = false),
}

/** Watch Later's playlist id, which "Save to Watch Later" adds to. */
internal const val WATCH_LATER_PLAYLIST_ID = "WL"

/** Liked videos' playlist id; YouTube keeps it, so nothing is removed from it by hand. */
internal const val LIKED_PLAYLIST_ID = "LL"

/** The tab's max quality choices, as the short side in pixels: 8K, 4K, 1440p, 1080p, 720p. */
internal val YOUTUBE_MAX_QUALITY_CHOICES = listOf(4320, 2160, 1440, 1080, 720)

/** 4K: an 8K encode is a heavy decode for little gain on most screens. */
internal const val YOUTUBE_DEFAULT_MAX_QUALITY = 2160

/** How much of Watch later is read for the cards' button: 100 videos a page. */
private const val WATCH_LATER_MAX_PAGES = 10

data class YouTubePlaylistListState(
    val playlists: List<YouTubePlaylist> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/** A channel the tab has open, and whether the account is subscribed to it (null until known). */
data class YouTubeOpenChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val subscribed: Boolean? = null,
    /** A subscribe or unsubscribe is on its way. */
    val changing: Boolean = false,
    /** Subscriber and video counts from the page's header; null until its first page is back. */
    val stats: YouTubeChannelStats? = null,
)

data class YouTubeChannelListState(
    val channels: List<YouTubeChannel> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class YouTubeFeedState(
    val videos: List<YouTubeVideo> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    /** True once a first page has come back (even an empty one). */
    val loaded: Boolean = false,
    val error: String? = null,
    /** When the first page came back; how old the feed is. */
    val loadedAtMs: Long = 0L,
)

/**
 * A feed older than this is fetched again when it is shown again (switched to, the tab reopened, the
 * player closed), as the website does on each visit to Home, so new uploads and recommendations
 * that follow what was just watched turn up without pressing Refresh.
 */
private const val FEED_STALE_MS = 10 * 60_000L

/** The feeds that change by themselves; a search, a channel page or a playlist does not. */
private val SELF_REFRESHING_FEEDS = setOf(YouTubeFeedKind.Home, YouTubeFeedKind.Subscriptions, YouTubeFeedKind.History)

/** Watched this far, a video counts as seen: YouTube's own bar stops a little short on end cards. */
private const val WATCHED_PERCENT = 90

/** Whether "Hide watched" leaves [video] out. */
internal fun isYouTubeVideoWatched(video: YouTubeVideo): Boolean = (video.watchedPercent ?: 0) >= WATCHED_PERCENT

sealed interface YouTubeBrowseResult {
    data class Ok(val body: String) : YouTubeBrowseResult
    data object SignedOut : YouTubeBrowseResult
    data class Failed(val message: String) : YouTubeBrowseResult
}

/**
 * One page of a browse feed: [browseId] for the first page, [continuation] for the ones after it,
 * acting as the brand channel [pageId] (null for the Google account's own profile).
 */
internal expect suspend fun youTubeBrowse(browseId: String?, continuation: String?, pageId: String?): YouTubeBrowseResult

/**
 * One page of search results: [query] for the first page, [continuation] for the ones after it,
 * signed like [youTubeBrowse] so the results are the account's (history, region, the chosen channel).
 */
internal expect suspend fun youTubeSearch(query: String?, continuation: String?, pageId: String?): YouTubeBrowseResult

/** One page of a channel's Videos tab, newest first, signed like [youTubeBrowse]. */
internal expect suspend fun youTubeChannelVideos(channelId: String, continuation: String?, pageId: String?): YouTubeBrowseResult

/** Subscribes the account (as [pageId]) to [channelId], or unsubscribes it. */
internal expect suspend fun youTubeSetSubscribed(channelId: String, subscribed: Boolean, pageId: String?): YouTubeBrowseResult

/** Likes [videoId] as the account (as [pageId]), or takes the like back. */
internal expect suspend fun youTubeSetLiked(videoId: String, liked: Boolean, pageId: String?): YouTubeBrowseResult

/** Adds [videoId] to [playlistId] as the account (as [pageId]), or removes it. */
internal expect suspend fun youTubeEditPlaylist(playlistId: String, videoId: String, add: Boolean, pageId: String?): YouTubeBrowseResult

/** The playlists "Save to playlist" offers for [videoId], with whether each already holds it. */
internal expect suspend fun youTubePlaylistOptions(videoId: String, pageId: String?): YouTubeBrowseResult

/** The WEB `next` answer: for [videoId] (its page, which names the comments section), or a [continuation] of it. */
internal expect suspend fun youTubeNext(videoId: String?, continuation: String?, pageId: String?): YouTubeBrowseResult

/** Posts [text] as a comment (`comment/create_comment`) or, with [reply], a reply (`comment/create_comment_reply`), with the params YouTube gave for it. */
internal expect suspend fun youTubeCreateComment(params: String, text: String, reply: Boolean, pageId: String?): YouTubeBrowseResult

/** Runs a comment's `performCommentActionEndpoint` action (its like, or taking it back) as the account. */
internal expect suspend fun youTubePerformCommentAction(action: String, pageId: String?): YouTubeBrowseResult

/** Sends a feed item's feedback token (`feedback`), as its menu rows do on the website: "Don't recommend channel". */
internal expect suspend fun youTubeSendFeedback(token: String, pageId: String?): YouTubeBrowseResult

/** The account's channel list (`account/accounts_list`), as a raw body like [youTubeBrowse]. */
internal expect suspend fun youTubeAccountsList(): YouTubeBrowseResult

/** A playable, probed source for a full-length video (video and audio), or null when none opens. */
internal expect suspend fun youTubeOpenVideo(videoId: String, ahead: Boolean): TrailerPlaybackSource?

internal expect object YouTubeModeStorage {
    fun loadTabEnabled(): Boolean
    fun saveTabEnabled(enabled: Boolean)

    /** Skip SponsorBlock's sponsor, self-promotion and reminder segments without a prompt; null when never set. */
    fun loadSponsorAutoSkip(): Boolean?
    fun saveSponsorAutoSkip(enabled: Boolean)

    /** Show DeArrow's community titles and thumbnails; null when never set. */
    fun loadDeArrow(): Boolean?
    fun saveDeArrow(enabled: Boolean)

    /** Leave videos this account has (nearly) finished out of the feeds. */
    fun loadHideWatched(): Boolean
    fun saveHideWatched(enabled: Boolean)

    /** [profileId]'s recent YouTube searches, as JSON; see [YouTubeSearchHistory]. */
    fun loadSearchHistory(profileId: Int): String?
    fun saveSearchHistory(profileId: Int, payload: String?)

    /** Play YouTube's next pick when a video ends outside a playlist. */
    fun loadAutoplay(): Boolean
    fun saveAutoplay(enabled: Boolean)

    /** Leave past live streams out of the feeds. */
    fun loadHideStreams(): Boolean
    fun saveHideStreams(enabled: Boolean)

    /** Draw the tab on the Settings screen's gradient instead of the plain background. */
    fun loadSkyBackground(): Boolean
    fun saveSkyBackground(enabled: Boolean)

    /** The chosen channel: a page id, "" for the account's own profile, null when never chosen. */
    fun loadMaxQuality(): Int?
    fun saveMaxQuality(shortSide: Int)

    fun loadOpenWindowed(): Boolean
    fun saveOpenWindowed(windowed: Boolean)

    /** fork:youtube-mini-player - Back from a video goes to the tab, the video playing on in the corner. */
    fun loadMiniPlayerOnBack(): Boolean
    fun saveMiniPlayerOnBack(enabled: Boolean)

    fun loadTheaterMode(): Boolean
    fun saveTheaterMode(theater: Boolean)

    /** fork:youtube-languages - a video's dubbed audio tracks and every subtitle language, not just the original's. */
    fun loadAllLanguages(): Boolean
    fun saveAllLanguages(enabled: Boolean)

    fun loadWatchedDateMode(): String?
    fun saveWatchedDateMode(mode: String)

    fun loadDateFormat(): String?
    fun saveDateFormat(format: String)

    fun loadChannelChoice(): String?
    fun saveChannelChoice(choice: String?)
}

/** See [YouTubeFeedRepository.watchPage]. */
data class YouTubeWatchPage(
    val related: List<YouTubeVideo>,
    val chapters: List<YouTubeChapter>,
    /** Title, channel and description for the windowed watch page; null when YouTube did not answer. */
    val details: YouTubeVideoDetails? = null,
    /** Token for more related videos ([YouTubeFeedRepository.moreRelated]); null when the column ends here. */
    val relatedContinuation: String? = null,
)

data class YouTubeChannelState(
    val channels: List<YouTubeChannelIdentity> = emptyList(),
    /** The brand channel requests act as; null for the account's own profile. */
    val pageId: String? = null,
    val loading: Boolean = false,
    /** True once the list has been asked for and the channel to use is settled (even on failure). */
    val resolved: Boolean = false,
    /** Several brand channels and a channel-less profile: the user has to pick one. */
    val needsChoice: Boolean = false,
    val error: String? = null,
) {
    val current: YouTubeChannelIdentity?
        get() = channels.firstOrNull { it.pageId == pageId }
}

object YouTubeFeedRepository {
    private val log = Logger.withTag("YouTubeFeed")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _feeds = MutableStateFlow(YouTubeFeedKind.entries.associateWith { YouTubeFeedState() })

    /** Bumped whenever the feeds are thrown away, so a page still in flight for the old channel is dropped. */
    @Volatile
    private var feedGeneration = 0
    val feeds: StateFlow<Map<YouTubeFeedKind, YouTubeFeedState>> = _feeds.asStateFlow()

    private val _tabEnabled = MutableStateFlow<Boolean?>(null)

    private val _channel = MutableStateFlow(YouTubeChannelState())
    val channel: StateFlow<YouTubeChannelState> = _channel.asStateFlow()

    /** Whether the YouTube tab is in the navigation (Settings > YouTube account > Show YouTube tab). */
    val tabEnabled: StateFlow<Boolean?>
        get() {
            if (_tabEnabled.value == null) _tabEnabled.value = YouTubeModeStorage.loadTabEnabled()
            return _tabEnabled.asStateFlow()
        }

    init {
        // A new sign-in (also one over a lapsed session, which never stops counting as signed in)
        // makes every cached page and the failed account lookup the old session's: drop them so
        // the tab reloads by itself instead of showing the old failure until clicked.
        scope.launch {
            YouTubeMusicAuth.state.map { it.sessionEpoch }.distinctUntilChanged().drop(1).collect {
                log.i { "New YouTube sign-in; reloading the feeds" }
                clear()
            }
        }
        // A session that was answered as signed out and then renewed (the app closed for hours): the
        // channel lookup that failed meanwhile left the feeds acting as the account profile, not the
        // chosen brand channel. Look the channels up again and reload, as a new sign-in would.
        scope.launch {
            YouTubeMusicAuth.state.map { it.sessionRejected }.distinctUntilChanged().drop(1).collect { rejected ->
                if (rejected) return@collect
                val failed = _channel.value.error != null || _feeds.value.values.any { it.error != null }
                if (failed) {
                    log.i { "YouTube sign-in accepted again; reloading the channels and feeds" }
                    clear()
                }
            }
        }
    }

    fun setTabEnabled(enabled: Boolean) {
        YouTubeModeStorage.saveTabEnabled(enabled)
        _tabEnabled.value = enabled
    }

    private val _sponsorAutoSkip by lazy { MutableStateFlow(YouTubeModeStorage.loadSponsorAutoSkip() ?: true) }

    /** Settings > YouTube account > Skip sponsors (on unless turned off); see [YOUTUBE_AUTO_SKIP_TYPES]. */
    val sponsorAutoSkip: StateFlow<Boolean>
        get() = _sponsorAutoSkip.asStateFlow()

    fun setSponsorAutoSkip(enabled: Boolean) {
        YouTubeModeStorage.saveSponsorAutoSkip(enabled)
        _sponsorAutoSkip.value = enabled
    }

    private val _deArrow by lazy { MutableStateFlow(YouTubeModeStorage.loadDeArrow() ?: true) }

    /** Settings > YouTube account > DeArrow (on unless turned off); see [YouTubeDeArrow]. */
    val deArrow: StateFlow<Boolean>
        get() = _deArrow.asStateFlow()

    fun setDeArrow(enabled: Boolean) {
        YouTubeModeStorage.saveDeArrow(enabled)
        _deArrow.value = enabled
    }

    private val _hideWatched by lazy { MutableStateFlow(YouTubeModeStorage.loadHideWatched()) }

    /** The tab's "Hide watched" toggle; see [isYouTubeVideoWatched]. */
    val hideWatched: StateFlow<Boolean>
        get() = _hideWatched.asStateFlow()

    fun setHideWatched(enabled: Boolean) {
        YouTubeModeStorage.saveHideWatched(enabled)
        _hideWatched.value = enabled
    }

    private val _autoplay by lazy { MutableStateFlow(YouTubeModeStorage.loadAutoplay()) }

    /** The tab's "Autoplay" toggle: after a video ends outside a playlist, on to YouTube's next pick ([autoplayPick]). */
    val autoplay: StateFlow<Boolean>
        get() = _autoplay.asStateFlow()

    fun setAutoplay(enabled: Boolean) {
        YouTubeModeStorage.saveAutoplay(enabled)
        _autoplay.value = enabled
    }

    private val _hideStreams by lazy { MutableStateFlow(YouTubeModeStorage.loadHideStreams()) }

    /** The tab's "Hide live streams" toggle; see [YouTubeVideo.wasLive]. */
    val hideStreams: StateFlow<Boolean>
        get() = _hideStreams.asStateFlow()

    fun setHideStreams(enabled: Boolean) {
        YouTubeModeStorage.saveHideStreams(enabled)
        _hideStreams.value = enabled
    }

    private val _skyBackground by lazy { MutableStateFlow(YouTubeModeStorage.loadSkyBackground()) }

    /** Settings > YouTube account > Gradient background: the Settings screen's sky behind the tab. Off unless turned on. */
    val skyBackground: StateFlow<Boolean>
        get() = _skyBackground.asStateFlow()

    fun setSkyBackground(enabled: Boolean) {
        YouTubeModeStorage.saveSkyBackground(enabled)
        _skyBackground.value = enabled
    }

    private val _maxQuality by lazy {
        MutableStateFlow(YouTubeModeStorage.loadMaxQuality()?.takeIf { it in YOUTUBE_MAX_QUALITY_CHOICES } ?: YOUTUBE_DEFAULT_MAX_QUALITY)
    }

    /** Settings > YouTube account > Max video quality: the short side, in pixels, the tab's videos play at most at. */
    val maxQuality: StateFlow<Int>
        get() = _maxQuality.asStateFlow()

    fun setMaxQuality(shortSide: Int) {
        _maxQuality.value = shortSide
        YouTubeModeStorage.saveMaxQuality(shortSide)
    }

    private val _openWindowed by lazy { MutableStateFlow(YouTubeModeStorage.loadOpenWindowed()) }

    /** Settings > YouTube account > Open videos: on the watch page (true) or filling the window. */
    val openWindowed: StateFlow<Boolean>
        get() = _openWindowed.asStateFlow()

    fun setOpenWindowed(windowed: Boolean) {
        _openWindowed.value = windowed
        YouTubeModeStorage.saveOpenWindowed(windowed)
    }

    private val _miniPlayerOnBack by lazy { MutableStateFlow(YouTubeModeStorage.loadMiniPlayerOnBack()) }

    /**
     * fork:youtube-mini-player - Settings > YouTube account > Mini player: Back from a YouTube tab video
     * returns to the tab with the video playing on in a corner box (YouTubeWatchLayout.browsing) rather
     * than closing it.
     */
    val miniPlayerOnBack: StateFlow<Boolean>
        get() = _miniPlayerOnBack.asStateFlow()

    fun setMiniPlayerOnBack(enabled: Boolean) {
        _miniPlayerOnBack.value = enabled
        YouTubeModeStorage.saveMiniPlayerOnBack(enabled)
    }

    private val _allLanguages by lazy { MutableStateFlow(YouTubeModeStorage.loadAllLanguages()) }

    /**
     * fork:youtube-languages - Settings > YouTube account > All languages: a dubbed video's other audio
     * tracks are opened with it, and every subtitle track is fetched rather than the first few. Off by
     * default, as each extra track is opened before the video starts.
     */
    val allLanguages: StateFlow<Boolean>
        get() = _allLanguages.asStateFlow()

    fun setAllLanguages(enabled: Boolean) {
        _allLanguages.value = enabled
        YouTubeModeStorage.saveAllLanguages(enabled)
    }

    private val _theaterMode by lazy { MutableStateFlow(YouTubeModeStorage.loadTheaterMode()) }

    /** The watch page's wide layout: the video across the page, related videos below it. Remembered. */
    val theaterMode: StateFlow<Boolean>
        get() = _theaterMode.asStateFlow()

    fun setTheaterMode(theater: Boolean) {
        _theaterMode.value = theater
        YouTubeModeStorage.saveTheaterMode(theater)
    }

    private val _watchedDateMode by lazy {
        MutableStateFlow(
            YouTubeModeStorage.loadWatchedDateMode()?.let { saved -> YouTubeWatchedDateMode.entries.firstOrNull { it.name == saved } }
                ?: YouTubeWatchedDateMode.HistoryOnly,
        )
    }

    /** Settings > YouTube account > Watched date: where the cards show the day a video was watched. */
    val watchedDateMode: StateFlow<YouTubeWatchedDateMode>
        get() = _watchedDateMode.asStateFlow()

    fun setWatchedDateMode(mode: YouTubeWatchedDateMode) {
        _watchedDateMode.value = mode
        YouTubeModeStorage.saveWatchedDateMode(mode.name)
    }

    private val _dateFormat by lazy {
        MutableStateFlow(
            YouTubeModeStorage.loadDateFormat()?.let { saved -> YouTubeDateFormat.entries.firstOrNull { it.name == saved } }
                ?: YouTubeDateFormat.DayMonthYear,
        )
    }

    /** Settings > YouTube account > Date format. */
    val dateFormat: StateFlow<YouTubeDateFormat>
        get() = _dateFormat.asStateFlow()

    fun setDateFormat(format: YouTubeDateFormat) {
        _dateFormat.value = format
        YouTubeModeStorage.saveDateFormat(format.name)
    }

    private val _searchQuery = MutableStateFlow("")

    /** What the Search feed holds results for; blank before the first search. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Bumped with each new search, so a page still in flight for the previous query is dropped. */
    @Volatile
    private var searchGeneration = 0

    /** Loads the first page unless one is already showing; [force] reloads it. */
    fun load(kind: YouTubeFeedKind, force: Boolean = false) {
        if (kind == YouTubeFeedKind.Search && _searchQuery.value.isBlank()) return
        if (kind == YouTubeFeedKind.Channel && _openChannel.value == null) return
        if (kind == YouTubeFeedKind.Playlist && _openPlaylist.value == null) return
        val current = _feeds.value.getValue(kind)
        if (current.loading || (current.loaded && !force && current.error == null)) return
        fetch(kind, continuation = null)
    }

    private val _openChannel = MutableStateFlow<YouTubeOpenChannel?>(null)

    /** The channel the Channel feed holds; null before one is opened. */
    val openChannel: StateFlow<YouTubeOpenChannel?> = _openChannel.asStateFlow()

    @Volatile
    private var channelGeneration = 0

    /** Opens [channelId]'s page: its uploads replace the Channel feed. */
    fun openChannel(channelId: String, name: String, avatarUrl: String? = null) {
        if (_openChannel.value?.channelId == channelId && _feeds.value.getValue(YouTubeFeedKind.Channel).error == null) return
        channelGeneration++
        _openChannel.value = YouTubeOpenChannel(channelId, name, avatarUrl)
        _feeds.update { it + (YouTubeFeedKind.Channel to YouTubeFeedState()) }
        fetch(YouTubeFeedKind.Channel, continuation = null)
    }

    /** Subscribes to the open channel, or unsubscribes; shown at once and put back if YouTube refuses. */
    fun setSubscribed(subscribed: Boolean) {
        val open = _openChannel.value ?: return
        if (open.changing) return
        _openChannel.value = open.copy(subscribed = subscribed, changing = true)
        scope.launch {
            val result = youTubeSetSubscribed(open.channelId, subscribed, _channel.value.pageId)
            val ok = result is YouTubeBrowseResult.Ok
            log.i { "${if (subscribed) "subscribe" else "unsubscribe"}: ok=$ok" }
            _openChannel.update { current ->
                if (current == null || current.channelId != open.channelId) current
                else current.copy(subscribed = if (ok) subscribed else open.subscribed, changing = false)
            }
            if (ok) {
                // The list and the feed no longer match the account.
                _subscribedChannels.value = YouTubeChannelListState()
                _feeds.update { it + (YouTubeFeedKind.Subscriptions to YouTubeFeedState()) }
            }
        }
    }

    /** Likes [videoId] (or takes it back). True when YouTube took it. */
    suspend fun setLiked(videoId: String, liked: Boolean): Boolean {
        val ok = youTubeSetLiked(videoId, liked, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "${if (liked) "like" else "remove like"}: ok=$ok" }
        if (ok) _likeStatus.update { it + (videoId to liked) }
        return ok
    }

    private val _likeStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Whether the account likes each video asked about ([loadLikeStatus]) or liked here; absent when not known. */
    val likeStatus: StateFlow<Map<String, Boolean>> = _likeStatus.asStateFlow()

    /** Asks YouTube once whether the account likes [videoId] (its watch page says), for the card's menu. */
    suspend fun loadLikeStatus(videoId: String) {
        if (videoId in _likeStatus.value) return
        val page = youTubeNext(videoId, continuation = null, pageId = _channel.value.pageId) as? YouTubeBrowseResult.Ok ?: return
        parseYouTubeLikeStatus(page.body)?.let { liked -> _likeStatus.update { it + (videoId to liked) } }
    }

    private val _openPlaylist = MutableStateFlow<YouTubePlaylist?>(null)

    /** The playlist the Playlist feed holds; null before one is opened. */
    val openPlaylist: StateFlow<YouTubePlaylist?> = _openPlaylist.asStateFlow()

    @Volatile
    private var playlistGeneration = 0

    /** Opens [playlist]: its videos, in order, replace the Playlist feed. */
    fun openPlaylist(playlist: YouTubePlaylist) {
        if (_openPlaylist.value?.playlistId == playlist.playlistId && _feeds.value.getValue(YouTubeFeedKind.Playlist).error == null) {
            _openPlaylist.value = playlist
            return
        }
        playlistGeneration++
        _openPlaylist.value = playlist
        _feeds.update { it + (YouTubeFeedKind.Playlist to YouTubeFeedState()) }
        fetch(YouTubeFeedKind.Playlist, continuation = null)
    }

    private val _playlists = MutableStateFlow(YouTubePlaylistListState())

    /** The account's playlists, Watch Later and Liked videos first. */
    val playlists: StateFlow<YouTubePlaylistListState> = _playlists.asStateFlow()

    fun loadPlaylists(force: Boolean = false) {
        val current = _playlists.value
        if (current.loading || (current.loaded && !force && current.error == null)) return
        _playlists.update { it.copy(loading = true, error = null) }
        val generation = feedGeneration
        scope.launch {
            val result = if (!YouTubeMusicAuth.isSignedIn) {
                YouTubeBrowseResult.SignedOut
            } else {
                youTubeBrowse("FEplaylist_aggregation", continuation = null, pageId = _channel.value.pageId)
            }
            if (generation != feedGeneration) return@launch
            _playlists.value = when (result) {
                is YouTubeBrowseResult.Ok -> {
                    val found = parseYouTubePlaylists(result.body)
                    log.i { "Playlists: ${found.size}" }
                    // The page does not always list the two built-in ones; they are always there.
                    val builtIn = listOf(
                        YouTubePlaylist(WATCH_LATER_PLAYLIST_ID, "Watch later", null, null),
                        YouTubePlaylist(LIKED_PLAYLIST_ID, "Liked videos", null, null),
                    ).filter { builtIn -> found.none { it.playlistId == builtIn.playlistId } }
                    val ordered = found.sortedBy { if (it.playlistId == WATCH_LATER_PLAYLIST_ID || it.playlistId == LIKED_PLAYLIST_ID) 0 else 1 }
                    YouTubePlaylistListState(playlists = builtIn + ordered, loaded = true)
                }
                YouTubeBrowseResult.SignedOut -> YouTubePlaylistListState(loaded = true, error = "Sign in to your YouTube account to see your playlists.")
                is YouTubeBrowseResult.Failed -> _playlists.value.copy(loading = false, loaded = true, error = result.message)
            }
        }
    }

    /**
     * Adds [video] to [playlistId] or removes it. True when YouTube took it; the open playlist's
     * page follows at once (a removed video goes, an added one shows on its next load).
     */
    suspend fun saveToPlaylist(playlistId: String, video: YouTubeVideo, add: Boolean): Boolean {
        val ok = youTubeEditPlaylist(playlistId, video.videoId, add, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "${if (add) "save to" else "remove from"} ${if (playlistId == WATCH_LATER_PLAYLIST_ID) "Watch later" else "playlist"}: ok=$ok" }
        if (ok && _openPlaylist.value?.playlistId == playlistId) {
            if (add) {
                _feeds.update { it + (YouTubeFeedKind.Playlist to it.getValue(YouTubeFeedKind.Playlist).copy(loaded = false)) }
            } else {
                _feeds.update { feeds ->
                    val state = feeds.getValue(YouTubeFeedKind.Playlist)
                    feeds + (YouTubeFeedKind.Playlist to state.copy(videos = state.videos.filterNot { it.videoId == video.videoId }))
                }
            }
        }
        // The library's counts and thumbnails changed.
        if (ok) _playlists.update { it.copy(loaded = false) }
        if (ok && playlistId == WATCH_LATER_PLAYLIST_ID) {
            _watchLater.update { ids -> if (add) ids + video.videoId else ids - video.videoId }
        }
        return ok
    }

    /**
     * Tells YouTube to stop recommending [video]'s channel (the item's own feedback token), and takes the
     * channel's videos off the home feed at once. True when YouTube took it.
     */
    suspend fun dontRecommendChannel(video: YouTubeVideo): Boolean {
        val token = video.dontRecommendToken ?: return false
        val ok = youTubeSendFeedback(token, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "don't recommend channel: ok=$ok" }
        if (ok) {
            fun sameChannel(other: YouTubeVideo) = other.videoId == video.videoId ||
                (video.channelId != null && other.channelId == video.channelId) ||
                (video.channelId == null && video.channel != null && other.channel == video.channel)
            _feeds.update { feeds ->
                val home = feeds.getValue(YouTubeFeedKind.Home)
                feeds + (YouTubeFeedKind.Home to home.copy(videos = home.videos.filterNot(::sameChannel)))
            }
        }
        return ok
    }

    private val _watchLater = MutableStateFlow<Set<String>>(emptySet())

    /** The videos in the account's Watch later as far as known: its first pages ([loadWatchLater]) and what was saved or removed here. */
    val watchLater: StateFlow<Set<String>> = _watchLater.asStateFlow()

    @Volatile
    private var watchLaterAsked = false

    /** Reads the account's Watch later once per session (its first [WATCH_LATER_MAX_PAGES] pages), for the cards' Watch later button. */
    fun loadWatchLater() {
        if (watchLaterAsked || !YouTubeMusicAuth.isSignedIn) return
        watchLaterAsked = true
        val generation = feedGeneration
        scope.launch {
            val ids = LinkedHashSet<String>()
            var continuation: String? = null
            var pages = 0
            do {
                val result = youTubeBrowse(
                    browseId = "VL$WATCH_LATER_PLAYLIST_ID".takeIf { continuation == null },
                    continuation = continuation,
                    pageId = _channel.value.pageId,
                ) as? YouTubeBrowseResult.Ok ?: break
                val page = parseYouTubeFeed(result.body)
                page.videos.mapTo(ids) { it.videoId }
                continuation = page.continuation
                pages++
            } while (continuation != null && pages < WATCH_LATER_MAX_PAGES)
            if (generation != feedGeneration) return@launch
            if (pages == 0) {
                // Asked again next time the tab shows.
                watchLaterAsked = false
                return@launch
            }
            log.i { "Watch later: ${ids.size} videos${if (continuation != null) " (the first $pages pages)" else ""}" }
            // Anything saved here while the pages loaded is kept.
            _watchLater.update { it + ids }
        }
    }

    /**
     * Marks [video] watched in the account's history without playing it ([markYouTubeVideoWatched]); its
     * card shows it finished at once. True when YouTube took it.
     */
    suspend fun markWatched(video: YouTubeVideo): Boolean {
        val ok = markYouTubeVideoWatched(video.videoId, _channel.value.pageId, youTubeDurationSeconds(video.duration))
        log.i { "mark watched: ok=$ok" }
        if (ok) {
            noteWatched(video.videoId, 100)
            YouTubeWatchHistory.noteMarkedWatched(video)
        }
        return ok
    }

    /**
     * A page of [videoId]'s top comments: the first with no [continuation], the next with the page's
     * token. An empty page with no token when comments are off; null when YouTube did not answer.
     */
    suspend fun comments(videoId: String, continuation: String?): YouTubeCommentPage? {
        val pageId = _channel.value.pageId
        val token = continuation ?: run {
            val video = youTubeNext(videoId, continuation = null, pageId = pageId) as? YouTubeBrowseResult.Ok ?: return null
            parseYouTubeCommentsToken(video.body) ?: return YouTubeCommentPage(emptyList(), null)
        }
        val page = youTubeNext(videoId = null, continuation = token, pageId = pageId) as? YouTubeBrowseResult.Ok ?: return null
        return parseYouTubeComments(page.body).also { log.i { "Comments: ${it.comments.size}, more=${it.continuation != null}" } }
    }

    /**
     * What the watch page of [videoId] holds for the player: the videos YouTube suggests beside it (its
     * column, [videoId] itself left out) and the video's chapters ([parseYouTubeChapters]). Empty when
     * YouTube did not answer.
     */
    suspend fun watchPage(videoId: String): YouTubeWatchPage {
        val page = youTubeNext(videoId, continuation = null, pageId = _channel.value.pageId) as? YouTubeBrowseResult.Ok
            ?: return YouTubeWatchPage(emptyList(), emptyList())
        val column = parseYouTubeRelated(page.body)
        val related = column.videos.filter { it.videoId != videoId }
        val chapters = parseYouTubeChapters(page.body)
        // The same answer says whether the account likes it: no second request for the like button.
        parseYouTubeLikeStatus(page.body)?.let { liked -> _likeStatus.update { it + (videoId to liked) } }
        log.i { "Watch page: ${related.size} related (more=${column.continuation != null}), ${chapters.size} chapters" }
        return YouTubeWatchPage(related, chapters, parseYouTubeVideoDetails(page.body), column.continuation)
    }

    /** The next page of a watch page's related videos ([YouTubeWatchPage.relatedContinuation]); null when YouTube did not answer. */
    suspend fun moreRelated(token: String): YouTubeFeedPage? {
        val page = youTubeNext(videoId = null, continuation = token, pageId = _channel.value.pageId) as? YouTubeBrowseResult.Ok
            ?: return null
        return parseYouTubeRelated(page.body).also { log.i { "Related: ${it.videos.size} more, more=${it.continuation != null}" } }
    }

    /** A page of a comment's replies ([YouTubeComment.repliesToken], then each page's own token); null when YouTube did not answer. */
    suspend fun commentReplies(token: String): YouTubeCommentPage? {
        val page = youTubeNext(videoId = null, continuation = token, pageId = _channel.value.pageId) as? YouTubeBrowseResult.Ok ?: return null
        return parseYouTubeCommentReplies(page.body).also { log.i { "Replies: ${it.comments.size}, more=${it.continuation != null}" } }
    }

    /** Posts [text] as a comment ([YouTubeCommentPage.createParams]) or a reply ([YouTubeComment.replyParams]). True when YouTube took it. */
    suspend fun postComment(params: String, text: String, reply: Boolean): Boolean {
        val ok = youTubeCreateComment(params, text, reply, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "${if (reply) "reply" else "comment"} posted: ok=$ok" }
        return ok
    }

    /** Likes [comment] as the account, or takes the like back. True when YouTube took it. */
    suspend fun setCommentLiked(comment: YouTubeComment, liked: Boolean): Boolean {
        val action = (if (liked) comment.likeAction else comment.unlikeAction) ?: return false
        val ok = youTubePerformCommentAction(action, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "comment ${if (liked) "like" else "remove like"}: ok=$ok" }
        return ok
    }

    /** Subscribes to [channelId] or unsubscribes, from anywhere (the open channel page has [setSubscribed]). True when YouTube took it. */
    suspend fun setSubscribedTo(channelId: String, subscribed: Boolean): Boolean {
        val ok = youTubeSetSubscribed(channelId, subscribed, _channel.value.pageId) is YouTubeBrowseResult.Ok
        log.i { "${if (subscribed) "subscribe" else "unsubscribe"}: ok=$ok" }
        if (ok) {
            _openChannel.update { if (it?.channelId == channelId) it.copy(subscribed = subscribed) else it }
            // The list and the feed no longer match the account.
            _subscribedChannels.value = YouTubeChannelListState()
            _feeds.update { it + (YouTubeFeedKind.Subscriptions to YouTubeFeedState()) }
        }
        return ok
    }

    /**
     * The video to play after [videoId] when autoplay is on: YouTube's own pick ([parseYouTubeAutoplay]),
     * taken from the related videos when it is among them (they carry its length and channel), or the
     * first related video not yet watched when YouTube named none. Null when YouTube did not answer.
     */
    suspend fun autoplayPick(videoId: String): YouTubeVideo? {
        val page = youTubeNext(videoId, continuation = null, pageId = _channel.value.pageId) as? YouTubeBrowseResult.Ok
            ?: return null
        val related = parseYouTubeFeed(page.body).videos.filter { it.videoId != videoId }
        val pick = parseYouTubeAutoplay(page.body)?.takeIf { it.videoId != videoId }
        val chosen = pick?.let { p -> related.firstOrNull { it.videoId == p.videoId } ?: p }
            ?: related.firstOrNull { !isYouTubeVideoWatched(it) }
        log.i { "Autoplay after $videoId: ${chosen?.videoId} (${if (pick != null) "YouTube's pick" else "first unwatched related"})" }
        return chosen
    }

    /** The playlists [videoId] can be saved to, or null when YouTube did not answer. */
    suspend fun playlistOptions(videoId: String): List<YouTubePlaylistOption>? {
        val result = youTubePlaylistOptions(videoId, _channel.value.pageId) as? YouTubeBrowseResult.Ok ?: return null
        return parseYouTubePlaylistOptions(result.body)
    }

    private val _subscribedChannels = MutableStateFlow(YouTubeChannelListState())

    /** The channels the account subscribes to (Subscriptions > Channels on the website). */
    val subscribedChannels: StateFlow<YouTubeChannelListState> = _subscribedChannels.asStateFlow()

    fun loadSubscribedChannels(force: Boolean = false) {
        val current = _subscribedChannels.value
        if (current.loading || (current.loaded && !force && current.error == null)) return
        fetchChannels(continuation = null)
    }

    fun loadMoreSubscribedChannels() {
        val current = _subscribedChannels.value
        val token = current.continuation ?: return
        if (current.loading) return
        fetchChannels(continuation = token)
    }

    private fun fetchChannels(continuation: String?) {
        _subscribedChannels.update { it.copy(loading = true, error = null) }
        val generation = feedGeneration
        scope.launch {
            val result = if (!YouTubeMusicAuth.isSignedIn) {
                YouTubeBrowseResult.SignedOut
            } else {
                youTubeBrowse(
                    browseId = "FEchannels".takeIf { continuation == null },
                    continuation = continuation,
                    pageId = _channel.value.pageId,
                )
            }
            if (generation != feedGeneration) return@launch
            _subscribedChannels.update { previous ->
                when (result) {
                    is YouTubeBrowseResult.Ok -> {
                        val page = parseYouTubeChannelList(result.body)
                        log.i { "Channels: ${page.channels.size}, more=${page.continuation != null}" }
                        val seen = if (continuation == null) emptySet() else previous.channels.mapTo(HashSet()) { it.channelId }
                        YouTubeChannelListState(
                            channels = (if (continuation == null) emptyList() else previous.channels) +
                                page.channels.filter { it.channelId !in seen },
                            continuation = page.continuation,
                            loaded = true,
                        )
                    }
                    YouTubeBrowseResult.SignedOut -> YouTubeChannelListState(loaded = true, error = "Sign in to your YouTube account to see your subscriptions.")
                    is YouTubeBrowseResult.Failed -> previous.copy(loading = false, loaded = true, error = result.message)
                }
            }
        }
    }

    /** Whether [kind] is one that changes by itself and was last fetched more than [FEED_STALE_MS] ago. */
    fun isStale(kind: YouTubeFeedKind): Boolean {
        if (kind !in SELF_REFRESHING_FEEDS) return false
        val state = _feeds.value.getValue(kind)
        return state.loaded && !state.loading && state.error == null &&
            com.nuvio.app.features.streams.epochMs() - state.loadedAtMs > FEED_STALE_MS
    }

    /** Replaces the Search feed with the results for [query]. Blank does nothing. */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        searchGeneration++
        _searchQuery.value = trimmed
        _feeds.update { it + (YouTubeFeedKind.Search to YouTubeFeedState()) }
        fetch(YouTubeFeedKind.Search, continuation = null)
    }

    fun loadMore(kind: YouTubeFeedKind) {
        val current = _feeds.value.getValue(kind)
        val token = current.continuation ?: return
        if (current.loading) return
        fetch(kind, continuation = token)
    }

    /** Forgets every feed, for a sign-in or sign-out: the old pages belong to the other session. */
    fun clear() {
        feedGeneration++
        _feeds.value = YouTubeFeedKind.entries.associateWith { YouTubeFeedState() }
        _channel.value = YouTubeChannelState()
        _subscribedChannels.value = YouTubeChannelListState()
        _playlists.value = YouTubePlaylistListState()
        _openChannel.update { it?.copy(subscribed = null, changing = false) }
        forgetWatchLater()
    }

    private fun forgetWatchLater() {
        _watchLater.value = emptySet()
        watchLaterAsked = false
    }

    /** Fetches the channel list once per session (again after a failure) and settles which channel the feeds act as. */
    fun loadChannels(force: Boolean = false) {
        val current = _channel.value
        if (current.loading || (current.resolved && !force && current.error == null)) return
        _channel.update { it.copy(loading = true, error = null) }
        scope.launch {
            val choice = YouTubeModeStorage.loadChannelChoice()
            val result = if (YouTubeMusicAuth.isSignedIn) youTubeAccountsList() else YouTubeBrowseResult.SignedOut
            val channels = (result as? YouTubeBrowseResult.Ok)?.let { parseYouTubeChannels(it.body) }.orEmpty()
            val known = channels.mapTo(HashSet()) { it.pageId.orEmpty() }
            val pageId: String?
            val needsChoice: Boolean
            when {
                // A remembered choice that is still on the account (or nothing to check it against).
                choice != null && (channels.isEmpty() || choice in known) -> {
                    pageId = choice.ifEmpty { null }
                    needsChoice = false
                }
                else -> {
                    pageId = autoChannelPageId(channels)
                    needsChoice = pageId == null && channelChoiceNeeded(channels)
                }
            }
            if (result is YouTubeBrowseResult.Ok && channels.isEmpty()) {
                log.w { "accounts_list held no channels; renderers: ${youTubeRendererNames(result.body)}" }
            }
            log.i { "channels: ${channels.size} (${channels.count { it.pageId != null }} brand, profile has channel=${channels.firstOrNull { it.pageId == null }?.hasChannel}), acting as ${pageId?.let { "brand channel" } ?: "account profile"}, choice=${choice != null}" }
            _channel.value = YouTubeChannelState(
                channels = channels,
                pageId = pageId,
                resolved = true,
                needsChoice = needsChoice,
                error = (result as? YouTubeBrowseResult.Failed)?.message,
            )
        }
    }

    /** A video just watched in Nuvio: show its new progress bar without waiting for YouTube's copy. */
    fun noteWatched(videoId: String, percent: Int) {
        _feeds.update { feeds ->
            feeds.mapValues { (_, state) ->
                if (state.videos.none { it.videoId == videoId }) {
                    state
                } else {
                    state.copy(videos = state.videos.map { if (it.videoId == videoId) it.copy(watchedPercent = percent) else it })
                }
            }
        }
    }

    /** Acts as [pageId] from now on (null: the account's own profile) and reloads the feeds. */
    fun selectChannel(pageId: String?) {
        YouTubeModeStorage.saveChannelChoice(pageId.orEmpty())
        _channel.update { it.copy(pageId = pageId, needsChoice = false) }
        feedGeneration++
        _feeds.value = YouTubeFeedKind.entries.associateWith { YouTubeFeedState() }
        forgetWatchLater()
    }

    private fun fetch(kind: YouTubeFeedKind, continuation: String?) {
        _feeds.update { it + (kind to it.getValue(kind).copy(loading = true, error = null)) }
        val generation = feedGeneration
        val search = searchGeneration
        val channelPage = channelGeneration
        val openChannelId = _openChannel.value?.channelId
        val playlistPage = playlistGeneration
        val openPlaylistId = _openPlaylist.value?.playlistId
        scope.launch {
            val result = when {
                !YouTubeMusicAuth.isSignedIn -> YouTubeBrowseResult.SignedOut
                kind == YouTubeFeedKind.Search -> youTubeSearch(
                    query = _searchQuery.value.takeIf { continuation == null },
                    continuation = continuation,
                    pageId = _channel.value.pageId,
                )
                kind == YouTubeFeedKind.Channel -> openChannelId?.let {
                    youTubeChannelVideos(it, continuation, _channel.value.pageId)
                } ?: YouTubeBrowseResult.Failed("No channel is open.")
                kind == YouTubeFeedKind.Playlist -> openPlaylistId?.let {
                    youTubeBrowse("VL$it".takeIf { continuation == null }, continuation, _channel.value.pageId)
                } ?: YouTubeBrowseResult.Failed("No playlist is open.")
                else -> youTubeBrowse(
                    browseId = kind.browseId.takeIf { continuation == null },
                    continuation = continuation,
                    pageId = _channel.value.pageId,
                )
            }
            if (generation != feedGeneration) return@launch
            if (kind == YouTubeFeedKind.Search && search != searchGeneration) return@launch
            if (kind == YouTubeFeedKind.Channel && channelPage != channelGeneration) return@launch
            if (kind == YouTubeFeedKind.Playlist && playlistPage != playlistGeneration) return@launch
            // The first page of a channel also says whether the account is subscribed to it, and its counts.
            if (kind == YouTubeFeedKind.Channel && continuation == null && result is YouTubeBrowseResult.Ok) {
                parseYouTubeSubscribed(result.body)?.let { subscribed ->
                    _openChannel.update {
                        if (it != null && it.channelId == openChannelId && !it.changing) it.copy(subscribed = subscribed) else it
                    }
                }
                parseYouTubeChannelStats(result.body)?.let { stats ->
                    _openChannel.update { if (it != null && it.channelId == openChannelId) it.copy(stats = stats) else it }
                }
            }
            _feeds.update { feeds ->
                val previous = feeds.getValue(kind)
                val next = when (result) {
                    is YouTubeBrowseResult.Ok -> {
                        val page = parseYouTubeFeed(result.body)
                        // Each History page goes into this PC's copy of the history, under its days.
                        if (kind == YouTubeFeedKind.History) YouTubeWatchHistory.noteHistoryPage(page)
                        // The label only: a search query stays out of the log.
                        log.i { "${kind.label}: ${page.videos.size} videos, more=${page.continuation != null}" }
                        val videos = if (continuation == null) {
                            page.videos
                        } else {
                            val seen = previous.videos.mapTo(HashSet()) { it.videoId }
                            previous.videos + page.videos.filter { it.videoId !in seen }
                        }
                        YouTubeFeedState(
                            videos = videos,
                            continuation = page.continuation,
                            loaded = true,
                            // A later page does not make the feed any newer.
                            loadedAtMs = if (continuation == null) com.nuvio.app.features.streams.epochMs() else previous.loadedAtMs,
                        )
                    }
                    YouTubeBrowseResult.SignedOut -> YouTubeFeedState(
                        loaded = true,
                        error = "Sign in to your YouTube account to see your feed.",
                    )
                    is YouTubeBrowseResult.Failed -> previous.copy(
                        loading = false,
                        loaded = previous.loaded || continuation == null,
                        error = result.message,
                    )
                }
                feeds + (kind to next)
            }
        }
    }
}
