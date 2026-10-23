package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Everything IPTV keeps for a profile. Local only: playlists hold provider passwords. */
@Serializable
data class IptvSettings(
    val playlists: List<IptvPlaylist> = emptyList(),
    /** `playlistId:channelKey`, in the order they were added. */
    val favourites: List<String> = emptyList(),
    /** `playlistId:channelKey`, most recent first. */
    val recents: List<String> = emptyList(),
    val refreshHours: Int = IptvRefreshPolicy.DEFAULT_HOURS,
    /** [IptvStreamFormat] to try first; null = Auto (TS first). */
    val preferredFormat: String? = null,
    /** Route IPTV traffic through a proxy, for providers the user's ISP blocks. */
    val proxy: IptvProxy = IptvProxy(),
    /** How far back live TV can be rewound, in minutes; 0 = mpv's own default (a few minutes). */
    val rewindMinutes: Int = 0,
)

/** The rewind buffer choices, in minutes; anything above 0 is kept on disk, not in memory. */
val IPTV_REWIND_OPTIONS = listOf(0, 10, 30, 60)

@Serializable
enum class IptvProxyType { Off, Http, Socks }

/**
 * A proxy for IPTV requests only (the rest of the app connects directly). Playlists, guides and
 * account checks honour both types; mpv can only use an HTTP proxy, so SOCKS covers loading but
 * not playback ([mpvHttpProxyUrl] is null for it).
 */
@Serializable
data class IptvProxy(
    val type: IptvProxyType = IptvProxyType.Off,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val enabled: Boolean get() = type != IptvProxyType.Off && host.isNotBlank() && port in 1..65535

    /** `http://user:pass@host:port` for mpv's `http-proxy` option; null unless an HTTP proxy is set. */
    fun mpvHttpProxyUrl(): String? {
        if (!enabled || type != IptvProxyType.Http) return null
        val auth = if (username.isNotEmpty()) {
            fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
            "${enc(username)}:${enc(password)}@"
        } else {
            ""
        }
        return "http://$auth$host:$port"
    }

    companion object {
        /** Accepts `host`, `host:port` or a pasted `http://` / `socks5://` URL; returns host and port (0 if absent). */
        fun parseHostAndPort(raw: String): Pair<String, Int> {
            val trimmed = raw.trim().substringAfter("://").substringBefore('/').substringAfterLast('@')
            if (trimmed.startsWith("[")) return trimmed to 0 // IPv6 literal; port stays separate
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0) return trimmed to 0
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return trimmed to 0
            return trimmed.substring(0, colon) to port
        }
    }
}

/** A playlist's channels as the UI sees them: what is cached, and whether a refresh is running or failed. */
data class IptvContentState(
    val content: IptvPlaylistContent? = null,
    val loading: Boolean = false,
    val error: IptvSourceException? = null,
)

/**
 * IPTV playlists for the active profile and their loaded channels. The settings follow the
 * PlaylistRepository shape (an in-memory StateFlow, loaded lazily, written through). Channel lists
 * are cached on disk and refreshed behind by [IptvRefreshPolicy].
 */
object IptvRepository {
    private val log = Logger.withTag("Iptv")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val MAX_RECENTS = 30
    private const val CACHE_VERSION = 1

    private val _settings = MutableStateFlow(IptvSettings())
    val settings: StateFlow<IptvSettings> = _settings.asStateFlow()

    private var hasLoaded = false

    private val contentStates = ConcurrentHashMap<String, MutableStateFlow<IptvContentState>>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val lastFailureAt = ConcurrentHashMap<String, Long>()
    private val checkedThisLaunch: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = IptvStorage.loadPayload()
        if (payload.isNullOrBlank()) return
        runCatching {
            _settings.value = json.decodeFromString<IptvSettings>(payload)
        }.onFailure { error ->
            log.e(error) { "Failed to load IPTV settings from storage" }
        }
    }

    fun onProfileChanged() {
        hasLoaded = false
        _settings.value = IptvSettings()
        contentStates.clear()
        lastFailureAt.clear()
        checkedThisLaunch.clear()
        IptvEpgRepository.clear()
    }

    fun clearLocalState() {
        onProfileChanged()
        IptvCacheStorage.clearAll()
    }

    // region Playlists

    fun playlist(id: String): IptvPlaylist? {
        ensureLoaded()
        return _settings.value.playlists.firstOrNull { it.id == id }
    }

    /** Adds [draft] under a new id (its own `id` is ignored) and returns the stored playlist. */
    fun addPlaylist(draft: IptvPlaylist): IptvPlaylist {
        ensureLoaded()
        val playlist = sanitized(draft.copy(id = UUID.randomUUID().toString()))
        mutate { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    /** Replaces the playlist with [playlist]'s id. Changed credentials or URLs invalidate its cache. */
    fun updatePlaylist(playlist: IptvPlaylist) {
        ensureLoaded()
        val clean = sanitized(playlist)
        val previous = playlist(clean.id) ?: return
        mutate { s -> s.copy(playlists = s.playlists.map { if (it.id == clean.id) clean else it }) }
        if (previous.withoutName() != clean.withoutName()) {
            contentStates[clean.id]?.value = IptvContentState()
            checkedThisLaunch.remove(clean.id)
            lastFailureAt.remove(clean.id)
            IptvEpgRepository.forget(clean.id)
        }
    }

    fun removePlaylist(id: String) {
        ensureLoaded()
        val prefix = "$id:"
        mutate { s ->
            s.copy(
                playlists = s.playlists.filterNot { it.id == id },
                favourites = s.favourites.filterNot { it.startsWith(prefix) },
                recents = s.recents.filterNot { it.startsWith(prefix) },
            )
        }
        contentStates.remove(id)
        lastFailureAt.remove(id)
        checkedThisLaunch.remove(id)
        IptvEpgRepository.forget(id)
        runCatching {
            IptvCacheStorage.delete(contentCacheName(id))
            IptvCacheStorage.delete(IptvEpgRepository.guideCacheName(id))
        }
    }

    private fun sanitized(p: IptvPlaylist): IptvPlaylist = p.copy(
        name = p.name.trim(),
        server = p.server?.trim()?.takeIf { it.isNotEmpty() },
        username = p.username?.trim()?.takeIf { it.isNotEmpty() },
        password = p.password?.takeIf { it.isNotEmpty() },
        url = p.url?.trim()?.takeIf { it.isNotEmpty() },
        epgUrl = p.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun IptvPlaylist.withoutName() = copy(name = "")

    // endregion

    // region Favourites, recents, preferences

    fun channelRef(playlistId: String, channelKey: String) = "$playlistId:$channelKey"

    fun isFavourite(playlistId: String, channelKey: String): Boolean =
        channelRef(playlistId, channelKey) in _settings.value.favourites

    fun toggleFavourite(playlistId: String, channelKey: String) {
        ensureLoaded()
        val ref = channelRef(playlistId, channelKey)
        mutate { s -> s.copy(favourites = if (ref in s.favourites) s.favourites - ref else s.favourites + ref) }
    }

    fun recordRecent(playlistId: String, channelKey: String) {
        ensureLoaded()
        val ref = channelRef(playlistId, channelKey)
        mutate { s -> s.copy(recents = (listOf(ref) + (s.recents - ref)).take(MAX_RECENTS)) }
    }

    fun setRefreshHours(hours: Int) {
        ensureLoaded()
        mutate { it.copy(refreshHours = hours.coerceAtLeast(0)) }
    }

    fun setPreferredFormat(format: String?) {
        ensureLoaded()
        mutate { it.copy(preferredFormat = format?.takeIf { f -> f == IptvStreamFormat.TS || f == IptvStreamFormat.HLS }) }
    }

    fun setRewindMinutes(minutes: Int) {
        ensureLoaded()
        mutate { it.copy(rewindMinutes = minutes.takeIf { m -> m in IPTV_REWIND_OPTIONS } ?: 0) }
    }

    fun setProxy(proxy: IptvProxy) {
        ensureLoaded()
        val clean = proxy.copy(host = proxy.host.trim(), username = proxy.username.trim())
        if (clean == _settings.value.proxy) return
        mutate { it.copy(proxy = clean) }
        // A list that failed because the provider was unreachable should be tried again at once.
        lastFailureAt.clear()
    }

    private fun mutate(change: (IptvSettings) -> IptvSettings) {
        _settings.update(change)
        persist()
    }

    private fun persist() {
        runCatching { IptvStorage.savePayload(json.encodeToString(IptvSettings.serializer(), _settings.value)) }
            .onFailure { log.e(it) { "Failed to save IPTV settings" } }
    }

    // endregion

    // region Channels

    fun contentState(playlistId: String): StateFlow<IptvContentState> =
        contentStates.getOrPut(playlistId) { MutableStateFlow(IptvContentState()) }.asStateFlow()

    /**
     * Makes [playlistId]'s channels available: the disk cache first (shown at once), then a download
     * when the cache is missing, stale by the refresh setting, or [force]d. A failed download keeps
     * whatever was cached and records the error. Concurrent calls for one playlist collapse into one.
     */
    suspend fun refresh(playlistId: String, force: Boolean = false) {
        val playlist = playlist(playlistId) ?: return
        val state = contentStates.getOrPut(playlistId) { MutableStateFlow(IptvContentState()) }
        val lock = loadLocks.getOrPut(playlistId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            if (state.value.content == null) {
                readCachedContent(playlist)?.let { cached -> state.update { it.copy(content = cached) } }
            }
            val now = System.currentTimeMillis()
            val cached = state.value.content
            val due = force || IptvRefreshPolicy.isDue(
                hours = _settings.value.refreshHours,
                fetchedAt = cached?.fetchedAt ?: 0L,
                now = now,
                checkedSinceOpen = playlistId in checkedThisLaunch,
            )
            val backingOff = !force && cached != null &&
                (lastFailureAt[playlistId]?.let { now - it < IptvRefreshPolicy.RETRY_MS } == true)
            if (!due || backingOff) return

            checkedThisLaunch += playlistId
            state.update { it.copy(loading = true, error = null) }
            try {
                val fresh = IptvSources.loadPlaylist(playlist)
                // The playlist may have been edited or removed while this was downloading.
                if (playlist(playlistId) != playlist) return
                lastFailureAt.remove(playlistId)
                state.value = IptvContentState(content = fresh)
                writeCachedContent(fresh)
                log.i { "Loaded IPTV playlist ${playlist.type}: ${fresh.channels.size} channels, ${fresh.categories.size} categories" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a native-image build reports a missing registration
                // as an Error, and that must end up on screen and in the log, not kill the job.
                val error = e.asIptvError()
                lastFailureAt[playlistId] = System.currentTimeMillis()
                state.update { it.copy(error = error) }
                log.w { "IPTV playlist load failed: ${describeIptvFailure(e)}" }
            } finally {
                state.update { it.copy(loading = false) }
            }
        } finally {
            lock.unlock()
        }
    }

    // Not tied to a screen: a refresh started from Settings keeps going after the page closes.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [refresh] then the playlist's guide, off any screen's scope. */
    fun refreshInBackground(playlistId: String, force: Boolean = false) {
        backgroundScope.launch {
            refresh(playlistId, force)
            contentStates[playlistId]?.value?.content?.let { IptvEpgRepository.refresh(it, force) }
        }
    }

    fun refreshAllInBackground(force: Boolean = false) {
        ensureLoaded()
        _settings.value.playlists.forEach { refreshInBackground(it.id, force) }
    }

    /**
     * Checks a playlist the user is about to save: Xtream logs in and reports the account; M3U
     * downloads and parses the list. Nothing is stored.
     */
    suspend fun test(playlist: IptvPlaylist): IptvTestResult {
        val clean = sanitized(playlist)
        log.i { "IPTV test ${clean.type} started" }
        return try {
            when (clean.type) {
                IptvPlaylistType.Xtream -> {
                    val account = XtreamClient.account(clean)
                    IptvTestResult(account = account, channelCount = null)
                }
                IptvPlaylistType.M3u -> {
                    val content = IptvSources.loadPlaylist(clean)
                    IptvTestResult(account = null, channelCount = content.channels.size)
                }
            }.also { log.i { "IPTV test ${clean.type} ok: channels=${it.channelCount} status=${it.account?.status}" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w { "IPTV test ${clean.type} failed: ${describeIptvFailure(e)}" }
            throw e.asIptvError()
        }
    }

    // endregion

    // region Disk cache

    @Serializable
    private class CachedContent(
        val version: Int,
        val playlist: IptvPlaylist,
        val categories: List<IptvCategory>,
        val channels: List<IptvChannel>,
        val account: XtreamAccount? = null,
        val guideUrl: String? = null,
        val fetchedAt: Long,
    )

    private fun contentCacheName(playlistId: String) = "playlist_$playlistId.json.gz"

    /** The cached list, only when it was fetched with the same server, credentials and URLs. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readCachedContent(playlist: IptvPlaylist): IptvPlaylistContent? = withContext(Dispatchers.IO) {
        runCatching {
            IptvCacheStorage.openRead(contentCacheName(playlist.id))?.use { input ->
                json.decodeFromStream(CachedContent.serializer(), GZIPInputStream(input.buffered()))
            }
        }.onFailure { log.w { "Unreadable IPTV channel cache: ${it.message}" } }.getOrNull()
            ?.takeIf { it.version == CACHE_VERSION && it.playlist.withoutName() == playlist.withoutName() && it.channels.isNotEmpty() }
            ?.let { IptvPlaylistContent(playlist, it.categories, it.channels, it.account, it.guideUrl, it.fetchedAt) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeCachedContent(content: IptvPlaylistContent) = withContext(Dispatchers.IO) {
        runCatching {
            val cached = CachedContent(
                CACHE_VERSION, content.playlist, content.categories, content.channels,
                content.account, content.guideUrl, content.fetchedAt,
            )
            IptvCacheStorage.write(contentCacheName(content.playlist.id)) { out ->
                GZIPOutputStream(out.buffered()).use { json.encodeToStream(CachedContent.serializer(), cached, it) }
            }
        }.onFailure { log.w { "Failed to cache IPTV channels: ${it.message}" } }
    }

    // endregion
}

data class IptvTestResult(val account: XtreamAccount?, val channelCount: Int?)
