package com.nuvio.app.features.preroll

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The post-show cards, drawn around the corner box the video has shrunk into.
 *
 * Reads [PostShowController] and draws; every decision is in the controller. The reading column
 * stops short of the right-hand side, where the video sits on top of this screen, so no text ever
 * runs under it. Keys normally arrive through the controls page (it holds keyboard focus while the
 * video plays) and reach the controller from there; this screen also takes them directly, for the
 * moments Compose has focus. With a mouse, clicking a card moves on and Close closes.
 */
@Composable
fun PostShowScreen(
    modifier: Modifier = Modifier,
    /** The player's skip-credits prompt, drawn here because the controls page is hidden while the cards are up. Null: none. */
    skipLabel: String? = null,
    skipKeyLabel: String = "",
    onSkip: () -> Unit = {},
) {
    val pack = PostShowController.pack ?: return
    val view = PostShowController.view
    val questions = PostShowController.questions
    val stage = view.stage
    val palette = remember(pack) { pack.theme.toPalette() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // How long the current card has been up. Holding a card stops the clock; questions never advance by themselves.
    var elapsedMs by remember(stage) { mutableStateOf(0L) }
    val readingMs = (stage as? PreShowStage.Slide)
        ?.let { pack.cards.getOrNull(it.index) }
        ?.let { slideReadingTimeMs(it.text, TRIVIA_SLIDE_DURATION_MS) }
        ?: TRIVIA_SLIDE_DURATION_MS
    LaunchedEffect(stage, view.held) {
        if (stage !is PreShowStage.Slide || view.held) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (elapsedMs < readingMs) {
            val now = withFrameMillis { it }
            elapsedMs += now - last
            last = now
        }
        PostShowController.autoAdvance()
    }

    val imageIndex = when (stage) {
        is PreShowStage.Slide -> stage.index
        is PreShowStage.Question -> pack.cards.size + stage.index
        PreShowStage.Finished -> 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mapped = when (event.key) {
                    Key.Escape, Key.ButtonB, Key.Back -> PostShowKey.Back
                    Key.DirectionUp -> PostShowKey.Up
                    Key.DirectionDown -> PostShowKey.Down
                    Key.DirectionLeft -> PostShowKey.Left
                    Key.DirectionRight -> PostShowKey.Right
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA -> PostShowKey.Select
                    Key.One, Key.NumPad1 -> PostShowKey.Option1
                    Key.Two, Key.NumPad2 -> PostShowKey.Option2
                    Key.Three, Key.NumPad3 -> PostShowKey.Option3
                    Key.Four, Key.NumPad4 -> PostShowKey.Option4
                    else -> null
                } ?: return@onPreviewKeyEvent false
                PostShowController.press(mapped)
                true
            },
    ) {
        TriviaBackdrop(
            backdrops = emptyList(),
            imageIndex = imageIndex,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = PostShowController.title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f),
            letterSpacing = 3.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp),
        )

        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "postShowStage",
            modifier = Modifier.align(Alignment.CenterStart),
        ) { shown ->
            Column(
                // Leaves the right third to the video box.
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .then(
                        // A click on a card moves on; a question has its own buttons.
                        if (shown is PreShowStage.Slide) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { PostShowController.press(PostShowKey.Right) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 56.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (shown) {
                    is PreShowStage.Slide -> pack.cards.getOrNull(shown.index)?.let {
                        CardBody(it, palette, finale = PostShowController.isFinale)
                    }
                    is PreShowStage.Question -> questions.getOrNull(shown.index)?.let {
                        QuestionBody(
                            question = it,
                            number = shown.index + 1,
                            total = questions.size,
                            picked = shown.picked,
                            cursor = view.cursor,
                            palette = palette,
                            onPick = { option -> PostShowController.press(optionKey(option)) },
                            onNext = { PostShowController.press(PostShowKey.Select) },
                        )
                    }
                    PreShowStage.Finished -> Unit
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (view.held) {
                Text(
                    text = "Held",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            TextButton(onClick = { PostShowController.press(PostShowKey.Back) }) {
                Text("Close", color = Color.White.copy(alpha = 0.85f))
            }
        }

        if (skipLabel != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 56.dp, bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, palette.accent.copy(alpha = 0.7f)), RoundedCornerShape(10.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skipLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                if (skipKeyLabel.isNotBlank()) {
                    Text(
                        text = skipKeyLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                }
            }
        }

        ProgressBars(
            total = pack.cards.size + questions.size,
            current = imageIndex,
            currentFraction = {
                if (stage is PreShowStage.Slide) (elapsedMs.toFloat() / readingMs).coerceIn(0f, 1f) else 1f
            },
            palette = palette,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 56.dp, end = 24.dp, bottom = 40.dp),
        )
    }
}

private fun optionKey(index: Int): PostShowKey = when (index) {
    0 -> PostShowKey.Option1
    1 -> PostShowKey.Option2
    2 -> PostShowKey.Option3
    else -> PostShowKey.Option4
}

@Composable
private fun CardBody(card: PostShowCard, palette: TriviaPalette, finale: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(36.dp).height(3.dp).background(palette.accent, RoundedCornerShape(2.dp)))
        Text(
            text = card.kind.heading(finale),
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent,
            letterSpacing = 3.sp,
        )
    }
    Text(
        text = card.text,
        style = MaterialTheme.typography.headlineMedium.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 14f),
        ),
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
}

internal fun PostShowCardKind.heading(finale: Boolean = false): String = when (this) {
    PostShowCardKind.Ending -> if (finale) "THE FINALE" else "THE ENDING"
    PostShowCardKind.EasterEgg -> "HIDDEN DETAIL"
    PostShowCardKind.WatchAgain -> "WATCH AGAIN"
    PostShowCardKind.Motivation -> "WHY THEY DID IT"
    PostShowCardKind.Connection -> "CONNECTIONS"
    PostShowCardKind.BehindTheScenes -> "BEHIND THE SCENES"
    PostShowCardKind.Themes -> "THEMES"
    PostShowCardKind.Source -> "SOURCE"
}
