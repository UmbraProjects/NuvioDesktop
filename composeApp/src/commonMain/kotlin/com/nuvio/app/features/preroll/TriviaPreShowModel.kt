package com.nuvio.app.features.preroll

import kotlin.random.Random

/**
 * A question with its options already in display order.
 *
 * The model's own order is kept in [TriviaQuestion] (it is what the cache stores); shuffling
 * happens once per showing, here, so the right answer is not always where the provider put it.
 * [correctIndex] points into [options], never into the original list.
 */
data class ShuffledQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String?,
)

/**
 * Shuffles [this] question's options, or returns null when it cannot be asked honestly: an
 * answer index outside the options, or fewer than two options. The parser already rejects both,
 * so this is the last line of defence for a cache entry written by an older build.
 */
fun TriviaQuestion.shuffled(random: Random = Random.Default): ShuffledQuestion? {
    if (options.size < 2 || answerIndex !in options.indices) return null
    val order = options.indices.shuffled(random)
    return ShuffledQuestion(
        question = question,
        options = order.map { options[it] },
        correctIndex = order.indexOf(answerIndex),
        explanation = explanation?.takeIf { it.isNotBlank() },
    )
}

/** The questions of [this] pack that can be asked, shuffled. */
fun TriviaPack.askableQuestions(random: Random = Random.Default): List<ShuffledQuestion> =
    questions.mapNotNull { it.shuffled(random) }

/** Where the pre-show is: reading slides, answering questions, or done. */
sealed interface PreShowStage {
    data class Slide(val index: Int) : PreShowStage
    data class Question(val index: Int, val picked: Int?) : PreShowStage
    data object Finished : PreShowStage
}

/**
 * The pre-show's progression as pure functions, so the order (slides first, then questions, then
 * finished) and the "an answer locks until Next" rule are testable without a screen.
 */
fun PreShowStage.advance(slideCount: Int, questionCount: Int): PreShowStage = when (this) {
    is PreShowStage.Slide ->
        when {
            index + 1 < slideCount -> PreShowStage.Slide(index + 1)
            questionCount > 0 -> PreShowStage.Question(0, picked = null)
            else -> PreShowStage.Finished
        }

    is PreShowStage.Question ->
        when {
            // Next is only meaningful once an answer is in; before that it does nothing.
            picked == null -> this
            index + 1 < questionCount -> PreShowStage.Question(index + 1, picked = null)
            else -> PreShowStage.Finished
        }

    PreShowStage.Finished -> this
}

/**
 * Steps back one slide. From the first question it returns to the last slide (the quiz starts over
 * when the viewer comes back to it), but never into a question already answered or past the first
 * slide: answers stay locked, and there is nothing before slide 0 to go back to.
 */
fun PreShowStage.back(slideCount: Int): PreShowStage = when (this) {
    is PreShowStage.Slide -> if (index > 0) PreShowStage.Slide(index - 1) else this
    is PreShowStage.Question ->
        if (index == 0 && picked == null && slideCount > 0) PreShowStage.Slide(slideCount - 1) else this
    PreShowStage.Finished -> this
}

/** Time allowed for a slide's lead-in, before the words start to count. */
private const val SLIDE_LEAD_IN_MS = 1_500L

/** Roughly three words a second, comfortable for reading large text from a sofa. */
private const val SLIDE_MS_PER_WORD = 330L

/** A slide never holds longer than this on its own; the viewer can still hold it by hand. */
private const val SLIDE_MAX_MS = 25_000L

/**
 * How long [text] stays on screen before the next slide: a short lead-in plus time per word, never
 * less than [minMs] (so short facts keep the old fixed pace) and never more than [SLIDE_MAX_MS].
 */
fun slideReadingTimeMs(text: String, minMs: Long): Long {
    val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
    val wanted = SLIDE_LEAD_IN_MS + words * SLIDE_MS_PER_WORD
    return wanted.coerceAtLeast(minMs).coerceAtMost(maxOf(minMs, SLIDE_MAX_MS))
}

/** The first stage for a pack with these counts. */
fun initialPreShowStage(slideCount: Int, questionCount: Int): PreShowStage = when {
    slideCount > 0 -> PreShowStage.Slide(0)
    questionCount > 0 -> PreShowStage.Question(0, picked = null)
    else -> PreShowStage.Finished
}

/** Records an answer. A second tap on the same question does not change it. */
fun PreShowStage.answer(option: Int): PreShowStage =
    if (this is PreShowStage.Question && picked == null) copy(picked = option) else this

/**
 * Moves the highlighted answer by [delta] (-1 up, +1 down), stopping at the first and last option
 * rather than wrapping, so a held d-pad key does not loop past the answer the viewer wanted.
 */
fun moveAnswerCursor(current: Int, delta: Int, optionCount: Int): Int =
    if (optionCount <= 0) 0 else (current + delta).coerceIn(0, optionCount - 1)
