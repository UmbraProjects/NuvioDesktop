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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.action_next
import nuvio.composeapp.generated.resources.trivia_category_awards
import nuvio.composeapp.generated.resources.trivia_category_cast
import nuvio.composeapp.generated.resources.trivia_category_legacy
import nuvio.composeapp.generated.resources.trivia_category_production
import nuvio.composeapp.generated.resources.trivia_category_trivia
import nuvio.composeapp.generated.resources.trivia_correct
import nuvio.composeapp.generated.resources.trivia_held
import nuvio.composeapp.generated.resources.trivia_hint_question
import nuvio.composeapp.generated.resources.trivia_incorrect
import nuvio.composeapp.generated.resources.trivia_not_right
import nuvio.composeapp.generated.resources.trivia_origin_ai
import nuvio.composeapp.generated.resources.trivia_origin_metadata
import nuvio.composeapp.generated.resources.trivia_origin_wikipedia
import nuvio.composeapp.generated.resources.trivia_question_progress
import nuvio.composeapp.generated.resources.trivia_skip
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The least time a slide stays before the next one, unless the viewer advances first. Longer slides
 * stay longer: see [slideReadingTimeMs].
 */
const val TRIVIA_SLIDE_DURATION_MS = 7_000L

/**
 * The pre-show: trivia slides that advance on their own, then a short multiple-choice quiz.
 *
 * Purely presentational: it is handed a ready [pack] and reports back through [onFinished] (the
 * viewer reached the end *or* pressed skip). Fetching, caching and deciding *when* to show it are
 * the caller's job, so this can be dropped into the player's opening overlay without knowing
 * anything about playback.
 *
 * Keys: Enter / Space / Right / pad A advance a slide, or pick the highlighted answer on a question
 * (Up / Down move the highlight, 1-4 answer directly), and Enter / Space / Right / pad A go to the next
 * question once answered. Left goes back a slide. On a slide, Up / Down, P, play/pause or pad X hold it
 * (the clock stops) and press again to release. Escape or pad B / Back skips the whole pre-show, like it
 * does on a trailer.
 */
@Composable
fun TriviaPreShow(
    pack: TriviaPack,
    title: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: TriviaArtwork = TriviaArtwork(),
    slideDurationMs: Long = TRIVIA_SLIDE_DURATION_MS,
    /**
     * Called when the viewer says these facts are wrong. The pre-show ends right after; what to
     * forget is the caller's business. Offered only for packs that can be wrong (not details-only).
     */
    onNotRight: (() -> Unit)? = null,
    /** True while a quiz question is on screen, false on the slides; lets a caller lower background music. */
    onQuestionActive: (Boolean) -> Unit = {},
    /**
     * Escape and pad B / Back. The pre-show gate passes "skip everything" here so the keys mean the
     * same thing as they do over a trailer; null falls back to ending just the trivia.
     */
    onSkipAll: (() -> Unit)? = null,
) {
    val questions = remember(pack) { pack.askableQuestions() }
    val slides = pack.slides
    var stage by remember(pack) {
        mutableStateOf(initialPreShowStage(slides.size, questions.size))
    }
    val finish by rememberUpdatedState(onFinished)
    val skipAll by rememberUpdatedState(onSkipAll ?: onFinished)
    // The answer under the d-pad cursor; starts at the top for each question.
    var cursor by remember(stage) { mutableStateOf(0) }
    val reportQuestionActive by rememberUpdatedState(onQuestionActive)
    val focus = remember { FocusRequester() }
    val palette = remember(pack) { pack.theme.toPalette() }

    fun next() {
        stage = stage.advance(slides.size, questions.size)
    }

    LaunchedEffect(stage) {
        if (stage == PreShowStage.Finished) finish()
    }
    LaunchedEffect(stage is PreShowStage.Question) { reportQuestionActive(stage is PreShowStage.Question) }
    // How long the current slide has been on screen. One clock drives both the auto-advance and the
    // progress bar, so they cannot drift apart, and holding a slide just stops the clock.
    var elapsedMs by remember(stage) { mutableStateOf(0L) }
    var held by remember(stage) { mutableStateOf(false) }
    val currentSlideMs = (stage as? PreShowStage.Slide)?.let { shown ->
        slides.getOrNull(shown.index)?.let { slideReadingTimeMs(it.text, slideDurationMs) }
    } ?: slideDurationMs
    // Slides advance by themselves, at a pace that follows their length; questions never do, since
    // the viewer is mid-thought.
    LaunchedEffect(stage, held) {
        val current = stage
        if (current !is PreShowStage.Slide || held) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (elapsedMs < currentSlideMs) {
            val now = withFrameMillis { it }
            elapsedMs += now - last
            last = now
        }
        stage = current.advance(slides.size, questions.size)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val current = stage
                val optionCount = (current as? PreShowStage.Question)
                    ?.let { questions.getOrNull(it.index)?.options?.size } ?: 0
                val unanswered = current is PreShowStage.Question && current.picked == null
                when (event.key) {
                    Key.Escape, Key.ButtonB, Key.Back -> { skipAll(); true }
                    // On a question these move the answer cursor; on a slide they hold or release it.
                    Key.DirectionUp -> when {
                        unanswered -> { cursor = moveAnswerCursor(cursor, -1, optionCount); true }
                        current is PreShowStage.Slide -> { held = !held; true }
                        else -> false
                    }
                    Key.DirectionDown -> when {
                        unanswered -> { cursor = moveAnswerCursor(cursor, 1, optionCount); true }
                        current is PreShowStage.Slide -> { held = !held; true }
                        else -> false
                    }
                    Key.P, Key.MediaPlayPause, Key.ButtonX ->
                        { if (current is PreShowStage.Slide) held = !held; true }
                    Key.DirectionLeft -> { stage = stage.back(slides.size); true }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA ->
                        { if (unanswered) stage = stage.answer(cursor) else next(); true }
                    Key.DirectionRight -> { next(); true }
                    Key.One, Key.NumPad1 -> { stage = stage.answer(0); true }
                    Key.Two, Key.NumPad2 -> { stage = stage.answer(1); true }
                    Key.Three, Key.NumPad3 -> { stage = stage.answer(2); true }
                    Key.Four, Key.NumPad4 -> { stage = stage.answer(3); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        // Each slide and question gets its own picture, working through the title's fanart.
        val imageIndex = when (val s = stage) {
            is PreShowStage.Slide -> s.index
            is PreShowStage.Question -> slides.size + s.index
            PreShowStage.Finished -> 0
        }
        TriviaBackdrop(
            backdrops = artwork.backdrops,
            imageIndex = imageIndex,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )

        // Who this is about: the film's own logo when there is one, its name otherwise.
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp)) {
            if (!artwork.logo.isNullOrBlank()) {
                NuvioAsyncImage(
                    model = artwork.logo,
                    contentDescription = title,
                    modifier = Modifier.height(64.dp).widthIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    letterSpacing = 3.sp,
                )
            }
        }

        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "triviaPreShowStage",
        ) { shown ->
            Column(
                modifier = Modifier.widthIn(max = 860.dp).padding(start = 56.dp, end = 56.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (shown) {
                    is PreShowStage.Slide -> slides.getOrNull(shown.index)?.let { SlideBody(it, palette) }
                    is PreShowStage.Question -> questions.getOrNull(shown.index)?.let {
                        QuestionBody(
                            question = it,
                            number = shown.index + 1,
                            total = questions.size,
                            picked = shown.picked,
                            cursor = cursor,
                            palette = palette,
                            onPick = { option -> stage = stage.answer(option) },
                            onNext = ::next,
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
            if (held) {
                Text(
                    text = stringResource(Res.string.trivia_held),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            // Where the facts came from, on every slide and question.
            Text(
                text = stringResource(pack.origin.label()),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            if (onNotRight != null && pack.origin != TriviaOrigin.Metadata) {
                TextButton(onClick = { onNotRight(); finish() }) {
                    Text(stringResource(Res.string.trivia_not_right), color = Color.White.copy(alpha = 0.85f))
                }
            }
            TextButton(onClick = { finish() }) {
                Text(stringResource(Res.string.trivia_skip), color = Color.White.copy(alpha = 0.85f))
            }
        }

        ProgressBars(
            total = slides.size + questions.size,
            current = imageIndex,
            // Only a slide fills over time; a question's segment is simply full while it is on screen.
            currentFraction = {
                if (stage is PreShowStage.Slide) (elapsedMs.toFloat() / currentSlideMs).coerceIn(0f, 1f) else 1f
            },
            palette = palette,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 56.dp, vertical = 40.dp),
        )
    }
}

@Composable
private fun ProgressBars(
    total: Int,
    current: Int,
    currentFraction: () -> Float,
    palette: TriviaPalette,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return
    // Read here, not in the caller, so the per-frame fill only recomposes this bar row.
    val fraction = currentFraction()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp)),
            ) {
                val fill = when {
                    i < current -> 1f
                    i == current -> fraction
                    else -> 0f
                }
                if (fill > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .background(palette.accent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlideBody(slide: TriviaSlide, palette: TriviaPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(36.dp).height(3.dp).background(palette.accent, RoundedCornerShape(2.dp)))
        Text(
            text = stringResource(slide.category.label()).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent,
            letterSpacing = 3.sp,
        )
    }
    Text(
        text = slide.text,
        style = MaterialTheme.typography.displaySmall.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 14f),
        ),
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
}

@Composable
private fun QuestionBody(
    question: ShuffledQuestion,
    number: Int,
    total: Int,
    picked: Int?,
    cursor: Int,
    palette: TriviaPalette,
    onPick: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = stringResource(Res.string.trivia_question_progress, number, total).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = palette.accent,
        letterSpacing = 3.sp,
    )
    Text(
        text = question.question,
        style = MaterialTheme.typography.headlineMedium.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 14f),
        ),
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.forEachIndexed { index, option ->
            val answered = picked != null
            val isCorrect = index == question.correctIndex
            val isPicked = index == picked
            val borderColor = when {
                answered && isCorrect -> colors.success
                answered && isPicked -> colors.danger
                !answered && index == cursor -> palette.accent
                else -> Color.White.copy(alpha = 0.28f)
            }
            val fill = if (!answered && index == cursor) 0.75f else 0.55f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = fill), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(12.dp))
                    .clickable(enabled = !answered) { onPick(index) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                )
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = if (answered && isCorrect) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    if (picked == null) {
        Text(
            text = stringResource(Res.string.trivia_hint_question),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
    if (picked != null) {
        val right = picked == question.correctIndex
        Text(
            text = stringResource(if (right) Res.string.trivia_correct else Res.string.trivia_incorrect),
            style = MaterialTheme.typography.titleMedium,
            color = if (right) colors.success else colors.danger,
        )
        question.explanation?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        }
        TextButton(onClick = onNext) {
            Text(stringResource(Res.string.action_next))
        }
    }
}

private fun TriviaCategory.label(): StringResource = when (this) {
    TriviaCategory.Production -> Res.string.trivia_category_production
    TriviaCategory.Cast -> Res.string.trivia_category_cast
    TriviaCategory.Legacy -> Res.string.trivia_category_legacy
    TriviaCategory.Awards -> Res.string.trivia_category_awards
    TriviaCategory.Trivia -> Res.string.trivia_category_trivia
}

private fun TriviaOrigin.label(): StringResource = when (this) {
    TriviaOrigin.Ai -> Res.string.trivia_origin_ai
    TriviaOrigin.Wikipedia -> Res.string.trivia_origin_wikipedia
    TriviaOrigin.Metadata -> Res.string.trivia_origin_metadata
}
