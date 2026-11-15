package com.spydermahn.nuvioremote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Live TV's stages on the phone (Nov 14): the stage's heading, the strip of stages as chips, its rows as fixed-height
 * glass cards and its buttons. A tap does what OK on that row does on the TV (`LIVETV_DO`).
 */

@Composable
fun LiveTvBody(screen: LiveTvScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        GlassLabel("Live TV")
        DisplayTitle(screen.heading, Modifier.padding(top = 4.dp), size = 22, maxLines = 1)
        Text(screen.sub, color = g.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        // The strip: every stage, the one on the TV lit; All channels last.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            screen.stages.forEach { (name, label) ->
                LiveTvChip(label, on = name == screen.stage) { viewModel.liveTvStage(name) }
            }
            LiveTvChip("All channels", on = false) { viewModel.liveTvAll() }
        }
        Spacer(Modifier.height(12.dp))
        // The TV's front door (Nov 15) gets the phone's own Home layout; every other stage is a list of rows.
        val home = screen.stage == "Door"
        if (home && screen.items.isNotEmpty()) {
            LiveTvHome(screen, viewModel, Modifier.weight(1f).fillMaxWidth())
        } else if (screen.items.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).glass(RoundedCornerShape(18.dp)).padding(18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(screen.empty ?: "Nothing here right now.", color = g.textSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(screen.items, key = { i, item -> "${item.group}:${item.index}:$i" }) { _, item ->
                    LiveTvRow(item) { viewModel.liveTvItem(item.group, item.index) }
                }
            }
        }
        if (screen.buttons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // On Home, Something else sits on the pick's card.
                screen.buttons.filter { !home || it.group != "doorPick" }.forEach { b -> LiveTvChip(b.label, on = b.accent) { viewModel.liveTvItem(b.group, b.index) } }
            }
        }
    }
}

@Composable
private fun LiveTvChip(label: String, on: Boolean, onTap: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier
            .height(38.dp)
            .glass(RoundedCornerShape(19.dp), fill = if (on) g.tint else g.glassFill, edge = if (on) g.tintEdge else g.glassEdge)
            .pressable { onTap() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) g.text else g.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One row: a picture, the slot, title, line and a progress bar, all at one height. */
@Composable
private fun LiveTvRow(item: LiveTvItemInfo, onTap: () -> Unit) {
    val g = LocalGlass.current
    val tappable = item.group != "-"
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .glass(RoundedCornerShape(18.dp))
            .then(if (tappable) Modifier.pressable { onTap() } else Modifier)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = item.picture,
            fallback = null,
            placeholder = item.title,
            modifier = Modifier.width(112.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.slot, color = if (item.live) g.accent else g.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1)
                if (tappable && item.action != null) {
                    Spacer(Modifier.weight(1f))
                    Text(item.action, color = g.textSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }
            Text(item.title, color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sub, color = g.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The bar's place is kept even when nothing is on, so every row is the same.
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (item.progress >= 0) g.track else g.track.copy(alpha = 0f))) {
                if (item.progress >= 0) {
                    Box(Modifier.fillMaxWidth(item.progress / 1000f).fillMaxHeight().background(g.accent))
                }
            }
        }
    }
}

/*
 * The TV's front door on the phone (Nov 15): the pick as one big card with Watch and Something else, then Carry on and
 * the favourites, the scores of the teams followed, and Next up, each under a heading. A tap does what OK does on the TV.
 */
@Composable
private fun LiveTvHome(screen: LiveTvScreenInfo, viewModel: RemoteViewModel, modifier: Modifier) {
    val pick = screen.items.firstOrNull { it.group == "doorPick" }
    val favourites = screen.items.filter { it.group == "doorCarry" || it.group == "doorFavs" }
    val scores = screen.items.filter { it.slot == "SCORE" }
    val next = screen.items.filter { it.group == "doorNext" }
    val other = screen.buttons.firstOrNull { it.group == "doorPick" }
    fun LazyListScope.section(title: String, rows: List<LiveTvItemInfo>, score: Boolean = false) {
        if (rows.isEmpty()) return
        item(key = "h:$title") { GlassLabel(title, Modifier.padding(top = 8.dp)) }
        itemsIndexed(rows, key = { i, it -> "$title:${it.group}:${it.index}:$i" }) { _, row ->
            if (score) LiveTvScoreRow(row) { viewModel.liveTvItem(row.group, row.index) }
            else LiveTvRow(row) { viewModel.liveTvItem(row.group, row.index) }
        }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pick != null) {
            item(key = "pick") {
                LiveTvPickCard(
                    pick,
                    onWatch = { viewModel.liveTvItem(pick.group, pick.index) },
                    onOther = other?.let { b -> { viewModel.liveTvItem(b.group, b.index) } },
                )
            }
        }
        section("Favourites now", favourites)
        section("Scores", scores, score = true)
        section("Next up", next)
    }
}

/** The pick: a wide picture, the rule that picked it, the title, why, how far in, and two equal buttons. */
@Composable
private fun LiveTvPickCard(pick: LiveTvItemInfo, onWatch: () -> Unit, onOther: (() -> Unit)?) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            RemoteImage(url = pick.picture, fallback = null, placeholder = pick.title, modifier = Modifier.fillMaxSize())
            if (pick.live) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(g.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                ) { Text("LIVE", color = g.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
            }
        }
        Text(pick.slot, color = g.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1)
        Text(pick.title, color = g.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pick.sub, color = g.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (pick.progress >= 0) g.track else g.track.copy(alpha = 0f))) {
            if (pick.progress >= 0) Box(Modifier.fillMaxWidth(pick.progress / 1000f).fillMaxHeight().background(g.accent))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveTvPickButton(pick.action ?: "Watch", accent = true, modifier = Modifier.weight(1f), onTap = onWatch)
            if (onOther != null) LiveTvPickButton("Something else", accent = false, modifier = Modifier.weight(1f), onTap = onOther)
        }
    }
}

@Composable
private fun LiveTvPickButton(label: String, accent: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier
            .height(46.dp)
            .glass(RoundedCornerShape(14.dp), fill = if (accent) g.tint else g.glassFill, edge = if (accent) g.tintEdge else g.glassEdge)
            .pressable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A followed match: a dot while it is live, the score and where it is on. Only a match on a channel can be tapped. */
@Composable
private fun LiveTvScoreRow(item: LiveTvItemInfo, onTap: () -> Unit) {
    val g = LocalGlass.current
    val tappable = item.group != "-"
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .glass(RoundedCornerShape(18.dp))
            .then(if (tappable) Modifier.pressable { onTap() } else Modifier)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (item.live) g.accent else g.track))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = g.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sub, color = g.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tappable && item.action != null) Text(item.action, color = g.textSecondary, fontSize = 12.sp, maxLines = 1)
    }
}
