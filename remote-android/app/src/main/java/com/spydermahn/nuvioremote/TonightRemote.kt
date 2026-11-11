package com.spydermahn.nuvioremote

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The TV's Tonight picker on the phone (Nov 10): the phone mirrors the launcher, Three Questions and Three Cards as big
 * tap targets, and every tap does what the TV's keys would (`TONIGHT_DO`). Other modes stay on the TV; the pad, from the
 * header tag, still drives them.
 */

/** The wire names of the PC's RemoteTonightAction. */
object TonightAction {
    /** Opens the Tonight picker on the TV from anywhere (the "What to watch?" button). */
    const val OPEN = "open"
    const val QUESTIONS = "questions"
    const val CARDS = "cards"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val EITHER = "either"
    const val BACK = "back"
    const val PLAY = "play"
    const val DETAILS = "details"
    const val RESHUFFLE = "reshuffle"
}

private fun modeLabel(mode: String?): String = when (mode) {
    "Knockout" -> "Knockout"
    "CoinToss" -> "Coin Toss"
    "Flick" -> "Channel Flick"
    "EndsBy" -> "Ends By"
    "Pile" -> "The Pile"
    "Map" -> "The Map"
    else -> "Tonight"
}

@Composable
private fun Modifier.tap(onTap: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(onTap) {
        detectTapGestures(onTap = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTap()
        })
    }
}

@Composable
fun TonightBody(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        when (screen.view) {
            "launcher" -> TonightLauncher(screen, viewModel)
            "question" -> TonightQuestion(screen, viewModel)
            "cards" -> TonightCards(screen, viewModel)
            else -> TonightElsewhere(screen, viewModel)
        }
    }
}

@Composable
private fun ColumnScope.TonightLauncher(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    GlassLabel("Tonight")
    DisplayTitle("How shall we choose tonight?", Modifier.padding(top = 4.dp), size = 24)
    Spacer(Modifier.height(18.dp))
    BigChoice(
        label = "Three Questions",
        detail = "Three taps, then three cards",
        accent = screen.mode == "ThreeQuestions",
        tag = if (screen.mode == "ThreeQuestions") "LAST TIME" else null,
        onTap = { viewModel.tonight(TonightAction.QUESTIONS) },
    )
    Spacer(Modifier.height(12.dp))
    BigChoice(
        label = "Three Cards",
        detail = "Safe, new, wildcard. One reshuffle.",
        accent = screen.mode == "ThreeCards",
        tag = if (screen.mode == "ThreeCards") "LAST TIME" else null,
        onTap = { viewModel.tonight(TonightAction.CARDS) },
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "The other ways to choose (Knockout, Coin Toss, The Map...) are on the TV: use the pad from the tag above.",
        color = g.textSecondary,
        fontSize = 13.sp,
    )
    if (screen.loading) {
        Spacer(Modifier.height(12.dp))
        Text("Still gathering tonight's titles…", color = g.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ColumnScope.TonightQuestion(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassLabel("Question ${screen.number} of ${screen.total}", Modifier.weight(1f))
        Text("${screen.countFrom} → ${screen.countLeft} left", color = g.textSecondary, fontSize = 13.sp)
    }
    // A fixed-height title slot, so the buttons below never move from question to question.
    Box(Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp), contentAlignment = Alignment.CenterStart) {
        DisplayTitle(
            when {
                screen.prompt != null -> screen.prompt
                screen.loading -> "Still gathering tonight's titles…"
                else -> "Dealing your cards…"
            },
            size = 28,
            maxLines = 2,
        )
    }
    BigChoice(screen.leftLabel ?: "", screen.leftDetail ?: "", accent = false, keyLabel = "◀ LEFT", enabled = screen.prompt != null) {
        viewModel.tonight(TonightAction.LEFT)
    }
    Spacer(Modifier.height(12.dp))
    BigChoice(screen.rightLabel ?: "", screen.rightDetail ?: "", accent = false, keyLabel = "RIGHT ▶", enabled = screen.prompt != null) {
        viewModel.tonight(TonightAction.RIGHT)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassTag("Either is fine", accent = true, onClick = { viewModel.tonight(TonightAction.EITHER) })
        GlassTag(if (screen.number <= 1) "Back to the start" else "Back a question", onClick = { viewModel.tonight(TonightAction.BACK) })
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassLabel("So far", color = g.textMuted)
        Text(screen.answers.joinToString(" · ").ifEmpty { "nothing yet" }, color = g.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColumnScope.TonightCards(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            GlassLabel("Tonight's hand")
            Text(
                screen.answers.joinToString(" · ").ifEmpty { "Everything that fits before bed" },
                color = g.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        GlassTag(
            if (screen.reshuffleLeft) "Reshuffle" else "No reshuffles left",
            accent = screen.reshuffleLeft,
            onClick = if (screen.reshuffleLeft) ({ viewModel.tonight(TonightAction.RESHUFFLE) }) else null,
        )
    }
    if (screen.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp), color = g.accent, trackColor = Color.White.copy(alpha = 0.08f))
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (screen.cards.isEmpty()) {
            Text(
                if (screen.loading) "Still gathering tonight's titles…" else "Nothing fits tonight yet.",
                color = g.textSecondary, fontSize = 15.sp,
            )
        }
        screen.cards.forEach { card ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .glass(RoundedCornerShape(18.dp))
                    .tap { viewModel.tonight(TonightAction.PLAY, card.number) }
                    .padding(10.dp),
            ) {
                RemoteImage(card.poster, null, card.name, Modifier.width(88.dp).fillMaxSize().glass(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        GlassLabel(
                            card.slot,
                            color = when (card.slot) {
                                "SAFE" -> Color(0xFF9CC7FF)
                                "WILDCARD" -> Color(0xFF6FD3C9)
                                else -> g.accent
                            },
                            size = 11,
                        )
                        Text(card.name, color = g.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(card.why, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(card.meta, color = g.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        GlassTag("Details", onClick = { viewModel.tonight(TonightAction.DETAILS, card.number) })
                    }
                }
            }
        }
        Text("Tap a card to play it on the TV.", color = g.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    GlassTag("Back to the start", onClick = { viewModel.tonight(TonightAction.BACK) })
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.TonightElsewhere(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    GlassLabel("Tonight")
    DisplayTitle("${modeLabel(screen.mode)} is on the TV", Modifier.padding(top = 4.dp), size = 24)
    Text(
        "This one is played on the TV. Use the pad from the tag above, or go back to the start.",
        color = g.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
    )
    Spacer(Modifier.height(16.dp))
    GlassTag("Back to the start", accent = true, onClick = { viewModel.tonight(TonightAction.BACK) })
}

/** A big glass answer: the key it matches on the TV, the choice, and a line under it. Fixed height. */
@Composable
private fun BigChoice(
    label: String,
    detail: String,
    accent: Boolean,
    keyLabel: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
    onTap: () -> Unit,
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(116.dp)
            .glass(shape, fill = if (accent) g.tint else g.glassFill, edge = if (accent) g.tintEdge else g.glassEdge)
            .then(if (enabled) Modifier.tap(onTap) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(keyLabel ?: "", color = g.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            if (tag != null) Text(tag, color = g.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        }
        Text(label, color = g.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** The Remote tab's "What to watch?" button while nothing plays: opens the Tonight picker on the TV. */
@Composable
fun WhatToWatchButton(viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .glass(shape, fill = g.tint, edge = g.tintEdge)
            .tap { viewModel.tonight(TonightAction.OPEN) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("What to watch?", color = g.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("Can't decide? Let the TV help", color = g.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Text("▶", color = g.accent, fontSize = 18.sp)
    }
}
