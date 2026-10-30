package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriviaQuizTest {
    private fun source(
        year: String? = "1999",
        directors: List<String> = listOf("Lana Wachowski", "Lilly Wachowski"),
        cast: List<String> = listOf("Keanu Reeves", "Carrie-Anne Moss", "Hugo Weaving", "Laurence Fishburne"),
        runtime: String? = "136 min",
    ) = TriviaSource(
        title = "The Matrix", year = year, synopsis = null, directors = directors, cast = cast,
        genres = emptyList(), studios = emptyList(), collection = null, awards = null,
        keywords = emptyList(), runtime = runtime, country = null, language = null,
    )

    @Test
    fun buildsYearDirectorAndRuntimeQuestions() {
        val questions = buildMetadataQuestions(source())
        assertEquals(3, questions.size)
        questions.forEach { q ->
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.distinct().size)
            assertEquals(0, q.answerIndex)
        }
        assertEquals("1999", questions[0].options[0])
        assertEquals("Lana Wachowski", questions[1].options[0])
        assertEquals("136 min", questions[2].options[0])
    }

    @Test
    fun wrongAnswersAreNeverTheRightOne() {
        val q = buildMetadataQuestions(source()).first()
        assertTrue(q.options.drop(1).none { it == "1999" })
        val director = buildMetadataQuestions(source())[1]
        assertTrue(director.options.drop(1).none { it in listOf("Lana Wachowski", "Lilly Wachowski") })
    }

    @Test
    fun directorWrongAnswersNeverIncludeAnotherDirector() {
        val q = buildMetadataQuestions(
            source(directors = listOf("Keanu Reeves"), cast = listOf("Keanu Reeves", "A One", "B Two", "C Three")),
        ).first { it.question.startsWith("Who directed") }
        assertEquals(listOf("A One", "B Two", "C Three"), q.options.drop(1).sorted())
    }

    @Test
    fun skipsQuestionsTheRecordCannotSupport() {
        assertEquals(emptyList(), buildMetadataQuestions(source(year = null, directors = emptyList(), runtime = null)))
        // Too few cast members to make three wrong directors.
        val few = buildMetadataQuestions(source(cast = listOf("A", "B")))
        assertTrue(few.none { it.question.startsWith("Who directed") })
    }

    @Test
    fun sameFilmGivesTheSameQuiz() {
        assertEquals(buildMetadataQuestions(source()), buildMetadataQuestions(source()))
    }

    @Test
    fun parsesRuntimes() {
        assertEquals(130, runtimeMinutes("130 min"))
        assertEquals(130, runtimeMinutes("2h 10m"))
        assertEquals(90, runtimeMinutes("1 hr 30 min"))
        assertEquals(105, runtimeMinutes("1 hour 45 minutes"))
        assertNull(runtimeMinutes("soon"))
        assertNull(runtimeMinutes(null))
    }

    @Test
    fun everyQuestionIsAskable() {
        buildMetadataQuestions(source()).forEach { assertTrue(it.shuffled() != null) }
    }
}
