package com.nuvio.app.features.tonight

import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.features.remote.RemoteTonight
import com.nuvio.app.features.remote.RemoteTonightAction
import com.nuvio.app.features.remote.RemoteTonightCard
import com.nuvio.app.features.remote.RemoteTonightScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.core.ui.smoothRoundClip
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    onPlay: (type: String, id: String) -> Unit,
    onOpenDetails: (type: String, id: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    remember { TonightSettingsRepository.ensureLoaded() }
    // Trending titles carry TMDB's own id ("tmdb:111803"), which most metadata addons cannot open, so the details
    // page said "Failed to load" (Knockout's White Lotus). Swap it for the IMDb id first, as Discover's posters do.
    val openScope = rememberCoroutineScope()
    val play: (String, String) -> Unit = { type, id -> openScope.launch { val (t, i) = tonightOpenableId(type, id); onPlay(t, i) } }
    val openDetails: (String, String) -> Unit = { type, id -> openScope.launch { val (t, i) = tonightOpenableId(type, id); onOpenDetails(t, i) } }
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
        hand = tonightDeal(
            from, taste, seed,
            exclude = if (reshuffle) dealt.toSet() else emptySet(),
            pickFrom = settings.pickFromTop,
        )
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

    fun answer(q: TonightQuestion, side: TonightSide) {
        asked += q.id
        answers += when (side) {
            TonightSide.Left -> q.left.label
            TonightSide.Right -> q.right.label
            TonightSide.Skip -> "Either"
        }
        narrowed += tonightApply(current, q, side)
    }

    fun questionBack() {
        if (asked.isEmpty()) {
            view = TonightView.Launcher
        } else {
            asked.removeAt(asked.lastIndex)
            answers.removeAt(answers.lastIndex)
            if (narrowed.isNotEmpty()) narrowed.removeAt(narrowed.lastIndex)
        }
    }

    val reshuffleLeft = settings.reshuffleUsedNight != clock.night

    fun reshuffle() {
        if (settings.reshuffleUsedNight == clock.night) return
        TonightSettingsRepository.markReshuffleUsed(clock.night)
        deal(dealtFrom, reshuffle = true)
    }

    fun openThreeCards() {
        TonightSettingsRepository.setLastMode(TonightMode.ThreeCards)
        answers.clear()
        deal(pool, reshuffle = false)
    }

    fun openMode(mode: TonightMode) {
        when (mode) {
            TonightMode.ThreeQuestions -> startQuestions()
            TonightMode.ThreeCards -> openThreeCards()
            else -> {
                val next = when (mode) {
                    TonightMode.CoinToss -> TonightView.CoinToss
                    TonightMode.Knockout -> TonightView.Knockout
                    TonightMode.ChannelFlick -> TonightView.Flick
                    TonightMode.TheMap -> TonightView.Map
                    TonightMode.EndsBy -> TonightView.EndsBy
                    TonightMode.ThePile -> TonightView.Pile
                    else -> null
                } ?: return
                TonightSettingsRepository.setLastMode(mode)
                view = next
            }
        }
    }

    // A pad's B and the phone remote's Back arrive as the app's GoBack, not as a key: while a mode is open, Tonight
    // takes them (the screen showing runs its own Back); on the launcher they go on to the app as before.
    com.nuvio.app.core.ui.TabBackHandler(enabled = true) { TonightBackBus.press() }

    // fork:phone-remote tonight - the phone mirrors the launcher, Three Questions and Three Cards; its taps run here.
    // The other modes publish their own screens (MirrorOnPhone), so the host publishes nothing while one is open.
    val remoteOwner = remember { Any() }
    val loadingTitles = poolState.loading && poolState.titles.isEmpty()
    val remoteScreen = when (view) {
        TonightView.Launcher -> RemoteTonightScreen(
            RemoteTonightScreen.LAUNCHER,
            mode = settings.lastMode.name,
            loading = loadingTitles,
            // Every tile, in the TV's order: slot is the mode's name, flag says it is built (all are, Nov 11).
            cards = Tiles.map { RemoteTonightCard(slot = it.mode.name, name = it.title, why = it.line, meta = "", poster = null, flag = true) },
        )
        TonightView.Questions -> RemoteTonightScreen(
            view = RemoteTonightScreen.QUESTION,
            loading = loadingTitles || question == null,
            number = (asked.size + 1).coerceAtMost(TONIGHT_QUESTION_COUNT),
            total = TONIGHT_QUESTION_COUNT,
            countFrom = pool.size,
            countLeft = current.size,
            prompt = question?.prompt,
            leftLabel = question?.left?.label,
            leftDetail = question?.left?.detail,
            rightLabel = question?.right?.label,
            rightDetail = question?.right?.detail,
            answers = answers.toList(),
        )
        TonightView.Cards -> RemoteTonightScreen(
            view = RemoteTonightScreen.CARDS,
            loading = loadingTitles,
            reshuffleLeft = reshuffleLeft,
            answers = answers.filter { it != "Either" },
            cards = hand.map { card ->
                RemoteTonightCard(
                    slot = card.slot.label(),
                    name = card.title.name,
                    why = card.why,
                    meta = card.title.tonightMetaLine(clock, minutesUntilBed),
                    poster = card.title.poster ?: card.title.backdrop,
                )
            },
        )
        else -> null
    }
    val remoteHandler by rememberUpdatedState { action: RemoteTonightAction, number: Int ->
        when (action) {
            RemoteTonightAction.Open -> Unit // already open
            RemoteTonightAction.OpenQuestions -> startQuestions()
            RemoteTonightAction.OpenCards -> openThreeCards()
            RemoteTonightAction.Left -> question?.let { answer(it, TonightSide.Left) }
            RemoteTonightAction.Right -> question?.let { answer(it, TonightSide.Right) }
            RemoteTonightAction.Either -> question?.let { answer(it, TonightSide.Skip) }
            RemoteTonightAction.Back -> {
                if (view == TonightView.Questions) questionBack() else view = TonightView.Launcher
            }
            RemoteTonightAction.Play -> if (view == TonightView.Cards) hand.getOrNull(number - 1)?.let { play(it.title.type, it.title.id) }
            RemoteTonightAction.Details -> if (view == TonightView.Cards) hand.getOrNull(number - 1)?.let { openDetails(it.title.type, it.title.id) }
            RemoteTonightAction.Reshuffle -> if (view == TonightView.Cards) reshuffle()
            RemoteTonightAction.Mode -> Tiles.getOrNull(number - 1)?.let { openMode(it.mode) }
            // Older phone apps only send the above; the TV's keys belong to the modes (MirrorOnPhone).
            RemoteTonightAction.Up, RemoteTonightAction.Down, RemoteTonightAction.Ok, RemoteTonightAction.LetGo -> Unit
        }
        Unit
    }
    LaunchedEffect(remoteScreen) {
        if (remoteScreen != null) RemoteTonight.publish(remoteOwner, remoteScreen) { a, n -> remoteHandler(a, n) }
        else RemoteTonight.clear(remoteOwner)
    }
    DisposableEffect(Unit) { onDispose { RemoteTonight.clear(remoteOwner) } }
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
    ) {
        when (view) {
            TonightView.Launcher -> TonightLauncher(
                clock = clock,
                settings = settings,
                bedtimeFilters = bedtimeFilters,
                art = remember(pool, poolState.titles, clock.night, minutesUntilBed / 30) {
                    tonightTileArt(pool, poolState.titles, taste, clock.night.hashCode().toLong(), minutesUntilBed, System.currentTimeMillis())
                },
                onOpen = ::openMode,
                onBrowseAll = onClose,
            )
            TonightView.Questions -> TonightQuestions(
                question = question,
                number = asked.size + 1,
                answers = answers,
                totalCount = pool.size,
                leftCount = current.size,
                loading = poolState.loading && poolState.titles.isEmpty(),
                poolArt = remember(current) { tonightRepresentativeArt(current) },
                leftArt = remember(current, question) { question?.let { tonightRepresentativeArt(tonightApply(current, it, TonightSide.Left)) } },
                rightArt = remember(current, question) { question?.let { tonightRepresentativeArt(tonightApply(current, it, TonightSide.Right)) } },
                onAnswer = ::answer,
                onBack = ::questionBack,
            )
            TonightView.CoinToss -> TonightCoinTossView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { play(it.type, it.id) },
                onDetails = { openDetails(it.type, it.id) },
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
                onPlay = { play(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Pile -> TonightPileView(
                titles = poolState.titles,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { play(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Map -> TonightMapView(
                pool = pool,
                clock = clock,
                settings = settings,
                minutesUntilBed = minutesUntilBed,
                bedtimeFilters = bedtimeFilters,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { play(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Flick -> TonightFlickView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { play(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Knockout -> TonightKnockoutView(
                pool = pool,
                taste = taste,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                onPlay = { play(it.type, it.id) },
                onBack = { view = TonightView.Launcher },
            )
            TonightView.Cards -> TonightCards(
                hand = hand,
                answers = answers.filter { it != "Either" },
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                loading = poolState.loading && poolState.titles.isEmpty(),
                reshuffleLeft = reshuffleLeft,
                onReshuffle = ::reshuffle,
                onPlay = { card -> play(card.title.type, card.title.id) },
                onDetails = { card -> openDetails(card.title.type, card.title.id) },
                onBack = { view = TonightView.Launcher },
            )
        }
    }
    // A hand dealt before the pool arrived is dealt again once it has.
    LaunchedEffect(poolState.loadedAtEpochMs) {
        if (view == TonightView.Cards && hand.isEmpty() && pool.isNotEmpty()) deal(pool, reshuffle = false)
    }
}

/** A title as the phone remote shows it (fork:phone-remote tonight). */
internal fun TonightTitle.remoteCard(slot: String, why: String, clock: TonightClock, minutesUntilBed: Int, flag: Boolean = false): RemoteTonightCard =
    RemoteTonightCard(slot = slot, name = name, why = why, meta = tonightMetaLine(clock, minutesUntilBed), poster = poster ?: backdrop, flag = flag)

// ---- Keys --------------------------------------------------------------------------------------

internal enum class TonightKey { Left, Right, Up, Down, Ok, Back }

/**
 * Back that does not arrive as a key: a pad's B and the phone remote's Back go through the app's GoBack shortcut
 * (DesktopNavigationGestureBridge), which [TonightHost] claims with a TabBackHandler while a mode is open and
 * forwards here, to whichever Tonight screen is showing.
 */
internal object TonightBackBus {
    private val _presses = kotlinx.coroutines.flow.MutableSharedFlow<TonightKey>(extraBufferCapacity = 8)
    val presses: kotlinx.coroutines.flow.SharedFlow<TonightKey> = _presses

    /** Nov 11: the phone remote's mode buttons arrive here too, as the TV's own keys. */
    fun press(key: TonightKey = TonightKey.Back) {
        _presses.tryEmit(key)
    }
}

/**
 * fork:phone-remote tonight - a mode screen mirrored on the phone (user, Nov 11: "all picker modes"). [screen] is what
 * the phone shows; its taps go to [onAction] first (numbered rows, letting go), and anything it leaves (returns false
 * for) becomes the TV's own key, so the phone does exactly what the remote's arrows, OK and Back would.
 */
@Composable
internal fun MirrorOnPhone(screen: RemoteTonightScreen, onAction: (RemoteTonightAction, Int) -> Boolean = { _, _ -> false }) {
    val owner = remember { Any() }
    val latest by rememberUpdatedState(onAction)
    LaunchedEffect(screen) {
        RemoteTonight.publish(owner, screen) { action, number ->
            if (!latest(action, number)) {
                val key = when (action) {
                    RemoteTonightAction.Left -> TonightKey.Left
                    RemoteTonightAction.Right -> TonightKey.Right
                    RemoteTonightAction.Up -> TonightKey.Up
                    RemoteTonightAction.Down -> TonightKey.Down
                    RemoteTonightAction.Ok, RemoteTonightAction.Play -> TonightKey.Ok
                    RemoteTonightAction.Back -> TonightKey.Back
                    else -> null
                }
                if (key != null) TonightBackBus.press(key)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { RemoteTonight.clear(owner) } }
}

@Composable
internal fun Modifier.tonightKeys(passBack: Boolean = false, handle: (TonightKey) -> Boolean): Modifier {
    val latest by rememberUpdatedState(handle)
    LaunchedEffect(Unit) { TonightBackBus.presses.collect { latest(it) } }
    return onPreviewKeyEvent { event ->
        // Back acts when the key comes up: on the HTPC Esc's key-down never reached these screens (Nov 10), while
        // the rest of the app goes back on its key-up. Its key-down is still taken so nothing else acts on it too.
        if (event.key in BackKeys) {
            // The launcher leaves Back to the app, as before.
            if (passBack) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp) latest(TonightKey.Back)
            return@onPreviewKeyEvent event.type == KeyEventType.KeyUp || event.type == KeyEventType.KeyDown
        }
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val key = when (event.key) {
            Key.DirectionLeft -> TonightKey.Left
            Key.DirectionRight -> TonightKey.Right
            Key.DirectionUp -> TonightKey.Up
            Key.DirectionDown -> TonightKey.Down
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> TonightKey.Ok
            else -> null
        } ?: return@onPreviewKeyEvent false
        latest(key)
    }
}

private val BackKeys = setOf(Key.Escape, Key.Back, Key.Backspace)
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
    }.tonightKeys(handle = handle)
}

@Composable
internal fun rememberScreenFocus(key: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) { requester.requestFocusLogged() }
    return requester
}

// ---- Launcher ----------------------------------------------------------------------------------

private data class TileInfo(val mode: TonightMode, val title: String, val line: String)

private val Tiles = listOf(
    TileInfo(TonightMode.ThreeQuestions, "Three Questions", "Three taps, three cards"),
    TileInfo(TonightMode.ThreeCards, "Three Cards", "Safe, new, wildcard: one reshuffle"),
    TileInfo(TonightMode.Knockout, "Knockout", "Eight titles, two at a time"),
    TileInfo(TonightMode.TheMap, "The Map", "Light or heavy, comfort or new"),
    TileInfo(TonightMode.EndsBy, "Ends By", "What fits before bed"),
    TileInfo(TonightMode.ChannelFlick, "Channel Flick", "Trailers, back to back"),
    TileInfo(TonightMode.CoinToss, "Coin Toss", "Nuvio picks; you veto or let it play"),
    TileInfo(TonightMode.ThePile, "The Pile", "Everything you meant to watch"),
)

/**
 * Everything on the launcher but the tiles, top to bottom: padding 40 + 40, header row 64, title 76, subtitle 28,
 * gap 36, then the controls strip (TonightControls). Keep in step with TonightLauncher's layout.
 */
private val LauncherFixedHeight = 284.dp + TonightControlsHeight

private const val SEL_BROWSE = 8
private const val SEL_BED = 9

@Composable
private fun TonightLauncher(
    clock: TonightClock,
    settings: TonightSettings,
    bedtimeFilters: Boolean,
    art: Map<TonightMode, TonightTitle>,
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

    // Behind everything, a wash of the chosen tile's colours (user, Nov 12: like the Home ambient, not the picture),
    // and each tile glows in its own backdrop's colour (TonightAmbient).
    val colours = rememberBackdropColours(art.values.mapNotNull { it.backdrop }.distinct())
    fun coloursOf(mode: TonightMode): List<Color>? = art[mode]?.backdrop?.let { colours[it] }?.takeIf { it.isNotEmpty() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Tiles at the backdrops' own 16:9 (user, Nov 12: were ~2.2:1, cropping the art), unless the screen is too short
    // for two rows of that and everything else, when they give up height evenly. Set by the screen, never the content.
    val tileWidth = (maxWidth - Side * 2 - 24.dp * 3) / 4
    val roomForTiles = (maxHeight - LauncherFixedHeight - 24.dp) / 2
    val tileHeight = minOf(tileWidth * 9f / 16f, roomForTiles).coerceAtLeast(160.dp)
    TonightAmbientWash(Tiles.getOrNull(lastTile)?.let { coloursOf(it.mode) })
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                // Back on the launcher closes Tonight (user, Nov 10: there must always be a way out).
                if (key == TonightKey.Back) {
                    onBrowseAll()
                    return@tonightKeys true
                }
                val s = selected
                when {
                    s == SEL_BED -> when (key) {
                        TonightKey.Left -> TonightSettingsRepository.nudgeBedtime(-1)
                        TonightKey.Right -> TonightSettingsRepository.nudgeBedtime(1)
                        TonightKey.Down -> selected = lastTile
                        else -> Unit
                    }
                    s == SEL_BROWSE -> when (key) {
                        TonightKey.Up -> selected = lastTile
                        TonightKey.Ok -> activate(s)
                        else -> Unit
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
                            TonightKey.Back -> Unit
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
            Row(modifier = Modifier.weight(1f).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock.dayName, color = TextFaint, fontSize = 15.sp, letterSpacing = 3.sp)
                Text(
                    " · ${clockLabel(clock.minuteOfDay)}",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                )
                Spacer(Modifier.width(20.dp))
                Box(
                    Modifier.width(320.dp).height(1.dp).background(
                        Brush.horizontalGradient(listOf(GlassEdge, Color.Transparent)),
                    ),
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
            buildAnnotatedString {
                append("How shall we choose ")
                withStyle(SpanStyle(color = lerp(accent, Color.White, 0.45f))) { append("tonight?") }
            },
            color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.height(76.dp),
        )
        Text(
            "A little inspiration goes a long way.",
            color = TextDim, fontSize = 19.sp, maxLines = 1,
            modifier = Modifier.height(28.dp),
        )
        Spacer(Modifier.height(36.dp))
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
                for (col in 0..3) {
                    val index = row * 4 + col
                    ModeTile(
                        info = Tiles[index],
                        art = art[Tiles[index].mode],
                        glow = coloursOf(Tiles[index].mode)?.first()?.let(::glowColourOf) ?: lerp(accent, Color.White, 0.2f),
                        selected = selected == index,
                        lastUsed = Tiles[index].mode == settings.lastMode,
                        accent = accent,
                        modifier = Modifier.weight(1f).height(tileHeight),
                        onHover = { selected = index; lastTile = index },
                        onClick = { selected = index; lastTile = index; activate(index) },
                    )
                }
            }
            if (row == 0) Spacer(Modifier.height(24.dp))
        }
        // The shared strip (TonightChrome): the hint, a separator, and the way out straight beneath it.
        TonightControls(hint = notYet ?: "OK starts a mode · Up sets bedtime · Back closes", hintColor = if (notYet != null) accent else TextFaint) {
            GlassPill(
                text = "Just browse everything  →",
                selected = selected == SEL_BROWSE,
                accent = accent,
                onClick = onBrowseAll,
                pill = true,
            )
        }
        Spacer(Modifier.weight(1f))
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

/**
 * A mode tile: the backdrop of a title this mode would offer tonight (TonightTileArt), darkened from the left so the
 * name reads, with the tag, name, line and arrow in fixed slots. Chosen, it glows all round in [glow] (its backdrop's
 * colour) instead of a bright border. Without art it is the plain glass tile.
 */
@Composable
private fun ModeTile(
    info: TileInfo,
    art: TonightTitle?,
    glow: Color,
    selected: Boolean,
    lastUsed: Boolean,
    accent: Color,
    modifier: Modifier,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    // The chosen tile is drawn above its neighbours so its glow lies over the gaps, not under the next tile.
    Box(modifier = modifier.zIndex(if (selected) 1f else 0f)) {
    AmbientGlow(lit = selected, colour = glow, cornerRadius = 18.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Not .clip(): desktop clips are hard-edged and the corners stair-stepped (user, Nov 12).
            .smoothRoundClip(18.dp)
            // Opaque under the glass, so the glow behind never shows through a tile without art.
            .background(Ground)
            .background(GlassFill)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .graphicsLayer { alpha = if (info.mode.built) 1f else 0.55f },
    ) {
        art?.backdrop?.let { url ->
            NuvioAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (selected) 1f else 0.82f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Ground.copy(alpha = 0.88f),
                        0.55f to Ground.copy(alpha = 0.45f),
                        1f to Ground.copy(alpha = 0.10f),
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ground.copy(alpha = 0.75f)),
                ),
            )
        }
        // The glass edge only while not chosen: chosen, the bloom is the edge (user, Nov 12: the two fought).
        if (!selected) Box(Modifier.fillMaxSize().smoothBorder(1.dp, GlassEdge, shape))
        val tag = when {
            !info.mode.built -> "COMING SOON"
            lastUsed -> "LAST TIME"
            else -> ""
        }
        // Top left: the dark side of the tile, where the fade keeps it readable.
        Text(
            tag,
            color = if (lastUsed && info.mode.built) lerp(accent, Color.White, 0.4f) else TextFaint,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 22.dp, start = 26.dp).height(18.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 26.dp, end = 22.dp, bottom = 26.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(info.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(info.line, color = Color.White.copy(alpha = 0.78f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text("→", color = Color.White.copy(alpha = if (selected) 1f else 0.75f), fontSize = 22.sp, modifier = Modifier.height(24.dp))
        }
    }
    }
}

@Composable
internal fun GlassPill(text: String, selected: Boolean, accent: Color, onClick: () -> Unit, enabled: Boolean = true, pill: Boolean = false) {
    val shape = RoundedCornerShape(if (pill) 999.dp else 14.dp)
    Box(
        modifier = Modifier
            .height(if (pill) 54.dp else 48.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else GlassFill)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else GlassEdge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (pill) 36.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else TextFaint, fontSize = 17.sp, maxLines = 1)
    }
}

// ---- Three Questions ---------------------------------------------------------------------------

/** The backdrop that best stands for [titles]: the best-rated one that has a backdrop. */
internal fun tonightRepresentativeArt(titles: List<TonightTitle>): String? =
    titles.filter { !it.backdrop.isNullOrBlank() }.maxByOrNull { it.rating ?: 0.0 }?.backdrop

@Composable
private fun TonightQuestions(
    question: TonightQuestion?,
    number: Int,
    answers: List<String>,
    totalCount: Int,
    leftCount: Int,
    loading: Boolean,
    /** What is left now, and what each answer would leave, as one backdrop each (the wash previews an answer). */
    poolArt: String?,
    leftArt: String?,
    rightArt: String?,
    onAnswer: (TonightQuestion, TonightSide) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val focus = rememberScreenFocus(question?.id)
    // The answer under the mouse (the remote answers at once, so only the mouse ever leans).
    var leaning by remember(question?.id) { mutableStateOf<TonightSide?>(null) }
    val washArt = when (leaning) {
        TonightSide.Left -> leftArt ?: poolArt
        TonightSide.Right -> rightArt ?: poolArt
        else -> poolArt
    }

    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(rememberColoursOf(washArt))
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
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
            AnswerCard(
                answer = question?.left, keyLabel = "◀ LEFT", mirrored = false, keyColor = AnswerLeftColour,
                onHover = { leaning = if (it) TonightSide.Left else leaning.takeIf { s -> s != TonightSide.Left } },
            ) { question?.let { onAnswer(it, TonightSide.Left) } }
            AnswerCard(
                answer = question?.right, keyLabel = "RIGHT ▶", mirrored = true, keyColor = AnswerRightColour,
                onHover = { leaning = if (it) TonightSide.Right else leaning.takeIf { s -> s != TonightSide.Right } },
            ) { question?.let { onAnswer(it, TonightSide.Right) } }
        }
        TonightControls(hint = "◀ ▶ answer · ▼ either is fine · Back goes back a question") {
            TonightBackPill(onBack = onBack)
            GlassPill(
                text = "▼ Either is fine",
                selected = false,
                accent = accent,
                enabled = question != null,
                pill = true,
                onClick = { question?.let { onAnswer(it, TonightSide.Skip) } },
            )
        }
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
            Text("$totalCount → $leftCount titles left", color = TextDim, fontSize = 17.sp)
        }
    }
    }
}

private val AnswerLeftColour = Color(0xFFF5B544)
private val AnswerRightColour = Color(0xFF6FD3C9)

/**
 * One answer. The two mirror each other about the gap between them (user, Nov 12: symmetry): the key sits in the
 * outer top corner and the answer itself hugs the inner edge, so the left card's text is right-aligned. Glows in its
 * key colour while the mouse is over it.
 */
@Composable
private fun AnswerCard(
    answer: TonightAnswer?,
    keyLabel: String,
    mirrored: Boolean,
    keyColor: Color,
    onHover: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }
    val lit = hovered && answer != null
    val inner = if (mirrored) Alignment.Start else Alignment.End
    val innerText = if (mirrored) TextAlign.Start else TextAlign.End
    Box(modifier = Modifier.width(420.dp).height(220.dp).zIndex(if (lit) 1f else 0f)) {
        AmbientGlow(lit = lit, colour = keyColor, cornerRadius = 20.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .smoothRoundClip(20.dp)
                .background(Ground)
                .background(GlassFill)
                .then(if (lit) Modifier else Modifier.smoothBorder(1.dp, GlassEdge, shape))
                .hoverable(hover)
                .clickable(enabled = answer != null, onClick = onClick)
                .padding(32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                keyLabel, color = keyColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                answer?.label ?: "", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = innerText,
                modifier = Modifier.fillMaxWidth().align(inner),
            )
            Text(
                answer?.detail ?: "", color = Color(0xFFCFCCC3), fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = innerText,
                modifier = Modifier.fillMaxWidth().align(inner),
            )
        }
    }
}

// ---- Three Cards -------------------------------------------------------------------------------

// The controls under the cards (Nov 12: moved from the header and the foot to one strip beneath them).
private const val SEL_BACK = 3
private const val SEL_PAGE = 4
private const val SEL_RESHUFFLE = 5

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
    // The card the controls act on: the one chosen last, kept while the cursor is on the controls.
    var lastCard by remember(hand) { mutableIntStateOf(selected) }
    // Phase 3: holding OK on a card plays its trailer in place of the hand.
    var trailerOf by remember(hand) { mutableStateOf<TonightCard?>(null) }
    val focus = rememberScreenFocus(Unit)
    val focused = hand.getOrNull(lastCard)

    fun activate(s: Int) {
        when (s) {
            SEL_BACK -> onBack()
            SEL_PAGE -> focused?.let(onDetails)
            SEL_RESHUFFLE -> if (reshuffleLeft) onReshuffle()
            else -> hand.getOrNull(s)?.let(onPlay)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(rememberColoursOf(focused?.title?.let { it.backdrop ?: it.poster }))
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeysWithHold(
                onHoldOk = { if (selected < SEL_BACK) trailerOf = hand.getOrNull(selected) },
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
                val onControls = s >= SEL_BACK
                when (key) {
                    TonightKey.Left -> if (onControls) {
                        if (s > SEL_BACK) selected = s - 1
                    } else if (s in 1..2) selected = s - 1
                    TonightKey.Right -> if (onControls) {
                        if (s < SEL_RESHUFFLE) selected = s + 1
                    } else if (s in 0..1 && s + 1 < hand.size) selected = s + 1
                    TonightKey.Up -> if (onControls) selected = lastCard
                    TonightKey.Down -> if (!onControls) selected = SEL_PAGE
                    TonightKey.Ok -> activate(s)
                    TonightKey.Back -> onBack()
                }
                if (selected < SEL_BACK) lastCard = selected
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("TONIGHT'S HAND", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp)
            Text(
                answers.joinToString(" · ").ifEmpty { "Straight from everything that fits before bed" },
                color = TextFaint, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
        // A fixed box for the hand (or the trailer in its place), so the controls under it never move.
        Box(modifier = Modifier.fillMaxWidth().height(CardsAreaHeight), contentAlignment = Alignment.Center) {
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
                        onReclaimFocus = { focus.requestFocusLogged() },
                    )
                }
            } else if (hand.isEmpty()) {
                Text(
                    if (loading) "Still gathering tonight's titles…"
                    else "Nothing fits tonight yet. Add a few films or shows to your library, or move bedtime later.",
                    color = TextDim, fontSize = 22.sp, textAlign = TextAlign.Center,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
                    for (i in 0..2) {
                        val card = hand.getOrNull(i)
                        DealtCard(
                            card = card,
                            selected = selected == i,
                            accent = accent,
                            meta = card?.metaLine(clock, minutesUntilBed).orEmpty(),
                            onHover = { if (card != null) { selected = i; lastCard = i } },
                            onClick = { if (card != null) onPlay(card) },
                        )
                    }
                }
            }
        }
        TonightControls(
            hint = if (trailerOf != null) "OK plays it · Back returns to the cards"
            else "◀ ▶ look at a card · OK plays · hold OK for the trailer · ▼ to these buttons",
        ) {
            TonightBackPill(onBack = onBack, selected = selected == SEL_BACK)
            GlassPill(
                text = "Detail page",
                selected = selected == SEL_PAGE,
                accent = accent,
                enabled = focused != null,
                pill = true,
                onClick = { focused?.let(onDetails) },
            )
            GlassPill(
                text = if (reshuffleLeft) "Reshuffle · 1 left tonight" else "No reshuffles left tonight",
                selected = selected == SEL_RESHUFFLE,
                accent = accent,
                enabled = reshuffleLeft,
                pill = true,
                onClick = onReshuffle,
            )
        }
        Spacer(Modifier.weight(1f))
    }
    }
}

/** The hand's box: a slot heading (22 + 14), a card (436) and its meta line (16 + 22), with room for the trailer's label and frame (518). */
private val CardsAreaHeight = 520.dp

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
    val art = card?.title?.let { it.poster ?: it.backdrop }
    // Chosen, it glows in its own poster's colour (the launcher's highlight) instead of an accent border.
    val glow = rememberGlowOf(art, lerp(accent, Color.White, 0.2f))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(320.dp).zIndex(if (selected) 1f else 0f)) {
        // The slot is a heading above the poster, not printed on it (user, Nov 12), in a fixed slot so a missing
        // card leaves the row level.
        Text(
            card?.slot?.label().orEmpty(),
            color = when (card?.slot) {
                TonightSlot.Safe -> Color(0xFF9CC7FF)
                TonightSlot.New -> accent
                TonightSlot.Wildcard -> Color(0xFF6FD3C9)
                null -> Color.Transparent
            }.let { if (selected) it else lerp(it, TextDim, 0.35f) },
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(22.dp),
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .size(width = 320.dp, height = 436.dp)
                // Grows a touch when chosen, without taking more room: nothing beside it moves.
                .graphicsLayer {
                    val s = if (selected) 1.04f else 1f
                    scaleX = s
                    scaleY = s
                },
        ) {
            AmbientGlow(lit = selected && card != null, colour = glow, cornerRadius = 20.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The dimming is on the poster only: an alpha on the outer layer clips to the card's square
                    // bounds, so the bloom fading out after a move survived only in the corners (user, Nov 12).
                    .graphicsLayer { alpha = if (card == null) 0.35f else if (selected) 1f else 0.82f }
                    .smoothRoundClip(20.dp)
                    .background(Ground)
                    .background(GlassFill)
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
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text(
                            card.title.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(card.why, color = Color(0xFFE8DCC8), fontSize = 15.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!selected) Box(Modifier.fillMaxSize().smoothBorder(1.dp, GlassEdge, shape))
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


/** A title's id as the details page and the addons expect it: a `tmdb:` id becomes its IMDb id when TMDB knows one. */
internal suspend fun tonightOpenableId(type: String, id: String): Pair<String, String> {
    val tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
        ?: return type to id
    val imdb = runCatching {
        com.nuvio.app.features.tmdb.TmdbService.tmdbToImdb(tmdbId, if (type == "series") "tv" else "movie")
    }.getOrNull()
    return type to (imdb ?: id)
}
