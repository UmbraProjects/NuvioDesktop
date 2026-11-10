package com.nuvio.app.features.tonight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
 * spine sizes, a fixed window of rows (the list scrolls by moving the window, never by resizing).
 */

private const val ENDS_BY_VISIBLE_ROWS = 6
private val EndsByRowHeight = 64.dp
private val EndsByLabelWidth = 280.dp

private val SpineColors = listOf(
    Color(0xFF3A5A7A), Color(0xFF7A4F5C), Color(0xFF3F7D6E), Color(0xFFA8743A),
    Color(0xFF5A6470), Color(0xFF4A3A2A), Color(0xFF2A3A5A), Color(0xFF5A3A5A),
)

private fun windowStart(selected: Int, size: Int, visible: Int): Int =
    (selected - visible / 2).coerceIn(0, (size - visible).coerceAtLeast(0))

// ---- Ends By -----------------------------------------------------------------------------------

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
    val first = windowStart(selected, rows.size, ENDS_BY_VISIBLE_ROWS)

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text("◀ ▶ moves bedtime · best fit first", color = TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val trackWidth = maxWidth - EndsByLabelWidth
            fun xOf(minuteOfDay: Int) = EndsByLabelWidth + trackWidth * ((minuteOfDay - axisStart) / span).coerceIn(0f, 1f)
            val rowsTop = 36.dp
            val rowsHeight = EndsByRowHeight * ENDS_BY_VISIBLE_ROWS

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
            for (slot in 0 until ENDS_BY_VISIBLE_ROWS) {
                val row = rows.getOrNull(first + slot) ?: continue
                val isSelected = first + slot == selected
                val y = rowsTop + EndsByRowHeight * slot
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .offset(y = y)
                        .fillMaxWidth()
                        .height(EndsByRowHeight - 8.dp)
                        .graphicsLayer { alpha = if (row.fits) 1f else 0.45f }
                        .clip(shape)
                        .then(if (isSelected) Modifier.background(Color.White.copy(alpha = 0.05f)).smoothBorder(2.dp, accent, shape) else Modifier)
                        .clickable { selected = first + slot; onPlay(row.title) },
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
                                .clip(RoundedCornerShape(10.dp))
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
            Text(
                if (rows.isEmpty()) "" else "${selected + 1} of ${rows.size} · ▲▼ choose · OK plays · faded rows run past bedtime",
                color = TextFaint, fontSize = 14.sp,
                modifier = Modifier.offset(y = rowsTop + rowsHeight + 12.dp).height(20.dp),
            )
        }
    }
}

// ---- The Pile ----------------------------------------------------------------------------------

private const val PILE_VISIBLE = 9

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
    var selected by remember { mutableIntStateOf(0) }
    if (selected > pile.lastIndex) selected = pile.lastIndex.coerceAtLeast(0)
    var confirming by remember(selected) { mutableStateOf(false) }
    val focus = rememberScreenFocus(Unit)
    val current = pile.getOrNull(selected)
    val first = windowStart(selected, pile.size, PILE_VISIBLE)

    fun letItGo() {
        val item = current ?: return
        if (!confirming) {
            confirming = true
            return
        }
        DiscoverRecommendationsRepository.markNotInterested(item.title.toMetaPreview())
        TonightPool.forget(item.title.key)
        letGo += item.title.key
        confirming = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Up -> if (selected > 0) selected -= 1
                    TonightKey.Down -> if (selected < pile.lastIndex) selected += 1
                    TonightKey.Ok -> current?.let { onPlay(it.title) }
                    TonightKey.Left -> letItGo()
                    TonightKey.Right -> confirming = false
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
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.width(720.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pile.isEmpty()) {
                    Text(
                        if (loading) "Still gathering tonight's titles…" else "Nothing on the pile: everything you added has been started.",
                        color = TextDim, fontSize = 22.sp, modifier = Modifier.padding(top = 120.dp),
                    )
                }
                for (slot in 0 until PILE_VISIBLE) {
                    val item = pile.getOrNull(first + slot)
                    if (item == null) {
                        Spacer(Modifier.height(56.dp))
                        continue
                    }
                    Spine(
                        item = item,
                        selected = first + slot == selected,
                        accent = accent,
                        onClick = { selected = first + slot },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            PilePanel(current, clock, minutesUntilBed, confirming, accent, onPlay = { current?.let { onPlay(it.title) } }, onLetGo = ::letItGo)
        }
        Text(
            "▲▼ run down the spines · OK finally watch it · ◀ let it go (press twice)",
            color = TextFaint, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().height(20.dp),
        )
    }
}

@Composable
private fun Spine(item: PileItem, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val hash = item.title.key.hashCode()
    // A slightly uneven stack: each spine sits a few dp left or right, the chosen one pulled out.
    val nudge = ((hash.absoluteValue % 5) * 3).dp
    val color = SpineColors[hash.absoluteValue % SpineColors.size]
    Box(
        modifier = Modifier
            .offset(x = if (selected) 64.dp else nudge)
            .width(620.dp)
            .height(50.dp)
            .graphicsLayer { alpha = if (selected) 1f else 1f - item.dust * 0.6f }
            .clip(shape)
            .background(color)
            .smoothBorder(if (selected) 2.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick),
    ) {
        if (item.dust > 0f && !selected) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 7.dp.toPx()
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(Color(0xFFDCD2BE).copy(alpha = 0.35f * item.dust), radius = 1.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
        }
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(pileAgeLabel(item.waitedDays), color = Color(0xFFCFCCC3), fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun PilePanel(
    item: PileItem?,
    clock: TonightClock,
    minutesUntilBed: Int,
    confirming: Boolean,
    accent: Color,
    onPlay: () -> Unit,
    onLetGo: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(440.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(GlassFill)
            .smoothBorder(1.dp, GlassEdge, shape)
            .padding(28.dp),
    ) {
        if (item == null) return@Column
        Text(
            if (item.waitedDays == 0) "ADDED TODAY" else "WAITING ${item.waitedDays} DAY${if (item.waitedDays == 1) "" else "S"}",
            color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, modifier = Modifier.height(20.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.height(180.dp)) {
            Box(Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f))) {
                val art = item.title.poster ?: item.title.backdrop
                if (art != null) {
                    NuvioAsyncImage(model = art, contentDescription = item.title.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(item.title.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(item.title.tonightMetaLine(clock, minutesUntilBed), color = Color(0xFFCFCCC3), fontSize = 16.sp, maxLines = 1, modifier = Modifier.height(24.dp))
        Text(item.title.genres.take(3).joinToString(" · "), color = TextDim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.height(22.dp))
        Spacer(Modifier.weight(1f))
        GlassPill("OK · Finally watch it", selected = true, accent = accent, onClick = onPlay)
        Spacer(Modifier.height(10.dp))
        GlassPill(
            if (confirming) "◀ again to let it go for good" else "◀ Let it go · off the pile for good",
            selected = confirming, accent = accent, onClick = onLetGo,
        )
    }
}
