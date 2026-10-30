package com.nuvio.app.features.preroll

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriviaPreShowModelTest {
    private val question = TriviaQuestion(
        question = "Who directed it?",
        options = listOf("Ridley Scott", "James Cameron", "Steven Spielberg", "Denis Villeneuve"),
        answerIndex = 1,
        explanation = "  ",
    )

    @Test
    fun shuffleKeepsTheCorrectAnswerAttachedToItsText() {
        repeat(50) { seed ->
            val shuffled = question.shuffled(Random(seed))!!
            assertEquals("James Cameron", shuffled.options[shuffled.correctIndex])
            assertEquals(question.options.toSet(), shuffled.options.toSet())
        }
    }

    @Test
    fun blankExplanationBecomesNull() {
        assertNull(question.shuffled(Random(1))!!.explanation)
    }

    @Test
    fun unaskableQuestionsAreDropped() {
        assertNull(question.copy(answerIndex = 9).shuffled())
        assertNull(question.copy(options = listOf("Only one"), answerIndex = 0).shuffled())
        val pack = TriviaPack(questions = listOf(question, question.copy(answerIndex = -1)))
        assertEquals(1, pack.askableQuestions().size)
    }

    @Test
    fun slidesComeBeforeQuestionsThenFinished() {
        var stage = initialPreShowStage(slideCount = 2, questionCount = 1)
        assertEquals(PreShowStage.Slide(0), stage)
        stage = stage.advance(2, 1)
        assertEquals(PreShowStage.Slide(1), stage)
        stage = stage.advance(2, 1)
        assertEquals(PreShowStage.Question(0, picked = null), stage)
        stage = stage.answer(2).advance(2, 1)
        assertEquals(PreShowStage.Finished, stage)
    }

    @Test
    fun nextIsIgnoredUntilAnAnswerIsPicked() {
        val stage = PreShowStage.Question(0, picked = null)
        assertEquals(stage, stage.advance(slideCount = 0, questionCount = 3))
    }

    @Test
    fun firstAnswerLocks() {
        val stage = PreShowStage.Question(0, picked = null).answer(1).answer(3)
        assertEquals(PreShowStage.Question(0, picked = 1), stage)
    }

    @Test
    fun packWithOnlyOneKindStillRuns() {
        assertEquals(PreShowStage.Question(0, null), initialPreShowStage(0, 2))
        assertEquals(PreShowStage.Finished, initialPreShowStage(0, 0))
        assertEquals(PreShowStage.Finished, PreShowStage.Slide(0).advance(slideCount = 1, questionCount = 0))
        assertTrue(PreShowStage.Finished.advance(1, 1) == PreShowStage.Finished)
    }
}

class PreShowPacingTest {
    private fun words(count: Int) = List(count) { "word" }.joinToString(" ")

    @Test
    fun shortSlidesKeepTheFloor() {
        assertEquals(7_000L, slideReadingTimeMs("Filmed in 1979.", minMs = 7_000))
        assertEquals(7_000L, slideReadingTimeMs("   ", minMs = 7_000))
    }

    @Test
    fun longerSlidesStayLonger() {
        // 1.5 s lead-in + 30 words at 330 ms.
        assertEquals(11_400L, slideReadingTimeMs(words(30), minMs = 7_000))
        assertTrue(slideReadingTimeMs(words(40), 7_000) > slideReadingTimeMs(words(30), 7_000))
    }

    @Test
    fun readingTimeIsCapped() {
        assertEquals(25_000L, slideReadingTimeMs(words(200), minMs = 7_000))
    }

    @Test
    fun aFloorAboveTheCapIsStillRespected() {
        assertEquals(30_000L, slideReadingTimeMs("A few words", minMs = 30_000))
        assertEquals(30_000L, slideReadingTimeMs(words(200), minMs = 30_000))
    }

    @Test
    fun wordsAreCountedAcrossAnyWhitespace() {
        assertEquals(
            slideReadingTimeMs(words(30), 0),
            slideReadingTimeMs(List(30) { "word" }.joinToString("  \n\t "), 0),
        )
    }

    @Test
    fun backStepsThroughSlides() {
        assertEquals(PreShowStage.Slide(1), PreShowStage.Slide(2).back(slideCount = 3))
        assertEquals(PreShowStage.Slide(0), PreShowStage.Slide(0).back(slideCount = 3))
    }

    @Test
    fun backFromTheFirstQuestionReturnsToTheLastSlide() {
        assertEquals(PreShowStage.Slide(2), PreShowStage.Question(0, picked = null).back(slideCount = 3))
    }

    @Test
    fun backNeverReopensAnAnsweredQuestion() {
        val answered = PreShowStage.Question(0, picked = 1)
        assertEquals(answered, answered.back(slideCount = 3))
        val second = PreShowStage.Question(1, picked = null)
        assertEquals(second, second.back(slideCount = 3))
    }

    @Test
    fun backHasNowhereToGoWithoutSlides() {
        val first = PreShowStage.Question(0, picked = null)
        assertEquals(first, first.back(slideCount = 0))
        assertEquals(PreShowStage.Finished, PreShowStage.Finished.back(slideCount = 3))
    }
}

class AnswerCursorTest {
    @Test
    fun cursorStopsAtTheEndsInsteadOfWrapping() {
        assertEquals(0, moveAnswerCursor(0, -1, 4))
        assertEquals(1, moveAnswerCursor(0, 1, 4))
        assertEquals(3, moveAnswerCursor(3, 1, 4))
        assertEquals(2, moveAnswerCursor(3, -1, 4))
    }

    @Test
    fun cursorIsSafeWithNoOptions() {
        assertEquals(0, moveAnswerCursor(0, 1, 0))
    }
}
