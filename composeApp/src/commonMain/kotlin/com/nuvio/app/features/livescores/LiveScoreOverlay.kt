package com.nuvio.app.features.livescores

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val GlassFill = Color(0xC70E0E14)
private val Hairline = Color(0x24FFFFFF)
private val Muted = Color(0xFF9A9AA6)
private val Soft = Color(0xFFC8C8D0)

private fun accentFor(event: ScoreEvent): Color = when (event.kind.group) {
    ScoreEventGroup.Cards -> Color(0xFFE5484D)
    ScoreEventGroup.PenaltiesVar -> Color(0xFFF5A524)
    else -> Color(0xFF3DD68C)
}

/**
 * fork:live-scores - the pop-up over the app's own screens (menus, Live TV guide, the music stage). Takes no input, so
 * the screen below stays usable. Over a playing video the HUD draws the same card (LiveScorePlatform). Every look
 * has a fixed size and a fixed place: nothing moves or resizes with the text (design rules).
 */
@Composable
fun LiveScoreOverlay(modifier: Modifier = Modifier) {
    val shown by LiveScoreEngine.showing.collectAsStateWithLifecycle()
    // Keep the last card while it fades out.
    var last by remember { mutableStateOf<LiveScoreEngine.ShownScore?>(null) }
    LaunchedEffect(shown) { if (shown != null) last = shown }
    val card = shown ?: last
    val look = card?.let { if (it.look == ScoreLook.BigFlash && !it.event.favourite) ScoreLook.Corner else it.look }
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown != null && look == ScoreLook.Corner,
            enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 },
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 32.dp),
        ) { card?.let { CornerCard(it.event) } }
        AnimatedVisibility(
            visible = shown != null && look == ScoreLook.Ticker,
            enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { -it },
            exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) { card?.let { TickerBar(it.event) } }
        AnimatedVisibility(
            visible = shown != null && look == ScoreLook.BigFlash,
            enter = fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.92f),
            exit = fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.96f),
            modifier = Modifier.align(Alignment.Center),
        ) { card?.let { FlashCard(it.event) } }
    }
}

@Composable
private fun Glass(modifier: Modifier, shape: RoundedCornerShape, accent: Color?, accentWidth: Int, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, Hairline, shape),
    ) {
        if (accent != null) {
            Box(Modifier.align(Alignment.CenterStart).width(accentWidth.dp).fillMaxHeight().background(accent))
        }
        content()
    }
}

@Composable
private fun CornerCard(event: ScoreEvent) {
    val accent = accentFor(event)
    Glass(Modifier.size(width = 420.dp, height = 112.dp), RoundedCornerShape(16.dp), accent, 4) {
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
            HeaderRow(event, accent, kindSize = 14.sp, metaSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            ScoreRow(event, accent, nameSize = 18.sp, scoreSize = 20.sp, scoreWidth = 70)
            Spacer(Modifier.height(6.dp))
            OneLine(event.detail.orEmpty(), Soft, 13.sp, align = if (isFight(event)) TextAlign.Start else TextAlign.Center)
        }
    }
}

@Composable
private fun FlashCard(event: ScoreEvent) {
    val accent = accentFor(event)
    Glass(Modifier.size(width = 640.dp, height = 260.dp), RoundedCornerShape(24.dp), accent, 6) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 36.dp, top = 28.dp, bottom = 24.dp)) {
            HeaderRow(event, accent, kindSize = 40.sp, metaSize = 16.sp)
            Spacer(Modifier.height(26.dp))
            ScoreRow(event, accent, nameSize = 30.sp, scoreSize = 40.sp, scoreWidth = 130)
            Spacer(Modifier.height(18.dp))
            OneLine(event.detail.orEmpty(), Soft, 18.sp, align = if (isFight(event)) TextAlign.Start else TextAlign.Center)
        }
    }
}

@Composable
private fun TickerBar(event: ScoreEvent) {
    val accent = accentFor(event)
    Box(
        Modifier.fillMaxWidth().height(56.dp).background(GlassFill).border(1.dp, Hairline),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OneLine(event.kind.label, accent, 15.sp, weight = FontWeight.ExtraBold, modifier = Modifier.width(150.dp))
            OneLine(scoreLine(event.match), Color.White, 18.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OneLine(event.detail.orEmpty(), Soft, 14.sp, align = TextAlign.End, modifier = Modifier.width(260.dp))
            OneLine(meta(event), Muted, 13.sp, align = TextAlign.End, modifier = Modifier.width(200.dp))
        }
    }
}

@Composable
private fun HeaderRow(event: ScoreEvent, accent: Color, kindSize: TextUnit, metaSize: TextUnit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OneLine(event.kind.label, accent, kindSize, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        OneLine(meta(event), Muted, metaSize, align = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun ScoreRow(event: ScoreEvent, accent: Color, nameSize: TextUnit, scoreSize: TextUnit, scoreWidth: Int) {
    val match = event.match
    if (isFight(event)) {
        OneLine(match.headline ?: "${match.home.name} vs ${match.away.name}", Color.White, nameSize, weight = FontWeight.Bold)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OneLine(
            match.home.shortName, if (event.forHome == true) accent else Color.White, nameSize,
            weight = FontWeight.Bold, align = TextAlign.End, modifier = Modifier.weight(1f),
        )
        OneLine(
            if (match.homeScore != null && match.awayScore != null) "${match.homeScore} – ${match.awayScore}" else "v",
            Color.White, scoreSize, weight = FontWeight.ExtraBold, align = TextAlign.Center, modifier = Modifier.width(scoreWidth.dp),
        )
        OneLine(
            match.away.shortName, if (event.forHome == false) accent else Color.White, nameSize,
            weight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OneLine(
    text: String,
    color: Color,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Text(text, color = color, fontSize = size, fontWeight = weight, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private fun isFight(event: ScoreEvent) = event.match.sport == LiveSport.Mma || event.match.sport == LiveSport.Boxing

private fun meta(event: ScoreEvent): String =
    listOfNotNull(event.clock?.takeIf { it.isNotBlank() }, event.match.competitionName ?: event.match.sport.label).joinToString(" · ")
