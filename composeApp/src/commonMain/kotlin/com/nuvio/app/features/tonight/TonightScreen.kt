package com.nuvio.app.features.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/*
 * fork:tonight - phase 1 of design-notes/tonight-picker.md: the launcher, Three Questions and
 * Three Cards, with the bedtime chip. Shown in place of Home while Settings > Random Play >
 * "Open on Tonight" is on. Every box has a fixed size and slot (design rules): nothing moves when
 * a title has a longer name or a card is missing.
 */

internal val Ground = Color(0xFF0B0C12)
internal val GlassFill = Color.White.copy(alpha = 0.06f)
internal val GlassEdge = Color.White.copy(alpha = 0.14f)
internal val TextDim = Color(0xFFB9B6AD)
internal val TextFaint = Color(0xFF8F8C84)
internal val Side = 56.dp

private enum class TonightView { Launcher, Questions, Cards, CoinToss, Knockout, Flick, EndsBy, Pile, Map }

/** The wall clock, re-read every 20 s so "ends 23:06" and the bedtime filter stay true. */
internal data class TonightClock(val minuteOfDay: Int, val isoDate: String, val previousIsoDate: String, val dayName: String, val year: Int) {
    val night: String get() = tonightNightOf(isoDate, minuteOfDay, previousIsoDate)

    companion object {
        fun now(): TonightClock {
            val t = LocalDateTime.now()
            return TonightClock(
                minuteOfDay = t.hour * 60 + t.minute,
                isoDate = t.toLocalDate().toString(),
                previousIsoDate = t.toLocalDate().minusDays(1).toString(),
                dayName = t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase(),
                year = t.year,
            )
        }
    }
}

@Composable
fun TonightHost(
    topPadding: Dp,
    onPlay: (type: String, id: String) -> Unit,
    onOpenDetails: (type: String, id: String) -> Unit,
    onBrowseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    remember { TonightSettingsRepository.ensureLoaded() }
    val settings by TonightSettingsRepository.state.collectAsStateWithLifecycle()
    val poolState by TonightPool.state.collectAsStateWithLifecycle()
    val library by LibraryRepository.uiState.collectAsStateWithLifecycle()
    var clock by remember { mutableStateOf(TonightClock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = TonightClock.now()
            delay(20_000)
        }
    }
    // The library usually finishes loading after the app opens: gather again once it has.
    LaunchedEffect(library.isLoaded) { TonightPool.refresh(System.currentTimeMillis(), force = library.isLoaded) }

    val minutesUntilBed = tonightMinutesUntilBed(clock.minuteOfDay, settings.bedMinuteOfDay)
    // With bedtime (nearly) here, the filter would leave nothing; it simply stops filtering.
    val bedtimeFilters = minutesUntilBed >= 20
    val pool = remember(poolState.titles, minutesUntilBed, bedtimeFilters) {
        if (bedtimeFilters) poolState.titles.filter { it.fitsBedtime(minutesUntilBed) } else poolState.titles
    }
    val taste = remember(poolState.titles) { TonightTaste.from(poolState.titles) }

    var view by remember { mutableStateOf(TonightView.Launcher) }
    // Three Questions: the pool narrowed so far, the questions asked and the answers given.
    val asked = remember { mutableStateListOf<String>() }
    val answers = remember { mutableStateListOf<String>() }
    val narrowed = remember { mutableStateListOf<List<TonightTitle>>() }
    // Three Cards: the hand, and every title dealt this visit (a reshuffle never repeats one).
    var hand by remember { mutableStateOf<List<TonightCard>>(emptyList()) }
    val dealt = remember { mutableStateListOf<String>() }
    var dealtFrom by remember { mutableStateOf<List<TonightTitle>>(emptyList()) }

    fun deal(from: List<TonightTitle>, reshuffle: Boolean) {
        val seed = (clock.night + answers.joinToString("|")).hashCode().toLong() + if (reshuffle) dealt.size else 0
        hand = tonightDeal(from, taste, seed, exclude = if (reshuffle) dealt.toSet() else emptySet())
        if (!reshuffle) dealt.clear()
        dealt += hand.map { it.title.key }
        dealtFrom = from
        view = TonightView.Cards
    }

    fun startQuestions() {
        asked.clear(); answers.clear(); narrowed.clear()
        TonightSettingsRepository.setLastMode(TonightMode.ThreeQuestions)
        view = TonightView.Questions
    }

    val order = remember(clock.night, clock.year) { tonightQuestionOrder(clock.night, clock.year - 3) }
    val current = narrowed.lastOrNull() ?: pool
    val question = if (view == TonightView.Questions) tonightNextQuestion(order, asked, current) else null
    // Out of questions (three asked, or nothing left to split): deal from what is left.
    LaunchedEffect(view, question, poolState.loading) {
        if (view == TonightView.Questions && question == null && !poolState.loading && pool.isNotEmpty()) deal(current, reshuffle = false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ground)
            .background(
                Brush.radialGradient(
                    listOf(MaterialTheme.nuvio.colors.accent.copy(alpha = 0.10f), Color.Transparent),
                    radius = 1400f,
                ),
            )
            .padding(top = topPadding),
    ) {
        when (view) {
            TonightView.Launcher -> TonightLauncher(
                clock = clock,
                settings = settings,
                bedtimeFilters = bedtimeFilters,
                onOpen = { mode ->
                    when (mode) {
                        TonightMode.ThreeQuestions -> startQuestions()
                        TonightMode.ThreeCards -> {
                            TonightSettingsRepository.setLastMode(TonightMode.ThreeCards)
                            answers.clear()
                            deal(pool, reshuffle = false)
                        }
                        TonightMode.CoinToss -> {
                            TonightSettingsRepository.setLastMode(TonightMode.CoinToss)
                            view = TonightView.CoinToss
                        }
                        TonightMode.Knockout -> {
                            TonightSettingsRepository.setLastMode(TonightMode.Knockout)
                            view = TonightView.Knockout
                        }
                        TonightMode.ChannelFlick -> {
                            TonightSettingsRepository.setLastMode(TonightMode.ChannelFlick)
                            view = TonightView.Flick
                        }
                        TonightMode.TheMap -> {
                            TonightSettingsRepository.setLastMode(TonightMode.TheMap)
                            view = TonightView.Map
                        }
                        TonightMode.EndsBy -> {
                            TonightSettingsRepository.setLastMode(TonightMode.EndsBy)
                            view = TonightView.EndsBy
                        }
                        TonightMode.ThePile -> {
                            TonightSettingsRepository.setLastMode(TonightMode.ThePile)
                            view = TonightView.Pile
                        }
                        else -> Unit
                    }
                },
                onBrowseAll = onBrowseAll,
            )
            TonightView.Questions -> TonightQuestions(
                question = question,
                number = asked.size + 1,
                answers = answers,
                totalCount = pool.size,
                leftCount = current.size,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onAnswer = { q, side ->
                    asked += q.id
                    answers += when (side) {
                        TonightSide.Left -> q.left.label
                        TonightSide.Right -> q.right.label
                        TonightSide.Skip -> "Either"
                    }
                    narrowed += tonightApply(current, q, side)
                },
                onBack = {
                    if (asked.isEmpty()) {
                        view = TonightView.Launcher
                    } else {
                        asked.removeAt(asked.lastIndex)
                        answers.removeAt(answers.lastIndex)
                        if (narrowed.isNotEmpty()) narrowed.removeAt(narrowed.lastIndex)
                    }
                },
            )
            TonightView.CoinToss -> TonightCoinTossView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onDetails = { onOpenDetails(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            // Ends By and The Pile show everything (Ends By fades what runs past bedtime).
            TonightView.EndsBy -> TonightEndsByView(
                titles = poolState.titles,
                taste = taste,
                clock = clock,
                settings = settings,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Pile -> TonightPileView(
                titles = poolState.titles,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Map -> TonightMapView(
                pool = pool,
                clock = clock,
                settings = settings,
                minutesUntilBed = minutesUntilBed,
                bedtimeFilters = bedtimeFilters,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Flick -> TonightFlickView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Knockout -> TonightKnockoutView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { onPlay(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Cards -> TonightCards(
                hand = hand,
                answers = answers.filter { it != "Either" },
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                reshuffleLeft = settings.reshuffleUsedNight != clock.night,
                onReshuffle = {
                    TonightSettingsRepository.markReshuffleUsed(clock.night)
                    deal(dealtFrom, reshuffle = true)
                },
                onPlay = { card -> onPlay(card.title.type, card.title.id) },
                onDetails = { card -> onOpenDetails(card.title.type, card.title.id) },
                onBack = { view = TonightView.Launcher },
            )
        }
    }
    // A hand dealt before the pool arrived is dealt again once it has.
    LaunchedEffect(poolState.loadedAtEpochMs) {
        if (view == TonightView.Cards && hand.isEmpty() && pool.isNotEmpty()) deal(pool, reshuffle = false)
    }
}

// ---- Keys --------------------------------------------------------------------------------------

internal enum class TonightKey { Left, Right, Up, Down, Ok, Back }

internal fun Modifier.tonightKeys(handle: (TonightKey) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val key = when (event.key) {
        Key.DirectionLeft -> TonightKey.Left
        Key.DirectionRight -> TonightKey.Right
        Key.DirectionUp -> TonightKey.Up
        Key.DirectionDown -> TonightKey.Down
        Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> TonightKey.Ok
        Key.Escape, Key.Back, Key.Backspace -> TonightKey.Back
        else -> null
    } ?: return@onPreviewKeyEvent false
    handle(key)
}

private val OkKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)
private const val HOLD_MS = 500L

private class HoldState {
    var downAt = -1L
    var fired = false
}

/**
 * [tonightKeys], with OK acting on release so a hold can be told from a tap: held for half a
 * second it calls [onHoldOk] (once) instead of passing OK on.
 */
@Composable
internal fun Modifier.tonightKeysWithHold(onHoldOk: () -> Unit, handle: (TonightKey) -> Boolean): Modifier {
    val hold = remember { HoldState() }
    return onPreviewKeyEvent { event ->
        if (event.key !in OkKeys) return@onPreviewKeyEvent false
        val now = System.currentTimeMillis()
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (hold.downAt < 0) {
                    hold.downAt = now
                    hold.fired = false
                } else if (!hold.fired && now - hold.downAt >= HOLD_MS) {
                    hold.fired = true
                    onHoldOk()
                }
                true
            }
            KeyEventType.KeyUp -> {
                val fired = hold.fired || (hold.downAt >= 0 && now - hold.downAt >= HOLD_MS && run { onHoldOk(); true })
                hold.downAt = -1L
                hold.fired = false
                if (!fired) handle(TonightKey.Ok)
                true
            }
            else -> false
        }
    }.tonightKeys(handle)
}

@Composable
internal fun rememberScreenFocus(key: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
    return requester
}

// ---- Launcher ----------------------------------------------------------------------------------

private data class TileInfo(val mode: TonightMode, val title: String, val line: String)

private val Tiles = listOf(
    TileInfo(TonightMode.ThreeQuestions, "Three Questions", "Three taps, then three cards"),
    TileInfo(TonightMode.ThreeCards, "Three Cards", "Safe, new, wildcard. One reshuffle."),
    TileInfo(TonightMode.Knockout, "Knockout", "Eight titles, two at a time"),
    TileInfo(TonightMode.TheMap, "The Map", "Light or heavy, comfort or new"),
    TileInfo(TonightMode.EndsBy, "Ends By", "What fits before bed"),
    TileInfo(TonightMode.ChannelFlick, "Channel Flick", "Trailers, back to back"),
    TileInfo(TonightMode.CoinToss, "Coin Toss", "Nuvio picks; you veto or let it play"),
    TileInfo(TonightMode.ThePile, "The Pile", "Everything you meant to watch"),
)

private const val SEL_BROWSE = 8
private const val SEL_BED = 9

@Composable
private fun TonightLauncher(
    clock: TonightClock,
    settings: TonightSettings,
    bedtimeFilters: Boolean,
    onOpen: (TonightMode) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    var selected by remember { mutableIntStateOf(Tiles.indexOfFirst { it.mode == settings.lastMode }.coerceAtLeast(0)) }
    var lastTile by remember { mutableIntStateOf(selected) }
    var notYet by remember { mutableStateOf<String?>(null) }
    val focus = rememberScreenFocus(Unit)

    fun activate(index: Int) {
        when (index) {
            SEL_BROWSE -> onBrowseAll()
            SEL_BED -> Unit
            else -> {
                val tile = Tiles[index]
                if (tile.mode.built) onOpen(tile.mode) else notYet = "${tile.title} is coming in a later update."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                val s = selected
                when {
                    s == SEL_BED -> when (key) {
                        TonightKey.Left -> TonightSettingsRepository.nudgeBedtime(-1)
                        TonightKey.Right -> TonightSettingsRepository.nudgeBedtime(1)
                        TonightKey.Down -> selected = lastTile
                        else -> return@tonightKeys key != TonightKey.Back
                    }
                    s == SEL_BROWSE -> when (key) {
                        TonightKey.Up -> selected = lastTile
                        TonightKey.Ok -> activate(s)
                        else -> return@tonightKeys key != TonightKey.Back
                    }
                    else -> {
                        val row = s / 4
                        val col = s % 4
                        when (key) {
                            TonightKey.Left -> if (col > 0) selected = s - 1
                            TonightKey.Right -> if (col < 3) selected = s + 1
                            TonightKey.Up -> if (row == 0) selected = SEL_BED else selected = s - 4
                            TonightKey.Down -> if (row == 1) selected = SEL_BROWSE else selected = s + 4
                            TonightKey.Ok -> activate(s)
                            TonightKey.Back -> return@tonightKeys false
                        }
                        if (selected < 8) lastTile = selected
                        notYet = null
                    }
                }
                true
            }
            .padding(horizontal = Side, vertical = 40.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${clock.dayName} · ${clockLabel(clock.minuteOfDay)}",
                    color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp,
                )
            }
            BedtimeChip(
                settings = settings,
                active = bedtimeFilters,
                selected = selected == SEL_BED,
                onClick = { selected = SEL_BED },
            )
        }
        Text(
            "How shall we choose tonight?",
            color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.height(72.dp),
        )
        Spacer(Modifier.height(40.dp))
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0..3) {
                    val index = row * 4 + col
                    ModeTile(
                        info = Tiles[index],
                        selected = selected == index,
                        lastUsed = Tiles[index].mode == settings.lastMode,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onHover = { selected = index; lastTile = index },
                        onClick = { selected = index; lastTile = index; activate(index) },
                    )
                }
            }
            if (row == 0) Spacer(Modifier.height(24.dp))
        }
        // One fixed line under the tiles: the "not yet" note, else the key hint.
        Text(
            notYet ?: "OK starts a mode · Up sets bedtime",
            color = if (notYet != null) accent else TextFaint, fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(22.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GlassPill(
                text = "Just browse everything ▸",
                selected = selected == SEL_BROWSE,
                accent = accent,
                onClick = onBrowseAll,
            )
        }
    }
}

@Composable
private fun BedtimeChip(settings: TonightSettings, active: Boolean, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.nuvio.colors.accent
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(44.dp)
            .width(260.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else GlassFill)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            when {
                !active -> "Bedtime ${clockLabel(settings.bedMinuteOfDay)} · not filtering"
                selected -> "◀ Ends by ${clockLabel(settings.bedMinuteOfDay)} ▶"
                else -> "Ends by ${clockLabel(settings.bedMinuteOfDay)}"
            },
            color = Color.White, fontSize = 16.sp, maxLines = 1,
        )
    }
}

@Composable
private fun ModeTile(
    info: TileInfo,
    selected: Boolean,
    lastUsed: Boolean,
    accent: Color,
    modifier: Modifier,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Column(
        modifier = modifier
            .height(200.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else GlassFill)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .graphicsLayer { alpha = if (info.mode.built) 1f else 0.55f }
            .padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            Spacer(Modifier.weight(1f))
            val tag = when {
                !info.mode.built -> "COMING SOON"
                lastUsed -> "LAST TIME"
                else -> ""
            }
            Text(tag, color = if (lastUsed && info.mode.built) accent else TextFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        }
        Column {
            Text(info.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(info.line, color = TextDim, fontSize = 15.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun GlassPill(text: String, selected: Boolean, accent: Color, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else GlassFill)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else TextFaint, fontSize = 17.sp, maxLines = 1)
    }
}

// ---- Three Questions ---------------------------------------------------------------------------

@Composable
private fun TonightQuestions(
    question: TonightQuestion?,
    number: Int,
    answers: List<String>,
    totalCount: Int,
    leftCount: Int,
    loading: Boolean,
    onAnswer: (TonightQuestion, TonightSide) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val focus = rememberScreenFocus(question?.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                val q = question
                when (key) {
                    // One tap each: Left and Right answer at once; Down means either is fine.
                    TonightKey.Left -> if (q != null) onAnswer(q, TonightSide.Left)
                    TonightKey.Right -> if (q != null) onAnswer(q, TonightSide.Right)
                    TonightKey.Down -> if (q != null) onAnswer(q, TonightSide.Skip)
                    TonightKey.Back -> onBack()
                    TonightKey.Up, TonightKey.Ok -> Unit
                }
                true
            }
            .padding(horizontal = Side, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WHAT TONIGHT?", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            for (i in 1..TONIGHT_QUESTION_COUNT) {
                Box(
                    Modifier.padding(start = 10.dp).width(28.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                        when {
                            i < number -> accent
                            i == number -> Color.White
                            else -> Color.White.copy(alpha = 0.2f)
                        },
                    ),
                )
            }
            Text(
                "Question ${number.coerceAtMost(TONIGHT_QUESTION_COUNT)} of $TONIGHT_QUESTION_COUNT",
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(80.dp))
        Text(
            when {
                loading -> "Still gathering tonight's titles…"
                question == null -> "Dealing your cards…"
                else -> question.prompt
            },
            color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(84.dp),
        )
        Spacer(Modifier.height(56.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            AnswerCard(question?.left, "◀ LEFT", false, accent, Color(0xFFF5B544)) {
                question?.let { onAnswer(it, TonightSide.Left) }
            }
            AnswerCard(question?.right, "RIGHT ▶", false, accent, Color(0xFF6FD3C9)) {
                question?.let { onAnswer(it, TonightSide.Right) }
            }
        }
        Spacer(Modifier.height(28.dp))
        GlassPill(
            text = "▼ Either is fine",
            selected = false,
            accent = accent,
            enabled = question != null,
            onClick = { question?.let { onAnswer(it, TonightSide.Skip) } },
        )
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SO FAR", color = TextFaint, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
            for (i in 0 until TONIGHT_QUESTION_COUNT) {
                val answer = answers.getOrNull(i)
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .width(150.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .smoothBorder(1.dp, if (answer != null) GlassEdge.copy(alpha = 0.3f) else GlassEdge, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(answer ?: "?", color = if (answer != null) Color.White else TextFaint, fontSize = 14.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "$totalCount → $leftCount titles left · Back goes back a question",
                color = TextDim, fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun AnswerCard(answer: TonightAnswer?, keyLabel: String, selected: Boolean, accent: Color, keyColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(420.dp)
            .height(220.dp)
            .clip(shape)
            .background(GlassFill)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
            .clickable(enabled = answer != null, onClick = onClick)
            .padding(32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(keyLabel, color = keyColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(answer?.label ?: "", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(answer?.detail ?: "", color = Color(0xFFCFCCC3), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Three Cards -------------------------------------------------------------------------------

private const val SEL_RESHUFFLE = 3

@Composable
private fun TonightCards(
    hand: List<TonightCard>,
    answers: List<String>,
    clock: TonightClock,
    minutesUntilBed: Int,
    loading: Boolean,
    reshuffleLeft: Boolean,
    onReshuffle: () -> Unit,
    onPlay: (TonightCard) -> Unit,
    onDetails: (TonightCard) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    var selected by remember(hand) { mutableIntStateOf(if (hand.size >= 2) 1 else 0) }
    // Phase 3: holding OK on a card plays its trailer in place of the hand.
    var trailerOf by remember(hand) { mutableStateOf<TonightCard?>(null) }
    val focus = rememberScreenFocus(Unit)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .tonightKeysWithHold(
                onHoldOk = { if (selected != SEL_RESHUFFLE) trailerOf = hand.getOrNull(selected) },
            ) { key ->
                val s = selected
                val showing = trailerOf
                if (showing != null) {
                    when (key) {
                        TonightKey.Ok -> onPlay(showing)
                        TonightKey.Back -> trailerOf = null
                        else -> Unit
                    }
                    return@tonightKeysWithHold true
                }
                when (key) {
                    TonightKey.Left -> if (s in 1..2) selected = s - 1
                    TonightKey.Right -> if (s in 0..1 && s + 1 < hand.size) selected = s + 1
                    TonightKey.Up -> selected = SEL_RESHUFFLE
                    TonightKey.Down -> if (s == SEL_RESHUFFLE) selected = if (hand.size >= 2) 1 else 0 else hand.getOrNull(s)?.let(onDetails)
                    TonightKey.Ok -> if (s == SEL_RESHUFFLE) {
                        if (reshuffleLeft) onReshuffle()
                    } else {
                        hand.getOrNull(s)?.let(onPlay)
                    }
                    TonightKey.Back -> onBack()
                }
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TONIGHT'S HAND", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp)
                Text(
                    answers.joinToString(" · ").ifEmpty { "Straight from everything that fits before bed" },
                    color = TextFaint, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                )
            }
            GlassPill(
                text = if (reshuffleLeft) "Reshuffle · 1 left tonight" else "No reshuffles left tonight",
                selected = selected == SEL_RESHUFFLE,
                accent = accent,
                enabled = reshuffleLeft,
                onClick = onReshuffle,
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val showing = trailerOf
            if (showing != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${showing.slot.label()} · ${showing.title.name}",
                        color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(860.dp).height(22.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    TonightTrailerFrame(
                        title = showing.title,
                        modifier = Modifier.width(860.dp).height(484.dp),
                        onFinished = { trailerOf = null },
                        onReclaimFocus = { runCatching { focus.requestFocus() } },
                    )
                }
            } else if (hand.isEmpty()) {
                Text(
                    if (loading) "Still gathering tonight's titles…"
                    else "Nothing fits tonight yet. Add a few films or shows to your library, or move bedtime later.",
                    color = TextDim, fontSize = 22.sp, textAlign = TextAlign.Center,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0..2) {
                        val card = hand.getOrNull(i)
                        DealtCard(
                            card = card,
                            selected = selected == i,
                            accent = accent,
                            meta = card?.metaLine(clock, minutesUntilBed).orEmpty(),
                            onHover = { if (card != null) selected = i },
                            onClick = { if (card != null) onPlay(card) },
                        )
                    }
                }
            }
        }
        Text(
            if (trailerOf != null) "OK plays it · Back returns to the cards"
            else "◀ ▶ look at a card · OK plays · hold OK for the trailer · Down opens its page",
            color = TextFaint, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(22.dp),
        )
    }
}

private fun TonightCard.metaLine(clock: TonightClock, minutesUntilBed: Int): String {
    val t = title
    val parts = mutableListOf<String>()
    when (t.kind) {
        TonightKind.Film -> {
            t.year?.let { parts += it.toString() }
            t.minutesTonight()?.let { minutes ->
                parts += if (t.resumeMinutesLeft != null) "${lengthLabel(minutes)} left" else lengthLabel(minutes)
            }
            t.endsAtLabel(clock.minuteOfDay)?.let { parts += "ends $it" }
        }
        TonightKind.Show -> {
            parts += t.nextEpisodeLabel?.let { "Next: $it" } ?: "Series"
            t.runtimeMinutes?.let { minutes ->
                parts += "$minutes min eps"
                if (minutesUntilBed > 0) parts += "${tonightEpisodesBeforeBed(minutes, minutesUntilBed)} before bed"
            }
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun DealtCard(
    card: TonightCard?,
    selected: Boolean,
    accent: Color,
    meta: String,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(320.dp)) {
        Box(
            modifier = Modifier
                .size(width = 320.dp, height = 460.dp)
                // Grows a touch when chosen, without taking more room: nothing beside it moves.
                .graphicsLayer {
                    val s = if (selected) 1.04f else 1f
                    scaleX = s
                    scaleY = s
                    alpha = if (card == null) 0.35f else if (selected) 1f else 0.82f
                }
                .clip(shape)
                .background(GlassFill)
                .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
                .hoverable(hover)
                .clickable(enabled = card != null, onClick = onClick),
        ) {
            if (card == null) {
                Text(
                    "Nothing else fits tonight",
                    color = TextDim, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                val art = card.title.poster ?: card.title.backdrop
                if (art != null) {
                    NuvioAsyncImage(
                        model = art,
                        contentDescription = card.title.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.35f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                )
                Text(
                    card.slot.label(),
                    color = when (card.slot) {
                        TonightSlot.Safe -> Color(0xFF9CC7FF)
                        TonightSlot.New -> accent
                        TonightSlot.Wildcard -> Color(0xFF6FD3C9)
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                )
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(
                        card.title.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(card.why, color = Color(0xFFE8DCC8), fontSize = 15.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            meta,
            color = if (selected) Color.White else TextDim, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(22.dp),
        )
    }
}
