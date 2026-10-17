package com.nuvio.app.features.player

import com.nuvio.app.features.party.OwnSeek
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyPresenceState
import com.nuvio.app.features.party.behindMs
import com.nuvio.app.features.party.displayTitle
import com.nuvio.app.features.party.formatPartyCode
import com.nuvio.app.features.party.partyInviteLink
import com.nuvio.app.features.party.presenceState
import com.nuvio.app.features.party.statusRes
import com.nuvio.app.core.ui.copyPlainTextToClipboard
import com.nuvio.app.features.party.PartyAction
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyCorrection
import com.nuvio.app.features.party.PartyEvent
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyNotice
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.PartySource
import com.nuvio.app.features.party.PartyStream
import com.nuvio.app.features.party.isShareableStreamUrl
import com.nuvio.app.features.party.PartySync
import com.nuvio.app.features.party.messageRes
import com.nuvio.app.features.party.partyMediaKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal val PlayerScreenRuntime.partyKey: String
    get() = partyMediaKey(parentMetaType, parentMetaId, activeSeasonNumber, activeEpisodeNumber)

internal val PlayerScreenRuntime.isPartySynced: Boolean
    get() = PartyRepository.state.value.party?.media?.key == partyKey

internal val PlayerScreenRuntime.isPartyControlLocked: Boolean
    get() = PartyRepository.state.value.party?.let { party -> !party.canControl && party.media.key == partyKey } == true

internal fun PlayerScreenRuntime.partyBlocksControl(): Boolean {
    if (!isPartyControlLocked) return false
    scope.launch { showPartyMessage(getString(Res.string.party_host_only_note)) }
    return true
}

internal fun PlayerScreenRuntime.toggleOwnPlayback() {
    controlsVisible = true
    when {
        shouldPlay -> {
            partyBehind = true
            shouldPlay = false
            playerController?.pause()
        }
        partyBehind -> {
            shouldPlay = true
            playerController?.play()
        }
        else -> partyBlocksControl()
    }
}

internal fun PlayerScreenRuntime.ownSeekTarget(targetMs: Long): Long? {
    val playback = PartyRepository.playback.value ?: return targetMs
    val partyPositionMs = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), playbackSnapshot.durationMs)
    return when (val seek = PartySync.ownSeek(targetMs, partyPositionMs, partyBehind)) {
        is OwnSeek.Behind -> {
            partyBehind = true
            seek.positionMs
        }
        OwnSeek.CatchUp -> {
            partyBehind = false
            null
        }
        OwnSeek.Blocked -> {
            partyBlocksControl()
            null
        }
    }
}

internal fun PlayerScreenRuntime.currentPartyMedia(): PartyMedia = PartyMedia(
    type = contentType ?: parentMetaType,
    metaId = parentMetaId,
    metaType = parentMetaType,
    videoId = activeVideoId,
    season = activeSeasonNumber,
    episode = activeEpisodeNumber,
    title = title,
    episodeTitle = activeEpisodeTitle,
    description = activePauseDescription?.take(DESCRIPTION_LIMIT),
    poster = poster,
    background = background,
    logo = logo,
    source = PartySource(
        infoHash = activeTorrentInfoHash,
        fileIdx = activeTorrentFileIdx,
        filename = activeStreamFilename ?: activeTorrentFilename,
        bingeGroup = currentStreamBingeGroup,
        addonId = activeProviderAddonId,
        name = activeStreamTitle,
    ),
)

internal fun PlayerScreenRuntime.currentPartyStream(): PartyStream? {
    val stream = activeStream ?: return null
    if (!isShareableStreamUrl(activeSourceUrl) || activeSourceAudioUrl != null) return null
    return PartyStream(
        mediaKey = partyKey,
        url = activeSourceUrl,
        headers = activeSourceHeaders,
        responseHeaders = activeSourceResponseHeaders,
        name = stream.name,
        description = stream.description,
        addonName = stream.addonName,
        addonId = stream.addonId,
        addonLogo = stream.addonLogo,
        filename = activeStreamFilename ?: stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = activeStreamType,
        badges = stream.badges,
    )
}

internal fun PlayerScreenRuntime.partyBaseRate(): Float =
    PartyRepository.playback.value?.rate ?: playbackSnapshot.playbackSpeed

internal fun PlayerScreenRuntime.currentPlaybackPositionMs(): Long {
    val snapshot = playbackSnapshot
    if (!snapshot.isPlaying || snapshot.isLoading) return snapshot.positionMs
    val elapsedMs = playbackSnapshotMark?.elapsedNow()?.inWholeMilliseconds?.coerceIn(0L, 1_000L) ?: 0L
    return snapshot.positionMs + (elapsedMs * snapshot.playbackSpeed).toLong()
}

internal fun PlayerScreenRuntime.publishPartyPlayback(
    positionMs: Long = currentPlaybackPositionMs(),
    rate: Float = partyBaseRate(),
) {
    if (!isPartySynced) return
    PartyRepository.publishPlayback(paused = !shouldPlay, positionMs = positionMs, rate = rate)
}

internal fun PlayerScreenRuntime.startParty() {
    scope.launch {
        val error = PartyRepository.create(currentPartyMedia()) ?: return@launch
        NuvioToastController.show(getString(error.messageRes))
    }
}

private fun PlayerScreenRuntime.applyPartyPaused(paused: Boolean, force: Boolean) {
    if (!force && shouldPlay == !paused) return
    shouldPlay = !paused
    if (paused) playerController?.pause() else playerController?.play()
}

private fun PlayerScreenRuntime.handlePartyMediaChange(media: PartyMedia) {
    if (media.key == partyKey) return
    val season = media.season
    val episode = media.episode
    if (media.metaId == parentMetaId && isSeries && season != null && episode != null) {
        val next = nextEpisodeInfo
        if (next != null && next.hasAired && next.season == season && next.episode == episode) {
            playNextEpisode()
            return
        }
        val video = playerMetaVideos.firstOrNull { it.season == season && it.episode == episode }
        if (video != null) {
            // Upstream opens the episode's stream list. Here episode picks are autoplay-first, as
            // in the HUD's own episode picker, and Kotlin cannot raise the HUD's panels anyway.
            autoPlaySelectedEpisode(video)
            return
        }
    }
    openPartyPanel()
}

@Composable
internal fun PlayerScreenRuntime.BindPartyEffects() {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyId = partyState.party?.id
    val key = partyKey
    val sharing = partyState.party?.let { it.isHost && it.sharing } == true
    val sharedStream = if (sharing) currentPartyStream() else null

    LaunchedEffect(sharing, sharedStream) {
        if (sharing) PartyRepository.shareStream(sharedStream)
    }

    LaunchedEffect(key) {
        partyBehind = false
        val previous = partyPreviousKey
        partyPreviousKey = key
        val party = PartyRepository.state.value.party ?: return@LaunchedEffect
        if (previous != null && previous != key && party.media.key == previous) {
            PartyRepository.publishMedia(currentPartyMedia(), positionMs = 0L)
        }
    }

    LaunchedEffect(Unit) {
        PartyRepository.events.collect { event ->
            showPartyMessage(event.message().orEmpty())
            if (event is PartyEvent.MediaChanged) handlePartyMediaChange(event.media)
        }
    }

    LaunchedEffect(Unit) {
        PartyRepository.notices.collect { notice -> showPartyMessage(notice.text()) }
    }

    // Upstream sets partyAppInBackground from app visibility so a backgrounded phone reports
    // "browsing" and stops correcting. On desktop the video keeps playing when the window loses
    // focus, so it stays false here.

    LaunchedEffect(partyId) {
        if (partyId == null) {
            clearPartyNudge()
            return@LaunchedEffect
        }
        val clock = TimeSource.Monotonic
        var bufferingSince: TimeMark? = null
        var mismatchSince: TimeMark? = null
        var lastSeek: TimeMark? = null
        var lastCorrection: TimeMark? = null
        var lastSeq: Pair<Long, String?>? = null
        var lastStatus: Triple<PartyMemberStatus, String, Long?>? = null
        var wasInBackground = false
        var publishedInitial = false
        var seekLeadMs = 0L
        var measureSeekLead = false
        var wasBehind = false
        while (isActive) {
            delay(PARTY_TICK)
            val party = PartyRepository.state.value.party ?: break
            val snapshot = playbackSnapshot
            val resumedFromBackground = wasInBackground && !partyAppInBackground
            wasInBackground = partyAppInBackground
            if (resumedFromBackground) lastSeq = null
            val behindGap = PartyRepository.playback.value
                ?.takeIf { partyBehind && party.media.key == partyKey }
                ?.let { PartySync.expectedPositionMs(it, PartyRepository.nowMs(), snapshot.durationMs) - currentPlaybackPositionMs() }
            val status = when {
                errorMessage != null || partyAppInBackground -> PartyMemberStatus.Browsing
                behindGap != null -> PartyMemberStatus.Behind
                !initialLoadCompleted -> PartyMemberStatus.Loading
                snapshot.isLoading && !snapshot.isEnded -> {
                    val since = bufferingSince ?: clock.markNow().also { bufferingSince = it }
                    if (since.elapsedNow() > BUFFERING_REPORT_DELAY) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
                }
                else -> {
                    bufferingSince = null
                    PartyMemberStatus.Ready
                }
            }
            val reportedStatus = Triple(
                status,
                partyKey,
                behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
            )
            if (reportedStatus != lastStatus) {
                lastStatus = reportedStatus
                PartyRepository.setLocalStatus(status, partyKey, reportedStatus.third)
            }

            val controller = playerController
            if (party.media.key != partyKey || controller == null || errorMessage != null || partyAppInBackground) {
                clearPartyNudge()
                continue
            }
            val playback = PartyRepository.playback.value
            if (playback == null) {
                if (initialLoadCompleted && !publishedInitial && party.isHost) {
                    publishedInitial = true
                    PartyRepository.publishPlayback(
                        paused = !shouldPlay,
                        positionMs = snapshot.positionMs,
                        rate = snapshot.playbackSpeed,
                    )
                }
                continue
            }
            publishedInitial = true
            if (!initialLoadCompleted) continue
            if (partyBehind && party.canControl) partyBehind = false
            if (partyBehind) {
                clearPartyNudge(playback)
                mismatchSince = null
                val gap = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), snapshot.durationMs) - currentPlaybackPositionMs()
                if (PartySync.shouldRejoin(gap, playing = shouldPlay && !snapshot.isLoading)) {
                    partyBehind = false
                } else {
                    val shown = partyBehindMs
                    if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) partyBehindMs = gap.coerceAtLeast(0L)
                    wasBehind = true
                    continue
                }
            }
            if (wasBehind) {
                wasBehind = false
                lastSeq = null
                partyBehindMs = null
                if (!party.canControl) showPartyMessage(getString(Res.string.party_caught_up))
            }
            val expected = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), snapshot.durationMs)
            val identity = playback.seq to playback.memberId
            if (identity != lastSeq) {
                val forced = lastSeq == null
                lastSeq = identity
                applyPartyPaused(playback.paused, force = forced)
                val own = playback.memberId == party.memberId && !forced
                if ((!own || playback.paused) && abs(snapshot.positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                    controller.seekTo(if (playback.paused) expected else expected + seekLeadMs)
                    measureSeekLead = !playback.paused
                }
                lastSeek = clock.markNow()
                if (partyRateNudge == null && abs(snapshot.playbackSpeed - playback.rate) > 0.01f) {
                    controller.setPlaybackSpeed(playback.rate)
                }
                mismatchSince = null
                continue
            }

            if (snapshot.isLoading || isScrubbingTimeline || snapshot.isEnded) {
                mismatchSince = null
                continue
            }

            if (snapshot.isPlaying != shouldPlay) {
                val since = mismatchSince ?: clock.markNow().also { mismatchSince = it }
                if (since.elapsedNow() > OUTSIDE_TOGGLE_DELAY) {
                    mismatchSince = null
                    when {
                        party.canControl -> {
                            shouldPlay = snapshot.isPlaying
                            PartyRepository.publishPlayback(
                                paused = !snapshot.isPlaying,
                                positionMs = snapshot.positionMs,
                                rate = playback.rate,
                            )
                        }
                        snapshot.isPlaying -> applyPartyPaused(playback.paused, force = true)
                        else -> {
                            partyBehind = true
                            shouldPlay = false
                        }
                    }
                }
                continue
            }
            mismatchSince = null

            val settled = lastSeek?.let { it.elapsedNow() > SEEK_SETTLE } ?: true
            val due = lastCorrection?.let { it.elapsedNow() >= CORRECTION_INTERVAL } ?: true
            if (!settled || !due) continue
            lastCorrection = clock.markNow()
            val drift = snapshot.positionMs - expected
            if (measureSeekLead && !playback.paused) {
                measureSeekLead = false
                if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
            }
            when (val correction = PartySync.correction(snapshot.positionMs, expected, playback, partyRateNudge != null)) {
                is PartyCorrection.Seek -> {
                    partyLog.i { "Seeking $drift ms back into sync with ${seekLeadMs} ms lead" }
                    clearPartyNudge(playback)
                    val target = if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs
                    controller.seekTo(if (snapshot.durationMs > 0L) target.coerceAtMost(snapshot.durationMs) else target)
                    lastSeek = clock.markNow()
                    measureSeekLead = !playback.paused
                }
                is PartyCorrection.Nudge -> {
                    if (partyRateNudge != correction.rate) {
                        partyLog.i { "Nudging speed to ${correction.rate} for ${snapshot.positionMs - expected} ms drift" }
                        partyRateNudge = correction.rate
                        controller.setPlaybackSpeed(correction.rate)
                    }
                }
                PartyCorrection.InSync -> clearPartyNudge(playback)
            }
        }
        clearPartyNudge()
    }

    DisposableEffect(Unit) {
        onDispose {
            val party = PartyRepository.state.value.party ?: return@onDispose
            val playback = PartyRepository.playback.value
            val othersWatching = party.presence.values.any {
                it.memberId != party.memberId && it.mediaKey == party.media.key && it.status != PartyMemberStatus.Browsing
            }
            if (party.media.key == partyKey && playback != null && !playback.paused && !othersWatching) {
                PartyRepository.publishPlayback(paused = true, positionMs = playbackSnapshot.positionMs, rate = playback.rate)
            }
            PartyRepository.setLocalStatus(PartyMemberStatus.Browsing, null)
        }
    }
}

private fun PlayerScreenRuntime.clearPartyNudge(playback: PartyPlayback? = PartyRepository.playback.value) {
    if (partyRateNudge == null) return
    partyRateNudge = null
    playerController?.setPlaybackSpeed(playback?.rate ?: 1f)
}

/**
 * The party pill and panel as HUD data. Upstream draws both in Compose over the video; on desktop
 * mpv and its HTML controls are native windows that Compose cannot draw over, so the HUD
 * (player-ui/controls.js) renders them from this and reports clicks as `party*` events
 * ([handlePartyControlsEvent]).
 */
@Composable
internal fun PlayerScreenRuntime.rememberPartyControlsState(): PlayerControlPartyState {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val playback by PartyRepository.playback.collectAsStateWithLifecycle()
    val available by PartyRepository.available.collectAsStateWithLifecycle()
    val party = partyState.party
    val labels = rememberPartyHudLabels(party)
    val statusLabels = rememberPartyStatusLabels()
    val behindMs = if (partyBehind) partyBehindMs ?: 0L else null
    val behindTitle = behindMs?.let { stringResource(Res.string.party_behind_title, formatPlaybackTime(it)) }.orEmpty()
    val elsewhereTitle = party?.takeIf { it.media.key != partyKey }
        ?.let { stringResource(Res.string.party_now_watching, it.media.displayTitle()) }
        .orEmpty()
    val waitingNames = party?.takeIf { playback?.paused == true && playback?.waiting == true }?.waitingNames().orEmpty()
    val pillMessage = when {
        party == null -> ""
        !party.connected -> stringResource(Res.string.party_reconnecting)
        waitingNames.isNotEmpty() -> stringResource(Res.string.party_waiting_for, waitingNames.joinToString())
        playback?.waiting == true -> stringResource(Res.string.party_waiting)
        partyBehind && !shouldPlay -> stringResource(Res.string.party_paused_for_you)
        partyBehind -> partyBehindMs?.let { stringResource(Res.string.party_behind, formatPlaybackTime(it)) }.orEmpty()
        else -> ""
    }
    val startNote = when {
        !available -> stringResource(Res.string.party_unavailable)
        !PartyRepository.canUse -> stringResource(Res.string.party_sign_in_required)
        else -> ""
    }
    val canShareStream = currentPartyStream() != null
    val members = remember(party, statusLabels, behindMs) {
        party?.members.orEmpty().mapIndexed { index, member ->
            val isSelf = member.id == party?.memberId
            val memberBehindMs = if (isSelf) behindMs else party?.behindMs(member)
            PlayerControlPartyMember(
                index = index,
                name = member.displayName,
                initials = member.displayName.partyInitials(),
                color = member.avatarColor.orEmpty(),
                avatarUrl = member.avatarUrl.orEmpty(),
                status = memberBehindMs?.let { statusLabels.behind(formatPlaybackTime(it)) }
                    ?: party?.let { statusLabels.status(it.statusRes(member)) }.orEmpty(),
                presence = when (party?.presenceState(member)) {
                    PartyPresenceState.Watching -> "watching"
                    PartyPresenceState.Busy -> "busy"
                    PartyPresenceState.Away -> "away"
                    else -> "offline"
                },
                isHost = member.id == party?.hostMemberId,
                isSelf = isSelf,
                canRemove = party?.isHost == true && !isSelf,
            )
        }
    }
    return PlayerControlPartyState(
        available = available,
        active = party != null,
        openPanelToken = partyPanelOpenToken,
        busy = partyState.busy,
        connected = party?.connected ?: true,
        isHost = party?.isHost == true,
        canControl = party?.canControl ?: true,
        hostOnly = party?.hostOnly == true,
        sharing = party?.sharing == true,
        canShareStream = canShareStream,
        sameMedia = party == null || party.media.key == partyKey,
        code = party?.code?.let(::formatPartyCode).orEmpty(),
        pillMessage = pillMessage,
        behindTitle = behindTitle,
        elsewhereTitle = elsewhereTitle,
        startNote = startNote,
        members = members,
        labels = labels,
    )
}

@Composable
private fun rememberPartyHudLabels(party: ActiveParty?): Map<String, String> {
    val seats = party?.let { stringResource(Res.string.party_seats, it.members.size, it.maxMembers) }.orEmpty()
    val removeTitle = stringResource(Res.string.party_remove_title, PARTY_NAME_SLOT)
    val title = stringResource(Res.string.party_title)
    val close = stringResource(Res.string.action_close)
    val startTitle = stringResource(Res.string.party_start_title)
    val startDescription = stringResource(Res.string.party_start_description)
    val startAction = stringResource(Res.string.party_start_action)
    val experimental = stringResource(Res.string.party_experimental)
    val codeLabel = stringResource(Res.string.party_code_label)
    val copyInvite = stringResource(Res.string.party_copy_invite)
    val host = stringResource(Res.string.party_host)
    val you = stringResource(Res.string.party_you)
    val hostOnlyTitle = stringResource(Res.string.party_host_only_title)
    val hostOnlyDescription = stringResource(Res.string.party_host_only_description)
    val shareTitle = stringResource(Res.string.party_share_title)
    val shareDescription = stringResource(Res.string.party_share_description)
    val shareUnavailable = stringResource(Res.string.party_share_unavailable)
    val hostOnlyNote = stringResource(Res.string.party_host_only_note)
    val watchWithParty = stringResource(Res.string.party_watch_with_party)
    val playForEveryone = stringResource(Res.string.party_play_for_everyone)
    val catchUp = stringResource(Res.string.party_catch_up)
    val leave = stringResource(Res.string.party_leave)
    val end = stringResource(Res.string.party_end)
    val endTitle = stringResource(Res.string.party_end_title)
    val endMessage = stringResource(Res.string.party_end_message)
    val removeMessage = stringResource(Res.string.party_remove_message)
    val remove = stringResource(Res.string.action_remove)
    val cancel = stringResource(Res.string.action_cancel)
    val reconnecting = stringResource(Res.string.party_reconnecting)
    return remember(seats, removeTitle, title) {
        mapOf(
            "title" to title,
            "close" to close,
            "startTitle" to startTitle,
            "startDescription" to startDescription,
            "startAction" to startAction,
            "experimental" to experimental,
            "codeLabel" to codeLabel,
            "copyInvite" to copyInvite,
            "seats" to seats,
            "host" to host,
            "you" to you,
            "hostOnlyTitle" to hostOnlyTitle,
            "hostOnlyDescription" to hostOnlyDescription,
            "shareTitle" to shareTitle,
            "shareDescription" to shareDescription,
            "shareUnavailable" to shareUnavailable,
            "hostOnlyNote" to hostOnlyNote,
            "watchWithParty" to watchWithParty,
            "playForEveryone" to playForEveryone,
            "catchUp" to catchUp,
            "leave" to leave,
            "end" to end,
            "endTitle" to endTitle,
            "endMessage" to endMessage,
            "remove" to remove,
            // "Remove {name}?": the HUD puts the member's name in the slot.
            "removeTitle" to removeTitle,
            "removeMessage" to removeMessage,
            "cancel" to cancel,
            "reconnecting" to reconnecting,
        )
    }
}

/** Member status texts, resolved once rather than per member on every position tick. */
private class PartyStatusLabels(
    private val byRes: Map<StringResource, String>,
    private val behindTemplate: String,
) {
    fun status(res: StringResource): String = byRes[res].orEmpty()
    fun behind(time: String): String = behindTemplate.replace(PARTY_NAME_SLOT, time)
}

@Composable
private fun rememberPartyStatusLabels(): PartyStatusLabels {
    val resources = listOf(
        Res.string.party_status_ready,
        Res.string.party_status_buffering,
        Res.string.party_status_loading,
        Res.string.party_status_choosing,
        Res.string.party_status_elsewhere,
        Res.string.party_status_offline,
    )
    val texts = resources.map { stringResource(it) }
    val behind = stringResource(Res.string.party_behind, PARTY_NAME_SLOT)
    return remember(texts, behind) { PartyStatusLabels(resources.zip(texts).toMap(), behind) }
}

private fun String.partyInitials(): String =
    trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** Opens the HUD's party panel. The panel is HTML, so this is a token the HUD reacts to once. */
internal fun PlayerScreenRuntime.openPartyPanel() {
    showPartyPanel = true
    partyPanelOpenToken += 1
}

/** Clicks in the HUD's party pill and panel. Returns true when [type] was a party event. */
internal fun PlayerScreenRuntime.handlePartyControlsEvent(type: String, value: Double): Boolean {
    when (type) {
        "partyPanelOpened" -> showPartyPanel = true
        "partyPanelClosed" -> showPartyPanel = false
        "partyStart" -> startParty()
        "partyCopyInvite" -> {
            val party = PartyRepository.state.value.party ?: return true
            scope.launch {
                copyPlainTextToClipboard(
                    getString(Res.string.party_invite_message, formatPartyCode(party.code), partyInviteLink(party.code)),
                )
                showPartyMessage(getString(Res.string.party_invite_copied))
            }
        }
        "partyPlayForEveryone" -> PartyRepository.publishMedia(currentPartyMedia(), positionMs = playbackSnapshot.positionMs)
        "partyWatchWithParty" -> {
            val media = PartyRepository.state.value.party?.media ?: return true
            showPartyPanel = false
            flushWatchProgress()
            args.onOpenPartyMedia(media)
        }
        "partyCatchUp" -> {
            partyBehind = false
            showPartyPanel = false
        }
        "partyHostOnly" -> PartyRepository.setHostOnly(value > 0.5)
        "partySharing" -> PartyRepository.setSharing(value > 0.5)
        "partyRemoveMember" -> {
            val member = PartyRepository.state.value.party?.members?.getOrNull(value.toInt()) ?: return true
            PartyRepository.removeMember(member.id)
        }
        "partyLeave" -> {
            showPartyPanel = false
            PartyRepository.leave()
        }
        "partyEnd" -> {
            showPartyPanel = false
            PartyRepository.end()
        }
        else -> return false
    }
    return true
}

/**
 * Party messages go to the HUD's preset pill. showGestureMessage draws a Compose pill, which sits
 * under the native player surface and is never seen during desktop playback.
 */
private suspend fun PlayerScreenRuntime.showPartyMessage(message: String) {
    if (message.isBlank()) return
    playerController?.showTransientMessage(getString(Res.string.party_title), message)
}

private suspend fun PartyNotice.text(): String = when (action) {
    PartyAction.Paused -> getString(Res.string.party_action_paused, name)
    PartyAction.Resumed -> getString(Res.string.party_action_resumed, name)
    PartyAction.Seeked -> getString(Res.string.party_action_seeked, name, formatPlaybackTime(positionMs))
    PartyAction.Speed -> getString(Res.string.party_action_speed, name, formatPlaybackSpeedLabel(rate))
}

private suspend fun PartyEvent.message(): String? = when (this) {
    is PartyEvent.MemberJoined -> getString(Res.string.party_member_joined, name)
    is PartyEvent.MemberLeft -> getString(Res.string.party_member_left, name)
    PartyEvent.Removed -> getString(Res.string.party_removed)
    PartyEvent.Ended -> getString(Res.string.party_ended)
    is PartyEvent.MediaChanged -> getString(Res.string.party_media_changed, media.title)
    is PartyEvent.ControlChanged -> getString(
        if (hostOnly) Res.string.party_control_host_only else Res.string.party_control_everyone,
    )
}

private fun ActiveParty.waitingNames(): List<String> =
    PartySync.waitingMemberIds(this, emptySet())
        .filter { it != memberId }
        .mapNotNull { id -> member(id)?.displayName }

private const val PARTY_NAME_SLOT = "{name}"

private val partyLog = Logger.withTag("Party")
private val PARTY_TICK = 250.milliseconds
private val NOTICE_DURATION = 3_000.milliseconds
private val BUFFERING_REPORT_DELAY = 1_000.milliseconds
private val OUTSIDE_TOGGLE_DELAY = 1_200.milliseconds
private val SEEK_SETTLE = 2_500.milliseconds
private val CORRECTION_INTERVAL = 500.milliseconds
private const val MAX_SEEK_LEAD_MS = 3_000L
private const val BEHIND_DISPLAY_STEP_MS = 1_000L
private const val DESCRIPTION_LIMIT = 600
