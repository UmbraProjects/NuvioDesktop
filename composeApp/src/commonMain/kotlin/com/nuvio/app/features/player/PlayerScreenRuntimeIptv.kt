package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.IptvChannel
import com.nuvio.app.features.iptv.IptvEpgRepository
import com.nuvio.app.features.iptv.IptvGroups
import com.nuvio.app.features.iptv.IptvRepository
import com.nuvio.app.features.iptv.IptvPlaybackSession
import com.nuvio.app.features.iptv.redactIptvCredentials
import com.nuvio.app.features.iptv.requestHeaders
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_category_all
import nuvio.composeapp.generated.resources.iptv_category_favourites
import nuvio.composeapp.generated.resources.iptv_category_other
import nuvio.composeapp.generated.resources.iptv_category_recent
import nuvio.composeapp.generated.resources.iptv_channel_number
import nuvio.composeapp.generated.resources.iptv_hud_categories
import nuvio.composeapp.generated.resources.iptv_hud_channels
import nuvio.composeapp.generated.resources.iptv_hud_channels_truncated
import nuvio.composeapp.generated.resources.iptv_hud_exit
import nuvio.composeapp.generated.resources.iptv_hud_go_live
import nuvio.composeapp.generated.resources.iptv_hud_live
import nuvio.composeapp.generated.resources.iptv_hud_next_channel
import nuvio.composeapp.generated.resources.iptv_hud_previous_channel
import nuvio.composeapp.generated.resources.iptv_hud_search_channels
import nuvio.composeapp.generated.resources.iptv_hud_search_results
import nuvio.composeapp.generated.resources.iptv_tab_next
import org.jetbrains.compose.resources.stringResource

private val IptvPlaybackLog = Logger.withTag("IptvPlayback")

/** Reopens allowed inside [IPTV_RECONNECT_WINDOW_MS] before a dropping stream is given up on. */
private const val IPTV_MAX_RECONNECTS = 5
private const val IPTV_RECONNECT_WINDOW_MS = 2 * 60_000L

/**
 * A live stream has no end: mpv reporting one means the provider closed the connection (a server
 * restart, a dropped TCP session, an HLS playlist that stopped advancing). Reopen the same channel
 * after a short pause instead of leaving the player on a frozen last frame, up to
 * [IPTV_MAX_RECONNECTS] times in [IPTV_RECONNECT_WINDOW_MS].
 */
@Composable
internal fun PlayerScreenRuntime.BindIptvLiveEffects() {
    if (!isIptvPlayback) return

    LaunchedEffect(playbackSnapshot.isEnded, playbackAttemptId) {
        if (!playbackSnapshot.isEnded) return@LaunchedEffect
        if (!takeIptvReconnectSlot("live stream ended")) return@LaunchedEffect
        delay(1_500L)
        reopenIptvSource(activeSourceUrl)
    }

    BindIptvStallReconnect()
    BindIptvBufferTelemetry()

    DisposableEffect(Unit) {
        onDispose { IptvPlaybackSession.clear() }
    }
}

/**
 * Counts a reconnect against [IPTV_MAX_RECONNECTS] in [IPTV_RECONNECT_WINDOW_MS] and logs it; false,
 * with nothing counted, when the allowance is spent and the channel should be left as it is.
 */
private fun PlayerScreenRuntime.takeIptvReconnectSlot(reason: String): Boolean {
    val now = System.currentTimeMillis()
    while (iptvReconnectTimesMs.isNotEmpty() && now - iptvReconnectTimesMs.first() > IPTV_RECONNECT_WINDOW_MS) {
        iptvReconnectTimesMs.removeFirst()
    }
    if (iptvReconnectTimesMs.size >= IPTV_MAX_RECONNECTS) {
        IptvPlaybackLog.w { "$reason; giving up after ${iptvReconnectTimesMs.size} reconnects" }
        return false
    }
    iptvReconnectTimesMs.addLast(now)
    IptvPlaybackLog.i { "$reason; reconnecting (${iptvReconnectTimesMs.size}/$IPTV_MAX_RECONNECTS)" }
    return true
}

/** How long a live stall may last with its buffer not growing before the channel is reopened. */
private const val IPTV_STALL_RECONNECT_MS = 20_000L

/** Buffer growth during a stall that counts as data arriving, and restarts [IPTV_STALL_RECONNECT_MS]. */
private const val IPTV_STALL_PROGRESS_MS = 1_000L

/**
 * Buffer that should have let a stall end: mpv resumes at `cache-pause-wait` (3 s for live TV,
 * IptvMpvOptions), so a stall holding this much has stopped for some other reason.
 */
private const val IPTV_STUCK_BUFFER_MS = 3_000L

/** How long a stall may hold [IPTV_STUCK_BUFFER_MS] without the position moving before reopening. */
private const val IPTV_STUCK_RECONNECT_MS = 8_000L

/**
 * Reopens a live channel stuck rebuffering. Such a stream raises no error and never ends, and
 * reopening is what fixed it by hand. Two shapes:
 * - Nothing arriving: the buffer stops growing (a download hung through a flaky proxy, or a
 *   stitched channel switching to stream IDs mpv is not reading). After [IPTV_STALL_RECONNECT_MS].
 *   A stall whose buffer is filling, however slowly, is left alone.
 * - Data arriving but not playing: a clip change whose timestamps jump across the MPEG-TS wrap
 *   (audio 110 s -> 95,102 s) can leave mpv paused with minutes buffered and the position frozen.
 *   The growing buffer keeps resetting the first clock, so this one watches the position instead,
 *   and fires after [IPTV_STUCK_RECONNECT_MS].
 * The start (the failover watchdog owns that) and a pause are left alone.
 */
@Composable
private fun PlayerScreenRuntime.BindIptvStallReconnect() {
    LaunchedEffect(playbackAttemptId) {
        var played = false
        var stallStartMs = 0L
        var stallBufferMs = 0L
        var stuckStartMs = 0L
        var stuckPositionMs = 0L
        while (true) {
            delay(1_000L)
            val snapshot = playbackSnapshot
            if (!snapshot.isLoading || !played || snapshot.isEnded || !shouldPlay) {
                if (!snapshot.isLoading && snapshot.isPlaying) played = true
                stallStartMs = 0L
                stuckStartMs = 0L
                continue
            }
            val now = System.currentTimeMillis()
            val aheadMs = snapshot.bufferedAheadMs()
            val reason = when {
                aheadMs < IPTV_STUCK_BUFFER_MS -> {
                    stuckStartMs = 0L
                    null
                }
                stuckStartMs == 0L || kotlin.math.abs(snapshot.positionMs - stuckPositionMs) > 500L -> {
                    stuckStartMs = now
                    stuckPositionMs = snapshot.positionMs
                    null
                }
                now - stuckStartMs >= IPTV_STUCK_RECONNECT_MS ->
                    "live stream stuck ${(now - stuckStartMs) / 1000}s with ${aheadMs}ms buffered"
                else -> null
            } ?: run {
                if (stallStartMs == 0L || aheadMs >= stallBufferMs + IPTV_STALL_PROGRESS_MS) {
                    stallStartMs = now
                    stallBufferMs = aheadMs
                    null
                } else if (now - stallStartMs >= IPTV_STALL_RECONNECT_MS) {
                    "live stream stalled ${(now - stallStartMs) / 1000}s with ${aheadMs}ms buffered"
                } else {
                    null
                }
            }
            if (reason != null) {
                if (takeIptvReconnectSlot(reason)) reopenIptvSource(activeSourceUrl)
                return@LaunchedEffect
            }
        }
    }
}

/** How often [BindIptvBufferTelemetry] reports the buffer. */
private const val IPTV_BUFFER_SAMPLE_MS = 30_000L

/**
 * Logs how the live buffer moves, to tell a provider delivering slower than real time from playback
 * consuming faster than it. Each stall is logged with the buffer it hit, and every
 * [IPTV_BUFFER_SAMPLE_MS] a summary: buffer ahead and its change, and the playback rate (stream
 * seconds per wall second; skipped across a seek, a pause or a speed change, where it means nothing).
 * A falling buffer at a rate of ~1.000 is the provider; a rate above 1 is us.
 */
@Composable
private fun PlayerScreenRuntime.BindIptvBufferTelemetry() {
    LaunchedEffect(playbackAttemptId) {
        var stalls = 0
        var played = false
        launch {
            snapshotFlow { playbackSnapshot.isLoading to playbackSnapshot.isEnded }.collect { (loading, ended) ->
                val snapshot = playbackSnapshot
                if (!loading) {
                    played = true
                } else if (played && !ended) {
                    stalls++
                    IptvPlaybackLog.i {
                        "live stall at ${snapshot.positionMs / 1000}s bufferAhead=${snapshot.bufferedAheadMs()}ms"
                    }
                }
            }
        }
        var lastWallMs = System.currentTimeMillis()
        var last = playbackSnapshot
        var lastStalls = 0
        while (true) {
            delay(IPTV_BUFFER_SAMPLE_MS)
            val nowMs = System.currentTimeMillis()
            val now = playbackSnapshot
            val wallMs = nowMs - lastWallMs
            val streamMs = now.positionMs - last.positionMs
            val steady = played && now.isPlaying && last.isPlaying && stalls == lastStalls &&
                now.playbackSpeed == 1f && last.playbackSpeed == 1f &&
                kotlin.math.abs(streamMs - wallMs) < 5_000L
            val rate = if (steady && wallMs > 0) "%.4f".format(streamMs.toDouble() / wallMs) else "n/a"
            val aheadMs = now.bufferedAheadMs()
            if (played) {
                IptvPlaybackLog.i {
                    "live buffer ahead=${aheadMs}ms change=${aheadMs - last.bufferedAheadMs()}ms/${wallMs / 1000}s " +
                        "playRate=$rate speed=${now.playbackSpeed} stalls=${stalls - lastStalls} (total $stalls)"
                }
            }
            lastWallMs = nowMs
            last = now
            lastStalls = stalls
        }
    }
}

private fun PlayerPlaybackSnapshot.bufferedAheadMs(): Long = (bufferedPositionMs - positionMs).coerceAtLeast(0L)

/**
 * An IPTV source failed to open or play. Tries the channel's next format (`ts` ⇄ `m3u8`); with
 * none left, leaves the player with the error like any other unplayable source. Addon failover,
 * credential refresh and the provider diagnostic probe all assume a Stremio stream and are skipped.
 */
internal fun PlayerScreenRuntime.handleIptvPlaybackError(message: String, playbackFailedToast: String) {
    if (tryNextIptvFormat(message)) return
    IptvPlaybackLog.w { "no format of this channel played: ${redactIptvCredentials(message)}" }
    exitAfterPlaybackFailure(message, playbackFailedToast)
}

/** Opens the channel's next format after the current one failed; false when none is left. */
internal fun PlayerScreenRuntime.tryNextIptvFormat(reason: String): Boolean {
    val failedUrl = activeSourceUrl
    val next = IptvPlaybackSession.nextCandidateAfter(failedUrl) ?: return false
    IptvPlaybackLog.i { "format ${failedFormat(failedUrl)} failed (${redactIptvCredentials(reason)}); trying ${next.format}" }
    playbackSourceFailureActive = false
    reopenIptvSource(next.url)
    return true
}

private fun failedFormat(url: String): String =
    IptvPlaybackSession.active.value?.candidates?.firstOrNull { it.url == url }?.format ?: "?"

/** Opens [url] as a fresh attempt; the same URL again is a reconnect (the engine keys on the attempt). */
private fun PlayerScreenRuntime.reopenIptvSource(url: String) {
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    errorMessage = null
    beginPlaybackAttempt()
    activeSourceUrl = url
    shouldPlay = true
}

/**
 * HUD events for live TV: the previous/next buttons beside play, channel up/down keys and typed
 * channel numbers. False for anything else, which then takes the normal path.
 */
internal fun PlayerScreenRuntime.handleIptvControlsEvent(type: String, value: Double): Boolean {
    if (!isIptvPlayback) return false
    // The HUD bridge only carries numbers, so the search text comes in the event name.
    if (type.startsWith(IPTV_PANEL_SEARCH_EVENT)) {
        iptvPanelQuery = type.removePrefix(IPTV_PANEL_SEARCH_EVENT).trim().take(80)
        return true
    }
    when (type) {
        "nextEpisode", "playNextEpisode" -> switchIptvChannel(1)
        "previousEpisode" -> switchIptvChannel(-1)
        "iptvChannel" -> switchIptvChannel(if (value < 0) -1 else 1)
        "iptvChannelNumber" -> switchIptvChannelNumber(value.toInt())
        "iptvPanelOpen" -> {
            iptvPanelOpen = value > 0
            if (!iptvPanelOpen) iptvPanelQuery = ""
        }
        "iptvPanelGroup" -> {
            val current = IptvPlaybackSession.active.value ?: return true
            val content = IptvRepository.contentState(current.playlist.id).value.content ?: return true
            IptvGroups.ids(content).getOrNull(value.toInt())?.let { iptvPanelGroup = it }
        }
        "iptvPanelPlay" -> playFromIptvPanel(value.toInt())
        else -> return false
    }
    return true
}

/** The switcher's selected category: the one picked, else the playing channel's own. */
private fun PlayerScreenRuntime.iptvPanelGroupId(current: IptvPlaybackSession.Active): String =
    iptvPanelGroup ?: current.channel.categoryId

/**
 * A click in the channel switcher: play it, and make its category what channel up/down walks. A
 * search result walks the whole playlist, since the search did not stay inside one category.
 */
private fun PlayerScreenRuntime.playFromIptvPanel(uid: Int) {
    val current = IptvPlaybackSession.active.value ?: return
    val content = IptvRepository.contentState(current.playlist.id).value.content ?: return
    val groupId = if (iptvPanelQuery.isNotBlank()) IptvGroups.ALL else iptvPanelGroupId(current)
    val group = IptvGroups.channels(content, groupId, IptvRepository.settings.value)
    val channel = group.firstOrNull { it.uid == uid } ?: content.channels.getOrNull(uid) ?: return
    if (group.isNotEmpty()) IptvPlaybackSession.setZapList(group)
    zapTo(channel)
}

/** The Sources and Episodes actions mean nothing for a channel; they step channels instead. */
internal fun PlayerScreenRuntime.handleIptvControlsAction(action: PlayerControlsAction): Boolean {
    if (!isIptvPlayback) return false
    when (action) {
        PlayerControlsAction.Sources -> switchIptvChannel(1)
        PlayerControlsAction.Episodes -> switchIptvChannel(-1)
        else -> return false
    }
    return true
}

internal fun PlayerScreenRuntime.switchIptvChannel(delta: Int) {
    val target = IptvPlaybackSession.neighbour(delta) ?: return
    zapTo(target)
}

internal fun PlayerScreenRuntime.switchIptvChannelNumber(number: Int) {
    val target = IptvPlaybackSession.byNumber(number)
    if (target == null) {
        playerController?.showTransientMessage("$number", "")
        return
    }
    zapTo(target)
}

private fun PlayerScreenRuntime.zapTo(channel: IptvChannel) {
    if (IptvPlaybackSession.active.value?.channel?.uid == channel.uid) return
    val url = IptvPlaybackSession.switchTo(channel) ?: return
    IptvPlaybackLog.i { "channel change to #${channel.num}" }
    activeSourceHeaders = sanitizePlaybackHeaders(channel.requestHeaders())
    iptvReconnectTimesMs.clear()
    playbackSourceFailureActive = false
    // A new channel starts at the live edge, whatever speed the last one was paused or rewound at.
    reopenIptvSource(url)
}

private val IptvTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun iptvTime(ms: Long): String = IptvTimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The HUD's live-mode content: the playing channel (it changes with channel up/down, unlike the
 * launch title) and what is on now and next. Null for anything that is not live TV.
 */
@Composable
internal fun PlayerScreenRuntime.rememberIptvControlState(): PlayerControlIptvState? {
    if (!isIptvPlayback) return null
    val active by IptvPlaybackSession.active.collectAsState()
    val guideVersion by IptvEpgRepository.version.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val current = active ?: return null
    // Xtream channels the XMLTV guide lacks get their own short guide; cached for ten minutes.
    LaunchedEffect(current.playlist.id, current.channel.uid) {
        IptvEpgRepository.nowAndNext(current.playlist, current.channel)
    }
    val entries = remember(current.channel, now, guideVersion) {
        IptvEpgRepository.cached(current.playlist, current.channel, now).orEmpty()
    }
    val nowEntry = entries.firstOrNull()?.takeIf { it.startMs <= now }
    val nextEntry = entries.firstOrNull { it.startMs > now }
    val name = current.channel.name.ifBlank { stringResource(Res.string.iptv_channel_number, current.channel.num) }

    // The channel switcher: categories always (a few hundred at most), channels only while open.
    val settings by IptvRepository.settings.collectAsState()
    val contentState by remember(current.playlist.id) { IptvRepository.contentState(current.playlist.id) }.collectAsState()
    val content = contentState.content
    val groupId = iptvPanelGroupId(current)
    val groupNames = mapOf(
        IptvGroups.FAVOURITES to stringResource(Res.string.iptv_category_favourites),
        IptvGroups.RECENT to stringResource(Res.string.iptv_category_recent),
        IptvGroups.ALL to stringResource(Res.string.iptv_category_all),
        com.nuvio.app.features.iptv.IptvPlaylistContent.OTHER_CATEGORY to stringResource(Res.string.iptv_category_other),
    )
    val groups = remember(content, settings.favourites, settings.recents, groupId, groupNames) {
        content?.let { c ->
            IptvGroups.ids(c).mapIndexed { index, id ->
                PlayerControlIptvGroup(
                    index = index,
                    name = groupNames[id] ?: c.categoryNames[id] ?: id,
                    count = IptvGroups.channels(c, id, settings).size,
                    selected = id == groupId,
                )
            }
        }.orEmpty()
    }
    val query = iptvPanelQuery
    val panelChannels = remember(iptvPanelOpen, content, groupId, query, current.channel.uid, now, guideVersion, settings.favourites, settings.recents) {
        if (!iptvPanelOpen || content == null) {
            emptyList<IptvChannel>() to 0
        } else if (query.isNotBlank()) {
            val matches = iptvSearch(content.channels, query)
            matches.take(IPTV_PANEL_MAX_CHANNELS) to matches.size
        } else {
            val all = IptvGroups.channels(content, groupId, settings)
            val at = all.indexOfFirst { it.uid == current.channel.uid }.coerceAtLeast(0)
            val from = (at - IPTV_PANEL_MAX_CHANNELS / 2).coerceIn(0, (all.size - IPTV_PANEL_MAX_CHANNELS).coerceAtLeast(0))
            all.subList(from, minOf(all.size, from + IPTV_PANEL_MAX_CHANNELS)) to all.size
        }
    }
    val (shownChannels, groupSize) = panelChannels
    val channelsNote = if (query.isNotBlank()) {
        stringResource(Res.string.iptv_hud_search_results, groupSize)
    } else if (groupSize > shownChannels.size) {
        stringResource(Res.string.iptv_hud_channels_truncated, shownChannels.size, groupSize)
    } else {
        ""
    }
    val channelItems = remember(shownChannels, now, guideVersion, current.channel.uid) {
        shownChannels.map { channel ->
            PlayerControlIptvChannel(
                uid = channel.uid,
                num = channel.num,
                name = channel.name.ifBlank { "#${channel.num}" },
                logo = channel.logo.orEmpty(),
                nowTitle = IptvEpgRepository.cached(current.playlist, channel, now)
                    ?.firstOrNull { it.startMs <= now }?.title.orEmpty(),
                playing = channel.uid == current.channel.uid,
            )
        }
    }
    return PlayerControlIptvState(
        channelLabel = "${current.channel.num}  $name",
        nowTitle = nowEntry?.title.orEmpty(),
        nowTimeLabel = nowEntry?.let { "${iptvTime(it.startMs)} – ${iptvTime(it.endMs)}" }.orEmpty(),
        nowProgress = nowEntry?.progress(now) ?: -1f,
        nextLabel = nextEntry?.let { stringResource(Res.string.iptv_tab_next, iptvTime(it.startMs), it.title) }.orEmpty(),
        liveLabel = stringResource(Res.string.iptv_hud_live),
        goLiveLabel = stringResource(Res.string.iptv_hud_go_live),
        previousChannelLabel = stringResource(Res.string.iptv_hud_previous_channel),
        nextChannelLabel = stringResource(Res.string.iptv_hud_next_channel),
        exitLabel = stringResource(Res.string.iptv_hud_exit),
        rewindWindowMs = settings.rewindMinutes.takeIf { it > 0 }?.let { it * 60_000L } ?: IPTV_DEFAULT_REWIND_MS,
        channelsLabel = stringResource(Res.string.iptv_hud_channels),
        groupsLabel = stringResource(Res.string.iptv_hud_categories),
        searchLabel = stringResource(Res.string.iptv_hud_search_channels),
        groups = groups,
        channels = channelItems,
        channelsNote = channelsNote,
    )
}

private const val IPTV_PANEL_SEARCH_EVENT = "iptvPanelSearch:"

/**
 * The switcher's search: channels whose name has every word of [query], or whose number is it.
 * Names that start with the query come first, then the rest in playlist order.
 */
internal fun iptvSearch(channels: List<IptvChannel>, query: String): List<IptvChannel> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val number = query.trim().toIntOrNull()
    val matches = channels.filter { channel ->
        channel.num == number || words.all { channel.name.contains(it, ignoreCase = true) }
    }
    val (leading, rest) = matches.partition { it.num == number || it.name.startsWith(words.first(), ignoreCase = true) }
    return leading + rest
}

/** The switcher shows this many channels around the playing one; the HUD payload stays small. */
private const val IPTV_PANEL_MAX_CHANNELS = 600

/**
 * The live seek bar's reach with the standard buffer: the Resilient preset keeps 128 MiB behind,
 * about two minutes of 8 Mbit/s HD. An estimate; seeking past what mpv kept just stops at it.
 */
private const val IPTV_DEFAULT_REWIND_MS = 2 * 60_000L
