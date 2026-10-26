package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * The programme guide ("now / next" and the grid).
 * - A playlist with an XMLTV guide (the account's, the M3U's `x-tvg-url`, or the user's override):
 *   downloaded whole, cut to its channels and a window around now, cached on disk, renewed by the
 *   refresh setting.
 * - Xtream channels the guide lacks: `get_short_epg`, asked channel by channel and kept 10 minutes.
 *
 * Ported from GoatTV's `EpgRepository`.
 */
object IptvEpgRepository {
    private val log = Logger.withTag("IptvEpg")
    private val json = Json { ignoreUnknownKeys = true }

    private const val SHORT_TTL_MS = 10 * 60 * 1000L
    private const val CACHE_VERSION = 1

    data class GuideState(val guide: XmltvGuide? = null, val loading: Boolean = false, val failed: Boolean = false)

    private val guides = ConcurrentHashMap<String, MutableStateFlow<GuideState>>()

    /** [IptvProviderGuides] guides, keyed `playlistId#sourceKey`, already cut to that playlist's channels. */
    private val providerGuides = ConcurrentHashMap<String, XmltvGuide>()
    private val guideLocks = ConcurrentHashMap<String, Mutex>()
    private val checkedThisLaunch: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class ShortCached(val fetchedAt: Long, val entries: List<IptvEpgEntry>)

    private val shortCache = ConcurrentHashMap<String, ShortCached>()

    private val _version = MutableStateFlow(0)

    /** Bumps whenever new guide data arrives (XMLTV or short EPG), so the UI re-reads. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun guideState(playlistId: String): StateFlow<GuideState> =
        guides.getOrPut(playlistId) { MutableStateFlow(GuideState()) }.asStateFlow()

    internal fun guideCacheName(playlistId: String) = "epg_$playlistId.json.gz"

    private fun providerKey(playlistId: String, source: IptvProviderGuides.Source) = "$playlistId#${source.key}"

    private fun providerCacheName(playlistId: String, source: IptvProviderGuides.Source) =
        "epg_${playlistId}_${source.key}.json.gz"

    /** Every guide cache file a playlist may have, for deleting with it. */
    internal fun guideCacheNames(playlistId: String): List<String> =
        listOf(guideCacheName(playlistId)) + IptvProviderGuides.sources.map { providerCacheName(playlistId, it) }

    internal fun forget(playlistId: String) {
        guides.remove(playlistId)
        checkedThisLaunch.remove(playlistId)
        val prefix = "$playlistId:"
        shortCache.keys.removeAll { it.startsWith(prefix) }
        IptvProviderGuides.sources.forEach { source ->
            providerGuides.remove(providerKey(playlistId, source))
            checkedThisLaunch.remove(providerKey(playlistId, source))
        }
    }

    internal fun clear() {
        guides.clear()
        checkedThisLaunch.clear()
        shortCache.clear()
        providerGuides.clear()
    }

    /**
     * Prepares [content]'s guides: its own XMLTV guide, and the [IptvProviderGuides] guide of every
     * free service it has channels of. Each comes from disk first, then a download when it is stale
     * by the refresh setting or [force]d.
     */
    suspend fun refresh(content: IptvPlaylistContent, force: Boolean = false) {
        refreshPlaylistGuide(content, force)
        refreshProviderGuides(content, force)
    }

    /** The playlist's own XMLTV guide; nothing for a playlist without a guide URL. */
    private suspend fun refreshPlaylistGuide(content: IptvPlaylistContent, force: Boolean) {
        val playlistId = content.playlist.id
        val url = content.guideUrl ?: return
        val state = guides.getOrPut(playlistId) { MutableStateFlow(GuideState()) }
        val lock = guideLocks.getOrPut(playlistId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            if (state.value.guide?.url != url) {
                state.value = GuideState(guide = readCachedGuide(guideCacheName(playlistId), url))
                if (state.value.guide != null) _version.value++
            }
            val due = force || IptvRefreshPolicy.isDue(
                hours = IptvRepository.settings.value.refreshHours,
                fetchedAt = state.value.guide?.fetchedAt ?: 0L,
                now = System.currentTimeMillis(),
                checkedSinceOpen = playlistId in checkedThisLaunch,
            )
            if (!due) return
            checkedThisLaunch += playlistId
            state.update { it.copy(loading = true, failed = false) }
            val fresh = try {
                IptvSources.loadGuide(url, content.channels).takeIf { it.programmes.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "IPTV guide load failed: ${describeIptvFailure(e)}" }
                null
            }
            if (fresh != null) {
                state.value = GuideState(guide = fresh)
                writeCachedGuide(guideCacheName(playlistId), fresh)
                _version.value++
                log.i { "Loaded IPTV guide: ${fresh.programmes.size} channels with programmes" }
            } else {
                state.update { it.copy(failed = true) }
            }
        } finally {
            state.update { it.copy(loading = false) }
            lock.unlock()
        }
    }

    /**
     * Each free service's guide for the playlist's channels of that service, loaded the same way as
     * the playlist's own (disk, then a download when due), from the first URL that answers.
     */
    private suspend fun refreshProviderGuides(content: IptvPlaylistContent, force: Boolean) {
        val playlistId = content.playlist.id
        for (source in IptvProviderGuides.sources) {
            val channels = IptvProviderGuides.channelsFor(source, content.channels)
            if (channels.isEmpty()) continue
            val key = providerKey(playlistId, source)
            val lock = guideLocks.getOrPut(key) { Mutex() }
            if (!lock.tryLock()) continue
            try {
                if (providerGuides[key] == null) {
                    readCachedGuide(providerCacheName(playlistId, source), url = null)?.let {
                        providerGuides[key] = it
                        _version.value++
                    }
                }
                val due = force || IptvRefreshPolicy.isDue(
                    hours = IptvRepository.settings.value.refreshHours,
                    fetchedAt = providerGuides[key]?.fetchedAt ?: 0L,
                    now = System.currentTimeMillis(),
                    checkedSinceOpen = key in checkedThisLaunch,
                )
                if (!due) continue
                checkedThisLaunch += key
                val fresh = source.urls.firstNotNullOfOrNull { url ->
                    try {
                        IptvSources.loadGuide(url, channels).takeIf { it.programmes.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.w { "${source.key} guide from $url failed: ${describeIptvFailure(e)}" }
                        null
                    }
                }
                if (fresh != null) {
                    providerGuides[key] = fresh
                    writeCachedGuide(providerCacheName(playlistId, source), fresh)
                    _version.value++
                    log.i { "Loaded ${source.key} guide: ${fresh.programmes.size} of ${channels.size} channels with programmes" }
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /** [channel]'s programmes from the guide of the free service it streams from, if any. */
    private fun providerEntries(playlist: IptvPlaylist, channel: IptvChannel): List<IptvEpgEntry> {
        for (source in IptvProviderGuides.sources) {
            val asGuideChannel = IptvProviderGuides.asGuideChannel(source, channel) ?: continue
            return providerGuides[providerKey(playlist.id, source)]?.entriesFor(asGuideChannel).orEmpty()
        }
        return emptyList()
    }

    /** What is already known about [channel] from now on, without any request; null if nothing. */
    fun cached(playlist: IptvPlaylist, channel: IptvChannel, now: Long = System.currentTimeMillis()): List<IptvEpgEntry>? {
        guides[playlist.id]?.value?.guide?.entriesFor(channel)
            ?.filter { it.endMs > now }?.takeIf { it.isNotEmpty() }?.let { return it }
        providerEntries(playlist, channel).filter { it.endMs > now }.takeIf { it.isNotEmpty() }?.let { return it }
        val c = shortCache[cacheKey(playlist, channel)] ?: return null
        if (now - c.fetchedAt > SHORT_TTL_MS) return null
        val current = c.entries.filter { it.endMs > now }
        // With nothing on air any more, it has to be asked again.
        return current.takeIf { it.isNotEmpty() && it.first().startMs <= now }
    }

    /** Every programme of [channel] in the XMLTV window (past ones included), for the guide grid. */
    fun guideEntries(playlist: IptvPlaylist, channel: IptvChannel): List<IptvEpgEntry> =
        guides[playlist.id]?.value?.guide?.entriesFor(channel)?.takeIf { it.isNotEmpty() }
            ?: providerEntries(playlist, channel)

    /** Now and next for [channel]: the XMLTV guide, else (Xtream only) a `get_short_epg` request. */
    suspend fun nowAndNext(playlist: IptvPlaylist, channel: IptvChannel): List<IptvEpgEntry> {
        cached(playlist, channel)?.let { return it }
        if (playlist.type != IptvPlaylistType.Xtream) return emptyList()
        val streamId = channel.streamId ?: return emptyList()
        return try {
            val entries = XtreamClient.shortEpg(playlist, streamId)
            shortCache[cacheKey(playlist, channel)] = ShortCached(System.currentTimeMillis(), entries)
            _version.value++
            val now = System.currentTimeMillis()
            entries.filter { it.endMs > now }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cacheKey(playlist: IptvPlaylist, channel: IptvChannel) = "${playlist.id}:${channel.key}"

    @Serializable
    private class CachedGuide(val version: Int, val guide: XmltvGuide)

    /** The guide cached as [cacheName]; when [url] is given, only if it was downloaded from there. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readCachedGuide(cacheName: String, url: String?): XmltvGuide? = withContext(Dispatchers.IO) {
        runCatching {
            IptvCacheStorage.openRead(cacheName)?.use { input ->
                json.decodeFromStream(CachedGuide.serializer(), GZIPInputStream(input.buffered()))
            }
        }.getOrNull()?.takeIf { it.version == CACHE_VERSION && (url == null || it.guide.url == url) }?.guide
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeCachedGuide(cacheName: String, guide: XmltvGuide) = withContext(Dispatchers.IO) {
        runCatching {
            IptvCacheStorage.write(cacheName) { out ->
                GZIPOutputStream(out.buffered()).use { json.encodeToStream(CachedGuide.serializer(), CachedGuide(CACHE_VERSION, guide), it) }
            }
        }.onFailure { log.w { "Failed to cache IPTV guide: ${it.message}" } }
    }
}
