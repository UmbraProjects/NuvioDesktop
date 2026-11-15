package com.nuvio.app.features.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** fork:live-tv-views - takes one still frame from a stream (desktop: the viewer's own ffmpeg). */
internal expect object IptvStillGrabber {
    /** Whether stills can be taken at all here. */
    val available: Boolean

    /** One JPEG frame of [url], or null when it could not be had in time. Blocking. */
    fun grab(url: String, headers: Map<String, String>): ByteArray?
}

/**
 * fork:live-tv-views - the picture wall's stills. The wall says which channels it shows; one at a time, the one with
 * the oldest picture is opened just long enough for a frame, at most every [REFRESH_MS] each. Never while anything
 * plays (most providers allow one connection, and the player's comes first), and never for portals (their links
 * expire). A channel that gave nothing is left for [RETRY_MS].
 */
internal object IptvStills {
    class Still(val bytes: ByteArray, val at: Long)

    private const val REFRESH_MS = 3 * 60_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val IDLE_MS = 5_000L
    private const val GAP_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _stills = MutableStateFlow<Map<Int, Still>>(emptyMap())
    val stills: StateFlow<Map<Int, Still>> = _stills.asStateFlow()
    private val failedAt = HashMap<Int, Long>()

    @Volatile private var wanted: Pair<IptvPlaylist, List<IptvMergedChannel>>? = null
    private var job: Job? = null

    /** The wall shows [channels] of [playlist]: keep their pictures fresh until [stop]. */
    @Synchronized
    fun want(playlist: IptvPlaylist, channels: List<IptvMergedChannel>) {
        wanted = playlist to channels
        if (job?.isActive == true || !IptvStillGrabber.available) return
        job = scope.launch {
            while (true) {
                val (pl, list) = wanted ?: break
                val now = System.currentTimeMillis()
                val due = list.filter { ch ->
                    val uid = ch.primary.uid
                    (now - (_stills.value[uid]?.at ?: 0L)) >= REFRESH_MS && (now - (failedAt[uid] ?: 0L)) >= RETRY_MS
                }.minByOrNull { _stills.value[it.primary.uid]?.at ?: 0L }
                if (due == null || pl.type == IptvPlaylistType.Stalker || IptvPlaybackSession.active.value != null) {
                    delay(IDLE_MS)
                    continue
                }
                val channel = due.primary
                val settings = IptvRepository.settings.value
                val allowed = IptvRepository.contentState(pl.id).value.content?.account?.allowedFormats.orEmpty()
                val candidate = iptvStreamCandidates(pl, channel, settings.preferredFormat, allowed).firstOrNull()
                val bytes = candidate?.takeIf { it.url.startsWith("http", ignoreCase = true) }?.let { c ->
                    withContext(Dispatchers.Default) { runCatching { IptvStillGrabber.grab(c.url, IPTV_DEFAULT_HEADERS + channel.requestHeaders()) }.getOrNull() }
                }
                if (bytes != null && bytes.size > 1000) {
                    _stills.update { it + (channel.uid to Still(bytes, System.currentTimeMillis())) }
                } else {
                    failedAt[channel.uid] = System.currentTimeMillis()
                }
                delay(GAP_MS)
            }
        }
    }

    /** The wall closed: no more opening channels. What was taken stays for when it comes back. */
    @Synchronized
    fun stop() {
        wanted = null
        job?.cancel()
        job = null
    }
}
