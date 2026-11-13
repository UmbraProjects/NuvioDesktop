package com.nuvio.app.features.tonight

import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.keyLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.smoothRoundClip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import kotlin.math.absoluteValue

/*
 * fork:tonight - phase 4 of design-notes/tonight-picker.md: Ends By and The Pile. Fixed row and
 * poster sizes (set by the screen), a window of rows or a 3 x 3 grid that scrolls by moving the window.
 */

private val EndsByRowHeight = 64.dp
private val EndsByLabelWidth = 280.dp

private fun windowStart(selected: Int, size: Int, visible: Int): Int =
    (selected - visible / 2).coerceIn(0, (size - visible).coerceAtLeast(0))

// ---- Ends By -----------------------------------------------------------------------------------

/** Room kept clear at the right of the timeline so its last hour label and BED are never cut off. */
private val EndsByTrackEndGap = 40.dp

@Composable
internal fun TonightEndsByView(
    titles: List<TonightTitle>,
    taste: TonightTaste,
    clock: TonightClock,
    settings: TonightSettings,
    minutesUntilBed: Int,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val rows = remember(titles, minutesUntilBed) { tonightEndsByRows(titles, taste, minutesUntilBed) }
    var selected by remember { mutableIntStateOf(0) }
    if (selected > rows.lastIndex) selected = rows.lastIndex.coerceAtLeast(0)
    val focus = rememberScreenFocus(Unit)
    val (axisStart, axisEnd) = tonightEndsByAxis(clock.minuteOfDay, minutesUntilBed)
    val span = (axisEnd - axisStart).toFloat()
    val chosen = rows.getOrNull(selected)?.title
    val glow = rememberGlowOf(chosen?.let { it.backdrop ?: it.poster }, lerp(accent, Color.White, 0.2f))

    // fork:phone-remote tonight - the rows as a list; a tap plays that row, ◀ ▶ move bedtime, ▲ ▼ the selection.
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.ENDS_BY,
            loading = loading && rows.isEmpty(),
            cards = rows.take(PHONE_ROWS).map { row ->
                val slot = when {
                    row.title.kind == TonightKind.Show -> "${row.episodesBeforeBed} before bed"
                    row.fits -> row.title.endsAtLabel(clock.minuteOfDay)?.let { "Ends $it" } ?: "Fits"
                    else -> "Past bed"
                }
                row.title.remoteCard(slot, "", clock, minutesUntilBed, flag = row.fits)
            },
            extra = "Bed at ${clockLabel(settings.bedMinuteOfDay)}",
            selected = if (rows.isEmpty()) 0 else selected + 1,
        ),
    ) { action, n ->
        if (action == com.nuvio.app.features.remote.RemoteTonightAction.Play && n > 0) {
            rows.getOrNull(n - 1)?.let { selected = n - 1; onPlay(it.title) }
            true
        } else false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(rememberColoursOf(chosen?.let { it.backdrop ?: it.poster }))
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Up -> if (selected > 0) selected -= 1
                    TonightKey.Down -> if (selected < rows.lastIndex) selected += 1
                    TonightKey.Left -> TonightSettingsRepository.nudgeBedtime(-1)
                    TonightKey.Right -> TonightSettingsRepository.nudgeBedtime(1)
                    TonightKey.Ok -> rows.getOrNull(selected)?.let { onPlay(it.title) }
                    TonightKey.Back -> onBack()
                }
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        Text("ENDS BY", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.height(22.dp))
        Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append("It's ${clockLabel(clock.minuteOfDay)}. Bed at ")
                    withStyle(SpanStyle(color = accent)) { append(clockLabel(settings.bedMinuteOfDay)) }
                    append(".")
                },
                color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text("best fit first", color = TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
        // The rows fill the page (user, Nov 12: it stopped after six), and the wheel runs down them.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .wheelSteps { step -> selected = (selected + step).coerceIn(0, rows.lastIndex.coerceAtLeast(0)) },
        ) {
            val trackWidth = maxWidth - EndsByLabelWidth - EndsByTrackEndGap
            fun xOf(minuteOfDay: Int) = EndsByLabelWidth + trackWidth * ((minuteOfDay - axisStart) / span).coerceIn(0f, 1f)
            val rowsTop = 36.dp
            val footer = 32.dp
            val visible = ((maxHeight - rowsTop - footer) / EndsByRowHeight).toInt().coerceAtLeast(1)
            val rowsHeight = EndsByRowHeight * visible
            val first = windowStart(selected, rows.size, visible)

            // Hour ticks along the top.
            var hour = axisStart
            while (hour <= axisEnd) {
                Text(
                    clockLabel(hour), color = TextFaint, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.offset(x = xOf(hour) - 24.dp).width(48.dp).height(20.dp),
                )
                hour += 60
            }
            // NOW and BED lines across every row.
            val nowX = xOf(clock.minuteOfDay)
            val bedX = xOf(clock.minuteOfDay + minutesUntilBed)
            Box(Modifier.offset(x = nowX - 1.dp, y = rowsTop).width(2.dp).height(rowsHeight).background(Color.White))
            Box(Modifier.offset(x = bedX - 1.dp, y = rowsTop).width(2.dp).height(rowsHeight).background(accent))
            Text("BED", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.offset(x = bedX + 6.dp, y = 14.dp))

            if (rows.isEmpty()) {
                Text(
                    if (loading) "Still gathering tonight's titles…" else "Nothing with a known length yet.",
                    color = TextDim, fontSize = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().offset(y = rowsTop + 120.dp),
                )
            }
            for (slot in 0 until visible) {
                val index = first + slot
                val row = rows.getOrNull(index) ?: continue
                val isSelected = index == selected
                val y = rowsTop + EndsByRowHeight * slot
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .offset(y = y)
                        .fillMaxWidth()
                        .height(EndsByRowHeight - 8.dp)
                        .zIndex(if (isSelected) 1f else 0f),
                ) {
                    // Chosen, the row glows in its title's colour instead of the accent border.
                    AmbientGlow(lit = isSelected, colour = glow, cornerRadius = 14.dp, spread = 4.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (row.fits) 1f else 0.45f }
                            .smoothRoundClip(14.dp)
                            .then(if (isSelected) Modifier.background(Ground).background(Color.White.copy(alpha = 0.05f)) else Modifier)
                            .clickable { selected = index; onPlay(row.title) },
                    ) {
                        Column(modifier = Modifier.width(EndsByLabelWidth).fillMaxHeight().padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
                            Text(row.title.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (row.title.kind) {
                                    TonightKind.Film -> "Film · ${lengthLabel(row.blockMinutes.sum())}" + if (row.title.resumeMinutesLeft != null) " left" else ""
                                    TonightKind.Show -> "Series · " + (row.title.nextEpisodeLabel?.let { "next: $it" } ?: "${row.blockMinutes.first()} min eps")
                                },
                                color = TextFaint, fontSize = 13.sp, maxLines = 1,
                            )
                        }
                        var start = clock.minuteOfDay
                        row.blockMinutes.forEachIndexed { i, minutes ->
                            val pastBed = row.title.kind == TonightKind.Show && i >= row.episodesBeforeBed
                            val left = xOf(start)
                            val width = (xOf(start + minutes) - left - 4.dp).coerceAtLeast(6.dp)
                            val isLast = i == row.blockMinutes.lastIndex || (row.title.kind == TonightKind.Show && i == row.episodesBeforeBed - 1)
                            Box(
                                modifier = Modifier
                                    .offset(x = left, y = 8.dp)
                                    .width(width)
                                    .height(40.dp)
                                    .smoothRoundClip(10.dp)
                                    .background(
                                        when {
                                            pastBed -> Color.White.copy(alpha = 0.08f)
                                            row.title.kind == TonightKind.Show -> Color(0xFF6FD3C9).copy(alpha = 0.35f)
                                            else -> accent.copy(alpha = if (isSelected) 0.6f else 0.35f)
                                        },
                                    ),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (isLast && !pastBed) {
                                    Text(
                                        row.endLabel(clock.minuteOfDay), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, modifier = Modifier.padding(end = 10.dp),
                                    )
                                }
                            }
                            start += minutes
                        }
                    }
                }
            }
            Text(
                if (rows.isEmpty()) "" else "${selected + 1} of ${rows.size} · faded rows run past bedtime",
                color = TextFaint, fontSize = 14.sp,
                modifier = Modifier.offset(y = rowsTop + rowsHeight + 8.dp).height(20.dp),
            )
        }
        TonightControls(hint = "▲▼ or the wheel choose · OK plays · ◀ ▶ moves bedtime") {
            TonightBackPill(onBack = onBack)
            GlassPill("◀ Bed earlier", selected = false, accent = accent, pill = true, onClick = { TonightSettingsRepository.nudgeBedtime(-1) })
            GlassPill("Bed later ▶", selected = false, accent = accent, pill = true, onClick = { TonightSettingsRepository.nudgeBedtime(1) })
            GlassPill("OK Play it", selected = true, accent = accent, enabled = chosen != null, pill = true, onClick = { chosen?.let(onPlay) })
        }
    }
    }
}

// ---- The Pile ----------------------------------------------------------------------------------

private const val PILE_COLUMNS = 3
private const val PILE_ROWS = 3
private val PileGap = 22.dp
/** Under each poster: its name (22) and how long it has waited (20), with a 10 gap above. */
private val PileInfoHeight = 52.dp

// The controls under the grid, after the nine posters' indexes.
private const val PILE_SEL_BACK = -1
private const val PILE_SEL_PLAY = -2
private const val PILE_SEL_LET_GO = -3

/** The most rows a list mode sends to the phone. */
private const val PHONE_ROWS = 30

@Composable
internal fun TonightPileView(
    titles: List<TonightTitle>,
    clock: TonightClock,
    minutesUntilBed: Int,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val letGo = remember { mutableStateListOf<String>() }
    val now = remember { System.currentTimeMillis() }
    val pile = remember(titles, letGo.size) { tonightPile(titles, now).filter { it.title.key !in letGo } }
    // The poster chosen; the controls act on it while the cursor is on them.
    var selected by remember { mutableIntStateOf(0) }
    if (selected > pile.lastIndex) selected = pile.lastIndex.coerceAtLeast(0)
    var onControl by remember { mutableStateOf<Int?>(null) }
    var confirming by remember(selected) { mutableStateOf(false) }
    val focus = rememberScreenFocus(Unit)
    val current = pile.getOrNull(selected)
    val totalRows = (pile.size + PILE_COLUMNS - 1) / PILE_COLUMNS
    val firstRow = windowStart(selected / PILE_COLUMNS, totalRows, PILE_ROWS)

    fun letGoFor(item: PileItem) {
        DiscoverRecommendationsRepository.markNotInterested(item.title.toMetaPreview())
        TonightPool.forget(item.title.key)
        letGo += item.title.key
        confirming = false
    }

    fun letItGo() {
        val item = current ?: return
        if (!confirming) {
            confirming = true
            return
        }
        letGoFor(item)
    }

    // fork:phone-remote tonight - the pile as a list: a tap plays a row, Let go (the phone asks first) lets that row go.
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.PILE,
            loading = loading && pile.isEmpty(),
            total = pile.size,
            cards = pile.take(PHONE_ROWS).map { it.title.remoteCard(pileAgeLabel(it.waitedDays), "", clock, minutesUntilBed) },
            selected = if (pile.isEmpty()) 0 else selected + 1,
            paused = confirming,
        ),
    ) { action, n ->
        when {
            action == com.nuvio.app.features.remote.RemoteTonightAction.Play && n > 0 -> {
                pile.getOrNull(n - 1)?.let { selected = n - 1; onPlay(it.title) }
                true
            }
            action == com.nuvio.app.features.remote.RemoteTonightAction.LetGo -> {
                pile.getOrNull((if (n > 0) n else selected + 1) - 1)?.let(::letGoFor)
                true
            }
            else -> false
        }
    }

    fun move(by: Int) {
        if (pile.isEmpty()) return
        selected = (selected + by).coerceIn(0, pile.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(rememberColoursOf(current?.title?.let { it.poster ?: it.backdrop }))
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                val control = onControl
                if (control != null) {
                    when (key) {
                        TonightKey.Left -> onControl = (control + 1).coerceAtMost(PILE_SEL_BACK)
                        TonightKey.Right -> onControl = (control - 1).coerceAtLeast(PILE_SEL_LET_GO)
                        TonightKey.Up -> { onControl = null; confirming = false }
                        TonightKey.Ok -> when (control) {
                            PILE_SEL_BACK -> onBack()
                            PILE_SEL_PLAY -> current?.let { onPlay(it.title) }
                            else -> letItGo()
                        }
                        TonightKey.Back -> if (confirming) confirming = false else onBack()
                        TonightKey.Down -> Unit
                    }
                    return@tonightKeys true
                }
                val col = selected % PILE_COLUMNS
                when (key) {
                    TonightKey.Left -> if (col > 0) move(-1)
                    TonightKey.Right -> if (col < PILE_COLUMNS - 1 && selected < pile.lastIndex) move(1)
                    TonightKey.Up -> if (selected >= PILE_COLUMNS) move(-PILE_COLUMNS)
                    TonightKey.Down -> if (selected + PILE_COLUMNS <= pile.lastIndex) move(PILE_COLUMNS)
                        else if (selected / PILE_COLUMNS < totalRows - 1) move(pile.lastIndex - selected)
                        else onControl = PILE_SEL_PLAY
                    TonightKey.Ok -> current?.let { onPlay(it.title) }
                    TonightKey.Back -> if (confirming) confirming = false else onBack()
                }
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        Text(
            if (pile.isEmpty()) "THE PILE" else "THE PILE · ${pile.size} THING${if (pile.size == 1) "" else "S"} YOU MEANT TO WATCH",
            color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.height(24.dp),
        )
        Spacer(Modifier.height(16.dp))
        // A centred 3 x 3 of posters (user, Nov 12), each with its name and wait under it; the chosen one's details
        // in a small panel at the top right. The wheel moves a row at a time.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .wheelSteps { step -> onControl = null; move(step * PILE_COLUMNS) },
        ) {
            val posterHeight = ((maxHeight - PileInfoHeight * PILE_ROWS - PileGap * (PILE_ROWS - 1)) / PILE_ROWS).coerceAtLeast(120.dp)
            val posterWidth = posterHeight * 2f / 3f
            if (pile.isEmpty()) {
                Text(
                    if (loading) "Still gathering tonight's titles…" else "Nothing on the pile: everything you added has been started.",
                    color = TextDim, fontSize = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(PileGap),
                ) {
                    for (r in 0 until PILE_ROWS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PileGap)) {
                            for (c in 0 until PILE_COLUMNS) {
                                val index = (firstRow + r) * PILE_COLUMNS + c
                                PileCell(
                                    item = pile.getOrNull(index),
                                    width = posterWidth,
                                    height = posterHeight,
                                    selected = index == selected && onControl == null,
                                    onHover = { selected = index; onControl = null },
                                    onClick = { pile.getOrNull(index)?.let { onPlay(it.title) } },
                                )
                            }
                        }
                    }
                }
            }
            PilePanel(current, clock, minutesUntilBed, accent, modifier = Modifier.align(Alignment.TopEnd))
        }
        TonightControls(
            hint = if (confirming) "OK again lets it go for good · Back keeps it"
            else "◀ ▶ ▲ ▼ or the wheel choose · OK finally watch it · ▼ to these buttons",
            hintColor = if (confirming) accent else TextFaint,
        ) {
            TonightBackPill(onBack = onBack, selected = onControl == PILE_SEL_BACK)
            GlassPill("OK Finally watch it", selected = onControl == PILE_SEL_PLAY, accent = accent, enabled = current != null, pill = true, onClick = { current?.let { onPlay(it.title) } })
            GlassPill(
                if (confirming) "Again to let it go for good" else "Let it go · off the pile for good",
                selected = onControl == PILE_SEL_LET_GO || confirming, accent = accent, enabled = current != null, pill = true,
                onClick = ::letItGo,
            )
        }
    }
    }
}

/** One poster on the pile with its name and wait beneath. An empty slot keeps its place, drawn as nothing. */
@Composable
private fun PileCell(item: PileItem?, width: Dp, height: Dp, selected: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val accent = MaterialTheme.nuvio.colors.accent
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered && item != null) onHover() }
    val art = item?.title?.let { it.poster ?: it.backdrop }
    val glow = rememberGlowOf(art, lerp(accent, Color.White, 0.2f))
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.width(width).zIndex(if (selected) 1f else 0f)) {
        Box(modifier = Modifier.size(width = width, height = height)) {
            if (item != null) {
                AmbientGlow(lit = selected, colour = glow, cornerRadius = 16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // The longer it has waited, the further it fades back (the old spines' dust).
                        .graphicsLayer { alpha = if (selected) 1f else 1f - item.dust * 0.45f }
                        .smoothRoundClip(16.dp)
                        .background(Ground)
                        .background(GlassFill)
                        .hoverable(hover)
                        .clickable(onClick = onClick),
                ) {
                    if (art != null) {
                        NuvioAsyncImage(model = art, contentDescription = item.title.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            item.title.name, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(12.dp),
                        )
                    }
                    if (!selected) Box(Modifier.fillMaxSize().smoothBorder(1.dp, GlassEdge, shape))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item?.title?.name.orEmpty(), color = if (selected) Color.White else Color(0xFFCFCCC3), fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(22.dp),
        )
        Text(
            item?.let { pileAgeLabel(it.waitedDays) }.orEmpty(), color = TextFaint, fontSize = 13.sp,
            maxLines = 1, modifier = Modifier.height(20.dp),
        )
    }
}

/** The chosen title's details, small, at the top right. */
@Composable
private fun PilePanel(item: PileItem?, clock: TonightClock, minutesUntilBed: Int, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(380.dp)
            .height(150.dp)
            .smoothRoundClip(18.dp)
            .background(GlassFill)
            .smoothBorder(1.dp, GlassEdge, shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        if (item == null) return@Column
        Text(
            if (item.waitedDays == 0) "ADDED TODAY" else "WAITING ${item.waitedDays} DAY${if (item.waitedDays == 1) "" else "S"}",
            color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, modifier = Modifier.height(20.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(item.title.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(32.dp))
        Text(item.title.tonightMetaLine(clock, minutesUntilBed), color = Color(0xFFCFCCC3), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(22.dp))
        Text(item.title.genres.take(3).joinToString(" · "), color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp))
    }
}
