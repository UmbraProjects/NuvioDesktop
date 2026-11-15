package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:live-tv-weed - the unattended side of [IptvWeedRules]. Checks every ten minutes whether it is quiet (02:00 to
 * 05:00, switch on, nothing playing) and then opens the night's channels one at a time. Stalker portals are left
 * alone (their links are asked for one at a time), as are catch-up recordings.
 */
object IptvWeedEngine {
    private val log = Logger.withTag("IptvWeed")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private const val TICK_MS = 10 * 60_000L
    private const val OPEN_TIMEOUT_MS = 8_000L
    private const val PAUSE_MS = 4_000L

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                runCatching { tick(System.currentTimeMillis()) }.onFailure { log.w { "weeding stopped: ${it.message}" } }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick(now: Long) {
        IptvRepository.ensureLoaded()
        val settings = IptvRepository.settings.value
        if (!settings.weed) return
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        if (!IptvWeedRules.quietHour(hour)) return
        val night = IptvDecide.nightOf(now)
        for (playlist in settings.playlists) {
            if (playlist.type == IptvPlaylistType.Stalker) continue
            val content = IptvRepository.contentState(playlist.id).value.content ?: continue
            val byKey = content.channels.associateBy { it.key }
            val prefix = IptvRepository.channelRef(playlist.id, "")
            val recents = settings.recents.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val records = settings.weeds[playlist.id].orEmpty()
            val targets = IptvWeedRules.targets(settings.lineup[playlist.id].orEmpty(), recents, records, night).mapNotNull { byKey[it] }
            for (channel in targets) {
                // Something playing (or a viewer back) ends the night's weeding; it carries on with what is left next tick.
                if (IptvPlaybackSession.active.value != null || !IptvRepository.settings.value.weed) return
                val ok = open(playlist, channel, settings.preferredFormat, content.account?.allowedFormats.orEmpty())
                IptvRepository.weedResult(playlist.id, channel.key, ok, night)
                delay(PAUSE_MS)
            }
        }
    }

    /** True when the channel's stream starts sending bytes within the timeout. Only the first bytes are read. */
    private suspend fun open(playlist: IptvPlaylist, channel: IptvChannel, preferred: String?, allowed: Set<String>): Boolean {
        val candidate = iptvStreamCandidates(playlist, channel, preferred, allowed).firstOrNull() ?: return false
        if (!candidate.url.startsWith("http", ignoreCase = true)) return true // not ours to test
        val read = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            runCatching {
                IptvHttp.get(candidate.url, IPTV_DEFAULT_HEADERS + channel.requestHeaders()) { input: InputStream ->
                    val buffer = ByteArray(4096)
                    input.read(buffer)
                }
            }.getOrNull()
        }
        return read != null && read > 0
    }
}
