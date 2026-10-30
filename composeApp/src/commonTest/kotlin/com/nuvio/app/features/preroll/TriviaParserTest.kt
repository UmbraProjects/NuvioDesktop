package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parser is the second line of defence behind the prompt: whatever the model was told, a slide
 * it flagged as a spoiler or was unsure of must not reach the screen.
 */
class TriviaParserTest {
    private fun slide(
        fact: String = "Filmed entirely on location.",
        risk: String? = "none",
        confidence: String? = "0.9",
        category: String = "production",
    ) = buildString {
        append("{\"fact\":\"$fact\",\"category\":\"$category\"")
        risk?.let { append(",\"spoiler_risk\":\"$it\"") }
        confidence?.let { append(",\"confidence\":$it") }
        append("}")
    }

    private fun question(
        q: String = "Who directed it?",
        options: String = "[\"A\",\"B\",\"C\",\"D\"]",
        answer: String = "\"B\"",
        extra: String = "",
    ) = "{\"q\":\"$q\",\"options\":$options,\"answer\":$answer$extra}"

    private fun pack(slides: List<String> = emptyList(), quiz: List<String> = emptyList()) =
        "{\"slides\":[${slides.joinToString(",")}],\"quiz\":[${quiz.joinToString(",")}]}"

    @Test
    fun `reads a well formed pack`() {
        val result = parseTriviaPack(pack(listOf(slide()), listOf(question())))
        assertEquals(1, result.slides.size)
        assertEquals(TriviaCategory.Production, result.slides.single().category)
        assertEquals(1, result.questions.single().answerIndex)
    }

    @Test
    fun `survives a markdown fence and a preamble`() {
        val wrapped = "Here you go:\n```json\n${pack(listOf(slide()))}\n```\nEnjoy!"
        assertEquals(1, parseTriviaPack(wrapped).slides.size)
    }

    @Test
    fun `garbage yields an empty pack rather than throwing`() {
        assertTrue(parseTriviaPack("").isEmpty)
        assertTrue(parseTriviaPack("sorry, I can't help with that").isEmpty)
        assertTrue(parseTriviaPack("{not json}").isEmpty)
    }

    @Test
    fun `drops slides flagged as spoilers`() {
        val result = parseTriviaPack(pack(listOf(slide(risk = "high"), slide(fact = "Safe fact.", risk = "low"))))
        assertEquals(listOf("Safe fact."), result.slides.map { it.text })
    }

    @Test
    fun `drops low confidence facts but keeps ones with no confidence`() {
        val result = parseTriviaPack(
            pack(listOf(slide(fact = "Weak.", confidence = "0.3"), slide(fact = "Unrated.", confidence = null))),
        )
        assertEquals(listOf("Unrated."), result.slides.map { it.text })
    }

    @Test
    fun `drops over long slides rather than truncating them`() {
        val result = parseTriviaPack(pack(listOf(slide(fact = "x".repeat(250)))))
        assertTrue(result.slides.isEmpty())
    }

    @Test
    fun `removes duplicate slides`() {
        val result = parseTriviaPack(pack(listOf(slide(fact = "Same."), slide(fact = "same."))))
        assertEquals(1, result.slides.size)
    }

    @Test
    fun `answer is matched by text before it is read as an index`() {
        val years = "[\"1997\",\"1999\",\"2001\",\"2003\"]"
        val result = parseTriviaPack(pack(quiz = listOf(question(options = years, answer = "\"1999\""))))
        assertEquals(1, result.questions.single().answerIndex)
    }

    @Test
    fun `numeric answer falls back to an index`() {
        val result = parseTriviaPack(pack(quiz = listOf(question(answer = "2"))))
        assertEquals(2, result.questions.single().answerIndex)
    }

    @Test
    fun `rejects questions that cannot be asked fairly`() {
        val tooFew = question(options = "[\"A\",\"B\"]")
        val duplicates = question(options = "[\"A\",\"a\",\"C\",\"D\"]")
        val noMatch = question(answer = "\"Z\"")
        val outOfRange = question(answer = "9")
        val result = parseTriviaPack(pack(quiz = listOf(tooFew, duplicates, noMatch, outOfRange)))
        assertTrue(result.questions.isEmpty())
    }

    @Test
    fun `a bad item does not cost its neighbours`() {
        val result = parseTriviaPack(pack(quiz = listOf(question(answer = "\"Z\""), question(q = "Which year?"))))
        assertEquals(listOf("Which year?"), result.questions.map { it.question })
    }
}
