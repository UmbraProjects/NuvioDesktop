package com.nuvio.app.features.iptv

import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Meta type of an IPTV playback. It is in `isLiveEventContentType`'s list, so the player keeps no
 * watch progress, writes no Continue Watching row and sends no tracker scrobble for it.
 */
const val IPTV_META_TYPE = "iptv"

/**
 * The channel being played and the URLs it can be opened with, in the order to try them (`ts` ⇄
 * `m3u8`, see [iptvStreamCandidates]). The player only knows a URL; this is how it learns that the
 * URL is IPTV (no redirect probe, live mpv options, proxy relay) and what to try when it fails.
 */
object IptvPlaybackSession {
    data class Active(
        val playlist: IptvPlaylist,
        val channel: IptvChannel,
        val candidates: List<IptvStreamCandidate>,
        val index: Int = 0,
        /** The channels channel up/down walks through: the list the user started from. */
        val zapList: List<IptvChannel> = emptyList(),
        /** The channel whose link the portal is being asked for; channel up/down counts from it. */
        val opening: IptvChannel? = null,
    ) {
        val current: IptvStreamCandidate get() = candidates[index]
    }

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    private fun candidatesFor(playlist: IptvPlaylist, channel: IptvChannel): List<IptvStreamCandidate> {
        val settings = IptvRepository.settings.value
        val allowed = IptvRepository.contentState(playlist.id).value.content?.account?.allowedFormats.orEmpty()
        return iptvStreamCandidates(playlist, channel, settings.preferredFormat, allowed)
    }

    /** Whether opening [channel] means asking its portal for a link first, because links expire. */
    fun needsLink(playlist: IptvPlaylist, channel: IptvChannel): Boolean =
        playlist.type == IptvPlaylistType.Stalker && StalkerUrls.needsLink(channel.url)

    /** Asks the playing playlist's portal for [channel]'s link now. */
    suspend fun linkCandidates(channel: IptvChannel): List<IptvStreamCandidate> {
        val current = _active.value ?: return emptyList()
        return StalkerClient.streamCandidates(current.playlist, channel)
    }

    fun needsLink(channel: IptvChannel): Boolean = _active.value?.let { needsLink(it.playlist, channel) } == true

    fun setOpening(channel: IptvChannel?) {
        _active.update { it?.copy(opening = channel) }
    }

    /** A new link for the playing channel when it needs one, for a reconnect; null to reuse the URL. */
    suspend fun renewLink(): String? {
        val current = _active.value ?: return null
        if (!needsLink(current.playlist, current.channel)) return null
        val candidates = linkCandidates(current.channel).takeIf { it.isNotEmpty() } ?: return null
        if (_active.value?.channel?.uid != current.channel.uid) return null
        _active.update { it?.copy(candidates = candidates, index = 0) }
        return candidates.first().url
    }

    /**
     * Starts a session for [channel] and builds its player launch; null when it has no playable URL.
     * [zapList] is what channel up/down steps through (the tab's current list); empty means the
     * whole playlist.
     */
    fun launchFor(
        playlist: IptvPlaylist,
        channel: IptvChannel,
        zapList: List<IptvChannel> = emptyList(),
        candidates: List<IptvStreamCandidate> = candidatesFor(playlist, channel),
    ): PlayerLaunch? {
        if (candidates.isEmpty()) return null
        _active.value = Active(playlist, channel, candidates, zapList = zapList)
        IptvRepository.recordRecent(playlist.id, channel.key)
        val title = channel.name.ifBlank { "#${channel.num}" }
        val id = "iptv:${playlist.id}:${channel.key}"
        return PlayerLaunch(
            title = title,
            sourceUrl = candidates.first().url,
            sourceHeaders = channel.requestHeaders(),
            logo = channel.logo,
            poster = channel.logo,
            streamTitle = playlist.name.ifBlank { title },
            providerName = playlist.name.ifBlank { "IPTV" },
            contentType = IPTV_META_TYPE,
            videoId = id,
            parentMetaId = id,
            parentMetaType = IPTV_META_TYPE,
            disableProgressTracking = true,
        )
    }

    /** Whether [url] is one of the current channel's URLs. */
    fun isIptvUrl(url: String?): Boolean =
        url != null && _active.value?.candidates?.any { it.url == url } == true

    /**
     * After [failedUrl] failed: the next URL to try for the same channel, or null when every format
     * has been tried.
     */
    fun nextCandidateAfter(failedUrl: String): IptvStreamCandidate? {
        val current = _active.value ?: return null
        val failedIndex = current.candidates.indexOfFirst { it.url == failedUrl }.takeIf { it >= 0 } ?: return null
        val next = failedIndex + 1
        if (next >= current.candidates.size) return null
        _active.value = current.copy(index = next)
        return current.candidates[next]
    }

    /** The channel [delta] steps from the playing one in the zap list, wrapping round; null if none. */
    fun neighbour(delta: Int): IptvChannel? {
        val current = _active.value ?: return null
        val list = zapListOf(current)
        if (list.size < 2) return null
        val from = current.opening ?: current.channel
        val at = list.indexOfFirst { it.uid == from.uid }.takeIf { it >= 0 } ?: return list.first()
        return list[Math.floorMod(at + delta, list.size)]
    }

    /** The channel numbered [number] in the playing playlist (not just the zap list), if any. */
    fun byNumber(number: Int): IptvChannel? {
        val current = _active.value ?: return null
        val all = IptvRepository.contentState(current.playlist.id).value.content?.channels ?: current.zapList
        return all.firstOrNull { it.num == number }
    }

    /** Channel up/down now walks [list] (a category picked in the player's channel switcher). */
    fun setZapList(list: List<IptvChannel>) {
        _active.value = _active.value?.copy(zapList = list)
    }

    /** Makes [channel] the playing one; returns the URL to open, or null when it has none. */
    fun switchTo(channel: IptvChannel, link: List<IptvStreamCandidate>? = null): String? {
        val current = _active.value ?: return null
        val candidates = link ?: candidatesFor(current.playlist, channel)
        if (candidates.isEmpty()) return null
        _active.value = current.copy(channel = channel, candidates = candidates, index = 0, opening = null)
        IptvRepository.recordRecent(current.playlist.id, channel.key)
        return candidates.first().url
    }

    private fun zapListOf(active: Active): List<IptvChannel> =
        active.zapList.ifEmpty { IptvRepository.contentState(active.playlist.id).value.content?.channels.orEmpty() }

    fun clear() {
        _active.value = null
    }
}

/**
 * Playback requests from screens that cannot reach the navigation controller (the Live TV settings
 * page now, the Live TV tab later). App collects these and opens the player.
 */
object IptvPlaybackRequests {
    private val _requests = MutableSharedFlow<PlayerLaunch>(extraBufferCapacity = 1)
    val requests: SharedFlow<PlayerLaunch> = _requests.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var linkJob: Job? = null

    /** False when the channel has no playable URL. [zapList]: see [IptvPlaybackSession.launchFor]. */
    fun play(playlist: IptvPlaylist, channel: IptvChannel, zapList: List<IptvChannel> = emptyList()): Boolean {
        linkJob?.cancel()
        if (IptvPlaybackSession.needsLink(playlist, channel)) {
            linkJob = scope.launch {
                val candidates = StalkerClient.streamCandidates(playlist, channel)
                IptvPlaybackSession.launchFor(playlist, channel, zapList, candidates)?.let { _requests.tryEmit(it) }
            }
            return true
        }
        val launch = IptvPlaybackSession.launchFor(playlist, channel, zapList) ?: return false
        return _requests.tryEmit(launch)
    }
}
