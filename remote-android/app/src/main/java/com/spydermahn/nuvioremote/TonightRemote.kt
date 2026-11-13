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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
 * tap targets, and every tap does what the TV's keys would (`TONIGHT_DO`). Nov 11: every mode is mirrored too
 * (Knockout, Coin Toss, Channel Flick, Ends By, The Pile), and The Map is a touchpad that steers the TV's lens.
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
    // Nov 11: the TV's own keys for the modes, opening mode n, and letting a Pile title go.
    const val UP = "up"
    const val DOWN = "down"
    const val OK = "ok"
    const val MODE = "mode"
    const val LET_GO = "letgo"
}

private fun modeLabel(mode: String?): String = when (mode) {
    "Knockout" -> "Knockout"
    "CoinToss" -> "Coin Toss"
    "Flick", "ChannelFlick" -> "Channel Flick"
    "EndsBy" -> "Ends By"
    "Pile", "ThePile" -> "The Pile"
    "Map", "TheMap" -> "The Map"
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
            "knockout" -> TonightKnockout(screen, viewModel)
            "cointoss" -> TonightCoinToss(screen, viewModel)
            "flick" -> TonightFlick(screen, viewModel)
            "endsby" -> TonightEndsBy(screen, viewModel)
            "pile" -> TonightPile(screen, viewModel)
            "map" -> TonightMap(screen, viewModel)
            else -> TonightElsewhere(screen, viewModel)
        }
    }
}

@Composable
private fun ColumnScope.TonightLauncher(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    GlassLabel("Tonight")
    DisplayTitle("How shall we choose tonight?", Modifier.padding(top = 4.dp), size = 24)
    Spacer(Modifier.height(14.dp))
    if (screen.cards.isEmpty()) {
        // A PC from before Nov 11 sends no tiles: the two the phone could always mirror.
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
    } else {
        // Every way to choose, in the TV's order; tapping one opens it on the TV and here.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            screen.cards.forEach { tile ->
                val last = tile.slot == screen.mode
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .glass(RoundedCornerShape(18.dp), fill = if (last) g.tint else g.glassFill, edge = if (last) g.tintEdge else g.glassEdge)
                        .then(if (tile.flag) Modifier.tap { viewModel.tonight(TonightAction.MODE, tile.number) } else Modifier)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tile.name, color = if (tile.flag) g.text else g.textMuted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (last) Text("LAST TIME", color = g.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    }
                    Text(tile.why, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
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

// ------------------------------------------------------------------------------------------------ The other modes (Nov 11)

/** The mode's name, a line beside it, and Back (which does what the TV's Back does there). */
@Composable
private fun ModeHeader(title: String, right: String?, viewModel: RemoteViewModel, backLabel: String = "Back") {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassLabel(title, Modifier.weight(1f))
        if (right != null) Text(right, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
        GlassTag(backLabel, onClick = { viewModel.tonight(TonightAction.BACK) })
    }
}

/** A title as one fixed-height glass row: poster, a small label, the name and its line. */
@Composable
private fun TitleRow(
    card: TonightCardInfo,
    height: androidx.compose.ui.unit.Dp = 112.dp,
    selected: Boolean = false,
    dim: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onTap: (() -> Unit)?,
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .glass(shape, fill = if (selected) g.tint else g.glassFill, edge = if (selected) g.tintEdge else g.glassEdge)
            .then(if (onTap != null) Modifier.tap(onTap) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(card.poster, null, card.name, Modifier.width((height.value * 0.6f).dp).fillMaxSize().glass(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (card.slot.isNotEmpty()) GlassLabel(card.slot, size = 11, color = if (dim) g.textMuted else null)
            Text(
                card.name, color = if (dim) g.textMuted else g.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            Text(card.meta, color = g.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A fixed-height row of equal buttons, each a TV key. */
@Composable
private fun KeyButtons(vararg buttons: Triple<String, Boolean, () -> Unit>) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, accent, onTap) ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .glass(RoundedCornerShape(16.dp), fill = if (accent) g.tint else g.glassFill, edge = if (accent) g.tintEdge else g.glassEdge)
                    .tap(onTap)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun EmptyLine(screen: TonightScreenInfo, otherwise: String) {
    Text(
        if (screen.loading) "Still gathering tonight's titles…" else otherwise,
        color = LocalGlass.current.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun ColumnScope.TonightCoinToss(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    val current = screen.cards.firstOrNull()
    ModeHeader("Coin toss · toss ${screen.number}", null, viewModel)
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(150.dp)) {
        if (current == null) EmptyLine(screen, "That's everything that fits tonight.")
        else TitleRow(current, height = 150.dp, selected = true, onTap = null)
    }
    // A fixed line for the countdown, so the buttons never move.
    Text(
        when {
            current == null -> ""
            screen.paused -> "Countdown held. Play now, or start the clock again."
            screen.seconds >= 0 -> "Plays by itself in ${screen.seconds} s. Feel a \"no\"? Toss again."
            else -> ""
        },
        color = g.text, fontSize = 15.sp, maxLines = 2, modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
    )
    KeyButtons(
        Triple("Not this,\ntoss again", false) { viewModel.tonight(TonightAction.LEFT) },
        Triple("Play now", true) { viewModel.tonight(TonightAction.OK) },
    )
    Spacer(Modifier.height(8.dp))
    KeyButtons(
        Triple(if (screen.paused) "Start the clock" else "Hold", false) { viewModel.tonight(TonightAction.UP) },
        Triple("Its page", false) { viewModel.tonight(TonightAction.DOWN) },
    )
    Spacer(Modifier.height(14.dp))
    GlassLabel("What your \"no\"s said", color = g.textMuted, size = 11)
    Text(
        screen.answers.joinToString(" · ").ifEmpty { "Nothing yet. Each \"not this\" teaches the next toss." },
        color = g.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.height(40.dp).padding(top = 4.dp),
    )
    Text(screen.extra?.let { "→ $it" } ?: "", color = g.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.height(20.dp))
}

@Composable
private fun ColumnScope.TonightKnockout(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    val winner = screen.seconds >= 0 && screen.cards.size == 1
    ModeHeader("Knockout" + (screen.prompt?.let { " · $it" } ?: ""), null, viewModel, backLabel = if (winner) "Undo the final" else "Undo")
    Spacer(Modifier.height(12.dp))
    when {
        screen.cards.isEmpty() -> EmptyLine(screen, "Knockout needs at least two titles that fit tonight.")
        winner -> {
            TitleRow(screen.cards[0], height = 150.dp, selected = true, onTap = { viewModel.tonight(TonightAction.OK) })
            Text("Playing in ${screen.seconds} s.", color = g.text, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp).height(24.dp))
            Spacer(Modifier.height(8.dp))
            KeyButtons(Triple("Play now", true) { viewModel.tonight(TonightAction.OK) })
        }
        else -> {
            Text("Tap the one to keep.", color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.height(22.dp))
            TitleRow(screen.cards[0], height = 128.dp, onTap = { viewModel.tonight(TonightAction.LEFT) })
            Text("vs", color = g.textMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp))
            screen.cards.getOrNull(1)?.let { TitleRow(it, height = 128.dp, onTap = { viewModel.tonight(TonightAction.RIGHT) }) }
            Text(screen.extra ?: "", color = g.accent, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp).height(22.dp))
            KeyButtons(Triple(if (screen.extra == null) "A 20 second taste of each" else "Next taste", false) { viewModel.tonight(TonightAction.DOWN) })
        }
    }
}

@Composable
private fun ColumnScope.TonightFlick(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    val current = screen.cards.firstOrNull()
    ModeHeader("Channel flick", if (screen.total > 0) "CH ${screen.number} of ${screen.total}" else null, viewModel)
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(150.dp)) {
        if (current == null) EmptyLine(screen, "No channels left tonight.")
        else TitleRow(current, height = 150.dp, selected = true, onTap = null)
    }
    Text(screen.extra ?: "", color = g.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).height(24.dp))
    KeyButtons(
        Triple("Never show\nme this", false) { viewModel.tonight(TonightAction.LEFT) },
        Triple("Play it", true) { viewModel.tonight(TonightAction.OK) },
    )
    Spacer(Modifier.height(8.dp))
    KeyButtons(
        Triple("Save for later", false) { viewModel.tonight(TonightAction.UP) },
        Triple("Next channel", false) { viewModel.tonight(TonightAction.RIGHT) },
    )
    Spacer(Modifier.height(14.dp))
    GlassLabel("Up next", color = g.textMuted, size = 11)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        screen.cards.drop(1).forEach { Text(it.name, color = g.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ColumnScope.TonightEndsBy(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    ModeHeader("Ends by", null, viewModel)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassTag("−15", onClick = { viewModel.tonight(TonightAction.LEFT) })
        Text(screen.extra ?: "", color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        GlassTag("+15", onClick = { viewModel.tonight(TonightAction.RIGHT) })
    }
    Text("Tap a title to play it. Faded ones run past bed.", color = g.textMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (screen.cards.isEmpty()) EmptyLine(screen, "Nothing to line up yet.")
        screen.cards.forEach { card ->
            TitleRow(card, height = 84.dp, selected = card.number == screen.selected, dim = !card.flag, onTap = { viewModel.tonight(TonightAction.PLAY, card.number) })
        }
    }
}

@Composable
private fun ColumnScope.TonightPile(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    // The phone asks before letting a title go, like the TV's second press.
    var confirming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    ModeHeader("The pile", if (screen.total > 0) "${screen.total} waiting" else null, viewModel)
    Text("Tap a title to finally watch it.", color = g.textMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (screen.cards.isEmpty()) EmptyLine(screen, "Nothing on the pile. Everything you saved has been watched.")
        screen.cards.forEach { card ->
            TitleRow(
                card,
                height = 84.dp,
                selected = card.number == screen.selected,
                trailing = {
                    GlassTag(
                        if (confirming == card.number) "Let go for good?" else "Let go",
                        accent = confirming == card.number,
                        onClick = {
                            if (confirming == card.number) {
                                confirming = 0
                                viewModel.tonight(TonightAction.LET_GO, card.number)
                            } else {
                                confirming = card.number
                            }
                        },
                    )
                },
                onTap = { confirming = 0; viewModel.tonight(TonightAction.PLAY, card.number) },
            )
        }
    }
}

@Composable
private fun ColumnScope.TonightMap(screen: TonightScreenInfo, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    val under = screen.cards.firstOrNull()
    ModeHeader("The map", if (screen.total > 0) "${screen.total} titles" else null, viewModel)
    Spacer(Modifier.height(10.dp))
    // Under the lens: a fixed box, so the touchpad below never moves.
    Box(Modifier.fillMaxWidth().height(112.dp)) {
        if (under == null) EmptyLine(screen, "Nothing on the map yet.")
        else TitleRow(under, height = 112.dp, selected = true, onTap = { viewModel.tonight(TonightAction.OK) })
    }
    Text(
        (screen.extra ?: "") + screen.cards.drop(1).joinToString("") { "  ·  near: ${it.name}" },
        color = g.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp).height(20.dp),
    )
    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
        Touchpad(
            onKey = { key ->
                when (key) {
                    RemoteKey.Up -> viewModel.tonight(TonightAction.UP)
                    RemoteKey.Down -> viewModel.tonight(TonightAction.DOWN)
                    RemoteKey.Left -> viewModel.tonight(TonightAction.LEFT)
                    RemoteKey.Right -> viewModel.tonight(TonightAction.RIGHT)
                    RemoteKey.Select -> viewModel.tonight(TonightAction.OK)
                    else -> Unit
                }
            },
            compact = false,
            modifier = Modifier.fillMaxSize().glass(RoundedCornerShape(22.dp)),
            step = 28.dp,
            title = "Drag to move the lens",
            hint = "Light ◀ ▶ heavy · new ▲ ▼ comfort · tap plays",
        )
        LensDot(screen.number, screen.countLeft, Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** Where the lens sits on the TV's field, in hundredths across and down: a small fixed square. */
@Composable
private fun LensDot(xHundredths: Int, yHundredths: Int, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val accent = g.accent
    androidx.compose.foundation.Canvas(modifier.size(56.dp).glass(RoundedCornerShape(8.dp))) {
        val x = size.width * (xHundredths.coerceIn(0, 100) / 100f)
        val y = size.height * (yHundredths.coerceIn(0, 100) / 100f)
        drawCircle(accent, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
    }
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
