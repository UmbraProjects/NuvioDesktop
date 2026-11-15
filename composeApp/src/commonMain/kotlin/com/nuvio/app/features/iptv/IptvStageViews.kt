package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

/*
 * fork:live-tv-views - the screens of the six extra stages (rules in IptvViews.kt). They draw with the stage's own
 * pieces (StagePanel, StageArt, StageRow) and take the stage's highlight (isFocused) and taps (act), so the remote,
 * the strip and Back work as on every other stage. Each tile has a fixed size; nothing moves with what is in it.
 */

private val TileShape = RoundedCornerShape(10.dp)

/** A programme tile: its art (or two sides, or the channel's logo), its title and one line under it. */
@Composable
private fun ProgrammeTile(
    p: IptvWallProgramme,
    now: Long,
    artHeight: Dp,
    focused: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.04f))
            .border(if (focused) 2.dp else 1.dp, if (focused) tokens.colors.accent else Color.White.copy(alpha = 0.08f), TileShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .stageSecondary(onClick),
    ) {
        StageArt(p, artHeight, badges = if (small) emptyList() else rowBadges(p.entry), radius = 0.dp, small = small)
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                IptvSportTitle.clean(p.entry.title),
                style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!small) {
                Text(
                    "${p.channel.primary.name} · ${IptvStageRules.untilText(p.entry, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (p.onNow(now)) ProgressLine(p.entry.progress(now), height = 2.dp)
    }
}

/** A grid of fixed-height cells, [columns] across, drawn as rows (the stage's highlight decides focus, not Compose). */
@Composable
private fun <T> FixedGrid(items: List<T>, columns: Int, cellHeight: Dp, gap: Dp = 10.dp, cell: @Composable (Int, T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { c, item -> cell(r * columns + c, item, Modifier.weight(1f).height(cellHeight)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The highlighted programme, large, with what follows: the right-hand panel of several views. */
@Composable
private fun ColumnScope.DetailPanel(p: IptvWallProgramme?, now: Long, next: IptvEpgEntry?, extra: String? = null) {
    val tokens = MaterialTheme.nuvio
    if (p == null) {
        StageNote("Move the highlight onto something to see it here.")
        return
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StageArt(p, 180.dp, badges = rowBadges(p.entry), caption = true)
        Text(IptvSportTitle.clean(p.entry.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${p.channel.primary.name} · ${IptvStageRules.clock(p.entry.startMs)} to ${IptvStageRules.clock(p.entry.endMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
        if (p.onNow(now)) ProgressLine(p.entry.progress(now), height = 3.dp)
        p.entry.desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 5, overflow = TextOverflow.Ellipsis) }
        next?.let { Text("Then ${IptvStageRules.clock(it.startMs)} ${it.title}", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
    }
}

private fun nextOn(p: IptvWallProgramme, picks: List<IptvChannelNow>): IptvEpgEntry? =
    picks.firstOrNull { it.channel.primary.uid == p.channel.primary.uid }?.entries?.firstOrNull { it.startMs >= p.entry.endMs - 60_000L && it.startMs > p.entry.startMs }

// ------------------------------------------------------------------------------------------------------------- Map

/** The map: blocks of kinds (zoom 0), or what is on in one block (zoom 1) with the highlighted one beside. */
@Composable
internal fun MapStage(
    m: IptvStageModel,
    blocks: List<IptvViewBlock>,
    zoom: Int,
    block: Int,
    now: Long,
    side: Dp,
    isFocused: Focused,
    act: Act,
) {
    val tokens = MaterialTheme.nuvio
    if (blocks.isEmpty()) {
        StageMessage("Nothing on the kept channels has a guide right now.")
        return
    }
    if (zoom == 0) {
        val busiest = blocks.maxOf { it.programmes.size }
        FixedGrid(blocks, columns = 3, cellHeight = 250.dp, gap = 14.dp) { i, b, mod ->
            val focused = isFocused("mapBlocks", i)
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = mod
                    .clip(shape)
                    .background(tokens.colors.accent.copy(alpha = 0.10f + 0.30f * b.programmes.size / busiest))
                    .border(if (focused) 2.dp else 1.dp, if (focused) tokens.colors.accent else Color.White.copy(alpha = 0.10f), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("mapBlocks", i) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(b.kind.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${b.programmes.size} on now", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.programmes.take(3).forEach { p ->
                        Column(Modifier.weight(1f)) {
                            StageArt(p, 110.dp, small = true)
                            Text(IptvSportTitle.clean(p.entry.title), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(3 - b.programmes.take(3).size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        return
    }
    val b = blocks.getOrNull(block) ?: blocks.first()
    val tiles = b.programmes.take(MAP_TILES)
    val focusedIndex = tiles.indices.firstOrNull { isFocused("mapTiles", it) } ?: 0
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel(b.kind.label, "${b.programmes.size} on now · Back zooms out", Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                FixedGrid(tiles, columns = 4, cellHeight = 160.dp) { i, p, mod ->
                    ProgrammeTile(p, now, 100.dp, isFocused("mapTiles", i), mod) { act("mapTiles", i) }
                }
            }
        }
        StagePanel("Highlighted", null, Modifier.width(side).fillMaxHeight()) {
            val p = tiles.getOrNull(focusedIndex)
            DetailPanel(p, now, p?.let { nextOn(it, m.picks) })
        }
    }
}

internal const val MAP_TILES = 24

// ---------------------------------------------------------------------------------------------------------- Mosaic

/** The mosaic: two big, six middle and eighteen small tiles, the likeliest first. */
@Composable
internal fun MosaicStage(mosaic: IptvMosaic, now: Long, isFocused: Focused, act: Act) {
    if (mosaic.big.isEmpty()) {
        StageMessage("Nothing on the kept channels has a guide right now.")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FixedGrid(mosaic.big, columns = 2, cellHeight = 250.dp) { i, p, mod -> ProgrammeTile(p, now, 190.dp, isFocused("mosaicBig", i), mod) { act("mosaicBig", i) } }
        FixedGrid(mosaic.mid, columns = 3, cellHeight = 150.dp) { i, p, mod -> ProgrammeTile(p, now, 92.dp, isFocused("mosaicMid", i), mod) { act("mosaicMid", i) } }
        FixedGrid(mosaic.small, columns = 6, cellHeight = 92.dp, gap = 8.dp) { i, p, mod ->
            ProgrammeTile(p, now, 58.dp, isFocused("mosaicSmall", i), mod, small = true) { act("mosaicSmall", i) }
        }
    }
}

// ----------------------------------------------------------------------------------------------------------- River

/** The river: a lane per kind from [fromMs] to [toMs], programmes placed by time, the now line across them. */
@Composable
internal fun RiverStage(m: IptvStageModel, lanes: List<IptvRiverLane>, fromMs: Long, toMs: Long, now: Long, isFocused: Focused, act: Act) {
    val tokens = MaterialTheme.nuvio
    val span = (toMs - fromMs).toFloat()
    var focusedP: IptvWallProgramme? = null
    lanes.forEachIndexed { l, lane -> lane.items.forEachIndexed { i, p -> if (isFocused("lane$l", i)) focusedP = p } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val labelW = 140.dp
            val trackW = maxWidth - labelW
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Spacer(Modifier.width(labelW))
                    Box(Modifier.width(trackW).height(18.dp)) {
                        var t = IptvViewRules.slotOf(fromMs) + 30 * 60_000L
                        while (t < toMs) {
                            val x = trackW * ((t - fromMs) / span)
                            Text(IptvViewRules.slotLabel(t), modifier = Modifier.offset(x = x - 18.dp), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
                            t += 30 * 60_000L
                        }
                    }
                }
                lanes.forEachIndexed { l, lane ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lane.kind.label, modifier = Modifier.width(labelW), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.width(trackW).height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.03f))) {
                            lane.items.forEachIndexed { i, p ->
                                val start = maxOf(p.entry.startMs, fromMs)
                                val end = minOf(p.entry.endMs, toMs)
                                val x = trackW * ((start - fromMs) / span)
                                val w = (trackW * ((end - start) / span) - 4.dp).coerceAtLeast(24.dp)
                                val focused = isFocused("lane$l", i)
                                Column(
                                    modifier = Modifier
                                        .offset(x = x + 2.dp)
                                        .width(w)
                                        .fillMaxHeight()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (focused) tokens.colors.accent.copy(alpha = 0.45f) else tokens.colors.accent.copy(alpha = if (p.onNow(now)) 0.22f else 0.12f))
                                        .border(if (focused) 2.dp else 0.dp, if (focused) tokens.colors.accent else Color.Transparent, RoundedCornerShape(7.dp))
                                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("lane$l", i) }.stageSecondary { act("lane$l", i) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                ) {
                                    Text(IptvSportTitle.clean(p.entry.title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(p.channel.primary.name, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // Now.
                            if (now in fromMs until toMs) {
                                Box(Modifier.offset(x = trackW * ((now - fromMs) / span)).width(2.dp).fillMaxHeight().background(tokens.colors.accent))
                            }
                        }
                    }
                }
            }
        }
        val p = focusedP
        StagePanel(p?.let { IptvSportTitle.clean(it.entry.title) } ?: "Pick a programme", p?.let {
            "${it.channel.primary.name} · ${IptvStageRules.clock(it.entry.startMs)} to ${IptvStageRules.clock(it.entry.endMs)} · OK " +
                if (it.onNow(now)) "watches" else "reminds you and switches"
        } ?: "Nuvio picks the best channel showing it", Modifier.fillMaxWidth().height(64.dp)) {}
    }
}

// ------------------------------------------------------------------------------------------------------------ Wall

/** The picture wall: real stills of [channels] where there are some, programme art until then. */
@Composable
internal fun WallStage(playlist: IptvPlaylist, m: IptvStageModel, channels: List<IptvMergedChannel>, now: Long, isFocused: Focused, act: Act) {
    val tokens = MaterialTheme.nuvio
    val stills by IptvStills.stills.collectAsStateWithLifecycle()
    LaunchedEffect(playlist.id, channels.map { it.primary.uid }) { IptvStills.want(playlist, channels) }
    DisposableEffect(Unit) { onDispose { IptvStills.stop() } }
    val byUid = remember(m.picks) { m.picks.associateBy { it.channel.primary.uid } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!IptvStillGrabber.available) StageNote("Real pictures need ffmpeg (winget install ffmpeg). Until then, programme art.")
        FixedGrid(channels, columns = 4, cellHeight = 210.dp, gap = 12.dp) { i, ch, mod ->
            val focused = isFocused("wall", i)
            val on = byUid[ch.primary.uid]?.current(now)
            val still = stills[ch.primary.uid]
            Box(
                modifier = mod
                    .clip(TileShape)
                    .border(if (focused) 2.dp else 1.dp, if (focused) tokens.colors.accent else Color.White.copy(alpha = 0.08f), TileShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("wall", i) }.stageSecondary { act("wall", i) },
            ) {
                if (still != null) {
                    NuvioAsyncImage(model = still.bytes, contentDescription = ch.primary.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    StageArt(on?.let { IptvWallProgramme(ch, it) }, 210.dp, radius = 0.dp)
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(ch.primary.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(on?.title ?: "No guide", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when {
                        still == null -> if (IptvStillGrabber.available) "waiting" else "art"
                        now - still.at < 60_000L -> "now"
                        else -> "${(now - still.at) / 60_000L} min"
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------ Grid

/** Tonight's grid: a row per kind, a column per half-hour, darker where more starts; the chosen cell's list beside. */
@Composable
internal fun GridStage(cells: List<IptvGridCell>, columns: Int, chosen: Int, now: Long, side: Dp, isFocused: Focused, act: Act) {
    val tokens = MaterialTheme.nuvio
    val busiest = cells.maxOfOrNull { it.programmes.size } ?: 0
    val cell = cells.getOrNull(chosen)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StagePanel("Tonight by kind", "darker: more starts then · OK lists them", Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Spacer(Modifier.width(130.dp))
                    cells.take(columns).forEach { c ->
                        Text(IptvViewRules.slotLabel(c.slotMs), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                    }
                }
                cells.chunked(columns).forEachIndexed { r, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.first().kind.label, modifier = Modifier.width(125.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.forEachIndexed { c, gc ->
                            val i = r * columns + c
                            val focused = isFocused("grid", i)
                            val shade = IptvViewRules.shade(gc.programmes.size, busiest)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (shade == 0) Color.White.copy(alpha = 0.03f) else tokens.colors.accent.copy(alpha = 0.10f + 0.13f * shade))
                                    .border(if (focused || i == chosen) 2.dp else 0.dp, if (focused) tokens.colors.textPrimary else if (i == chosen) tokens.colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("grid", i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (gc.programmes.isNotEmpty()) Text(gc.programmes.size.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary)
                            }
                        }
                    }
                }
            }
        }
        StagePanel(
            cell?.let { "${it.kind.label} at ${IptvViewRules.slotLabel(it.slotMs)}" } ?: "A cell",
            cell?.let { "${it.programmes.size} starting" },
            Modifier.width(side).fillMaxHeight(),
        ) {
            val list = cell?.programmes.orEmpty()
            StageList(list.size, list.indices.firstOrNull { isFocused("gridList", it) }, "Nothing starts there.") { i ->
                val p = list[i]
                StageRow(
                    lead = IptvStageRules.clock(p.entry.startMs), title = IptvSportTitle.clean(p.entry.title), sub = p.channel.primary.name,
                    trailing = null, focused = isFocused("gridList", i), thumb = p, thumbWidth = 64.dp,
                ) { act("gridList", i) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------ Dial

/** The dial: the highlighted channel big, the two before and after it small; up and down flip, numbers jump. */
@Composable
internal fun DialStage(cards: List<IptvDialCard>, typed: String, now: Long, isFocused: Focused, act: Act) {
    val tokens = MaterialTheme.nuvio
    if (cards.isEmpty()) {
        StageMessage("No channels with a guide.")
        return
    }
    val at = cards.indices.firstOrNull { isFocused("dial", it) } ?: 0
    @Composable
    fun Peek(i: Int, alpha: Float) {
        val c = cards.getOrNull(i) ?: run { Spacer(Modifier.height(54.dp)); return }
        Row(
            Modifier.fillMaxWidth().height(54.dp).alpha(alpha).clip(TileShape).background(Color.White.copy(alpha = 0.04f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("dial", i) }.stageSecondary { act("dial", i) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(c.number.toString(), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textMuted)
            Text(c.channel.primary.name, modifier = Modifier.width(220.dp), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.now?.title ?: "No guide", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val c = cards[at]
    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Peek(at - 2, 0.45f)
        Peek(at - 1, 0.75f)
        Row(
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.07f))
                .border(2.dp, tokens.colors.accent, RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { act("dial", at) }.stageSecondary { act("dial", at) },
        ) {
            Box(Modifier.width(500.dp).fillMaxHeight()) { StageArt(c.now?.let { IptvWallProgramme(c.channel, it) }, 300.dp, radius = 0.dp) }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (typed.isNotEmpty()) "Channel $typed..." else "${c.number} · ${c.channel.primary.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.accent,
                    maxLines = 1,
                )
                Text(c.now?.let { IptvSportTitle.clean(it.title) } ?: "No guide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                c.now?.let { e ->
                    Text("${IptvStageRules.clock(e.startMs)} to ${IptvStageRules.clock(e.endMs)}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    ProgressLine(e.progress(now), height = 4.dp)
                    e.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.weight(1f))
                c.next?.let { Text("Then ${IptvStageRules.clock(it.startMs)} ${it.title}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Peek(at + 1, 0.75f)
        Peek(at + 2, 0.45f)
    }
}
internal const val GRID_COLUMNS = 10
