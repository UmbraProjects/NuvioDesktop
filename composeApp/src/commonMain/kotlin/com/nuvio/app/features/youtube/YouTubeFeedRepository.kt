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
}

/** A channel the tab has open, and whether the account is subscribed to it (null until known). */
data class YouTubeOpenChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val subscribed: Boolean? = null,
    /** A subscribe or unsubscribe is on its way. */
    val changing: Boolean = false,
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
)

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

/** The account's channel list (`account/accounts_list`), as a raw body like [youTubeBrowse]. */
internal expect suspend fun youTubeAccountsList(): YouTubeBrowseResult

/** A playable, probed source for a full-length video (video and audio), or null when none opens. */
internal expect suspend fun youTubeOpenVideo(videoId: String): TrailerPlaybackSource?

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

    /** The chosen channel: a page id, "" for the account's own profile, null when never chosen. */
    fun loadChannelChoice(): String?
    fun saveChannelChoice(choice: String?)
}

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
        return ok
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
        _openChannel.update { it?.copy(subscribed = null, changing = false) }
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
    }

    private fun fetch(kind: YouTubeFeedKind, continuation: String?) {
        _feeds.update { it + (kind to it.getValue(kind).copy(loading = true, error = null)) }
        val generation = feedGeneration
        val search = searchGeneration
        val channelPage = channelGeneration
        val openChannelId = _openChannel.value?.channelId
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
                else -> youTubeBrowse(
                    browseId = kind.browseId.takeIf { continuation == null },
                    continuation = continuation,
                    pageId = _channel.value.pageId,
                )
            }
            if (generation != feedGeneration) return@launch
            if (kind == YouTubeFeedKind.Search && search != searchGeneration) return@launch
            if (kind == YouTubeFeedKind.Channel && channelPage != channelGeneration) return@launch
            // The first page of a channel also says whether the account is subscribed to it.
            if (kind == YouTubeFeedKind.Channel && continuation == null && result is YouTubeBrowseResult.Ok) {
                parseYouTubeSubscribed(result.body)?.let { subscribed ->
                    _openChannel.update {
                        if (it != null && it.channelId == openChannelId && !it.changing) it.copy(subscribed = subscribed) else it
                    }
                }
            }
            _feeds.update { feeds ->
                val previous = feeds.getValue(kind)
                val next = when (result) {
                    is YouTubeBrowseResult.Ok -> {
                        val page = parseYouTubeFeed(result.body)
                        // The label only: a search query stays out of the log.
                        log.i { "${kind.label}: ${page.videos.size} videos, more=${page.continuation != null}" }
                        val videos = if (continuation == null) {
                            page.videos
                        } else {
                            val seen = previous.videos.mapTo(HashSet()) { it.videoId }
                            previous.videos + page.videos.filter { it.videoId !in seen }
                        }
                        YouTubeFeedState(videos = videos, continuation = page.continuation, loaded = true)
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
