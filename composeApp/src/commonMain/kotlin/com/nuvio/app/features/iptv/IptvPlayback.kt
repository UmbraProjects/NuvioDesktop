package com.nuvio.app.features.iptv

import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * Starts a session for [channel] and builds its player launch; null when it has no playable URL.
     * [zapList] is what channel up/down steps through (the tab's current list); empty means the
     * whole playlist.
     */
    fun launchFor(playlist: IptvPlaylist, channel: IptvChannel, zapList: List<IptvChannel> = emptyList()): PlayerLaunch? {
        val candidates = candidatesFor(playlist, channel)
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
        val at = list.indexOfFirst { it.uid == current.channel.uid }.takeIf { it >= 0 } ?: return list.first()
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
    fun switchTo(channel: IptvChannel): String? {
        val current = _active.value ?: return null
        val candidates = candidatesFor(current.playlist, channel)
        if (candidates.isEmpty()) return null
        _active.value = current.copy(channel = channel, candidates = candidates, index = 0)
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

    /** False when the channel has no playable URL. [zapList]: see [IptvPlaybackSession.launchFor]. */
    fun play(playlist: IptvPlaylist, channel: IptvChannel, zapList: List<IptvChannel> = emptyList()): Boolean {
        val launch = IptvPlaybackSession.launchFor(playlist, channel, zapList) ?: return false
        return _requests.tryEmit(launch)
    }
}
