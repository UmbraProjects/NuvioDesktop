@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.app.features.iptv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.livescores.LiveMatch
import com.nuvio.app.features.livescores.LiveScoreEngine
import com.nuvio.app.features.livescores.LiveScoreSettingsRepository
import com.nuvio.app.features.livescores.MatchPhase
import com.nuvio.app.features.livescores.LiveScoreTeams
import com.nuvio.app.features.livescores.LiveTeam
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.settings.settingsSkyBackground
import com.nuvio.app.features.tonight.TonightSettingsRepository
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.nuvio.app.features.remote.RemoteLiveTv
import com.nuvio.app.features.remote.RemoteLiveTvButton
import com.nuvio.app.features.remote.RemoteLiveTvDo
import com.nuvio.app.features.remote.RemoteLiveTvItem
import com.nuvio.app.features.remote.RemoteLiveTvScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * fork:live-tv-stage - Live TV as stages, one decision each, as the v2 mockups have them ("Nuvio Live TV: Less to
 * choose", Stage v2 row): a centred heading, the strip of stages under it, then two glass side panels round a centre
 * (or three equal columns), lit from behind by the highlighted programme's picture. Every row, tile and card has a
 * fixed size; nothing moves or resizes with what it holds. One highlight, moved by the arrows across the panels
 * (IptvStageFocusRules); OK acts, holding OK opens a channel's menu, Back goes to the strip.
 */

internal class IptvStageChannel(val number: Int, val channel: IptvMergedChannel, val now: IptvEpgEntry?, val next: IptvEpgEntry?)

internal class IptvStageModel(
    val total: Int,
    val mergedCount: Int,
    /** fork:live-tv-polish - merged channels the triage keeps (mergedCount counts every one, kept or not). */
    val keptMerged: Int = mergedCount,
    val lineup: List<IptvStageChannel>,
    val lineupUids: Set<Int>,
    val usualKeys: Set<String>,
    val three: IptvThree,
    val canReshuffle: Boolean,
    val board: IptvMinutesBoard,
    val upcoming: List<IptvWallProgramme>,
    val earlier: List<IptvWallProgramme>,
    val follows: List<IptvFollowResult>,
    val followSuggestions: List<IptvWallProgramme>,
    /** fork:live-tv-taste - why each suggestion is there ("You keep watching", or null for "on your channels"). */
    val suggestionWhy: List<String?>,
    /** fork:live-tv-learn - teams watched often enough that Following asks whether to follow them. */
    val learnedTeams: List<IptvLearnedTeam> = emptyList(),
    /** fork:live-tv-taste - what was watched and finished, per title. */
    val taste: Map<String, IptvTitleStat>,
    val order: List<IptvWallProgramme>,
    val orderApproved: Boolean,
    val orderLeftOut: IptvWallProgramme?,
    val orderFrom: Long,
    val bedMs: Long,
    val ask: IptvAskAnswer?,
    /** fork:live-tv-ask-ai - what would go to the AI for this question, and whether the answer shown is the AI's. */
    val askCandidates: List<IptvWallProgramme>,
    val askByAi: Boolean,
    /** Channels kept off the picks: 24/7 loops, radio, shopping, adult, religious (unless on the lineup). */
    val hiddenFiller: Int,
    /** fork:live-tv-views - every programme on the kept channels (filler out) and the taste rank, for the extra views. */
    val picks: List<IptvChannelNow> = emptyList(),
    val rank: (IptvWallProgramme) -> Int = { 0 },
    /** Shopping slots on now or within the hour, kept off the picks. */
    val hiddenShopping: Int,
    /** fork:live-tv-front-door - the pick's candidates, best first (IptvDoorRules.candidates). */
    val doorCandidates: List<IptvDoorPick> = emptyList(),
    /** fork:live-tv-front-door - the starred channels with what is on them (number: on Your 40, else 0). */
    val favourites: List<IptvStageChannel> = emptyList(),
    /** fork:live-tv-front-door - the last channel watched. */
    val carryOn: IptvStageChannel? = null,
    /** fork:live-tv-front-door - the viewer's own channels (lineup, else usuals, starred and recent). */
    val ownUids: Set<Int> = emptySet(),
    /** fork:live-tv-front-door - each followed match's id to the programme showing it, when one was found. */
    val matchProgrammes: Map<String, IptvWallProgramme> = emptyMap(),
)

/** The stage's data, from the guide as it stands. Heavy (every channel's guide): run it off the UI thread. */
internal fun buildIptvStage(
    playlist: IptvPlaylist,
    content: IptvPlaylistContent,
    merged: List<IptvMergedChannel>,
    lineup: List<IptvMergedChannel>,
    settings: IptvSettings,
    now: Long,
    askText: String,
    askSkips: Int,
    orderExclude: Set<String>,
    orderFrom: Long,
    /** fork:live-tv-ask-ai - the AI's answer to [askText], when there is one. */
    aiPick: IptvAiPick? = null,
    /** fork:live-tv-triage - the categories the triage kept; null = all. The viewer's own channels are never left out. */
    shownCategories: Set<String>? = null,
    /** fork:live-tv-learn - the Live Scores team list, to tell "Man Utd" and "Manchester United" are one team. */
    liveTeams: List<LiveTeam> = emptyList(),
    /** fork:live-tv-front-door - the followed matches (LiveScoreEngine.followed). */
    followedMatches: List<LiveMatch> = emptyList(),
): IptvStageModel {
    IptvLogos.placeholders = IptvLogos.placeholdersOf(content.channels)
    val categoryNames = content.categoryNames
    val everyChannel = merged.mapNotNull { channel ->
        val around = iptvGuideEntries(playlist, channel).filter { it.endMs > now - 5 * 60_000L && it.startMs < now + 12 * 3_600_000L }
        if (around.isEmpty()) null else IptvChannelNow(channel, around, categoryNames[channel.primary.categoryId].orEmpty())
    }
    val byRef = HashMap<String, IptvMergedChannel>()
    merged.forEach { channel -> channel.sources.forEach { byRef[IptvRepository.channelRef(playlist.id, it.key)] = channel } }
    val prefix = IptvRepository.channelRef(playlist.id, "")
    val likely = IptvUsuals.rank(settings.plays.filter { it.ref.startsWith(prefix) }, now) + settings.favourites + settings.recents
    val usuals = likely.asSequence().mapNotNull { byRef[it] }.distinctBy { it.primary.uid }.take(8).toList()
    val usualKeys = usuals.map { it.primary.key }.toHashSet()
    val lineupUids = lineup.mapTo(HashSet()) { it.primary.uid }
    val inLineup: ((IptvMergedChannel) -> Boolean)? = lineupUids.takeIf { it.isNotEmpty() }?.let { uids -> { channel: IptvMergedChannel -> channel.primary.uid in uids } }
    val ownUids = if (lineupUids.isNotEmpty()) lineupUids else (usuals + (settings.favourites + settings.recents).mapNotNull { byRef[it] }).mapTo(HashSet()) { it.primary.uid }
    val own = merged.filter { it.primary.uid in ownUids }
    // fork:live-tv-triage - every pick (Three things, Starting soon, Tonight...) comes from what the triage kept,
    // plus the viewer's own channels (lineup, usuals, starred, recent) wherever they sit.
    val keepUids = ownUids + usuals.map { it.primary.uid } + (settings.favourites + settings.recents).mapNotNull { byRef[it]?.primary?.uid }
    val channels = if (shownCategories == null) everyChannel else everyChannel.filter { on ->
        on.channel.primary.uid in keepUids || shownCategories.keeps(on.channel)
    }

    // Filler never reaches the picks unless the viewer put the channel on the lineup themselves.
    fun filler(on: IptvChannelNow) = on.channel.primary.uid !in lineupUids &&
        IptvGuideText.fillerChannel(on.channel.primary.name, on.categoryName) != null
    val hiddenFiller = channels.count(::filler)
    val hiddenShopping = channels.sumOf { on -> on.entries.count { it.startMs <= now + 60 * 60_000L && it.endMs > now && IptvGuideText.isShopping(it.title) } }
    val picks = channels.filterNot(::filler)
        .map { on -> IptvChannelNow(on.channel, on.entries.filterNot { IptvGuideText.isShopping(it.title) }, on.categoryName) }
        .filter { it.entries.isNotEmpty() }

    val past = own.flatMap { channel ->
        iptvGuideEntries(playlist, channel).filter { it.endMs <= now && it.startMs >= now - 24 * 3_600_000L && !IptvGuideText.isShopping(it.title) }
            .map { IptvWallProgramme(channel, it) }
    }.filter { IptvCatchup.available(playlist, it.channel, it.entry, now) }
    val night = IptvDecide.nightOf(now)
    val bedMs = IptvRunningOrder.bedtimeMs(now, TonightSettingsRepository.state.value.bedMinuteOfDay)
    // fork:live-tv-taste - followed, finished before, new rather than a repeat, with a picture and words.
    val taste = settings.titles
    // fork:live-tv-polish - the home country lifts its own channels and sinks other countries' news.
    val regionByCategory = IptvRegions.categoryRegions(content)
    val home = IptvRegions.effectiveHome(settings.homeRegion, settings.triage[playlist.id]?.regions)
    val rank = { p: IptvWallProgramme ->
        val category = categoryNames[p.channel.primary.categoryId].orEmpty()
        val newsy = p.entry.kind == IptvProgrammeKind.NEWS || category.contains("news", ignoreCase = true) ||
            p.channel.primary.name.contains("news", ignoreCase = true)
        IptvTasteRules.score(p.entry, IptvTasteRules.statOf(taste, p.entry.title), IptvDecide.isFollowed(settings.follows, p.entry.title)) +
            IptvRegions.homeBonus(regionByCategory[p.channel.primary.categoryId], home, if (newsy) IptvProgrammeKind.NEWS else p.entry.kind)
    }
    val three = IptvDecide.three(picks, usuals, inLineup, now, IptvDecide.rotationFor(settings.shuffle, night), endsBy = bedMs, rank = rank)
    val board = IptvDecide.minutes(picks, past, now, settings.minutes, perColumn = 6)
    val upcoming = picks.flatMap { on ->
        on.entries.filter { it.startMs > now && it.startMs <= now + 60 * 60_000L }.map { IptvWallProgramme(on.channel, it) }
    }.sortedBy { it.entry.startMs }.take(400)
    val earlier = IptvDecide.earlier(past, settings.follows, now, limit = 40, rank = rank)
    val ahead = if (settings.follows.isEmpty()) emptyList() else merged.flatMap { channel ->
        iptvGuideEntries(playlist, channel).filter { it.endMs > now }.map { IptvWallProgramme(channel, it) }
    }
    val follows = IptvDecide.follows(settings.follows, ahead, inLineup, limit = 3)
    val ownNow = picks.filter { it.channel.primary.uid in ownUids }
    // fork:live-tv-taste - what the viewer keeps coming back to comes first, at its next airing; then what is on now.
    val kept = IptvTasteRules.keepWatching(taste, settings.follows, now).mapNotNull { stat ->
        picks.flatMap { on -> on.entries.filter { it.endMs > now && IptvTasteRules.key(it.title) == IptvTasteRules.key(stat.title) }.map { IptvWallProgramme(on.channel, it) } }
            .minByOrNull { it.entry.startMs }
            ?.let { it to "You keep watching · seen ${stat.seen} times" }
    }
    val onYours = ownNow.mapNotNull { on -> on.current(now)?.let { IptvWallProgramme(on.channel, it) } }
        .filter { !IptvDecide.isFollowed(settings.follows, it.entry.title) && it.entry.endMs - it.entry.startMs >= 20 * 60_000L }
        .sortedByDescending(rank)
        .map { it to null }
    val suggestionPairs = (kept + onYours).distinctBy { IptvDecide.normalise(it.first.entry.title) }.take(6)
    val suggestions = suggestionPairs.map { it.first }
    // The evening reads the whole guide of the viewer's channels, so a plan made in the morning reaches the night.
    val ownGuide = own.flatMap { channel ->
        iptvGuideEntries(playlist, channel).filter { it.endMs > orderFrom && it.startMs < bedMs + 3 * 3_600_000L && !IptvGuideText.isShopping(it.title) }
            .map { IptvWallProgramme(channel, it) }
    }
    // When the viewer's own channels run out (after a few swaps), the rest of what the triage kept fills in.
    val wideGuide = {
        picks.asSequence().map { it.channel }.filter { it.primary.uid !in ownUids }.flatMap { channel ->
            iptvGuideEntries(playlist, channel).asSequence()
                .filter { it.endMs > orderFrom && it.startMs < bedMs && !IptvGuideText.isShopping(it.title) }
                .map { IptvWallProgramme(channel, it) }
        }.toList()
    }
    val order = IptvRunningOrder.build(ownGuide, settings.follows, usualKeys, orderFrom, bedMs, orderExclude, taste, wideGuide)
    val allGuide = picks.flatMap { on -> on.entries.filter { it.startMs <= now + IptvOnNow.SOON_MS && it.endMs > now }.map { IptvWallProgramme(on.channel, it) } }
    // fork:live-tv-ask-ai - the AI's picks first, then the rules carry on with the presses of Not that left over.
    val askCandidates = if (askText.isBlank() || !settings.askAi) emptyList() else IptvAskAiRules.candidates(allGuide, now, rank)
    val aiAnswer = if (askText.isBlank()) null else aiPick?.let { IptvAskAiRules.answer(it, allGuide, askSkips, now) }
    val ask = if (askText.isBlank()) null else aiAnswer ?: IptvAsk.answer(
        askText, allGuide, settings.follows, usualKeys + lineup.map { it.primary.key }, now,
        IptvAskAiRules.rulesSkips(aiPick, allGuide, askSkips, now), taste,
    )
    val guideByUid = channels.associateBy { it.channel.primary.uid }
    // fork:live-tv-front-door - the pick, the starred channels, the last channel and where each followed match is on.
    val numberOf = lineup.withIndex().associate { (i, ch) -> ch.primary.uid to i + 1 }
    fun stageChannel(channel: IptvMergedChannel): IptvStageChannel {
        val on = guideByUid[channel.primary.uid]
        return IptvStageChannel(numberOf[channel.primary.uid] ?: 0, channel, on?.current(now), on?.next(now))
    }
    val matchPool = picks.flatMap { on ->
        on.entries.filter { it.endMs > now - 5 * 60_000L && it.startMs < now + 180 * 60_000L }.map { IptvWallProgramme(on.channel, it) }
    }
    val matchProgrammes = followedMatches.mapNotNull { match -> IptvSportMatch.programmeFor(match, matchPool, now)?.let { match.id to it } }.toMap()
    val followedLive = followedMatches.filter { it.phase == MatchPhase.Live || it.phase == MatchPhase.HalfTime }.mapNotNull { matchProgrammes[it.id] }
    val doorCandidates = IptvDoorRules.candidates(picks, settings.follows, followedLive, lineupUids, usuals, now, rank)
    // fork:live-tv-polish - two starred channels showing the same thing (the WSL on Sky Sports and Sky One) are one row.
    val favourites = settings.favourites.asSequence().mapNotNull { byRef[it] }.distinctBy { it.primary.uid }.map(::stageChannel)
        .distinctBy { c -> c.now?.let { IptvDecide.normalise(it.title) + "@" + it.startMs / 60_000L } ?: "uid:${c.channel.primary.uid}" }
        .take(10).toList()
    val carryOn = settings.recents.asSequence().mapNotNull { byRef[it] }.firstOrNull()?.let(::stageChannel)
    return IptvStageModel(
        doorCandidates = doorCandidates,
        favourites = favourites,
        carryOn = carryOn,
        ownUids = ownUids,
        matchProgrammes = matchProgrammes,
        total = if (shownCategories == null) content.channels.size else content.channels.count { shownCategories.keeps(it) },
        mergedCount = merged.size,
        keptMerged = if (shownCategories == null) merged.size else merged.count { shownCategories.keeps(it) },
        lineup = lineup.mapIndexed { index, channel ->
            val on = guideByUid[channel.primary.uid]
            IptvStageChannel(index + 1, channel, on?.current(now), on?.next(now))
        },
        lineupUids = lineupUids,
        usualKeys = usualKeys,
        picks = picks,
        rank = rank,
        three = three,
        canReshuffle = IptvDecide.canReshuffle(settings.shuffle, night),
        board = board,
        upcoming = upcoming,
        earlier = earlier,
        follows = follows,
        followSuggestions = suggestions,
        suggestionWhy = suggestionPairs.map { it.second },
        learnedTeams = IptvTeamRules.learned(settings.teams, liveTeams, settings.follows, settings.teamsDeclined, now),
        taste = taste,
        order = order,
        orderApproved = IptvRunningOrder.approved(playlist.id, order, settings.reminders),
        orderLeftOut = IptvRunningOrder.pastBedtime(ownGuide, settings.follows, usualKeys, orderFrom, bedMs),
        orderFrom = orderFrom,
        bedMs = bedMs,
        ask = ask,
        askCandidates = askCandidates,
        askByAi = aiAnswer != null,
        hiddenFiller = hiddenFiller,
        hiddenShopping = hiddenShopping,
    )
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance(Locale.UK).format(n)

internal val StageSidePanel = 296.dp
private val StageRowHeight = 64.dp
private val StageBackKeys = setOf(Key.Escape, Key.Back, Key.Backspace)
private val StageKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.NumPadEnter, Key.PageUp, Key.PageDown,
)
private const val HOLD_MS = 550L
private val BedChoices = listOf(22 * 60 + 30, 23 * 60 + 30, 30)
/** Tonight's "Starts" choices: now, 18:00, 20:00 (as minutes of the day; -1 = now). */
private val FromChoices = listOf(-1, 18 * 60, 20 * 60)
/** fork:live-tv-polish - groups whose hold OK does something of its own, so it does not open the programme menu. */
private val OWN_HOLD_GROUPS = setOf("lineup", "follows", "learned")

/** fork:live-tv-polish - the strip's one chip for the six views. */
private const val STRIP_VIEWS = "views"
private val AskExamples = listOf("a film, an hour", "the football", "news, ten minutes")

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/** Today at [minuteOfDay], or [now] when that has passed (or for -1). */
private fun startAt(now: Long, minuteOfDay: Int): Long {
    if (minuteOfDay < 0) return now
    val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        .atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return maxOf(now, at)
}

@Composable
internal fun IptvStageScreen(
    playlist: IptvPlaylist,
    content: IptvPlaylistContent,
    merged: List<IptvMergedChannel>,
    lineup: List<IptvMergedChannel>,
    settings: IptvSettings,
    alternates: Map<Int, List<IptvChannel>>,
    guideVersion: Int,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onAllChannels: () -> Unit,
    onTriage: () -> Unit,
    /** fork:live-tv-triage - the categories the triage kept; null = all. */
    keptCategories: Set<String>? = null,
) {
    val tokens = MaterialTheme.nuvio
    // fork:live-tv-front-door - Live TV opens on the front door.
    var stage by rememberSaveable(playlist.id) { mutableStateOf(IptvStage.Door.name) }
    // fork:live-tv-brakes - "Pick something for me" on the zap card opens a stage.
    val requested by IptvStageNav.requested.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { stage = it.name }
        IptvStageNav.consume()
    }
    // fork:live-tv-polish - the six extra views are always there, picked from one Views chip on the strip.
    val shownStages = IptvStage.entries.filter { it != IptvStage.Week || settings.brakes }
    val current = shownStages.firstOrNull { it.name == stage } ?: IptvStage.Door
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    var askText by rememberSaveable(playlist.id) { mutableStateOf("") }
    var askSkips by remember(askText) { mutableIntStateOf(0) }
    var orderExclude by remember { mutableStateOf(emptySet<String>()) }
    var followText by remember { mutableStateOf("") }
    var soonKinds by remember { mutableStateOf(IptvSoonKind.entries.toSet()) }
    var soonLineupOnly by remember { mutableStateOf(true) }
    var soonWindow by remember { mutableIntStateOf(30) }
    var earlierDay by remember { mutableIntStateOf(0) }
    var soonSel by remember { mutableIntStateOf(0) }
    var earlierSel by remember { mutableIntStateOf(0) }
    var orderSel by remember { mutableIntStateOf(0) }
    // In the morning or afternoon "Tonight" plans the evening; from five it starts now.
    var fromChoice by remember {
        mutableIntStateOf(if (Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).hour < 17) 1 else 0)
    }
    remember { TonightSettingsRepository.ensureLoaded() }
    val tonightSettings by TonightSettingsRepository.state.collectAsStateWithLifecycle()
    val bedMinute = tonightSettings.bedMinuteOfDay
    val orderFrom = startAt(now, FromChoices[fromChoice])

    // fork:live-tv-ask-ai
    val aiPicked by IptvAskAiEngine.pick.collectAsStateWithLifecycle()
    val aiState by IptvAskAiEngine.state.collectAsStateWithLifecycle()
    val aiPick = aiPicked?.takeIf { settings.askAi && it.question.trim().equals(askText.trim(), ignoreCase = true) }

    // fork:live-tv-learn - ESPN's team lists (kept for the session), so every spelling of a team counts as one.
    val liveTeams by produceState(emptyList<LiveTeam>()) { value = runCatching { LiveScoreTeams.all() }.getOrDefault(emptyList()) }
    // fork:live-tv-front-door - the followed matches, for the pick and the scores.
    val followedMatches by LiveScoreEngine.followed.collectAsStateWithLifecycle()
    // Only which matches and their phase rebuild the stage; a goal changes the score shown, not the picks.
    val matchShape = remember(followedMatches) { followedMatches.map { it.id to it.phase } }
    val model by produceState<IptvStageModel?>(
        null,
        playlist.id, merged, lineup, guideVersion, now / 60_000L, settings.plays.size, settings.favourites, settings.recents, settings.homeRegion,
        settings.follows, settings.shuffle, settings.minutes, settings.reminders, askText, askSkips, orderExclude, bedMinute, fromChoice,
        aiPick, settings.askAi, keptCategories, liveTeams, settings.teams, settings.teamsDeclined, matchShape,
    ) {
        value = withContext(Dispatchers.Default) {
            buildIptvStage(playlist, content, merged, lineup, settings, System.currentTimeMillis(), askText, askSkips, orderExclude, orderFrom, aiPick, keptCategories, liveTeams, followedMatches)
        }
    }

    val zapList = remember(lineup, merged) { (lineup.ifEmpty { merged }).map { it.primary } }
    fun play(channel: IptvMergedChannel) {
        IptvStageNav.leftForPlayer()
        IptvPlaybackRequests.play(playlist, channel.primary, zapList = zapList, alternates = alternates)
    }
    fun playFromStart(p: IptvWallProgramme) {
        IptvStageNav.leftForPlayer()
        if (!IptvPlaybackRequests.playCatchup(playlist, p.channel, p.entry, zapList, alternates)) play(p.channel)
    }
    fun remind(p: IptvWallProgramme, autoSwitch: Boolean, quiet: Boolean = false) {
        IptvRepository.addReminder(
            IptvReminder(
                playlistId = playlist.id,
                channelKey = p.channel.primary.key,
                channelName = p.channel.primary.name.ifBlank { "Channel ${p.channel.primary.num}" },
                title = p.entry.title,
                startMs = p.entry.startMs,
                endMs = p.entry.endMs,
                autoSwitch = autoSwitch,
            ),
        )
        if (!quiet) {
            NuvioToastController.show(
                message = if (autoSwitch) "Nuvio switches to ${p.channel.primary.name} at ${IptvStageRules.clock(p.entry.startMs)}" else "Reminder set for ${p.entry.title}",
                durationMillis = 3500L,
            )
        }
    }
    /** The one thing OK does with a programme: on now plays, not begun tunes in at its start, over plays from the start. */
    // fork:live-tv-on-demand - a programme under way can be played from the start through the add-ons instead.
    var onDemandFor by remember { mutableStateOf<IptvWallProgramme?>(null) }
    var onDemandIndex by remember { mutableIntStateOf(0) }
    val onDemandTarget by produceState<IptvOnDemandTarget?>(null, onDemandFor) {
        value = onDemandFor?.let { IptvOnDemand.peek(it.entry) ?: IptvOnDemand.find(it.entry) }
    }
    var onDemandLooked by remember(onDemandFor) { mutableStateOf(false) }
    LaunchedEffect(onDemandFor) {
        val p = onDemandFor ?: return@LaunchedEffect
        IptvOnDemand.find(p.entry)
        onDemandLooked = true
    }
    /** The choice's buttons: from the start (once found), join live, close. */
    val onDemandOptions = remember<List<Pair<String, String>>>(onDemandFor, onDemandTarget, now / 60_000L) {
        val p = onDemandFor ?: return@remember emptyList()
        buildList {
            onDemandTarget?.let { add("demand" to IptvOnDemandRules.playLabel(it)) }
            add("live" to "Join live, ${((now - p.entry.startMs) / 60_000L).coerceAtLeast(1)} min in")
            add("close" to "Close")
        }
    }
    LaunchedEffect(onDemandOptions.size) { onDemandIndex = 0 }

    fun take(p: IptvWallProgramme) {
        val t = System.currentTimeMillis()
        if (settings.onDemand && p.onNow(t) && IptvOnDemandRules.worth(p.entry, t)) {
            onDemandFor = p
            return
        }
        // fork:live-tv-brakes - a programme picked here gets the end card.
        if (p.entry.endMs > t) IptvBrakeEngine.chose(p.entry.title, p.entry.endMs)
        when {
            p.entry.endMs <= t -> playFromStart(p)
            p.entry.startMs > t + 60_000L -> remind(p, autoSwitch = true)
            else -> play(p.channel)
        }
    }
    fun follow(title: String) {
        IptvRepository.follow(title)
        NuvioToastController.show(message = "Following $title", durationMillis = 3000L)
    }
    /** fork:live-tv-learn - yes to a learned team: followed here and a favourite in Live Scores. */
    fun followTeam(learned: IptvLearnedTeam) {
        IptvRepository.follow(learned.name)
        learned.team?.let {
            LiveScoreSettingsRepository.ensureLoaded()
            LiveScoreSettingsRepository.addFavourite(it)
        }
        val also = if (learned.team != null) " · added to Live Scores" else ""
        NuvioToastController.show(message = "Following ${learned.name}$also", durationMillis = 3500L)
    }
    fun declineTeam(learned: IptvLearnedTeam) {
        IptvRepository.declineTeam(learned.key)
        NuvioToastController.show(message = "Won't ask about ${learned.name} again", durationMillis = 3000L)
    }
    /** What follows [p] on its channel, for "then ... at ...". */
    fun nextOf(p: IptvWallProgramme): IptvEpgEntry? =
        iptvGuideEntries(playlist, p.channel).firstOrNull { it.startMs >= p.entry.endMs - 60_000L && it.startMs > p.entry.startMs }

    // Your 40's channel menu (hold OK), and typed channel numbers.
    var menuChannel by remember { mutableStateOf<IptvMergedChannel?>(null) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    // fork:live-tv-views - on the Dial a typed number moves the dial to it instead of playing.
    var dialJump by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1_200)
        if (current == IptvStage.Dial) dialJump = typed.toInt() else model?.lineup?.getOrNull(typed.toInt() - 1)?.let { play(it.channel) }
        typed = ""
    }
    val storedLineup = settings.lineup[playlist.id].orEmpty()
    val menuActions = remember(menuChannel, storedLineup) {
        menuChannel?.let { IptvLineupAction.forChannel(IptvLineupRules.position(storedLineup, it), storedLineup.size) }.orEmpty()
    }
    fun runMenu(action: IptvLineupAction) {
        val whole = menuChannel ?: return
        when (action) {
            IptvLineupAction.MoveUp -> IptvRepository.setLineup(playlist.id, IptvLineupRules.move(storedLineup, whole, -1))
            IptvLineupAction.MoveDown -> IptvRepository.setLineup(playlist.id, IptvLineupRules.move(storedLineup, whole, 1))
            IptvLineupAction.Add -> IptvLineupRules.add(storedLineup, whole)?.let { IptvRepository.setLineup(playlist.id, it) }
            IptvLineupAction.Remove -> IptvRepository.setLineup(playlist.id, IptvLineupRules.remove(storedLineup, whole))
            IptvLineupAction.Never -> IptvRepository.neverAgain(playlist.id, whole)
        }
        menuChannel = null
    }

    // ---- What can be highlighted on each stage --------------------------------------------------------------
    val m = model
    val soonLists = remember(m, soonKinds, soonLineupOnly, soonWindow, now) {
        if (m == null) emptyList<IptvWallProgramme>() to emptyList() else
            IptvStageRules.departures(m.upcoming, now, soonWindow, soonKinds, soonLineupOnly, m.lineupUids)
    }
    val soonRows = soonLists.first + soonLists.second
    val earlierDays = remember(m, now) { m?.let { IptvStageRules.byDay(it.earlier, now) }.orEmpty() }
    val earlierRows = earlierDays.getOrNull(earlierDay)?.second.orEmpty()
    val weekRows = remember(m) {
        m?.follows.orEmpty().flatMap { it.matches }
            // fork:live-tv-polish - one showing on several copies of a channel (Sky Witness, its HEVC HB and LB) is one row.
            .distinctBy { IptvDecide.normalise(it.entry.title) to it.entry.startMs / 60_000L }.sortedBy { it.entry.startMs }.take(8)
    }
    val askPick = m?.ask?.programme
    // fork:live-tv-front-door - the pick holds for a quarter of an hour; "Something else" skips it.
    var doorSkipped by remember { mutableStateOf(emptySet<String>()) }
    var doorHeld by remember { mutableStateOf<IptvDoorPick?>(null) }
    var doorHeldAt by remember { mutableLongStateOf(0L) }
    val doorPick = remember(m, doorSkipped, now) { m?.let { IptvDoorRules.choose(it.doorCandidates, doorSkipped, doorHeld, doorHeldAt, now) } }
    LaunchedEffect(doorPick?.key) {
        if (doorPick != null && doorPick.key != doorHeld?.key) {
            doorHeld = doorPick
            doorHeldAt = System.currentTimeMillis()
        }
    }
    val doorNext = remember(m, doorPick?.key) { m?.let { IptvDoorRules.nextUp(it.upcoming, it.ownUids, now, doorPick?.key) }.orEmpty() }
    val scoreRows = if (settings.doorHideScores) emptyList() else followedMatches.take(3)
    // fork:live-tv-views - what the extra stages show, worked out from the model as it stands (only for the one open).
    var mapZoom by remember { mutableIntStateOf(0) }
    var mapBlock by remember { mutableIntStateOf(0) }
    var gridSel by remember { mutableIntStateOf(0) }
    val mapBlocks = remember(m, current) { if (m != null && current == IptvStage.Map) IptvViewRules.blocks(m.picks, now, m.rank) else emptyList() }
    val mapTiles = mapBlocks.getOrNull(mapBlock)?.programmes?.take(MAP_TILES).orEmpty()
    val mosaic = remember(m, current) {
        if (m != null && current == IptvStage.Mosaic) IptvViewRules.mosaic(m.picks, now, m.rank, smallCount = 18) else IptvMosaic(emptyList(), emptyList(), emptyList())
    }
    val riverFrom = remember(m) { now - 30 * 60_000L }
    val riverTo = riverFrom + 210 * 60_000L
    val riverLanes = remember(m, current) { if (m != null && current == IptvStage.River) IptvViewRules.river(m.picks, riverFrom, riverTo, m.rank) else emptyList() }
    val wallChannels = remember(m, current) { if (m != null && current == IptvStage.Wall) IptvViewRules.wall(m.lineup, m.picks, m.usualKeys) else emptyList() }
    val gridCells = remember(m, current) { if (m != null && current == IptvStage.Grid) IptvViewRules.grid(m.picks, now, m.rank, GRID_COLUMNS) else emptyList() }
    val dialCards = remember(m, current) { if (m != null && current == IptvStage.Dial) IptvViewRules.dial(m.lineup, m.picks, now) else emptyList() }
    val layout: List<List<IptvStageGroup>> = when (current) {
        IptvStage.Door -> listOf(
            listOf(IptvStageGroup("doorCarry", if (m?.carryOn != null) 1 else 0), IptvStageGroup("doorFavs", m?.favourites?.size ?: 0)),
            listOf(IptvStageGroup("doorPick", if (doorPick != null) 2 else 0, columns = 2)),
            listOf(IptvStageGroup("doorScores", scoreRows.size), IptvStageGroup("doorNext", doorNext.size), IptvStageGroup("doorScoresToggle", 1)),
        )
        IptvStage.Three -> listOf(listOf(IptvStageGroup("cards", 3, columns = 3), IptvStageGroup("deal", 1)))
        IptvStage.Soon -> listOf(
            listOf(IptvStageGroup("kinds", 1 + IptvSoonKind.entries.size), IptvStageGroup("window", 3, columns = 3)),
            listOf(IptvStageGroup("departures", soonRows.size)),
            listOf(IptvStageGroup("soonActions", if (soonRows.isEmpty()) 0 else 2)),
        )
        IptvStage.Minutes -> listOf(
            listOf(IptvStageGroup("on", m?.board?.alreadyOn?.size ?: 0)),
            listOf(IptvStageGroup("dial", IptvDecide.MINUTE_CHOICES.size, columns = IptvDecide.MINUTE_CHOICES.size), IptvStageGroup("starting", m?.board?.starting?.size ?: 0)),
            listOf(IptvStageGroup("catchup", m?.board?.catchUp?.size ?: 0)),
        )
        IptvStage.Order -> listOf(
            listOf(IptvStageGroup("builtFrom", 0)),
            listOf(IptvStageGroup("order", m?.order?.size ?: 0)),
            listOf(
                IptvStageGroup("orderActions", 3),
                IptvStageGroup("from", FromChoices.size, columns = FromChoices.size),
                IptvStageGroup("bed", BedChoices.size, columns = BedChoices.size),
            ),
        )
        IptvStage.Earlier -> listOf(
            listOf(IptvStageGroup("days", earlierDays.size)),
            listOf(IptvStageGroup("earlier", earlierRows.size)),
            listOf(IptvStageGroup("earlierActions", if (earlierRows.isEmpty()) 0 else 2)),
        )
        IptvStage.Following -> listOf(
            listOf(IptvStageGroup("followInput", 1), IptvStageGroup("learned", m?.learnedTeams?.size ?: 0), IptvStageGroup("suggest", m?.followSuggestions?.size ?: 0)),
            listOf(IptvStageGroup("follows", m?.follows?.size ?: 0)),
            listOf(IptvStageGroup("week", weekRows.size)),
        )
        IptvStage.Ask -> listOf(
            listOf(IptvStageGroup("asked", settings.asks.size)),
            listOf(
                IptvStageGroup("askInput", 1),
                IptvStageGroup("askActions", if (askPick == null) 0 else 2, columns = 2),
                IptvStageGroup("examples", AskExamples.size, columns = AskExamples.size),
            ),
            listOf(IptvStageGroup("why", 0)),
        )
        IptvStage.Lineup -> listOf(
            listOf(IptvStageGroup("lineupActions", 1)),
            listOf(IptvStageGroup("lineup", m?.lineup?.size ?: 0, columns = 5)),
            listOf(IptvStageGroup("keptOut", 0)),
        )
        IptvStage.Week -> listOf(listOf(IptvStageGroup("weekTonight", 0)), listOf(IptvStageGroup("weekBars", 0)), listOf(IptvStageGroup("weekTotals", 0)))
        IptvStage.Map -> if (mapZoom == 0) {
            listOf(listOf(IptvStageGroup("mapBlocks", mapBlocks.size, columns = 3)))
        } else {
            listOf(listOf(IptvStageGroup("mapTiles", mapTiles.size, columns = 4)))
        }
        IptvStage.Mosaic -> listOf(
            listOf(
                IptvStageGroup("mosaicBig", mosaic.big.size, columns = 2),
                IptvStageGroup("mosaicMid", mosaic.mid.size, columns = 3),
                IptvStageGroup("mosaicSmall", mosaic.small.size, columns = 6),
            ),
        )
        IptvStage.River -> listOf(riverLanes.mapIndexed { l, lane -> IptvStageGroup("lane$l", lane.items.size, columns = lane.items.size.coerceAtLeast(1)) })
        IptvStage.Wall -> listOf(listOf(IptvStageGroup("wall", wallChannels.size, columns = 4)))
        IptvStage.Grid -> listOf(
            listOf(IptvStageGroup("grid", gridCells.size, columns = GRID_COLUMNS)),
            listOf(IptvStageGroup("gridList", gridCells.getOrNull(gridSel)?.programmes?.size ?: 0)),
        )
        IptvStage.Dial -> listOf(listOf(IptvStageGroup("dial", dialCards.size)))
    }
    // fork:live-tv-polish - the strip: the stages, then one Views chip for the six views, then All channels.
    val strip = shownStages.filter { it !in IptvViewRules.VIEWS }.map { it.name } + STRIP_VIEWS + "all"
    fun stripIndexOf(name: String): Int = strip.indexOf(if (IptvStage.entries.any { it.name == name && it in IptvViewRules.VIEWS }) STRIP_VIEWS else name).coerceAtLeast(0)
    var viewsOpen by remember { mutableStateOf(false) }
    var viewsIndex by remember { mutableIntStateOf(0) }
    var onStrip by remember { mutableStateOf(false) }
    var stripIndex by remember { mutableIntStateOf(0) }
    var keyboardActive by remember { mutableStateOf(false) }
    var focus by remember(current) { mutableStateOf<IptvStageFocus?>(null) }
    val defaultColumn = if (layout.size == 3) 1 else 0
    val at = (focus ?: IptvStageFocusRules.first(layout, defaultColumn))?.let { IptvStageFocusRules.clamp(layout, it) }
    val atGroup = at?.let { layout.getOrNull(it.column)?.getOrNull(it.group)?.id }
    fun isFocused(group: String, index: Int): Boolean =
        keyboardActive && !onStrip && at != null && atGroup == group && at.item == index
    // The side panels follow the highlighted row of the centre, and stay on it when the highlight moves to them.
    LaunchedEffect(at, onStrip) {
        if (at == null || onStrip) return@LaunchedEffect
        when (atGroup) {
            "departures" -> soonSel = at.item
            "earlier" -> earlierSel = at.item
            "order" -> orderSel = at.item
        }
    }

    // What lights the stage from behind: the programme the highlight is on, else the stage's own pick.
    val ambient: IptvWallProgramme? = m?.let { md ->
        fun focusedIn(group: String) = at?.item?.takeIf { atGroup == group && !onStrip }
        when (current) {
            IptvStage.Door -> focusedIn("doorFavs")?.let { i -> md.favourites.getOrNull(i)?.let { c -> c.now?.let { IptvWallProgramme(c.channel, it) } } }
                ?: focusedIn("doorNext")?.let { doorNext.getOrNull(it) }
                ?: focusedIn("doorScores")?.let { i -> scoreRows.getOrNull(i)?.let { md.matchProgrammes[it.id] } }
                ?: doorPick?.programme
            IptvStage.Three -> listOf(md.three.safe, md.three.soon, md.three.wildcard).let { it.getOrNull(focusedIn("cards") ?: 1) ?: it.firstOrNull { p -> p != null } }
            IptvStage.Soon -> soonRows.getOrNull(soonSel)
            IptvStage.Minutes -> (focusedIn("on")?.let { md.board.alreadyOn.getOrNull(it) } ?: focusedIn("catchup")?.let { md.board.catchUp.getOrNull(it) }
                ?: md.board.starting.getOrNull(focusedIn("starting") ?: 0))?.programme
            IptvStage.Order -> md.order.getOrNull(orderSel)
            IptvStage.Earlier -> earlierRows.getOrNull(earlierSel)
            IptvStage.Following -> md.follows.getOrNull(focusedIn("follows") ?: 0)?.matches?.firstOrNull()
            IptvStage.Ask -> md.ask?.programme
            IptvStage.Lineup -> md.lineup.getOrNull(focusedIn("lineup") ?: 0)?.let { item -> item.now?.let { IptvWallProgramme(item.channel, it) } }
            IptvStage.Week -> null
            IptvStage.Map -> if (mapZoom == 0) mapBlocks.getOrNull(focusedIn("mapBlocks") ?: 0)?.programmes?.firstOrNull() else mapTiles.getOrNull(focusedIn("mapTiles") ?: 0)
            IptvStage.Mosaic -> focusedIn("mosaicBig")?.let { mosaic.big.getOrNull(it) } ?: focusedIn("mosaicMid")?.let { mosaic.mid.getOrNull(it) }
                ?: focusedIn("mosaicSmall")?.let { mosaic.small.getOrNull(it) } ?: mosaic.big.firstOrNull()
            IptvStage.River -> riverLanes.withIndex().firstNotNullOfOrNull { (l, lane) -> focusedIn("lane$l")?.let { lane.items.getOrNull(it) } }
            IptvStage.Wall -> wallChannels.getOrNull(focusedIn("wall") ?: 0)?.let { ch ->
                md.picks.firstOrNull { it.channel.primary.uid == ch.primary.uid }?.current(now)?.let { IptvWallProgramme(ch, it) }
            }
            IptvStage.Grid -> gridCells.getOrNull(gridSel)?.programmes?.let { it.getOrNull(focusedIn("gridList") ?: 0) ?: it.firstOrNull() }
            IptvStage.Dial -> dialCards.getOrNull(focusedIn("dial") ?: 0)?.let { c -> c.now?.let { IptvWallProgramme(c.channel, it) } }
        }
    }
    LaunchedEffect(dialJump) {
        val number = dialJump ?: return@LaunchedEffect
        IptvViewRules.dialIndexOf(dialCards, number)?.let {
            focus = IptvStageFocus(0, 0, it)
            keyboardActive = true
            onStrip = false
        }
        dialJump = null
    }

    // fork:live-tv-front-door - back from the player always lands on the front door.
    val playingNow by IptvPlaybackSession.active.collectAsStateWithLifecycle()
    LaunchedEffect(playingNow == null) {
        if (playingNow == null && IptvStageNav.consumeReturn()) {
            stage = IptvStage.Door.name
            focus = null
            onStrip = false
        }
    }

    var editing by remember { mutableStateOf<String?>(null) }
    val askFocus = remember { FocusRequester() }
    val followFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(48)
        }
    }
    // fork:live-tv-ask-ai - the question goes to the AI once typing has stopped (not on every key).
    val askCandidates = model?.askCandidates.orEmpty()
    LaunchedEffect(askText, editing == "ask", askCandidates.isNotEmpty(), settings.askAi) {
        if (!settings.askAi || askText.isBlank() || editing == "ask" || askCandidates.isEmpty()) return@LaunchedEffect
        delay(400)
        IptvAskAiEngine.ask(askText, askCandidates)
    }
    fun leaveEditing() {
        when (editing) {
            "ask" -> IptvRepository.rememberAsk(askText)
            "follow" -> if (followText.isNotBlank()) {
                follow(followText.trim())
                followText = ""
            }
        }
        editing = null
        runCatching { rootFocus.requestFocus() }
    }

    fun pickStage(name: String) {
        if (name == "all") {
            onAllChannels()
            return
        }
        if (name == STRIP_VIEWS) {
            viewsIndex = IptvViewRules.VIEWS.indexOfFirst { it.name == stage }.coerceAtLeast(0)
            viewsOpen = true
            return
        }
        stage = name
        focus = null
    }

    // fork:live-tv-polish - hold OK on any programme: its menu (Watch, Follow, Remind me). Groups whose hold already
    // does something of its own keep it (Your 40's channel menu, unfollow, unstar, no to a learned team).
    var progMenuFor by remember { mutableStateOf<IptvWallProgramme?>(null) }
    var progMenuIndex by remember { mutableIntStateOf(0) }
    val progMenuActions = remember(progMenuFor, settings.follows, settings.favourites, now / 60_000L) {
        progMenuFor?.let { p ->
            val starred = p.channel.sources.any { IptvRepository.channelRef(playlist.id, it.key) in settings.favourites }
            IptvProgrammeMenu.actions(p, now, IptvDecide.isFollowed(settings.follows, p.entry.title), starred)
        }.orEmpty()
    }
    fun programmeAt(group: String, index: Int): IptvWallProgramme? {
        val md = model ?: return null
        fun onNowOf(channel: IptvMergedChannel) =
            md.picks.firstOrNull { it.channel.primary.uid == channel.primary.uid }?.current(now)?.let { IptvWallProgramme(channel, it) }
        return when (group) {
            "cards" -> listOf(md.three.safe, md.three.soon, md.three.wildcard).getOrNull(index)
            "departures" -> soonRows.getOrNull(index)
            "on" -> md.board.alreadyOn.getOrNull(index)?.programme
            "starting" -> md.board.starting.getOrNull(index)?.programme
            "catchup" -> md.board.catchUp.getOrNull(index)?.programme
            "order" -> md.order.getOrNull(index)
            "earlier" -> earlierRows.getOrNull(index)
            "week" -> weekRows.getOrNull(index)
            "suggest" -> md.followSuggestions.getOrNull(index)
            "askActions" -> askPick
            "doorPick" -> doorPick?.programme
            "doorNext" -> doorNext.getOrNull(index)
            "doorCarry" -> md.carryOn?.let { c -> c.now?.let { IptvWallProgramme(c.channel, it) } }
            "doorFavs" -> md.favourites.getOrNull(index)?.let { c -> c.now?.let { IptvWallProgramme(c.channel, it) } }
            "doorScores" -> scoreRows.getOrNull(index)?.let { md.matchProgrammes[it.id] }
            "mapTiles" -> mapTiles.getOrNull(index)
            "mosaicBig" -> mosaic.big.getOrNull(index)
            "mosaicMid" -> mosaic.mid.getOrNull(index)
            "mosaicSmall" -> mosaic.small.getOrNull(index)
            "wall" -> wallChannels.getOrNull(index)?.let(::onNowOf)
            "gridList" -> gridCells.getOrNull(gridSel)?.programmes?.getOrNull(index)
            "dial" -> dialCards.getOrNull(index)?.let { c -> c.now?.let { IptvWallProgramme(c.channel, it) } }
            else -> if (group.startsWith("lane")) riverLanes.getOrNull(group.removePrefix("lane").toIntOrNull() ?: -1)?.items?.getOrNull(index) else null
        }
    }
    fun runProgAction(action: IptvProgrammeAction) {
        val p = progMenuFor ?: return
        progMenuFor = null
        when (action) {
            IptvProgrammeAction.Watch -> take(p)
            IptvProgrammeAction.Follow -> follow(IptvProgrammeMenu.followName(p.entry.title))
            IptvProgrammeAction.Unfollow -> {
                IptvDecide.followsOf(settings.follows, p.entry.title).forEach(IptvRepository::unfollow)
                NuvioToastController.show(message = "No longer following ${IptvProgrammeMenu.followName(p.entry.title)}", durationMillis = 3000L)
            }
            IptvProgrammeAction.Remind -> remind(p, autoSwitch = false)
            IptvProgrammeAction.Unstar -> {
                IptvRepository.toggleFavourite(playlist.id, p.channel)
                NuvioToastController.show(message = "${p.channel.primary.name} is no longer a favourite", durationMillis = 3000L)
            }
            IptvProgrammeAction.Close -> Unit
        }
    }

    fun openView(view: IptvStage) {
        viewsOpen = false
        onStrip = false
        stage = view.name
        focus = null
    }

    fun activate(group: String, index: Int, held: Boolean) {
        val md = model ?: return
        if (held && group !in OWN_HOLD_GROUPS) {
            programmeAt(group, index)?.let {
                progMenuFor = it
                progMenuIndex = 0
                return
            }
        }
        when (group) {
            // fork:live-tv-front-door
            "doorCarry" -> md.carryOn?.let { play(it.channel) }
            "doorFavs" -> md.favourites.getOrNull(index)?.let { item ->
                if (held) {
                    IptvRepository.toggleFavourite(playlist.id, item.channel)
                    NuvioToastController.show(message = "${item.channel.primary.name} is no longer a favourite", durationMillis = 3000L)
                } else {
                    play(item.channel)
                }
            }
            "doorPick" -> {
                val pick = doorPick
                if (pick != null) {
                    when {
                        index != 0 -> doorSkipped = doorSkipped + pick.key
                        // Starting within five minutes: the channel goes on now, so it is seen from the first minute.
                        pick.reason == IptvDoorReason.StartsSoon -> {
                            IptvBrakeEngine.chose(pick.programme.entry.title, pick.programme.entry.endMs)
                            play(pick.programme.channel)
                        }
                        else -> take(pick.programme)
                    }
                }
            }
            "doorScores" -> scoreRows.getOrNull(index)?.let { match -> md.matchProgrammes[match.id]?.let(::take) }
            "doorNext" -> doorNext.getOrNull(index)?.let(::take)
            "doorScoresToggle" -> IptvRepository.setDoorHideScores(!settings.doorHideScores)
            "cards" -> listOf(md.three.safe, md.three.soon, md.three.wildcard).getOrNull(index)?.let(::take)
            "deal" -> if (md.canReshuffle) IptvRepository.reshuffleThree(IptvDecide.nightOf(System.currentTimeMillis()))
            "kinds" -> if (index == 0) soonLineupOnly = !soonLineupOnly else IptvSoonKind.entries.getOrNull(index - 1)?.let { kind ->
                soonKinds = if (kind in soonKinds && soonKinds.size > 1) soonKinds - kind else soonKinds + kind
            }
            "window" -> soonWindow = IptvStageRules.SOON_WINDOWS[index]
            "departures" -> soonRows.getOrNull(index)?.let { remind(it, autoSwitch = true) }
            "soonActions" -> soonRows.getOrNull(soonSel)?.let { p ->
                if (index == 0) {
                    remind(p, autoSwitch = true)
                } else {
                    IptvBrakeEngine.chose(p.entry.title, p.entry.endMs)
                    play(p.channel)
                }
            }
            "on" -> md.board.alreadyOn.getOrNull(index)?.let { take(it.programme) }
            "starting" -> md.board.starting.getOrNull(index)?.let { take(it.programme) }
            "catchup" -> md.board.catchUp.getOrNull(index)?.let { playFromStart(it.programme) }
            "dial" -> IptvDecide.MINUTE_CHOICES.getOrNull(index)?.let(IptvRepository::setMinutes)
            "order" -> md.order.getOrNull(index)?.let(::take)
            "orderActions" -> when (index) {
                0 -> if (md.orderApproved) {
                    md.order.forEach { IptvRepository.removeReminder(IptvRunningOrder.reminderId(playlist.id, it)) }
                    IptvBrakeEngine.orderApproved(null)
                } else {
                    // fork:live-tv-brakes - the last slot's end gets "That's tonight".
                    IptvBrakeEngine.orderApproved(md.order.lastOrNull()?.entry?.endMs)
                    val t = System.currentTimeMillis()
                    md.order.forEach { if (it.entry.startMs > t + 60_000L) remind(it, autoSwitch = true, quiet = true) }
                    md.order.firstOrNull()?.takeIf { it.onNow(t) }?.let { play(it.channel) }
                    NuvioToastController.show(message = "Approved: Nuvio changes channel at each start", durationMillis = 3500L)
                }
                1 -> md.order.getOrNull(orderSel)?.let {
                    // Moved to the end, so a title that came back and is swapped again waits longest to return.
                    val title = IptvDecide.normalise(it.entry.title)
                    orderExclude = orderExclude - title + title
                }
                else -> {
                    md.order.forEach { IptvRepository.removeReminder(IptvRunningOrder.reminderId(playlist.id, it)) }
                    IptvBrakeEngine.orderApproved(null)
                    pickStage(IptvStage.Three.name)
                }
            }
            "from" -> fromChoice = index
            "bed" -> TonightSettingsRepository.setBedtime(BedChoices[index])
            "days" -> {
                earlierDay = index
                earlierSel = 0
            }
            "earlier" -> earlierRows.getOrNull(index)?.let(::playFromStart)
            "earlierActions" -> earlierRows.getOrNull(earlierSel)?.let { p -> if (index == 0) playFromStart(p) else follow(p.entry.title) }
            "followInput" -> {
                editing = "follow"
                runCatching { followFocus.requestFocus() }
            }
            "learned" -> md.learnedTeams.getOrNull(index)?.let { if (held) declineTeam(it) else followTeam(it) }
            "learnedNo" -> md.learnedTeams.getOrNull(index)?.let(::declineTeam)
            "suggest" -> md.followSuggestions.getOrNull(index)?.let { follow(it.entry.title) }
            "follows" -> md.follows.getOrNull(index)?.let { result ->
                val first = result.matches.firstOrNull()
                when {
                    held -> IptvRepository.unfollow(result.text)
                    first == null -> Unit
                    first.onNow(System.currentTimeMillis()) -> play(first.channel)
                    else -> remind(first, autoSwitch = false)
                }
            }
            "week" -> weekRows.getOrNull(index)?.let(::take)
            "asked" -> settings.asks.getOrNull(index)?.let { askText = it.text }
            "askInput" -> {
                editing = "ask"
                runCatching { askFocus.requestFocus() }
            }
            "askActions" -> askPick?.let { p -> if (index == 0) take(p) else askSkips++ }
            "examples" -> AskExamples.getOrNull(index)?.let {
                askText = it
                IptvRepository.rememberAsk(it)
            }
            "lineupActions" -> onTriage()
            // fork:live-tv-views
            "mapBlocks" -> if (index in mapBlocks.indices) {
                mapBlock = index
                mapZoom = 1
                focus = null
            }
            "mapTiles" -> mapTiles.getOrNull(index)?.let(::take)
            "mosaicBig" -> mosaic.big.getOrNull(index)?.let(::take)
            "mosaicMid" -> mosaic.mid.getOrNull(index)?.let(::take)
            "mosaicSmall" -> mosaic.small.getOrNull(index)?.let(::take)
            "wall" -> wallChannels.getOrNull(index)?.let(::play)
            "grid" -> if (index in gridCells.indices) gridSel = index
            "gridList" -> gridCells.getOrNull(gridSel)?.programmes?.getOrNull(index)?.let(::take)
            "dial" -> dialCards.getOrNull(index)?.let { play(it.channel) }
            in riverLanes.indices.map { "lane$it" } -> riverLanes.getOrNull(group.removePrefix("lane").toInt())?.items?.getOrNull(index)?.let(::take)
            "lineup" -> md.lineup.getOrNull(index)?.let { item ->
                if (held) {
                    menuChannel = item.channel
                    menuIndex = 0
                } else {
                    play(item.channel)
                }
            }
        }
    }

    // fork:phone-remote live-tv - the stage on the phone; a tap there is OK on that row here.
    val remoteOwner = remember { Any() }
    DisposableEffect(remoteOwner) { onDispose { RemoteLiveTv.clear(remoteOwner) } }
    val remoteAct = rememberUpdatedState { action: RemoteLiveTvDo ->
        keyboardActive = false
        when (action) {
            is RemoteLiveTvDo.Stage -> if (shownStages.any { it.name == action.name }) pickStage(action.name)
            is RemoteLiveTvDo.Item -> activate(action.group, action.index, false)
            RemoteLiveTvDo.All -> onAllChannels()
        }
    }
    val remoteScreen = remember(m, current, soonRows, earlierRows, earlierDay, now / 60_000L, shownStages, settings.minutes, doorPick?.key, doorNext, scoreRows, settings.doorHideScores) {
        m?.let { remoteLiveTvScreen(current, it, settings, now, shownStages, soonRows, earlierRows, earlierDays.getOrNull(earlierDay)?.first, doorPick, doorNext, scoreRows) }
    }
    LaunchedEffect(remoteScreen) {
        remoteScreen?.let { screen -> RemoteLiveTv.publish(remoteOwner, screen) { remoteAct.value(it) } }
    }

    fun onDemandChoose(code: String) {
        val p = onDemandFor ?: return
        onDemandFor = null
        when (code) {
            "demand" -> onDemandTarget?.let(IptvOnDemand::play)
            "live" -> {
                IptvBrakeEngine.chose(p.entry.title, p.entry.endMs)
                play(p.channel)
            }
        }
    }
    // fork:live-tv-on-demand - looked up ahead: the highlighted programme and the three cards, so the choice is ready.
    LaunchedEffect(m, ambient?.entry, settings.onDemand) {
        if (!settings.onDemand) return@LaunchedEffect
        val t = System.currentTimeMillis()
        (listOfNotNull(ambient, m?.three?.safe, m?.three?.soon, m?.three?.wildcard))
            .filter { it.onNow(t) && IptvOnDemandRules.worth(it.entry, t) }
            .distinctBy { it.entry.title }
            .forEach { IptvOnDemand.find(it.entry) }
    }

    var enterDownAt by remember { mutableLongStateOf(0L) }
    fun handleKey(key: Key): Boolean {
        if (onStrip) {
            when (key) {
                Key.DirectionLeft -> stripIndex = (stripIndex - 1).coerceAtLeast(0)
                Key.DirectionRight -> stripIndex = (stripIndex + 1).coerceAtMost(strip.lastIndex)
                Key.DirectionDown -> onStrip = false
                else -> return false
            }
            // The strip switches stages as the highlight moves over it, like tabs; "All channels" waits for OK.
            strip[stripIndex].takeIf { it != "all" && it != STRIP_VIEWS }?.let { if (it != stage) pickStage(it) }
            return true
        }
        val start = at ?: run {
            if (key == Key.DirectionUp) {
                onStrip = true
                stripIndex = stripIndexOf(stage)
            }
            return true
        }
        val move = when (key) {
            Key.DirectionUp -> IptvStageMove.Up
            Key.DirectionDown -> IptvStageMove.Down
            Key.DirectionLeft -> IptvStageMove.Left
            Key.DirectionRight -> IptvStageMove.Right
            Key.PageDown -> {
                repeat(5) { focus = IptvStageFocusRules.move(layout, focus ?: start, IptvStageMove.Down) ?: focus }
                return true
            }
            Key.PageUp -> {
                repeat(5) { IptvStageFocusRules.move(layout, focus ?: start, IptvStageMove.Up)?.let { focus = it } }
                return true
            }
            else -> return false
        }
        val next = IptvStageFocusRules.move(layout, start, move)
        if (next == null) {
            onStrip = true
            stripIndex = stripIndexOf(stage)
        } else {
            focus = next
        }
        return true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .settingsSkyBackground(tokens.colors.background, tokens.colors.accent)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (editing != null) {
                    // The box types; only the keys that leave it are taken. Raw keys, so WASD does not swallow letters.
                    if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Escape, Key.Enter, Key.NumPadEnter, Key.DirectionDown)) {
                        leaveEditing()
                        keyboardActive = true
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                val menuOpen = menuChannel != null || onDemandFor != null || viewsOpen || progMenuFor != null
                if (event.key in StageBackKeys) {
                    if (!menuOpen && onStrip) return@onPreviewKeyEvent false
                    // Back acts as the key comes up, as it does across the app.
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            onDemandFor != null -> onDemandFor = null
                            viewsOpen -> viewsOpen = false
                            progMenuFor != null -> progMenuFor = null
                            menuOpen -> menuChannel = null
                            // fork:live-tv-views - Back on the map's inside zooms out to the blocks first.
                            current == IptvStage.Map && mapZoom > 0 -> {
                                mapZoom = 0
                                focus = IptvStageFocus(0, 0, mapBlock)
                            }
                            else -> {
                                onStrip = true
                                stripIndex = stripIndexOf(stage)
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if ((current == IptvStage.Lineup || current == IptvStage.Dial) && event.type == KeyEventType.KeyDown) {
                    digitOf(event.key)?.let { digit ->
                        if (typed.length < 3 && !(typed.isEmpty() && digit == 0)) typed += digit
                        return@onPreviewKeyEvent true
                    }
                }
                val navKey = event.navigationKey()
                val enter = navKey == Key.Enter || navKey == Key.NumPadEnter
                if (onDemandFor != null) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (navKey) {
                            Key.DirectionUp -> onDemandIndex = (onDemandIndex - 1).coerceAtLeast(0)
                            Key.DirectionDown -> onDemandIndex = (onDemandIndex + 1).coerceAtMost(onDemandOptions.lastIndex)
                            Key.Enter, Key.NumPadEnter -> onDemandOptions.getOrNull(onDemandIndex)?.first?.let(::onDemandChoose)
                            else -> Unit
                        }
                    }
                    return@onPreviewKeyEvent navKey in StageKeys
                }
                // fork:live-tv-polish - the programme menu: up and down, OK does it.
                if (progMenuFor != null) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (navKey) {
                            Key.DirectionUp -> progMenuIndex = (progMenuIndex - 1).coerceAtLeast(0)
                            Key.DirectionDown -> progMenuIndex = (progMenuIndex + 1).coerceAtMost(progMenuActions.lastIndex)
                            Key.Enter, Key.NumPadEnter -> progMenuActions.getOrNull(progMenuIndex)?.let(::runProgAction)
                            else -> Unit
                        }
                    }
                    return@onPreviewKeyEvent navKey in StageKeys
                }
                // fork:live-tv-polish - the Views picker: up and down, OK opens one.
                if (viewsOpen) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (navKey) {
                            Key.DirectionUp -> viewsIndex = (viewsIndex - 1).coerceAtLeast(0)
                            Key.DirectionDown -> viewsIndex = (viewsIndex + 1).coerceAtMost(IptvViewRules.VIEWS.lastIndex)
                            Key.Enter, Key.NumPadEnter -> openView(IptvViewRules.VIEWS[viewsIndex])
                            else -> Unit
                        }
                    }
                    return@onPreviewKeyEvent navKey in StageKeys
                }
                if (menuOpen) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (navKey) {
                            Key.DirectionUp -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                            Key.DirectionDown -> menuIndex = (menuIndex + 1).coerceAtMost(menuActions.lastIndex)
                            Key.Enter, Key.NumPadEnter -> menuActions.getOrNull(menuIndex)?.let(::runMenu)
                            else -> Unit
                        }
                    }
                    return@onPreviewKeyEvent navKey in StageKeys
                }
                if (enter) {
                    when (event.type) {
                        KeyEventType.KeyDown -> if (enterDownAt == 0L) enterDownAt = System.currentTimeMillis()
                        KeyEventType.KeyUp -> {
                            val held = enterDownAt != 0L && System.currentTimeMillis() - enterDownAt >= HOLD_MS
                            enterDownAt = 0L
                            if (!keyboardActive) {
                                keyboardActive = true
                            } else if (onStrip) {
                                when (strip[stripIndex]) {
                                    "all" -> onAllChannels()
                                    STRIP_VIEWS -> pickStage(STRIP_VIEWS)
                                    else -> onStrip = false
                                }
                            } else {
                                at?.let { f -> layout.getOrNull(f.column)?.getOrNull(f.group)?.let { activate(it.id, f.item, held) } }
                            }
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent navKey in StageKeys
                if (!keyboardActive && navKey in StageKeys) {
                    // The first press only shows where the highlight is.
                    keyboardActive = true
                    return@onPreviewKeyEvent true
                }
                handleKey(navKey)
            },
    ) {
        AmbientLight(ambient)
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = topChromePadding ?: 24.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (title, subtitle) = stageHeading(current, m, settings, now)
            Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            StageStrip(
                items = strip.map { name ->
                    when (name) {
                        "all" -> "All channels" to Icons.Rounded.ViewList
                        STRIP_VIEWS -> (if (current in IptvViewRules.VIEWS) "Views · ${current.label}" else "Views") to Icons.Rounded.Dashboard
                        else -> {
                            val s = IptvStage.valueOf(name)
                            (if (s == IptvStage.Minutes) "${settings.minutes} minutes" else s.label) to stageIcon(s)
                        }
                    }
                },
                selected = stripIndexOf(stage),
                focused = if (keyboardActive && onStrip) stripIndex else -1,
                onPick = { index ->
                    keyboardActive = false
                    pickStage(strip[index])
                },
            )
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The side panels are always a pair: the same width, narrower on a narrow window.
                val side = (maxWidth * 0.23f).coerceIn(200.dp, StageSidePanel)
                if (m == null) {
                    StageMessage("Reading the guide...")
                } else {
                    // fork:live-tv-polish - a right click on an item arrives here as a hold (IptvRightClick), so it opens
                    // the same menus as holding OK.
                    val tap: Act = { group, i -> keyboardActive = false; activate(group, i, IptvRightClick.take()) }
                    when (current) {
                        IptvStage.Door -> DoorStage(
                            playlist, m, now, side, doorPick, doorNext, scoreRows, settings, ::isFocused,
                            onLongClick = { i -> activate("doorFavs", i, true) },
                            act = tap,
                        )
                        IptvStage.Three -> ThreeStage(m, now, ::isFocused, tap)
                        IptvStage.Soon -> SoonStage(m, now, side, soonLists, soonSel, soonKinds, soonLineupOnly, soonWindow, ::isFocused, ::nextOf) { group, i ->
                            if (group == "departures") soonSel = i
                            tap(group, i)
                        }
                        IptvStage.Minutes -> MinutesStage(m, now, settings.minutes, ::isFocused, tap)
                        IptvStage.Order -> OrderStage(m, now, side, orderSel, settings.follows, bedMinute, fromChoice, ::isFocused) { group, i ->
                            if (group == "order") orderSel = i
                            tap(group, i)
                        }
                        IptvStage.Earlier -> EarlierStage(now, side, earlierDays, earlierDay, earlierRows, earlierSel, ::isFocused) { group, i ->
                            if (group == "earlier") earlierSel = i
                            tap(group, i)
                        }
                        IptvStage.Following -> FollowingStage(
                            m, now, side, weekRows, followText, { followText = it }, followFocus,
                            onEditing = { editing = "follow" }, isFocused = ::isFocused,
                            onLongClick = { i -> activate("follows", i, true) },
                            act = tap,
                        )
                        IptvStage.Ask -> AskStage(
                            m, now, side, settings.asks, askText, { askText = it }, askFocus,
                            aiState = if (settings.askAi && askText.isNotBlank()) aiState else IptvAskAiState.Off,
                            onEditing = { editing = "ask" }, onSubmit = { leaveEditing() }, isFocused = ::isFocused, act = tap,
                        )
                        IptvStage.Lineup -> LineupStage(
                            m, now, side, playlist, settings, content, typed, ::isFocused, ::nextOf,
                            onLongClick = { i -> activate("lineup", i, true) },
                            act = tap,
                        )
                        IptvStage.Week -> WeekStage(settings, now, side)
                        IptvStage.Map -> MapStage(m, mapBlocks, mapZoom, mapBlock, now, side, ::isFocused, tap)
                        IptvStage.Mosaic -> MosaicStage(mosaic, now, ::isFocused, tap)
                        IptvStage.River -> RiverStage(m, riverLanes, riverFrom, riverTo, now, ::isFocused, tap)
                        IptvStage.Wall -> WallStage(playlist, m, wallChannels, now, ::isFocused, tap)
                        IptvStage.Grid -> GridStage(gridCells, GRID_COLUMNS, gridSel, now, side, ::isFocused, tap)
                        IptvStage.Dial -> DialStage(dialCards, typed, now, ::isFocused, tap)
                    }
                }
            }
        }
        // fork:live-tv-front-door - the pick's live picture steps aside while a menu is up.
        val anyMenu = onDemandFor != null || progMenuFor != null || viewsOpen || menuChannel != null
        LaunchedEffect(anyMenu) { IptvPreviewCover.set(anyMenu) }
        DisposableEffect(Unit) { onDispose { IptvPreviewCover.set(false) } }
        val demandFor = onDemandFor
        if (demandFor != null) {
            Box(Modifier.iptvScrim { onDemandFor = null }, contentAlignment = Alignment.Center) {
                IptvOnDemandChoice(
                    programme = demandFor,
                    line = IptvOnDemandRules.lateLine(demandFor.entry, now, IptvStageRules::clock),
                    note = when {
                        onDemandTarget != null -> "Found on TMDB as ${onDemandTarget?.name}. Your add-ons pick the source."
                        !onDemandLooked -> "Looking it up on TMDB…"
                        else -> "Not found on TMDB, so it can only be joined live."
                    },
                    options = onDemandOptions.map { it.second },
                    focused = onDemandIndex,
                    onChoose = { i -> onDemandOptions.getOrNull(i)?.first?.let(::onDemandChoose) },
                )
            }
        }
        // fork:live-tv-polish - the programme menu over the stage.
        val progFor = progMenuFor
        if (progFor != null) {
            Box(Modifier.iptvScrim { progMenuFor = null }, contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.width(480.dp).iptvDialog(NuvioGlass.Shape).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(progFor.entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${progFor.channel.primary.name} · ${IptvStageRules.clock(progFor.entry.startMs)} to ${IptvStageRules.clock(progFor.entry.endMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        progMenuActions.forEachIndexed { i, action ->
                            LineupMenuButton(
                                label = if (action == IptvProgrammeAction.Unstar) "Unstar ${progFor.channel.primary.name}" else IptvProgrammeMenu.label(action, progFor, now, IptvStageRules::clock),
                                focused = i == progMenuIndex,
                                danger = action == IptvProgrammeAction.Unfollow || action == IptvProgrammeAction.Unstar,
                                onClick = { runProgAction(action) },
                            )
                        }
                    }
                }
            }
        }
        // fork:live-tv-polish - the Views picker over the stage.
        if (viewsOpen) {
            Box(Modifier.iptvScrim { viewsOpen = false }, contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.width(420.dp).iptvDialog(NuvioGlass.Shape).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Views", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary)
                        Text("Other ways to see everything on", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                        IptvViewRules.VIEWS.forEachIndexed { i, view ->
                            LineupMenuButton(label = view.label, focused = i == viewsIndex, danger = false, onClick = { openView(view) })
                        }
                    }
                }
            }
        }
        val menuFor = menuChannel
        if (menuFor != null) {
            Box(Modifier.iptvScrim { menuChannel = null }, contentAlignment = Alignment.Center) {
                IptvLineupMenu(
                    channelName = menuFor.primary.name.ifBlank { "Channel ${menuFor.primary.num}" },
                    note = IptvLineupRules.position(storedLineup, menuFor).let { if (it >= 0) "Number ${it + 1} in Your 40" else "Not in Your 40" },
                    actions = menuActions,
                    focused = menuIndex,
                    onAction = ::runMenu,
                    onBack = { menuChannel = null },
                )
            }
        }
    }
}

/** fork:live-tv-on-demand - join live, or play it from the start through the add-ons. A fixed width; buttons stack. */
@Composable
private fun IptvOnDemandChoice(
    programme: IptvWallProgramme,
    line: String,
    note: String,
    options: List<String>,
    focused: Int,
    onChoose: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(480.dp).iptvDialog(NuvioGlass.Shape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(programme.entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${programme.channel.primary.name} · $line", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            options.forEachIndexed { i, label -> LineupMenuButton(label = label, focused = i == focused, danger = false, onClick = { onChoose(i) }) }
        }
    }
}

/** fork:live-tv-brakes - a stage asked for from outside (the zap card's "Pick something for me"). */
object IptvStageNav {
    /** fork:live-tv-front-door - set when a stage starts playback, so coming back opens the front door. */
    @Volatile private var returning = false

    fun leftForPlayer() {
        returning = true
    }

    fun consumeReturn(): Boolean = returning.also { returning = false }

    private val _requested = MutableStateFlow<IptvStage?>(null)
    val requested: StateFlow<IptvStage?> = _requested.asStateFlow()

    fun open(stage: IptvStage) {
        _requested.value = stage
    }

    fun consume() {
        _requested.value = null
    }
}

/** fork:live-tv-brakes - Your week: tonight on the left, seven equal bars in the middle, the week's totals on the right. */
@Composable
private fun WeekStage(settings: IptvSettings, now: Long, side: Dp) {
    val tokens = MaterialTheme.nuvio
    val tonight = IptvDecide.nightOf(now)
    val nights = IptvBrakeRules.nights(settings.tally, tonight)
    val week = IptvBrakeRules.week(settings.tally, tonight)
    val today = nights.last().second
    val most = nights.maxOf { it.second.minutes }.coerceAtLeast(1)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Tonight", null, Modifier.width(side).fillMaxHeight()) {
            StageInfoRow("Watching", "since 05:00", IptvBrakeRules.hoursText(today.minutes), icon = Icons.Rounded.Tv)
            StageInfoRow("Channel changes", "every zap counts", today.changes.toString(), icon = Icons.Rounded.ViewColumn)
            StageInfoRow("Seen to the end", "programmes you stayed with", today.finished.toString(), icon = Icons.Rounded.Check)
            StageInfoRow("Stopped when asked", "on a brake card", today.stopped.toString(), icon = Icons.Rounded.Bedtime)
        }
        StagePanel("Seven nights", "minutes watched", Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                nights.forEach { (night, n) ->
                    val isTonight = night == tonight
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (n.minutes == 0) "-" else IptvBrakeRules.hoursText(n.minutes),
                            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Every bar sits in the same box; only its fill changes.
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight((n.minutes.toFloat() / most).coerceIn(0.02f, 1f))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isTonight) tokens.colors.accent else tokens.colors.textPrimary.copy(alpha = 0.22f)),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            IptvBrakeRules.nightLabel(night, tonight),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isTonight) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isTonight) tokens.colors.textPrimary else tokens.colors.textMuted,
                            maxLines = 1,
                        )
                        Text("${n.changes} changes", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
                    }
                }
            }
        }
        StagePanel("The week", null, Modifier.width(side).fillMaxHeight()) {
            StageInfoRow("Watching", "seven nights", IptvBrakeRules.hoursText(week.minutes), icon = Icons.Rounded.Tv)
            StageInfoRow(
                "Channel changes",
                IptvBrakeRules.changesPerHour(week)?.let { "$it an hour" } ?: "under an hour watched",
                week.changes.toString(), icon = Icons.Rounded.ViewColumn,
            )
            StageInfoRow("Seen to the end", "programmes you chose", week.finished.toString(), icon = Icons.Rounded.Check)
            StageInfoRow("Stopped when asked", "on a brake card", week.stopped.toString(), icon = Icons.Rounded.Bedtime)
        }
    }
}

/** fork:phone-remote live-tv - what the phone shows of [stage]: its rows with the group and index OK would act on. */
private fun remoteLiveTvScreen(
    stage: IptvStage,
    m: IptvStageModel,
    settings: IptvSettings,
    now: Long,
    stages: List<IptvStage>,
    soonRows: List<IptvWallProgramme>,
    earlierRows: List<IptvWallProgramme>,
    earlierDay: String?,
    doorPick: IptvDoorPick? = null,
    doorNext: List<IptvWallProgramme> = emptyList(),
    doorScores: List<LiveMatch> = emptyList(),
): RemoteLiveTvScreen {
    fun clock(ms: Long) = IptvStageRules.clock(ms)
    fun progress(p: IptvWallProgramme): Int {
        if (!p.onNow(now)) return -1
        val length = (p.entry.endMs - p.entry.startMs).coerceAtLeast(1L)
        return (((now - p.entry.startMs) * 1000L) / length).toInt().coerceIn(0, 1000)
    }
    fun takeLabel(p: IptvWallProgramme) = when {
        p.entry.endMs <= now -> "Play from the start"
        p.entry.startMs > now + 60_000L -> "Tune in at ${clock(p.entry.startMs)}"
        else -> "Watch"
    }
    fun item(group: String, index: Int, slot: String, p: IptvWallProgramme, action: String? = takeLabel(p)) = RemoteLiveTvItem(
        group = group,
        index = index,
        slot = slot,
        title = p.entry.title,
        sub = IptvProgrammeLine.text(p.channel.primary.name, p.entry, now, 0, IptvStageRules::clock),
        picture = p.entry.image ?: p.channel.primary.logo,
        live = p.onNow(now),
        progress = progress(p),
        action = action,
    )
    val (heading, sub) = stageHeading(stage, m, settings, now)
    val items = ArrayList<RemoteLiveTvItem>()
    val buttons = ArrayList<RemoteLiveTvButton>()
    var empty: String? = null
    when (stage) {
        // fork:live-tv-front-door - the pick, then the favourites with something on. Scores stay on the TV.
        IptvStage.Door -> {
            // fork:live-tv-front-door - the phone's Home: the pick (its slot carries the rule, its line the reason), then
            // Carry on and the favourites, the scores and Next up, each told apart by its slot.
            val pick = doorPick ?: m.doorCandidates.firstOrNull()
            if (pick != null) {
                val p = pick.programme
                val number = m.lineup.firstOrNull { it.channel.primary.uid == p.channel.primary.uid }?.number
                val finished = IptvTasteRules.finishText(IptvTasteRules.statOf(m.taste, p.entry.title))
                items += item("doorPick", 0, IptvDoorRules.label(pick.reason), p, action = if (p.entry.startMs <= now + 60_000L) "Watch" else "Watch · starts ${clock(p.entry.startMs)}")
                    .copy(sub = "${p.channel.primary.name} · " + IptvDoorRules.why(pick, now, number, finished))
                buttons += RemoteLiveTvButton("doorPick", 1, "Something else")
            }
            fun channelItem(group: String, i: Int, slot: String, c: IptvStageChannel) = RemoteLiveTvItem(
                group = group,
                index = i,
                slot = slot,
                title = c.now?.title ?: c.channel.primary.name,
                sub = c.channel.primary.name + (c.now?.let { " · ${IptvStageRules.length(it.endMs - now)} left" }
                    ?: c.next?.let { " · ${it.title} at ${clock(it.startMs)}" } ?: ""),
                picture = c.now?.image ?: c.channel.primary.logo,
                live = c.now != null,
                progress = c.now?.let { progress(IptvWallProgramme(c.channel, it)) } ?: -1,
                action = "Watch",
            )
            m.carryOn?.let { items += channelItem("doorCarry", 0, "CARRY ON", it) }
            m.favourites.forEachIndexed { i, c -> items += channelItem("doorFavs", i, "FAVOURITE", c) }
            doorScores.forEachIndexed { i, match ->
                val on = m.matchProgrammes[match.id]
                items += RemoteLiveTvItem(
                    group = if (on != null) "doorScores" else "-",
                    index = i,
                    slot = "SCORE",
                    title = scoreLine(match),
                    sub = listOfNotNull(scoreState(match, now), on?.channel?.primary?.name ?: match.competitionName).joinToString(" · "),
                    picture = null,
                    live = match.phase == MatchPhase.Live || match.phase == MatchPhase.HalfTime,
                    action = if (on != null) "Watch" else null,
                )
            }
            doorNext.forEachIndexed { i, p -> items += item("doorNext", i, "NEXT UP", p) }
            buttons += RemoteLiveTvButton("doorScoresToggle", 0, if (settings.doorHideScores) "Show scores" else "Hide scores")
            if (items.isEmpty()) empty = "Nothing to pick yet: star a few channels in All channels."
        }
        IptvStage.Three -> {
            listOf("SAFE" to m.three.safe, "STARTS SOON" to m.three.soon, "WILDCARD" to m.three.wildcard)
                .forEachIndexed { i, (slot, p) -> if (p != null) items += item("cards", i, slot, p) }
            if (m.canReshuffle) buttons += RemoteLiveTvButton("deal", 0, "Deal again")
            empty = "Nothing to pick right now."
        }
        IptvStage.Soon -> {
            soonRows.forEachIndexed { i, p -> items += item("departures", i, clock(p.entry.startMs), p, "Tune in at ${clock(p.entry.startMs)}") }
            empty = "Nothing starts soon."
        }
        IptvStage.Minutes -> {
            m.board.alreadyOn.forEachIndexed { i, f -> items += item("on", i, "ON NOW", f.programme) }
            m.board.starting.forEachIndexed { i, f -> items += item("starting", i, "STARTS ${clock(f.programme.entry.startMs)}", f.programme) }
            m.board.catchUp.forEachIndexed { i, f -> items += item("catchup", i, "FROM EARLIER", f.programme, "Play from the start") }
            IptvDecide.MINUTE_CHOICES.forEachIndexed { i, minutes -> buttons += RemoteLiveTvButton("dial", i, "$minutes min", accent = minutes == settings.minutes) }
            empty = "Nothing fits in ${settings.minutes} minutes."
        }
        IptvStage.Order -> {
            m.order.forEachIndexed { i, p -> items += item("order", i, clock(p.entry.startMs), p) }
            if (m.order.isNotEmpty()) {
                buttons += RemoteLiveTvButton("orderActions", 0, if (m.orderApproved) "Approved: cancel" else "Approve and start", accent = !m.orderApproved)
                buttons += RemoteLiveTvButton("orderActions", 2, "Not tonight")
            }
            empty = "Nothing fits before bedtime."
        }
        IptvStage.Earlier -> {
            earlierRows.forEachIndexed { i, p -> items += item("earlier", i, earlierDay?.uppercase() ?: "EARLIER", p, "Play from the start") }
            empty = "Nothing kept from earlier."
        }
        IptvStage.Following -> {
            m.follows.forEachIndexed { i, result ->
                val first = result.matches.firstOrNull()
                items += if (first != null) {
                    item("follows", i, "FOLLOWING", first, if (first.onNow(now)) "Watch" else "Remind me")
                        .copy(title = IptvDecide.displayFollow(result.text), sub = IptvStageRules.foundText(first, now))
                } else {
                    RemoteLiveTvItem("-", i, "FOLLOWING", IptvDecide.displayFollow(result.text), "Nothing in the guide yet", null)
                }
            }
            m.learnedTeams.forEachIndexed { i, team ->
                items += RemoteLiveTvItem("learned", i, "LEARNED", "Follow ${team.name}?", learnedSub(team), null)
                buttons += RemoteLiveTvButton("learnedNo", i, "Not ${team.name}")
            }
            m.followSuggestions.forEachIndexed { i, p ->
                items += item("suggest", i, if (m.suggestionWhy.getOrNull(i) != null) "YOU KEEP WATCHING" else "ON YOUR CHANNELS", p, "Follow")
            }
            empty = "Nothing followed yet."
        }
        IptvStage.Ask -> {
            m.ask?.programme?.let { p -> items += item("askActions", 0, if (m.askByAi) "FROM YOUR AI" else "ONE ANSWER", p).copy(sub = m.ask?.why ?: "") }
            if ((m.ask?.others ?: 0) > 0) buttons += RemoteLiveTvButton("askActions", 1, "Not that")
            AskExamples.forEachIndexed { i, line -> buttons += RemoteLiveTvButton("examples", i, line) }
            empty = m.ask?.why ?: "Type on the phone's keyboard, or pick a line below."
        }
        IptvStage.Lineup -> {
            m.lineup.forEach { c ->
                val on = c.now
                items += RemoteLiveTvItem(
                    group = "lineup",
                    index = c.number - 1,
                    slot = c.number.toString(),
                    title = c.channel.primary.name.ifBlank { "Channel ${c.channel.primary.num}" },
                    sub = on?.title ?: "No guide",
                    picture = c.channel.primary.logo,
                    live = on != null,
                    progress = on?.let { e -> (((now - e.startMs) * 1000L) / (e.endMs - e.startMs).coerceAtLeast(1L)).toInt().coerceIn(0, 1000) } ?: -1,
                    action = "Watch",
                )
            }
            empty = "Your 40 is empty."
        }
        IptvStage.Week -> empty = IptvBrakeRules.weekLine(IptvBrakeRules.week(settings.tally, IptvDecide.nightOf(now))) + "."
        // fork:live-tv-views - the phone gets the list-shaped ones; the map, river and grid are for the big screen.
        IptvStage.Mosaic -> {
            val mo = IptvViewRules.mosaic(m.picks, now, m.rank, smallCount = 18)
            mo.big.forEachIndexed { i, p -> items += item("mosaicBig", i, "FOR YOU", p) }
            mo.mid.forEachIndexed { i, p -> items += item("mosaicMid", i, "ON NOW", p) }
            mo.small.forEachIndexed { i, p -> items += item("mosaicSmall", i, "ALSO ON", p) }
            empty = "Nothing on right now."
        }
        IptvStage.Dial -> {
            IptvViewRules.dial(m.lineup, m.picks, now).take(200).forEachIndexed { i, c ->
                items += RemoteLiveTvItem(
                    group = "dial", index = i, slot = c.number.toString(), title = c.channel.primary.name,
                    sub = c.now?.title ?: "No guide", picture = c.channel.primary.logo, live = c.now != null,
                    progress = c.now?.let { e -> (((now - e.startMs) * 1000L) / (e.endMs - e.startMs).coerceAtLeast(1L)).toInt().coerceIn(0, 1000) } ?: -1,
                    action = "Watch",
                )
            }
            empty = "No channels."
        }
        IptvStage.Wall -> {
            IptvViewRules.wall(m.lineup, m.picks, m.usualKeys).forEachIndexed { i, ch ->
                val on = m.picks.firstOrNull { it.channel.primary.uid == ch.primary.uid }?.current(now)
                items += RemoteLiveTvItem(group = "wall", index = i, slot = "", title = ch.primary.name, sub = on?.title ?: "No guide", picture = ch.primary.logo, live = on != null, progress = -1, action = "Watch")
            }
            empty = "No channels."
        }
        IptvStage.Map, IptvStage.River, IptvStage.Grid -> empty = "This view is on the TV."
    }
    return RemoteLiveTvScreen(
        stage = stage.name,
        heading = heading,
        sub = sub,
        stages = stages.map { it.name to it.label },
        items = items,
        buttons = buttons,
        empty = empty,
    )
}

private fun stageIcon(stage: IptvStage): ImageVector = when (stage) {
    IptvStage.Door -> Icons.Rounded.Home
    IptvStage.Three -> Icons.Rounded.ViewColumn
    IptvStage.Soon -> Icons.Rounded.Schedule
    IptvStage.Minutes -> Icons.Rounded.WatchLater
    IptvStage.Order -> Icons.Rounded.Bedtime
    IptvStage.Earlier -> Icons.Rounded.History
    IptvStage.Following -> Icons.Rounded.Notifications
    IptvStage.Ask -> Icons.Rounded.AutoAwesome
    IptvStage.Lineup -> Icons.Rounded.Tv
    IptvStage.Week -> Icons.Rounded.BarChart
    IptvStage.Map -> Icons.Rounded.Map
    IptvStage.Mosaic -> Icons.Rounded.Dashboard
    IptvStage.River -> Icons.Rounded.Timeline
    IptvStage.Wall -> Icons.Rounded.PhotoLibrary
    IptvStage.Grid -> Icons.Rounded.GridOn
    IptvStage.Dial -> Icons.Rounded.Dialpad
}

private fun hiddenLine(m: IptvStageModel): String? {
    val parts = buildList {
        if (m.hiddenFiller > 0) add("${count(m.hiddenFiller)} loop, radio and shopping channels")
        if (m.hiddenShopping > 0) add("${m.hiddenShopping} shopping slots")
    }
    return if (parts.isEmpty()) null else "Hidden: " + parts.joinToString(", ") + "."
}

private fun stageHeading(stage: IptvStage, m: IptvStageModel?, settings: IptvSettings, now: Long): Pair<String, String> {
    val total = m?.total?.let(::count) ?: "all the"
    val clock = IptvStageRules.clock(now)
    return when (stage) {
        IptvStage.Door -> "Live TV" to "It is $clock. One pick out of $total channels: OK plays it."
        IptvStage.Three -> "Three things" to "Out of $total channels at $clock. Pick one; there is never a fourth."
        IptvStage.Soon -> "Starting soon" to "Things you can watch from the first minute. It is $clock."
        IptvStage.Minutes -> "I have ${settings.minutes} minutes" to "Only what finishes inside it."
        IptvStage.Order -> "Tonight" to if (m != null && m.orderFrom > now + 30 * 60_000L) {
            "It is $clock, so this is the plan from ${IptvStageRules.clock(m.orderFrom)}. Approve it now and Nuvio changes channel for you."
        } else {
            "One evening from $total channels. Approve it once and Nuvio changes channel for you."
        }
        IptvStage.Earlier -> "Earlier today, ready to play" to "Already shown on your channels and still in the provider's catch-up. Each plays from its start."
        IptvStage.Following -> "Following" to "Hold OK on any programme to follow it. Nuvio finds it on whichever of the $total channels has it."
        IptvStage.Ask -> "Just ask" to "Typed or spoken. It answers with one thing, not a list."
        IptvStage.Lineup -> "Your 40" to "The whole of Live TV, numbered like a television. The other ${m?.let { count((it.keptMerged - it.lineup.size).coerceAtLeast(0)) } ?: ""} are in search."
        IptvStage.Week -> "Your week" to "Counted on the player for seven nights. Plain numbers, not a score."
        IptvStage.Map -> "Map" to "Everything on now across $total channels, by kind. OK opens a block; Back zooms out."
        IptvStage.Mosaic -> "For you now" to "Everything on at $clock, the likeliest biggest."
        IptvStage.River -> "River" to "The next three hours by kind, not by channel. Nuvio picks the channel."
        IptvStage.Wall -> "Picture wall" to "What your channels show, a still every few minutes, taken only while nothing plays."
        IptvStage.Grid -> "Tonight grid" to "When the good things start, by kind and half-hour."
        IptvStage.Dial -> "Dial" to "One channel at a time. Up and down flip; type a number to jump."
    }
}

// ---- The stages ----------------------------------------------------------------------------------------------

internal typealias Focused = (String, Int) -> Boolean
internal typealias Act = (String, Int) -> Unit

/**
 * fork:live-tv-front-door - the front door, as the mockups' Front door row: favourites now on the left (the last
 * channel first), one pick in the centre with Watch and Something else, the followed scores and what is next up on
 * the right. The panels keep their size whatever is in them.
 */
@Composable
private fun DoorStage(
    playlist: IptvPlaylist,
    m: IptvStageModel,
    now: Long,
    side: Dp,
    pick: IptvDoorPick?,
    nextUp: List<IptvWallProgramme>,
    scores: List<LiveMatch>,
    settings: IptvSettings,
    isFocused: Focused,
    onLongClick: (Int) -> Unit,
    act: Act,
) {
    fun focusedIndex(group: String, size: Int) = (0 until size).firstOrNull { isFocused(group, it) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Favourites now", if (m.favourites.isEmpty()) null else "${m.favourites.size} starred", Modifier.width(side).fillMaxHeight()) {
            m.carryOn?.let { c ->
                val p = c.now?.let { IptvWallProgramme(c.channel, it) }
                StageRow(
                    lead = null,
                    title = c.now?.title ?: c.channel.primary.name,
                    sub = "Carry on · ${c.channel.primary.name}",
                    trailing = null,
                    focused = isFocused("doorCarry", 0),
                    thumb = p,
                    thumbWidth = 72.dp,
                    trailingIcon = null,
                    progress = c.now?.progress(now),
                    onClick = { act("doorCarry", 0) },
                )
                StageDivider()
            }
            StageList(
                m.favourites.size,
                focusedIndex("doorFavs", m.favourites.size),
                "No favourites yet. Star 6 to 10 channels in All channels, or in the triage's last question.",
            ) { i ->
                val c = m.favourites[i]
                val p = c.now?.let { IptvWallProgramme(c.channel, it) }
                StageRow(
                    lead = null,
                    title = c.now?.title ?: c.channel.primary.name,
                    sub = c.channel.primary.name + (c.now?.let { " · ${IptvStageRules.length(it.endMs - now)} left" }
                        ?: c.next?.let { " · ${it.title} at ${IptvStageRules.clock(it.startMs)}" } ?: ""),
                    trailing = null,
                    focused = isFocused("doorFavs", i),
                    thumb = p,
                    thumbWidth = 72.dp,
                    dim = c.now == null,
                    progress = c.now?.progress(now),
                    onLongClick = { onLongClick(i) },
                    onClick = { act("doorFavs", i) },
                )
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) { StageNoteInline("Hold OK or right-click for Follow, Remind me and Unstar.") }
        }

        DoorPickCard(playlist, m, pick, now, settings.doorPreview, isFocused, act, Modifier.weight(1f).fillMaxHeight())

        StagePanel("Scores", if (settings.doorHideScores) "hidden" else "teams you follow", Modifier.width(side).fillMaxHeight()) {
            when {
                settings.doorHideScores -> StageNote("Scores are hidden, so nothing here spoils a result.")
                scores.isEmpty() -> StageNote("No followed matches today. Follow teams in Settings > Live Scores.")
                else -> scores.forEachIndexed { i, match ->
                    if (i > 0) StageDivider()
                    val on = m.matchProgrammes[match.id]
                    StageRow(
                        lead = null,
                        title = scoreLine(match),
                        sub = listOfNotNull(scoreState(match, now), on?.channel?.primary?.name ?: match.competitionName).joinToString(" · "),
                        trailing = null,
                        focused = isFocused("doorScores", i),
                        live = match.phase == MatchPhase.Live || match.phase == MatchPhase.HalfTime,
                        onClick = { act("doorScores", i) },
                    )
                }
            }
            StageDivider()
            StageSubheading("Next up", Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
            StageList(nextUp.size, focusedIndex("doorNext", nextUp.size), "Nothing else starts on your channels this hour.") { i ->
                val p = nextUp[i]
                StageRow(
                    lead = null,
                    title = p.entry.title,
                    sub = "${IptvStageRules.clock(p.entry.startMs)} on ${p.channel.primary.name} · ${IptvStageRules.untilText(p.entry, now)}",
                    trailing = null,
                    focused = isFocused("doorNext", i),
                    thumb = p,
                    thumbWidth = 72.dp,
                    onClick = { act("doorNext", i) },
                )
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                StageButton(
                    if (settings.doorHideScores) "Show scores" else "Hide scores",
                    accent = false,
                    focused = isFocused("doorScoresToggle", 0),
                    icon = if (settings.doorHideScores) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                ) { act("doorScoresToggle", 0) }
            }
        }
    }
}

/** "Arsenal 1 – 0 Spurs"; "Arsenal v Spurs" before kick-off. */
private fun scoreLine(match: LiveMatch): String {
    val home = match.home.shortName.ifBlank { match.home.name }
    val away = match.away.shortName.ifBlank { match.away.name }
    val hs = match.homeScore
    val aws = match.awayScore
    return if (match.phase == MatchPhase.Before || hs == null || aws == null) "$home v $away" else "$home $hs – $aws $away"
}

private fun scoreState(match: LiveMatch, now: Long): String = when (match.phase) {
    MatchPhase.Live -> match.clock?.takeIf { it.isNotBlank() } ?: "Live"
    MatchPhase.HalfTime -> "Half-time"
    MatchPhase.Finished -> "Full time"
    MatchPhase.Postponed -> "Postponed"
    // fork:live-tv-polish - a match on another day says which ("Kick-off Tomorrow 12:30", "Kick-off Sat 15:00").
    MatchPhase.Before -> match.startEpochMs?.let { start ->
        val day = IptvStageRules.dayLabel(start, now)
        "Kick-off " + if (day == "Today") IptvStageRules.clock(start) else if (day == "Tomorrow") "tomorrow ${IptvStageRules.clock(start)}" else IptvStageRules.weekLabel(start)
    } ?: "Later"
}

/** The front door's one pick: a big picture (the channel itself, muted, when Preview is on), why it is there, Watch and Something else. */
@Composable
private fun DoorPickCard(
    playlist: IptvPlaylist,
    m: IptvStageModel,
    pick: IptvDoorPick?,
    now: Long,
    preview: Boolean,
    isFocused: Focused,
    act: Act,
    modifier: Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = NuvioGlass.Shape
    val p = pick?.programme
    // fork:live-tv-front-door - Preview: the pick's channel playing, muted, while nothing else plays and no menu covers
    // the stage (the picture is a native window and would sit on top of it).
    val playingNow by IptvPlaybackSession.active.collectAsStateWithLifecycle()
    val covered by IptvPreviewCover.covered.collectAsStateWithLifecycle()
    val previewChannel = p?.channel?.takeIf { preview && p.onNow(now) && playingNow == null && !covered }
    val previewSource = remember(playlist.id, previewChannel?.primary?.uid) {
        previewChannel?.let { c ->
            val settings = IptvRepository.settings.value
            val allowed = IptvRepository.contentState(playlist.id).value.content?.account?.allowedFormats.orEmpty()
            iptvStreamCandidates(playlist, c.primary, settings.preferredFormat, allowed).firstOrNull()
                ?.url?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?.let { it to (IPTV_DEFAULT_HEADERS + c.primary.requestHeaders()) }
        }
    }
    var live by remember(previewSource?.first) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .iptvGlass(shape, raised = isFocused("doorPick", 0) || isFocused("doorPick", 1))
            .clip(shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // fork:live-tv-polish - the picture takes the room the words do not need, so the card has no empty gap.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val artHeight = maxHeight
            StageArt(p, artHeight, badges = p?.let { topBadges(it, now) }.orEmpty(), caption = !live)
            IptvLivePreview(
                url = previewSource?.first,
                headers = previewSource?.second.orEmpty(),
                modifier = Modifier.fillMaxWidth().height(artHeight),
                onShowing = { live = it },
            )
        }
        if (pick == null || p == null) {
            StageLabel("THE PICK")
            StageSentence("Nothing to pick yet. Once the guide is in and you have watched a few channels, the best thing on shows here.")
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageLabel(IptvDoorRules.label(pick.reason))
            Spacer(Modifier.weight(1f))
            val number = m.lineup.firstOrNull { it.channel.primary.uid == p.channel.primary.uid }?.number
            Text(
                p.channel.primary.name + (number?.let { " · $it on Your 40" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(p.entry.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaChips(p)
        ProgrammeWords(p, lines = 4, fixed = true)
        if (p.onNow(now)) ProgressLine(p.entry.progress(now)) else Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Star, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(14.dp))
            val number = m.lineup.firstOrNull { it.channel.primary.uid == p.channel.primary.uid }?.number
            val finished = IptvTasteRules.finishText(IptvTasteRules.statOf(m.taste, p.entry.title))
            Text(IptvDoorRules.why(pick, now, number, finished), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val begun = p.entry.startMs <= now + 60_000L
            StageButton(
                if (begun) "Watch" else "Watch · starts ${IptvStageRules.clock(p.entry.startMs)}",
                accent = true,
                focused = isFocused("doorPick", 0),
                icon = Icons.Rounded.PlayArrow,
                modifier = Modifier.weight(1f),
            ) { act("doorPick", 0) }
            StageButton(
                "Something else",
                accent = false,
                focused = isFocused("doorPick", 1),
                icon = Icons.Rounded.SwapHoriz,
                modifier = Modifier.weight(1f),
            ) { act("doorPick", 1) }
        }
    }
}

@Composable
private fun ThreeStage(m: IptvStageModel, now: Long, isFocused: Focused, act: Act) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val lineupNumber = { p: IptvWallProgramme -> m.lineup.firstOrNull { it.channel.primary.uid == p.channel.primary.uid }?.number }
            val cards = listOf(
                Triple("SAFE", m.three.safe) { p: IptvWallProgramme ->
                    // fork:live-tv-taste - "You finished 4 of the last 5" when there is a history to go on.
                    (IptvTasteRules.finishText(IptvTasteRules.statOf(m.taste, p.entry.title))?.let { "$it · " } ?: "") +
                        "Your usual at this hour" + (lineupNumber(p)?.let { ", $it on Your 40" } ?: "")
                },
                Triple("STARTS SOON", m.three.soon) { p: IptvWallProgramme -> "From the first minute, done by ${IptvStageRules.clock(p.entry.endMs)}" },
                Triple("WILDCARD", m.three.wildcard) { p: IptvWallProgramme ->
                    if (p.entry.kind == IptvProgrammeKind.FILM) "A film off your usual channels" else "Something off your usual channels"
                },
            )
            cards.forEachIndexed { index, (label, p, why) ->
                StageCard(
                    label = label,
                    programme = p,
                    now = now,
                    empty = when (index) {
                        0 -> "None of your usual channels has a guide."
                        1 -> "Nothing starts in the next half hour."
                        else -> "No film to suggest right now."
                    },
                    reason = p?.let(why).orEmpty(),
                    focused = isFocused("cards", index),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { act("cards", index) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            StageButton(
                label = if (m.canReshuffle) "Deal again (one left tonight)" else "Dealt again tonight",
                accent = false,
                enabled = m.canReshuffle,
                focused = isFocused("deal", 0),
                icon = Icons.Rounded.SwapHoriz,
                modifier = Modifier.width(300.dp),
            ) { act("deal", 0) }
            Spacer(Modifier.width(16.dp))
            StageNoteInline("There is never a fourth." + (hiddenLine(m)?.let { " $it" } ?: ""))
        }
    }
}

@Composable
private fun SoonStage(
    m: IptvStageModel,
    now: Long,
    side: Dp,
    lists: Pair<List<IptvWallProgramme>, List<IptvWallProgramme>>,
    selected: Int,
    kinds: Set<IptvSoonKind>,
    lineupOnly: Boolean,
    window: Int,
    isFocused: Focused,
    nextOf: (IptvWallProgramme) -> IptvEpgEntry?,
    act: Act,
) {
    val rows = lists.first + lists.second
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Show me", null, Modifier.width(side).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StageChip(if (m.lineupUids.isEmpty()) "My channels only" else "My 40 only", lineupOnly, isFocused("kinds", 0)) { act("kinds", 0) }
                IptvSoonKind.entries.forEachIndexed { i, kind ->
                    StageChip(kind.label, kind in kinds, isFocused("kinds", i + 1)) { act("kinds", i + 1) }
                }
                Spacer(Modifier.height(4.dp))
                StageSubheading("Leaving within")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IptvStageRules.SOON_WINDOWS.forEachIndexed { i, minutes ->
                        StageChip(if (i == 0) "$minutes min" else "$minutes", minutes == window, isFocused("window", i)) { act("window", i) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            hiddenLine(m)?.let { StageInfoRow("Hidden", it.removePrefix("Hidden: ").removeSuffix("."), null, icon = Icons.Rounded.VisibilityOff) }
            StageNote("Nothing here has started. Times are the start.")
        }
        StagePanel("Departures", "${lists.first.size} in the next $window minutes", Modifier.weight(1f).fillMaxHeight()) {
            StageList(rows.size, focusedIndex = rows.indices.firstOrNull { isFocused("departures", it) }, empty = "Nothing starts in the next hour on these channels.") { i ->
                val p = rows[i]
                StageRow(
                    lead = IptvStageRules.untilText(p.entry, now).removePrefix("in "),
                    thumb = p,
                    title = p.entry.title,
                    badges = rowBadges(p.entry),
                    sub = listOfNotNull(p.channel.primary.name, kindLabel(p.entry), IptvStageRules.length(p.entry.endMs - p.entry.startMs)).joinToString(" · "),
                    trailing = IptvStageRules.clock(p.entry.startMs),
                    dim = i >= lists.first.size,
                    selected = i == selected,
                    focused = isFocused("departures", i),
                ) { act("departures", i) }
            }
        }
        val p = rows.getOrNull(selected) ?: rows.firstOrNull()
        DetailPanel(p, now, side, nextOf, note = "Carry on with what is on. Nuvio switches over at the start.") { prog ->
            StageButton("Tune in at ${IptvStageRules.clock(prog.entry.startMs)}", accent = true, focused = isFocused("soonActions", 0), icon = Icons.Rounded.Schedule) { act("soonActions", 0) }
            StageButton("Go to ${prog.channel.primary.name} now", accent = false, focused = isFocused("soonActions", 1)) { act("soonActions", 1) }
        }
    }
}

@Composable
private fun MinutesStage(m: IptvStageModel, now: Long, minutes: Int, isFocused: Focused, act: Act) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            IptvDecide.MINUTE_CHOICES.forEachIndexed { i, choice ->
                StageChip(if (choice == 60) "1 hour" else "$choice min", choice == minutes, isFocused("dial", i)) { act("dial", i) }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            fun fitSub(fit: IptvFit, extra: String) = fit.programme.channel.primary.name + " · " + extra +
                if (fit.overMin > 0) ", ${fit.overMin} over" else ""
            StagePanel("Ends in time", "already on", Modifier.weight(1f).fillMaxHeight()) {
                val list = m.board.alreadyOn
                StageList(list.size, list.indices.firstOrNull { isFocused("on", it) }, "Nothing on now ends inside $minutes minutes.") { i ->
                    val f = list[i]
                    StageRow(
                        lead = IptvStageRules.clock(f.programme.entry.endMs), leadWidth = 44.dp,
                        thumb = f.programme, thumbWidth = 72.dp,
                        title = f.programme.entry.title,
                        badges = listOf(StageBadge("LIVE", BadgeKind.Live)) + rowBadges(f.programme.entry, live = false),
                        sub = fitSub(f, "${((f.programme.entry.endMs - now) / 60_000L).coerceAtLeast(0)} min left"),
                        trailing = null, dim = f.overMin > 0, focused = isFocused("on", i), progress = f.programme.entry.progress(now),
                    ) { act("on", i) }
                }
            }
            StagePanel("Starts and ends in time", "from the top", Modifier.weight(1f).fillMaxHeight()) {
                val list = m.board.starting
                StageList(list.size, list.indices.firstOrNull { isFocused("starting", it) }, "Nothing starting soon fits.") { i ->
                    val f = list[i]
                    StageRow(
                        lead = IptvStageRules.clock(f.programme.entry.startMs), leadWidth = 44.dp,
                        thumb = f.programme, thumbWidth = 72.dp,
                        title = f.programme.entry.title,
                        badges = rowBadges(f.programme.entry),
                        sub = fitSub(f, IptvStageRules.length(f.programme.entry.endMs - f.programme.entry.startMs)),
                        trailing = null, dim = f.overMin > 0, focused = isFocused("starting", i),
                    ) { act("starting", i) }
                }
            }
            StagePanel("From earlier, fits", "catch-up", Modifier.weight(1f).fillMaxHeight()) {
                val list = m.board.catchUp
                StageList(list.size, list.indices.firstOrNull { isFocused("catchup", it) }, "No catch-up is short enough, or the provider keeps none.") { i ->
                    val f = list[i]
                    StageRow(
                        lead = (f.programme.entry.endMs - f.programme.entry.startMs).let { "${it / 60_000L}m" }, leadWidth = 44.dp,
                        thumb = f.programme, thumbWidth = 72.dp,
                        title = f.programme.entry.title,
                        badges = rowBadges(f.programme.entry),
                        sub = fitSub(f, "shown ${IptvStageRules.clock(f.programme.entry.startMs)}"),
                        trailing = null, dim = f.overMin > 0, focused = isFocused("catchup", i),
                    ) { act("catchup", i) }
                }
            }
        }
        Text(
            text = "It is ${IptvStageRules.clock(now)}. Everything here is over by ${IptvStageRules.clock(now + minutes * 60_000L)}, and nothing plays after it." +
                (hiddenLine(m)?.let { " $it" } ?: ""),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrderStage(
    m: IptvStageModel,
    now: Long,
    side: Dp,
    selected: Int,
    follows: List<String>,
    bedMinute: Int,
    fromChoice: Int,
    isFocused: Focused,
    act: Act,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Built from", null, Modifier.width(side).fillMaxHeight()) {
            StageInfoRow("Your usuals", "What you watch at each hour", null, icon = Icons.Rounded.Visibility)
            StageDivider()
            StageInfoRow("Followed", if (follows.isEmpty()) "Nothing yet" else follows.take(3).joinToString(", ", transform = IptvDecide::displayFollow), null, icon = Icons.Rounded.Notifications)
            StageDivider()
            StageInfoRow("Films", "The best of the evening", null, icon = Icons.Rounded.Star)
            StageDivider()
            StageInfoRow("Bedtime", "Nothing runs past ${IptvStageRules.clock(m.bedMs)}", null, icon = Icons.Rounded.Bedtime)
            Spacer(Modifier.weight(1f))
            StageNote("No loop channels, no shopping, nothing that runs past bed.")
        }
        val total = m.order.sumOf { it.entry.endMs - maxOf(it.entry.startMs, m.orderFrom) }
        StagePanel(
            "Running order",
            if (m.order.isEmpty()) null else "${m.order.size} things, ${IptvStageRules.length(total)}, all over by ${IptvStageRules.clock(m.bedMs)}",
            Modifier.weight(1f).fillMaxHeight(),
        ) {
            if (m.order.isNotEmpty()) OrderRibbon(m.order, m.orderFrom, m.bedMs, selected)
            val leftOut = m.orderLeftOut
            val rows = m.order.size + if (leftOut != null) 1 else 0
            StageList(rows, m.order.indices.firstOrNull { isFocused("order", it) }, "Nothing on your channels fits before bedtime.") { i ->
                val p = m.order.getOrNull(i)
                if (p != null) {
                    StageRow(
                        lead = IptvStageRules.clock(p.entry.startMs),
                        thumb = p, thumbWidth = 80.dp,
                        title = p.entry.title,
                        badges = rowBadges(p.entry),
                        sub = p.channel.primary.name + " · " + IptvRunningOrder.reason(p, follows, m.usualKeys),
                        trailing = IptvStageRules.length(p.entry.endMs - maxOf(p.entry.startMs, m.orderFrom)),
                        selected = i == selected,
                        focused = isFocused("order", i),
                        live = p.onNow(now),
                    ) { act("order", i) }
                } else if (leftOut != null) {
                    StageRow(
                        lead = IptvStageRules.clock(leftOut.entry.startMs),
                        thumb = leftOut, thumbWidth = 80.dp,
                        title = leftOut.entry.title,
                        sub = "${leftOut.channel.primary.name} · runs to ${IptvStageRules.clock(leftOut.entry.endMs)}, past bedtime: another night",
                        trailing = null,
                        dim = true,
                        focused = false,
                    ) {}
                }
            }
        }
        StagePanel("Your call", null, Modifier.width(side).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val swapName = m.order.getOrNull(selected)?.entry?.title
                StageButton(
                    if (m.orderApproved) "Approved: cancel" else if (m.orderFrom > now + 30 * 60_000L) "Approve for tonight" else "Approve and start",
                    accent = !m.orderApproved, focused = isFocused("orderActions", 0), icon = Icons.Rounded.Check,
                ) { act("orderActions", 0) }
                StageButton(swapName?.let { "Swap $it" } ?: "Swap one", accent = false, focused = isFocused("orderActions", 1), icon = Icons.Rounded.SwapHoriz) { act("orderActions", 1) }
                StageButton("Not tonight", accent = false, focused = isFocused("orderActions", 2)) { act("orderActions", 2) }
                Spacer(Modifier.height(2.dp))
                StageSubheading("Starts")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FromChoices.forEachIndexed { i, minute ->
                        StageChip(if (minute < 0) "Now" else "%02d:%02d".format(minute / 60, minute % 60), i == fromChoice, isFocused("from", i)) { act("from", i) }
                    }
                }
                StageSubheading("Bedtime")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BedChoices.forEachIndexed { i, minute ->
                        StageChip("%02d:%02d".format(minute / 60, minute % 60), minute == bedMinute, isFocused("bed", i)) { act("bed", i) }
                    }
                }
                Spacer(Modifier.weight(1f))
                StageNoteInline("Any key takes over. At ${IptvStageRules.clock(m.bedMs)} nothing starts by itself.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The evening as blocks from its start to bedtime, under hour marks. Every block is placed by time, not by content. */
@Composable
private fun OrderRibbon(order: List<IptvWallProgramme>, from: Long, bed: Long, selected: Int) {
    val tokens = MaterialTheme.nuvio
    val span = (bed - from).coerceAtLeast(60_000L).toFloat()
    val step = if (bed - from > 8 * 3_600_000L) 2 * 3_600_000L else 3_600_000L
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val firstHour = (from / 3_600_000L + 1) * 3_600_000L
            var t = firstHour
            while (t < bed) {
                val x = maxWidth * ((t - from) / span)
                Text(IptvStageRules.clock(t), modifier = Modifier.offset(x = x - 14.dp), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                t += step
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
        ) {
            order.forEachIndexed { i, p ->
                val start = (maxOf(p.entry.startMs, from) - from) / span
                val end = (minOf(p.entry.endMs, bed) - from) / span
                val hue = ((p.channel.primary.name.hashCode() % 360) + 360) % 360
                Box(
                    Modifier.offset(x = maxWidth * start).width(maxWidth * (end - start).coerceAtLeast(0.01f)).fillMaxHeight().padding(5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.linearGradient(listOf(Color.hsv(hue.toFloat(), 0.45f, 0.55f), Color.hsv(hue.toFloat(), 0.4f, 0.28f))))
                        .then(if (i == selected) Modifier.border(1.5.dp, tokens.colors.borderFocus, RoundedCornerShape(6.dp)) else Modifier),
                ) {
                    Text(
                        p.entry.title,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        IptvStageRules.clock(p.entry.startMs),
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EarlierStage(
    now: Long,
    side: Dp,
    days: List<Pair<String, List<IptvWallProgramme>>>,
    day: Int,
    rows: List<IptvWallProgramme>,
    selected: Int,
    isFocused: Focused,
    act: Act,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Day", null, Modifier.width(side).fillMaxHeight()) {
            StageList(days.size, days.indices.firstOrNull { isFocused("days", it) }, "Nothing kept yet.") { i ->
                StageRow(lead = null, title = days[i].first, sub = "${days[i].second.size} worth watching", trailing = null, selected = i == day, focused = isFocused("days", i)) { act("days", i) }
            }
            StageNote("Best first: what you follow, films, then new episodes. Repeats, filler and shopping are left out.")
        }
        StagePanel(days.getOrNull(day)?.first ?: "Earlier", "best first, not by the clock", Modifier.weight(1f).fillMaxHeight()) {
            StageList(rows.size, rows.indices.firstOrNull { isFocused("earlier", it) }, "Nothing from earlier is kept for your channels. This needs an Xtream account whose provider keeps catch-up.") { i ->
                val p = rows[i]
                StageRow(
                    lead = IptvStageRules.clock(p.entry.startMs),
                    thumb = p, thumbWidth = 80.dp,
                    title = p.entry.title,
                    badges = rowBadges(p.entry),
                    sub = listOfNotNull(p.channel.primary.name, kindLabel(p.entry), IptvStageRules.length(p.entry.endMs - p.entry.startMs)).joinToString(" · "),
                    trailing = "Play",
                    trailingIcon = Icons.Rounded.PlayArrow,
                    selected = i == selected,
                    focused = isFocused("earlier", i),
                ) { act("earlier", i) }
            }
        }
        DetailPanel(rows.getOrNull(selected) ?: rows.firstOrNull(), now, side, nextOf = null, note = "Kept by the provider's catch-up.") { prog ->
            StageButton("Play from the start", accent = true, focused = isFocused("earlierActions", 0), icon = Icons.Rounded.Replay) { act("earlierActions", 0) }
            StageButton("Follow ${prog.entry.title}", accent = false, focused = isFocused("earlierActions", 1), icon = Icons.Rounded.Notifications) { act("earlierActions", 1) }
        }
    }
}

@Composable
private fun FollowingStage(
    m: IptvStageModel,
    now: Long,
    side: Dp,
    week: List<IptvWallProgramme>,
    text: String,
    onText: (String) -> Unit,
    fieldFocus: FocusRequester,
    onEditing: () -> Unit,
    isFocused: Focused,
    onLongClick: (Int) -> Unit,
    act: Act,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Follow something", null, Modifier.width(side).fillMaxHeight()) {
            StageField(
                value = text,
                onValueChange = onText,
                placeholder = "A programme, team or film",
                focusRequester = fieldFocus,
                voiceOwner = "iptv-follow",
                focused = isFocused("followInput", 0),
                onEditing = onEditing,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // fork:live-tv-learn - asks once per team; hold OK for no.
            if (m.learnedTeams.isNotEmpty()) {
                StageSubheading("Learned from what you watch", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                m.learnedTeams.forEachIndexed { i, team ->
                    if (i > 0) StageDivider()
                    StageRow(
                        lead = null, title = "Follow ${team.name}?", sub = learnedSub(team),
                        trailing = "Follow", trailingIcon = Icons.Rounded.Star, focused = isFocused("learned", i),
                        onLongClick = { act("learnedNo", i) },
                    ) { act("learned", i) }
                }
                StageNote("Hold OK if you're not interested; it won't ask again.")
            }
            StageSubheading(
                if (m.suggestionWhy.firstOrNull() != null) "You keep watching" else "On your channels",
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            StageList(m.followSuggestions.size, m.followSuggestions.indices.firstOrNull { isFocused("suggest", it) }, "Nothing on your channels to suggest.") { i ->
                val p = m.followSuggestions[i]
                val why = m.suggestionWhy.getOrNull(i)
                StageRow(
                    lead = null, thumb = p, thumbWidth = 64.dp, title = p.entry.title,
                    sub = why?.let { "$it · ${p.channel.primary.name}" } ?: p.channel.primary.name,
                    trailing = "Follow", trailingIcon = Icons.Rounded.Star, focused = isFocused("suggest", i),
                ) { act("suggest", i) }
            }
        }
        StagePanel("Programmes", "${m.follows.size} followed · hold OK to stop following", Modifier.weight(1f).fillMaxHeight()) {
            StageList(m.follows.size, m.follows.indices.firstOrNull { isFocused("follows", it) }, "Nothing followed yet. Type one on the left, or pick something on your channels.") { i ->
                val result = m.follows[i]
                val first = result.matches.firstOrNull()
                StageRow(
                    lead = null,
                    thumb = first, thumbWidth = 80.dp,
                    title = IptvDecide.displayFollow(result.text),
                    badges = first?.let { rowBadges(it.entry) }.orEmpty(),
                    sub = first?.let { "Found: " + IptvStageRules.foundText(it, now) + if (!it.entry.title.equals(result.text, true)) ", ${it.entry.title}" else "" }
                        ?: "Nothing in the guide yet",
                    trailing = first?.let { IptvStageRules.untilText(it.entry, now) },
                    live = first?.onNow(now) == true,
                    dim = first == null,
                    focused = isFocused("follows", i),
                    onLongClick = { onLongClick(i) },
                ) { act("follows", i) }
            }
        }
        StagePanel("Coming up", null, Modifier.width(side).fillMaxHeight()) {
            StageList(week.size, week.indices.firstOrNull { isFocused("week", it) }, "Nothing followed is in the guide.") { i ->
                val p = week[i]
                StageRow(lead = IptvStageRules.weekLabel(p.entry.startMs), leadWidth = 76.dp, title = p.entry.title, sub = p.channel.primary.name, trailing = null, focused = isFocused("week", i)) { act("week", i) }
            }
            StageNote("When one starts, a card offers it. Where there is catch-up it waits under Earlier today.")
        }
    }
}

private fun learnedSub(team: IptvLearnedTeam): String =
    "You watched ${team.games} of their games" + if (team.team != null) " · also adds them to Live Scores" else ""

@Composable
private fun AskStage(
    m: IptvStageModel,
    now: Long,
    side: Dp,
    asks: List<IptvAsked>,
    text: String,
    onText: (String) -> Unit,
    fieldFocus: FocusRequester,
    aiState: IptvAskAiState,
    onEditing: () -> Unit,
    onSubmit: () -> Unit,
    isFocused: Focused,
    act: Act,
) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Asked before", null, Modifier.width(side).fillMaxHeight()) {
            StageList(asks.size, asks.indices.firstOrNull { isFocused("asked", it) }, "What you ask is kept here.") { i ->
                StageRow(lead = null, title = asks[i].text, sub = IptvStageRules.dayLabel(asks[i].at, now), trailing = null, focused = isFocused("asked", i)) { act("asked", i) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StageField(
                value = text,
                onValueChange = onText,
                placeholder = "Say or type what you are in the mood for",
                focusRequester = fieldFocus,
                voiceOwner = "iptv-ask",
                focused = isFocused("askInput", 0),
                onEditing = onEditing,
                onSubmit = onSubmit,
                height = 52.dp,
            )
            val p = m.ask?.programme
            Row(
                Modifier.weight(1f).fillMaxWidth().iptvGlass(NuvioGlass.Shape).clip(NuvioGlass.Shape).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StageArt(p, 203.dp, badges = p?.let { topBadges(it, now) }.orEmpty(), caption = true)
                    if (p != null) MetaChips(p)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageLabel(if (m.askByAi) "ONE ANSWER · FROM YOUR AI" else "ONE ANSWER")
                    when {
                        text.isBlank() -> {
                            Text("Ask for one thing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary)
                            StageSentence("Say what you are in the mood for and how long you have. Or pick one of the lines below.")
                        }
                        p == null -> {
                            Text("Nothing fits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary)
                            StageSentence(m.ask?.why ?: "")
                        }
                        else -> {
                            Text(p.entry.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StageMeta(IptvProgrammeLine.text(p.channel.primary.name, p.entry, now, 0, IptvStageRules::clock))
                            ProgrammeWords(p, lines = 4)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // fork:live-tv-polish - no answer yet: the buttons keep their place but are not shown.
                    Row(Modifier.alpha(if (p == null) 0f else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val primary = when {
                            p == null -> "Watch"
                            p.entry.startMs > now + 60_000L -> "Tune in at ${IptvStageRules.clock(p.entry.startMs)}"
                            else -> "Watch now"
                        }
                        StageButton(primary, accent = p != null, enabled = p != null, focused = isFocused("askActions", 0), icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f)) { act("askActions", 0) }
                        StageButton("Not that", accent = false, enabled = p != null && (m.ask?.others ?: 0) > 0, focused = isFocused("askActions", 1), icon = Icons.Rounded.Close, modifier = Modifier.weight(1f)) { act("askActions", 1) }
                    }
                    val others = m.ask?.others ?: 0
                    when {
                        aiState == IptvAskAiState.Asking -> StageNoteInline("Asking your AI. The rules' answer is shown until it replies.")
                        aiState == IptvAskAiState.Failed && !m.askByAi -> StageNoteInline("Your AI did not answer, so this is the rules' answer.")
                        p != null -> StageNoteInline(if (others > 0) "$others more fit if you say Not that." else "That is the only one that fits.")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AskExamples.forEachIndexed { i, line ->
                    StageChip(line, false, isFocused("examples", i), icon = Icons.Rounded.Search) { act("examples", i) }
                }
            }
        }
        StagePanel("Why this", null, Modifier.width(side).fillMaxHeight()) {
            val reasons = m.ask?.reasons.orEmpty()
            if (reasons.isEmpty()) {
                StageNote("The reasons for the answer show here.")
            } else {
                reasons.forEachIndexed { i, (head, detail) ->
                    if (i > 0) StageDivider()
                    StageInfoRow(head, detail, null, icon = Icons.Rounded.Check)
                }
            }
        }
    }
}

@Composable
private fun LineupStage(
    m: IptvStageModel,
    now: Long,
    side: Dp,
    playlist: IptvPlaylist,
    settings: IptvSettings,
    content: IptvPlaylistContent,
    typed: String,
    isFocused: Focused,
    nextOf: (IptvWallProgramme) -> IptvEpgEntry?,
    onLongClick: (Int) -> Unit,
    act: Act,
) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("How it got here", null, Modifier.width(side).fillMaxHeight()) {
            val triage = settings.triage[playlist.id]
            val prefix = IptvRepository.channelRef(playlist.id, "")
            val played = settings.plays.mapTo(HashSet()) { it.ref }
            fun inLineup(refs: Collection<String>) = m.lineup.count { item -> item.channel.sources.any { IptvRepository.channelRef(playlist.id, it.key) in refs } }
            StageInfoRow("Triage", if (triage == null) "Not done yet" else "${triage.regions.size} countries, ${triage.kinds.size} kinds", null)
            StageDivider()
            StageInfoRow("Copies merged", "FHD, HD, SD and backups", count((m.total - m.keptMerged).coerceAtLeast(0)))
            StageDivider()
            StageInfoRow("What you watch", "From your viewing", count(inLineup(played.filter { it.startsWith(prefix) })))
            StageDivider()
            StageInfoRow("Starred", "Your favourites", count(inLineup(settings.favourites)))
            Spacer(Modifier.weight(1f))
            StageButton("Redo the triage", accent = false, focused = isFocused("lineupActions", 0), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { act("lineupActions", 0) }
            Spacer(Modifier.height(16.dp))
        }
        val focusedIndex = m.lineup.indices.firstOrNull { isFocused("lineup", it) }
        val shown = m.lineup.getOrNull(focusedIndex ?: 0)
        StagePanel("Lineup", if (typed.isNotEmpty()) "Channel $typed..." else "Type a number to go straight to it", Modifier.weight(1f).fillMaxHeight()) {
            // The band: what is on the highlighted channel. Always this tall, whatever it holds.
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).height(96.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val p = shown?.now?.let { IptvWallProgramme(shown.channel, it) }
                Box(Modifier.width(170.dp)) { StageArt(p ?: shown?.let { s -> IptvWallProgramme(s.channel, IptvEpgEntry("", 0, 0)) }, 96.dp) }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown?.let { StageBadgeView(StageBadge("${it.number} · ${it.channel.primary.name}".uppercase(), BadgeKind.Plain)) }
                        if (p != null) StageBadgeView(StageBadge("LIVE", BadgeKind.Live))
                        p?.entry?.episode?.let { StageBadgeView(StageBadge(it, BadgeKind.Plain)) }
                    }
                    Text(p?.entry?.title ?: "No guide for this channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val then = p?.let(nextOf)
                    Text(
                        p?.let { "${IptvStageRules.clock(it.entry.startMs)} to ${IptvStageRules.clock(it.entry.endMs)}" + (then?.let { n -> " · then ${n.title} at ${IptvStageRules.clock(n.startMs)}" } ?: "") }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ProgressLine(p?.entry?.progress(now) ?: 0f, height = 2.dp)
                }
            }
            val grid = rememberLazyGridState()
            LaunchedEffect(focusedIndex) {
                if (focusedIndex != null && grid.layoutInfo.visibleItemsInfo.none { it.index == focusedIndex }) grid.scrollToItem(focusedIndex)
            }
            if (m.lineup.isEmpty()) {
                StageNote("Your 40 is built from what you watch and star. Watch a few channels, or star them, and they appear here.")
                Spacer(Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = grid,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(m.lineup, key = { _, item -> item.channel.primary.uid }) { i, item ->
                        LineupTile(item, now, isFocused("lineup", i), onLongClick = { onLongClick(i) }) { act("lineup", i) }
                    }
                }
            }
            StageNote("OK watches. Hold OK to move, take out or never see a channel again.")
        }
        StagePanel("Kept out", null, Modifier.width(side).fillMaxHeight()) {
            val never = settings.never[playlist.id].orEmpty()
            val records = settings.weeds[playlist.id].orEmpty()
            val dead = IptvWeedRules.dead(records)
            StageInfoRow("Never again", "Channels you threw out", count(never.size), icon = Icons.Rounded.VisibilityOff)
            StageDivider()
            StageInfoRow("Dead", "Failed three nights running", count(dead.size), icon = Icons.Rounded.Close)
            StageDivider()
            StageInfoRow("Loops and shopping", "Kept off the picks", count(m.hiddenFiller), icon = Icons.Rounded.Visibility)
            StageDivider()
            StageInfoRow("Unwatched", "Still in search", count((m.keptMerged - m.lineup.size).coerceAtLeast(0)), icon = Icons.Rounded.Search)
            if (dead.isNotEmpty()) {
                StageSubheading("Found dead", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                val names = content.channels.filter { it.key in dead }.map { it.name }.distinct().take(3)
                names.forEach { StageInfoRow(it, "Dropped after three nights offline", null, Modifier.alpha(0.6f)) }
            }
        }
    }
}

// ---- Pieces ---------------------------------------------------------------------------------------------------

internal enum class BadgeKind { Live, New, Plain, Dark }

internal class StageBadge(val text: String, val kind: BadgeKind)

internal fun kindLabel(entry: IptvEpgEntry): String? = when (entry.kind) {
    IptvProgrammeKind.FILM -> "Film"
    IptvProgrammeKind.SPORT -> "Sport"
    IptvProgrammeKind.NEWS -> "News"
    IptvProgrammeKind.KIDS -> "Children's"
    else -> null
}

/** The marks a row carries after its title: the episode, New, Live, Film. */
internal fun rowBadges(entry: IptvEpgEntry, live: Boolean = true): List<StageBadge> = buildList {
    entry.episode?.let { add(StageBadge(it, BadgeKind.Plain)) }
    if (entry.isNew) add(StageBadge("NEW", BadgeKind.New))
    if (live && entry.isLive) add(StageBadge("LIVE", BadgeKind.Live))
    if (entry.kind == IptvProgrammeKind.FILM) add(StageBadge("FILM", BadgeKind.Plain))
}

/** The marks on a picture: how far in it is, or when it starts. */
private fun topBadges(p: IptvWallProgramme, now: Long): List<StageBadge> = buildList {
    if (p.onNow(now)) {
        add(StageBadge("LIVE", BadgeKind.Live))
        add(StageBadge("${((now - p.entry.startMs) / 60_000L).coerceAtLeast(0)} MIN IN", BadgeKind.Dark))
    } else if (p.entry.startMs > now) {
        add(StageBadge("STARTS ${IptvStageRules.clock(p.entry.startMs)}", BadgeKind.New))
    }
    if (p.entry.kind == IptvProgrammeKind.FILM) add(StageBadge("FILM", BadgeKind.New))
}

@Composable
internal fun StageBadgeView(badge: StageBadge) {
    val tokens = MaterialTheme.nuvio
    val (bg, fg) = when (badge.kind) {
        BadgeKind.Live -> tokens.colors.accent to Color.White
        BadgeKind.New -> Color.White.copy(alpha = 0.92f) to Color(0xFF101014)
        BadgeKind.Plain -> Color.White.copy(alpha = 0.10f) to Color(0xFFDDE1E5)
        BadgeKind.Dark -> Color.Black.copy(alpha = 0.55f) to Color.White
    }
    Box(
        Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .then(if (badge.kind == BadgeKind.Plain) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(badge.text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = fg, maxLines = 1)
    }
}

/** Genre, year, length, episode: one row of plain marks under a title. */
@Composable
private fun MetaChips(p: IptvWallProgramme) {
    val art = rememberProgrammeArt(p)
    val year = p.entry.year ?: art?.year
    val items = listOfNotNull(
        kindLabel(p.entry)?.uppercase(),
        year?.toString(),
        IptvStageRules.length(p.entry.endMs - p.entry.startMs).uppercase(),
        p.entry.episode,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { StageBadgeView(StageBadge(it, BadgeKind.Plain)) }
        if (p.entry.isNew) StageBadgeView(StageBadge("NEW", BadgeKind.New))
    }
}

/** The programme's description: the guide's, else TMDB's; a line about it when neither has one. */
@Composable
private fun ProgrammeWords(p: IptvWallProgramme, lines: Int, fixed: Boolean = false) {
    val art = rememberProgrammeArt(p)
    val words = p.entry.desc ?: art?.overview
    // fork:live-tv-polish - fixed: always [lines] tall, so a picture sized by what is left does not move with the words.
    val min = if (fixed) lines else 1
    if (words != null) {
        StageSentence(words, lines, min)
    } else {
        StageSentence("${kindLabel(p.entry) ?: "On"} ${p.channel.primary.name}, ${IptvStageRules.clock(p.entry.startMs)} to ${IptvStageRules.clock(p.entry.endMs)}.", lines, min)
    }
}

/** The highlighted programme's picture, blurred, behind the whole stage. The light changes; nothing moves. */
@Composable
private fun AmbientLight(programme: IptvWallProgramme?) {
    val art = rememberProgrammeArt(programme)
    val sport = rememberSportArt(rememberSportInfo(programme?.entry?.title, programme?.entry?.kind))
    val picture = art?.picture ?: sport?.sides?.firstNotNullOfOrNull { it.picture } ?: IptvLogos.good(programme?.channel?.primary?.logo)
    Crossfade(targetState = picture, animationSpec = tween(700), label = "stage-ambient") { url ->
        if (url != null) {
            NuvioAsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.5f),
                contentScale = ContentScale.Crop,
            )
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0x4D08080C), Color(0x8C08080C), Color(0xD108080C))),
        ),
    )
}

@Composable
private fun StageStrip(items: List<Pair<String, ImageVector>>, selected: Int, focused: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, (label, icon) ->
            StageChip(label, i == selected, i == focused, check = false, icon = icon, height = 32.dp) { onPick(i) }
        }
    }
}

/** A glass section card with a heading, like a settings section; the body fills the rest. */
@Composable
internal fun StagePanel(title: String, note: String?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier.iptvGlass(NuvioGlass.Shape).clip(NuvioGlass.Shape)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

/** A right-hand panel about one programme: picture, marks, words, when, and the actions. */
@Composable
private fun DetailPanel(
    p: IptvWallProgramme?,
    now: Long,
    side: Dp,
    nextOf: ((IptvWallProgramme) -> IptvEpgEntry?)?,
    note: String,
    actions: @Composable ColumnScope.(IptvWallProgramme) -> Unit,
) {
    StagePanel(p?.entry?.title ?: "Nothing to show", p?.channel?.primary?.name, Modifier.width(side).fillMaxHeight()) {
        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (p != null) {
                StageArt(p, 150.dp, badges = topBadges(p, now), caption = true)
                MetaChips(p)
                ProgrammeWords(p, lines = 3)
                val then = nextOf?.invoke(p)
                StageNoteInline(
                    (if (p.entry.endMs <= now) "Shown ${IptvStageRules.clock(p.entry.startMs)} on ${p.channel.primary.name}."
                    else "${IptvStageRules.clock(p.entry.startMs)} to ${IptvStageRules.clock(p.entry.endMs)} on ${p.channel.primary.name}.") +
                        (then?.let { " Then ${it.title} at ${IptvStageRules.clock(it.startMs)}." } ?: ""),
                )
                Spacer(Modifier.weight(1f))
                actions(p)
                StageNoteInline(note)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A list of fixed-height rows with a hairline between them, scrolled to keep the highlight in view. */
@Composable
internal fun ColumnScope.StageList(size: Int, focusedIndex: Int?, empty: String, row: @Composable (Int) -> Unit) {
    if (size == 0) {
        StageNote(empty)
        Spacer(Modifier.weight(1f))
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(focusedIndex) {
        if (focusedIndex != null) {
            val visible = state.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: 0
            if (focusedIndex < first) state.scrollToItem(focusedIndex)
            else if (focusedIndex >= last) state.scrollToItem((focusedIndex - (visible.size - 2)).coerceAtLeast(0))
        }
    }
    LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed((0 until size).toList()) { i, _ ->
            if (i > 0) StageDivider()
            row(i)
        }
    }
}

@Composable
internal fun StageDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
}

@Composable
internal fun StageRow(
    lead: String?,
    title: String,
    sub: String?,
    trailing: String?,
    focused: Boolean,
    thumb: IptvWallProgramme? = null,
    thumbWidth: Dp = 96.dp,
    badges: List<StageBadge> = emptyList(),
    dim: Boolean = false,
    selected: Boolean = false,
    leadWidth: Dp = 52.dp,
    trailingIcon: ImageVector? = null,
    live: Boolean = false,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StageRowHeight)
            .alpha(if (dim) 0.55f else 1f)
            .background(
                when {
                    focused || hovered -> Color.White.copy(alpha = 0.09f)
                    selected -> tokens.colors.accent.copy(alpha = 0.20f)
                    else -> Color.Transparent
                },
                if (focused || hovered) shape else RoundedCornerShape(0.dp),
            )
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .stageSecondary(onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (lead != null) {
            Text(lead, modifier = Modifier.width(leadWidth), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1)
        }
        if (thumb != null) {
            Box(Modifier.width(thumbWidth)) { StageArt(thumb, thumbWidth * 9f / 16f, radius = 7.dp, small = true) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                badges.take(2).forEach { StageBadgeView(it) }
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) ProgressLine(progress, height = 2.dp)
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(tokens.colors.accent))
                if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(14.dp))
                Text(trailing, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** A row of words only, for the side panels' facts. */
@Composable
private fun StageInfoRow(title: String, sub: String, trailing: String?, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier.fillMaxWidth().height(StageRowHeight).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun StageChip(
    label: String,
    on: Boolean,
    focused: Boolean,
    check: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(height)
            .iptvGlass(shape, raised = focused || hovered, selected = on && !hovered, selectedTint = tokens.colors.accent.copy(alpha = 0.42f))
            .then(if (on) Modifier.border(1.dp, tokens.colors.accent.copy(alpha = 0.85f), shape) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = if (on) tokens.colors.textPrimary else tokens.colors.textMuted, modifier = Modifier.size(15.dp))
            on && check -> Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(14.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) tokens.colors.textPrimary else tokens.colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun StageButton(
    label: String,
    accent: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    val lit = accent && enabled
    Row(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .iptvGlass(shape, raised = focused || (hovered && enabled), selected = lit && !hovered, selectedTint = tokens.colors.accent.copy(alpha = 0.42f))
            .then(if (lit) Modifier.border(1.dp, tokens.colors.accent.copy(alpha = 0.85f), shape) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .stageSecondary(if (enabled) onClick else null)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of the Three things: picture with its marks, what it is, words, why, and the action. All three the same size. */
@Composable
private fun StageCard(
    label: String,
    programme: IptvWallProgramme?,
    now: Long,
    empty: String,
    reason: String,
    focused: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    Column(
        modifier = modifier
            .iptvGlass(shape, raised = focused || (hovered && programme != null))
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = null, enabled = programme != null, onClick = onClick)
            .stageSecondary(if (programme != null) onClick else null)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // fork:live-tv-polish - the picture takes the room the words do not need, so the card has no empty gap.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            StageArt(programme, maxHeight, badges = programme?.let { topBadges(it, now) }.orEmpty(), caption = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageLabel(label)
            Spacer(Modifier.weight(1f))
            Text(programme?.channel?.primary?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (programme == null) {
            StageSentence(empty)
        } else {
            Text(programme.entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaChips(programme)
            ProgrammeWords(programme, lines = 4, fixed = true)
            if (programme.onNow(now)) ProgressLine(programme.entry.progress(now)) else Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = tokens.colors.textSecondary, modifier = Modifier.size(14.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val begun = programme.entry.startMs <= now + 60_000L
            StageButton(
                if (begun) "Watch now" else "Tune in at ${IptvStageRules.clock(programme.entry.startMs)}",
                accent = focused,
                focused = false,
                icon = if (begun) Icons.Rounded.PlayArrow else Icons.Rounded.Schedule,
                onClick = onClick,
            )
        }
    }
}

/**
 * A programme's picture (film backdrop, the guide's picture, series art), else the channel's logo on a tint of its own,
 * else the channel's name. Always the same height, with any marks laid over its top corner.
 */
@Composable
internal fun StageArt(
    programme: IptvWallProgramme?,
    height: Dp,
    badges: List<StageBadge> = emptyList(),
    caption: Boolean = false,
    radius: Dp = 10.dp,
    small: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(radius)
    val name = programme?.channel?.primary?.name.orEmpty()
    val hue = ((name.hashCode() % 360) + 360) % 360
    val art = if (programme != null && programme.entry.title.isNotEmpty()) rememberProgrammeArt(programme) else null
    val picture = art?.picture
    val logo = IptvLogos.good(programme?.channel?.primary?.logo)
    // fork:live-tv-sport-art - a fixture with no picture of its own shows its two sides instead of the channel's logo.
    val sport = rememberSportArt(rememberSportInfo(programme?.entry?.title, programme?.entry?.kind))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(
                if (programme == null) {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.03f)))
                } else {
                    Brush.radialGradient(listOf(Color.hsv(hue.toFloat(), 0.5f, 0.38f), Color.hsv(hue.toFloat(), 0.35f, 0.14f)))
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> {
                NuvioAsyncImage(model = picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (!small) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
                }
            }
            sport != null && (sport.anyPicture || logo == null) ->
                IptvMatchup(sport, crest = if (small) height * 0.5f else (height * 0.36f).coerceAtMost(120.dp), names = !small && height >= 140.dp)
            logo != null -> NuvioAsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(if (small) 0.7f else 0.45f).height(height * if (small) 0.6f else 0.45f),
                contentScale = ContentScale.Fit,
            )
            programme != null -> Text(
                name.ifBlank { "Live TV" },
                modifier = Modifier.padding(horizontal = 8.dp),
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> Icon(Icons.Rounded.Mic, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(if (small) 16.dp else 32.dp))
        }
        if (badges.isNotEmpty()) {
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.take(3).forEach { StageBadgeView(it) }
            }
        }
        val captionText = art?.caption
        if (caption && captionText != null && picture != null) {
            Text(
                captionText,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun LineupTile(item: IptvStageChannel, now: Long, focused: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    val name = item.channel.primary.name.ifBlank { "Channel ${item.channel.primary.num}" }
    val logo = IptvLogos.good(item.channel.primary.logo)
    val hue = ((name.hashCode() % 360) + 360) % 360
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .iptvGlass(shape, raised = focused || hovered)
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            .stageSecondary(onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.height(20.dp).width(44.dp).clip(RoundedCornerShape(5.dp)).background(Color.hsv(hue.toFloat(), 0.4f, 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) {
                    NuvioAsyncImage(model = logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
                } else {
                    Text(name, modifier = Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.number.toString(), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, maxLines = 1)
        }
        Text(
            item.now?.title ?: "No guide",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (item.now != null) tokens.colors.textPrimary else tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressLine(item.now?.progress(now) ?: 0f, height = 2.dp)
    }
}

@Composable
private fun StageField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    voiceOwner: String,
    focused: Boolean,
    onEditing: () -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    height: Dp = 44.dp,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, NuvioGlass.Shape) else Modifier),
    ) {
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onEditing() },
            placeholder = placeholder,
            focusRequester = focusRequester,
            onImeAction = onSubmit,
            leadingContent = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp)) },
            trailingContent = {
                com.nuvio.app.core.ui.VoiceSearchButton(
                    owner = voiceOwner,
                    focusField = { runCatching { focusRequester.requestFocus() } },
                    tint = tokens.colors.textSecondary,
                    size = 26.dp,
                    iconSize = 18.dp,
                )
            },
        )
        com.nuvio.app.core.ui.VoiceSearchCommands(owner = voiceOwner, text = value, onTextChange = onValueChange)
    }
}

@Composable
private fun StageLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1)
}

@Composable
internal fun StageSubheading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.nuvio.colors.textPrimary)
}

@Composable
private fun StageMeta(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StageSentence(text: String, lines: Int = 3, minLines: Int = 1) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary.copy(alpha = 0.88f), maxLines = lines, minLines = minLines.coerceAtMost(lines), overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun StageNote(text: String) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
private fun StageNoteInline(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
internal fun StageMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted)
    }
}

/** fork:live-tv-polish - a right click on a stage item: set just before its click runs, read by the stage's tap. */
internal object IptvRightClick {
    private var armedAt = 0L

    fun arm() {
        armedAt = System.currentTimeMillis()
    }

    /** True once, right after a right click; an item whose click never reaches the stage cannot leave it set. */
    fun take(): Boolean = (System.currentTimeMillis() - armedAt < 500L).also { armedAt = 0L }
}

/** fork:live-tv-polish - right-clicking an item does what holding OK on it does (its menu). */
internal fun Modifier.stageSecondary(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else secondaryClick {
        IptvRightClick.arm()
        onClick()
    }
