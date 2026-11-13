package com.nuvio.app.features.tonight

import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.smoothRoundClip
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import kotlinx.coroutines.delay

/*
 * fork:tonight - phase 2 of design-notes/tonight-picker.md: Coin Toss and Knockout. Fixed boxes
 * throughout (design rules): a missing poster, a long title or an empty veto panel never moves
 * anything else.
 */

/** "2016 · 1h 56m · ends 23:06" for a film, "Next: S2 E4 · 30 min eps · 3 before bed" for a show. */
internal fun TonightTitle.tonightMetaLine(clock: TonightClock, minutesUntilBed: Int): String {
    val parts = mutableListOf<String>()
    when (kind) {
        TonightKind.Film -> {
            year?.let { parts += it.toString() }
            minutesTonight()?.let { minutes ->
                parts += if (resumeMinutesLeft != null) "${lengthLabel(minutes)} left" else lengthLabel(minutes)
            }
            endsAtLabel(clock.minuteOfDay)?.let { parts += "ends $it" }
        }
        TonightKind.Show -> {
            parts += nextEpisodeLabel?.let { "Next: $it" } ?: "Series"
            runtimeMinutes?.let { minutes ->
                parts += "$minutes min eps"
                if (minutesUntilBed > 0) parts += "${tonightEpisodesBeforeBed(minutes, minutesUntilBed)} before bed"
            }
        }
    }
    return parts.joinToString(" · ")
}

/**
 * A poster in a fixed box, with an optional tag in the corner and the name along the bottom. Lit (chosen, or under
 * the mouse when it can be clicked), it glows in its own colours instead of taking an accent border.
 */
@Composable
internal fun TonightPoster(
    title: TonightTitle?,
    width: Dp,
    height: Dp,
    selected: Boolean,
    tag: String? = null,
    tagColor: Color = TextDim,
    tagAtEnd: Boolean = false,
    emptyText: String = "",
    onClick: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val shape = RoundedCornerShape(20.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val art = title?.let { it.poster ?: it.backdrop }
    val lit = title != null && (selected || (hovered && onClick != null))
    val glow = rememberGlowOf(art, lerp(accent, Color.White, 0.2f))
    Box(modifier = Modifier.size(width = width, height = height).zIndex(if (lit) 1f else 0f)) {
        AmbientGlow(lit = lit, colour = glow, cornerRadius = 20.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .smoothRoundClip(20.dp)
                .background(Ground)
                .background(GlassFill)
                .hoverable(hover)
                .then(if (onClick != null && title != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            if (title == null) {
                Text(
                    emptyText, color = TextDim, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                if (art != null) {
                    NuvioAsyncImage(model = art, contentDescription = title.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.3f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                )
                if (tag != null) {
                    Text(
                        tag, color = tagColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
                        modifier = Modifier.align(if (tagAtEnd) Alignment.TopEnd else Alignment.TopStart).padding(18.dp),
                    )
                }
                Text(
                    title.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(22.dp),
                )
            }
            if (!lit) Box(Modifier.fillMaxSize().smoothBorder(1.dp, GlassEdge, shape))
        }
    }
}

@Composable
internal fun CountdownRing(secondsLeft: Int, total: Int, size: Dp, paused: Boolean) {
    val accent = MaterialTheme.nuvio.colors.accent
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val fraction = if (total <= 0) 0f else secondsLeft.toFloat() / total
            drawArc(
                if (paused) TextFaint else accent, -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            if (paused) "II" else secondsLeft.toString(),
            color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold,
        )
    }
}

// ---- Coin Toss ---------------------------------------------------------------------------------

@Composable
internal fun TonightCoinTossView(
    pool: List<TonightTitle>,
    taste: TonightTaste,
    clock: TonightClock,
    minutesUntilBed: Int,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onDetails: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val vetoes = remember { mutableStateListOf<TonightVeto>() }
    val tossSeed = (clock.night.hashCode().toLong() shl 8) + vetoes.size
    val current = remember(pool, vetoes.size) { tonightToss(pool, taste, vetoes, tossSeed) }
    var secondsLeft by remember(current?.key) { mutableIntStateOf(TONIGHT_COIN_SECONDS) }
    var paused by remember(current?.key) { mutableStateOf(false) }
    val focus = rememberScreenFocus(Unit)

    LaunchedEffect(current?.key, paused) {
        val title = current ?: return@LaunchedEffect
        while (!paused && secondsLeft > 0) {
            delay(1_000)
            if (!paused) secondsLeft -= 1
        }
        if (!paused && secondsLeft <= 0) onPlay(title)
    }

    fun veto() {
        val title = current ?: return
        vetoes += TonightVeto(title, tonightVetoTrait(title, pool))
    }

    // fork:phone-remote tonight - ◀ not this, OK play, ▲ hold, ▼ its page, all as the TV's keys.
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.COIN_TOSS,
            loading = loading && current == null,
            number = vetoes.size + 1,
            cards = listOfNotNull(current?.remoteCard("NUVIO PICKED THIS", "", clock, minutesUntilBed)),
            answers = vetoes.takeLast(3).map { v -> v.title.name + (v.trait?.let { " · ${it.reason}" } ?: "") },
            extra = tonightLeanLabel(vetoes),
            seconds = if (current == null) -1 else secondsLeft,
            secondsTotal = TONIGHT_COIN_SECONDS,
            paused = paused,
        ),
    )

    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(rememberColoursOf(current?.let { it.poster ?: it.backdrop }))
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Left -> veto()
                    TonightKey.Ok -> current?.let(onPlay)
                    TonightKey.Up -> paused = !paused
                    TonightKey.Down -> current?.let { paused = true; onDetails(it) }
                    TonightKey.Right -> Unit
                    TonightKey.Back -> onBack()
                }
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        Text("COIN TOSS · TOSS ${vetoes.size + 1}", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.height(24.dp))
        Spacer(Modifier.height(36.dp))
        // Centred as one block (user, Nov 12: aligned like the other pages), its buttons in the shared strip below.
        Row(
            modifier = Modifier.fillMaxWidth().height(530.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonightPoster(
                title = current,
                width = 360.dp,
                height = 530.dp,
                selected = true,
                emptyText = if (loading) "Still gathering tonight's titles…" else "That's everything that fits tonight.",
            )
            Spacer(Modifier.width(64.dp))
            Column(modifier = Modifier.width(620.dp)) {
                Text("NUVIO PICKED THIS FOR YOU", color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    current?.name ?: "", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(56.dp),
                )
                Text(
                    current?.tonightMetaLine(clock, minutesUntilBed).orEmpty(),
                    color = TextDim, fontSize = 18.sp, maxLines = 1, modifier = Modifier.height(26.dp),
                )
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountdownRing(secondsLeft = if (current == null) 0 else secondsLeft, total = TONIGHT_COIN_SECONDS, size = 150.dp, paused = paused || current == null)
                    Spacer(Modifier.width(30.dp))
                    Text(
                        if (paused) "Countdown held. OK plays it, ▲ starts the clock again."
                        else "Do nothing and it plays.\nFeel a \"no\"? That's the point: ◀ tosses again.",
                        color = Color(0xFFCFCCC3), fontSize = 18.sp, lineHeight = 27.sp,
                        modifier = Modifier.width(380.dp).height(84.dp),
                    )
                }
                Spacer(Modifier.height(36.dp))
                VetoPanel(vetoes)
            }
        }
        TonightControls(hint = "◀ toss again · OK plays · ▲ holds · ▼ opens the detail page · Back returns to the launcher") {
            TonightBackPill(onBack = onBack)
            GlassPill("◀ Not this, toss again", selected = false, accent = accent, onClick = ::veto, enabled = current != null, pill = true)
            GlassPill("OK Play now", selected = true, accent = accent, onClick = { current?.let(onPlay) }, enabled = current != null, pill = true)
            GlassPill(if (paused) "▲ Start the clock" else "▲ Hold", selected = false, accent = accent, onClick = { paused = !paused }, enabled = current != null, pill = true)
        }
        Spacer(Modifier.weight(1f))
    }
    }
}

@Composable
private fun VetoPanel(vetoes: List<TonightVeto>) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(620.dp)
            .height(112.dp)
            .smoothRoundClip(16.dp)
            .background(Color.White.copy(alpha = 0.05f))
            .smoothBorder(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("WHAT YOUR \"NO\"S SAID", color = TextFaint, fontSize = 13.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
            if (vetoes.isEmpty()) {
                Text("Nothing yet. Each ◀ teaches the next toss.", color = TextDim, fontSize = 15.sp)
            } else {
                // The last three, oldest first, each in a fixed-width chip.
                for (veto in vetoes.takeLast(3)) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .width(150.dp)
                            .height(30.dp)
                            .smoothBorder(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            veto.title.name + (veto.trait?.let { " · ${it.reason}" } ?: ""),
                            color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                tonightLeanLabel(vetoes)?.let {
                    Text("→ $it", color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

// ---- Knockout ----------------------------------------------------------------------------------

private const val KNOCKOUT_WINNER_SECONDS = 5

@Composable
internal fun TonightKnockoutView(
    pool: List<TonightTitle>,
    taste: TonightTaste,
    clock: TonightClock,
    minutesUntilBed: Int,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val entrants = remember(pool.isEmpty()) { tonightKnockoutEntrants(pool, taste, clock.night.hashCode().toLong()) }
    var bracket by remember(entrants) { mutableStateOf(TonightBracket(entrants)) }
    val champion = bracket.champion
    var secondsLeft by remember(champion?.key) { mutableIntStateOf(KNOCKOUT_WINNER_SECONDS) }
    // Phase 3: Down plays a 20 s taste of each side of the match, left then right.
    var tasting by remember(bracket.winners.size) { mutableStateOf<Int?>(null) }
    val focus = rememberScreenFocus(Unit)
    val match = bracket.currentMatch

    LaunchedEffect(champion?.key) {
        val winner = champion ?: return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
        onPlay(winner)
    }

    fun taste() {
        if (champion != null || bracket.currentMatch == null) return
        tasting = when (tasting) {
            null -> 0
            0 -> 1
            else -> null
        }
    }

    // The wash: the winner's colours, or the match's two, the right poster's lighting the top right and the left's
    // the lower left, so the room is split between them.
    val leftColours = rememberColoursOf(match?.first?.let { it.poster ?: it.backdrop })
    val rightColours = rememberColoursOf(match?.second?.let { it.poster ?: it.backdrop })
    val championColours = rememberColoursOf(champion?.let { it.poster ?: it.backdrop })
    val wash = championColours ?: listOfNotNull(
        rightColours?.firstOrNull() ?: leftColours?.firstOrNull(),
        leftColours?.firstOrNull(),
        rightColours?.getOrNull(1),
    ).takeIf { it.isNotEmpty() }

    // fork:phone-remote tonight - tap a side to keep it (◀ ▶), Taste (▼), Back undoes, OK plays the winner.
    val phoneMatch = bracket.currentMatch
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.KNOCKOUT,
            loading = loading && bracket.entrants.size < 2,
            prompt = when {
                champion != null -> "Tonight's winner"
                bracket.entrants.size < 2 -> null
                bracket.roundNumber == bracket.roundCount -> "The final"
                else -> "Round ${bracket.roundNumber} of ${bracket.roundCount}"
            },
            number = bracket.roundNumber,
            total = bracket.roundCount,
            countLeft = bracket.entrants.size,
            cards = when {
                champion != null -> listOf(champion.remoteCard("WINNER", "", clock, minutesUntilBed))
                phoneMatch != null -> listOf(
                    phoneMatch.first.remoteCard("◀ KEEP", "", clock, minutesUntilBed),
                    phoneMatch.second.remoteCard("KEEP ▶", "", clock, minutesUntilBed),
                )
                else -> emptyList()
            },
            extra = phoneMatch?.let { m -> tasting?.let { "Taste ${it + 1} of 2 · ${if (it == 0) m.first.name else m.second.name}" } },
            seconds = if (champion != null) secondsLeft else -1,
            secondsTotal = KNOCKOUT_WINNER_SECONDS,
        ),
    )

    // Laid out like Three Cards and Three Questions (user, Nov 12: one system): the heading and its line at the top
    // left, the match centred on the screen in the same band as the dealt cards, the strip of buttons straight under
    // it, and the bracket in the free space on the left, beside rather than in the middle's way.
    Box(modifier = Modifier.fillMaxSize()) {
    TonightAmbientWash(wash)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Left -> if (champion == null) bracket = bracket.keep(left = true)
                    TonightKey.Right -> if (champion == null) bracket = bracket.keep(left = false)
                    TonightKey.Ok -> champion?.let(onPlay)
                    TonightKey.Back -> when {
                        tasting != null -> tasting = null
                        bracket.winners.isEmpty() -> onBack()
                        else -> bracket = bracket.undo()
                    }
                    TonightKey.Down -> taste()
                    TonightKey.Up -> Unit
                }
                true
            }
            .padding(horizontal = Side, vertical = 36.dp),
    ) {
        if (bracket.entrants.size >= 2) {
            // The posters fill the left from under the heading to the bottom (eight down the first column at most),
            // and never reach into the centre band.
            val rows = bracket.entrants.size
            val columns = bracket.roundCount + 1
            val bracketHeight = maxHeight - KnockoutHeaderHeight - KnockoutHeaderGap
            val sideRoom = (maxWidth - KnockoutCentreWidth) / 2 - 40.dp
            val byHeight = (bracketHeight - BracketPadding * 2 - BracketGap * (rows - 1)) / rows
            val byWidth = (sideRoom - BracketPadding * 2 - 22.dp * (columns - 1)) / columns * 1.5f
            val thumbHeight = minOf(byHeight, byWidth).coerceIn(36.dp, 120.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = KnockoutHeaderHeight + KnockoutHeaderGap)
                    .height(bracketHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                BracketPanel(bracket, thumbHeight * 2f / 3f, thumbHeight)
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().height(KnockoutHeaderHeight)) {
                Text(
                    when {
                        champion != null -> "KNOCKOUT · TONIGHT'S WINNER"
                        bracket.entrants.size < 2 -> "KNOCKOUT"
                        bracket.roundNumber == bracket.roundCount -> "KNOCKOUT · THE FINAL"
                        else -> "KNOCKOUT · ROUND ${bracket.roundNumber} OF ${bracket.roundCount}"
                    },
                    color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp,
                )
                Text(
                    if (champion != null) "The last one standing plays" else "Keep one of each pair; the last one standing plays",
                    color = TextFaint, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(KnockoutHeaderGap))
            Box(modifier = Modifier.fillMaxWidth().height(KnockoutAreaHeight), contentAlignment = Alignment.Center) {
                when {
                    bracket.entrants.size < 2 -> Text(
                        if (loading) "Still gathering tonight's titles…" else "Knockout needs at least two titles that fit tonight.",
                        color = TextDim, fontSize = 22.sp, textAlign = TextAlign.Center,
                    )
                    champion != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TonightPoster(champion, 330.dp, 470.dp, selected = true, tag = "WINNER", tagColor = accent, onClick = { onPlay(champion) })
                        Spacer(Modifier.width(48.dp))
                        Column {
                            Text(champion.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(420.dp).height(104.dp))
                            Text(champion.tonightMetaLine(clock, minutesUntilBed), color = TextDim, fontSize = 18.sp, maxLines = 1, modifier = Modifier.height(26.dp))
                            Spacer(Modifier.height(24.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CountdownRing(secondsLeft, KNOCKOUT_WINNER_SECONDS, 110.dp, paused = false)
                                Spacer(Modifier.width(24.dp))
                                Text("Playing in a moment.\nOK now · Back reopens the final", color = Color(0xFFCFCCC3), fontSize = 17.sp, lineHeight = 26.sp)
                            }
                        }
                    }
                    match != null && tasting != null -> {
                        val side = tasting ?: 0
                        val tasted = if (side == 0) match.first else match.second
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "TASTE ${side + 1} OF 2 · ${tasted.name}",
                                color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(800.dp).height(22.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            TonightTrailerFrame(
                                title = tasted,
                                modifier = Modifier.width(800.dp).height(450.dp),
                                limitMs = 20_000L,
                                onFinished = { tasting = if (side == 0) 1 else null },
                                onReclaimFocus = { focus.requestFocusLogged() },
                            )
                        }
                    }
                    match != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TonightPoster(match.first, 330.dp, 470.dp, selected = false, tag = "◀ KEEP", tagColor = accent, onClick = { bracket = bracket.keep(left = true) })
                            Text(match.first.tonightMetaLine(clock, minutesUntilBed), color = TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(330.dp).padding(top = 14.dp).height(22.dp))
                        }
                        Text("vs", color = TextFaint, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 28.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TonightPoster(match.second, 330.dp, 470.dp, selected = false, tag = "KEEP ▶", tagColor = accent, tagAtEnd = true, onClick = { bracket = bracket.keep(left = false) })
                            Text(match.second.tonightMetaLine(clock, minutesUntilBed), color = TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(330.dp).padding(top = 14.dp).height(22.dp))
                        }
                    }
                }
            }
            TonightControls(
                hint = if (champion != null) "OK plays now · Back undoes the final"
                else if (tasting != null) "◀ ▶ keep one · ▼ next taste · Back stops the tastes"
                else "◀ ▶ keep one · ▼ a 20 second taste of each · Back undoes the last pick",
            ) {
                TonightBackPill(onBack = onBack)
                GlassPill("Undo last pick", selected = false, accent = accent, enabled = bracket.winners.isNotEmpty(), pill = true, onClick = { tasting = null; bracket = bracket.undo() })
                if (champion != null) {
                    GlassPill("OK Play now", selected = true, accent = accent, pill = true, onClick = { onPlay(champion) })
                } else {
                    GlassPill(if (tasting == null) "▼ Taste each" else "▼ Next taste", selected = false, accent = accent, enabled = match != null, pill = true, onClick = ::taste)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
    }
}

/** Three Cards' frame, so the two pages line up: a 56 high heading, a 36 gap, and a 520 high band for the match. */
private val KnockoutHeaderHeight = 56.dp
private val KnockoutHeaderGap = 36.dp
private val KnockoutAreaHeight = 520.dp
/** The widest thing in the centre band (a taste's frame), which the bracket stays clear of. */
private val KnockoutCentreWidth = 800.dp

private val BracketPadding = 18.dp
private val BracketGap = 8.dp

/**
 * The bracket as small posters (user, Nov 12: in place of the name boxes): one column per round and one for the
 * winner, each slot centred between the two that feed it. The pair in play glows, a title that lost fades back, and
 * a match not yet played is an empty glass slot. Sizes come from the screen, never from what is in the slots.
 */
@Composable
private fun BracketPanel(bracket: TonightBracket, thumbWidth: Dp, thumbHeight: Dp) {
    val shape = RoundedCornerShape(20.dp)
    val match = bracket.currentMatch
    val columns = bracket.roundCount + 1
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(thumbWidth * columns + 22.dp * (columns - 1) + BracketPadding * 2)
            .smoothRoundClip(20.dp)
            .background(Color.White.copy(alpha = 0.04f))
            .smoothBorder(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(BracketPadding),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (columnIndex in 0 until columns) {
            val column = bracket.column(columnIndex)
            val next = bracket.column(columnIndex + 1)
            Column(
                modifier = Modifier.width(thumbWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceAround,
            ) {
                column.forEachIndexed { i, title ->
                    val inPlay = title != null && match != null && columnIndex == bracket.roundNumber - 1 &&
                        (title.key == match.first.key || title.key == match.second.key)
                    val decided = next.getOrNull(i / 2)
                    val out = title != null && decided != null && decided.key != title.key
                    val isChampion = columnIndex == columns - 1 && title != null
                    BracketThumb(title, thumbWidth, thumbHeight, lit = inPlay || isChampion, out = out)
                }
            }
        }
    }
}

@Composable
private fun BracketThumb(title: TonightTitle?, width: Dp, height: Dp, lit: Boolean, out: Boolean) {
    val accent = MaterialTheme.nuvio.colors.accent
    val art = title?.let { it.poster ?: it.backdrop }
    val glow = rememberGlowOf(art, lerp(accent, Color.White, 0.2f))
    val shape = RoundedCornerShape(10.dp)
    // No bloom here (user, Nov 12): the thumbs sit too close and the halos ran into each other. The pair in play
    // gets an edge in its own colour, drawn inside the thumb so it never reaches a neighbour.
    Box(modifier = Modifier.size(width = width, height = height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (out) 0.28f else 1f }
                .smoothRoundClip(10.dp)
                .background(Ground)
                .background(GlassFill),
        ) {
            if (art != null) {
                NuvioAsyncImage(model = art, contentDescription = title.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (title != null) {
                Text(
                    title.name, color = TextDim, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(4.dp),
                )
            }
            Box(Modifier.fillMaxSize().smoothBorder(if (lit) 2.dp else 1.dp, if (lit) glow else GlassEdge, shape))
        }
    }
}
