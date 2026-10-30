package com.nuvio.app.features.preroll

import kotlin.random.Random

private const val MAX_QUIZ_QUESTIONS = 3
private const val OPTION_COUNT = 4

/**
 * Quiz questions built from a film's own details, for packs that have no AI behind them.
 *
 * Only the Wikipedia and metadata packs use this; an AI pack brings its own questions. Every
 * question is about a fact the details record holds, so none can be invented. The wrong answers
 * are the weak part and are chosen to be plausible but never true: neighbouring years, nearby
 * running times, and, for the director question, cast members who are not the director.
 *
 * Deterministic for a given film (the random seed comes from [TriviaSource.fingerprint]), so the
 * cached pack and a rebuilt one agree. The correct answer is first; the pre-show shuffles.
 */
internal fun buildMetadataQuestions(source: TriviaSource): List<TriviaQuestion> {
    val random = Random(source.fingerprint().hashCode())
    return listOfNotNull(
        yearQuestion(source, random),
        directorQuestion(source, random),
        runtimeQuestion(source, random),
    ).take(MAX_QUIZ_QUESTIONS)
}

private fun yearQuestion(source: TriviaSource, random: Random): TriviaQuestion? {
    val year = source.year?.toIntOrNull()?.takeIf { it in 1888..2100 } ?: return null
    val wrong = listOf(-6, -4, -3, -2, -1, 1, 2, 3, 4, 6)
        .map { year + it }
        .filter { it in 1888..2100 }
        .shuffled(random)
        .take(OPTION_COUNT - 1)
    if (wrong.size < OPTION_COUNT - 1) return null
    return TriviaQuestion(
        question = "In which year did ${source.title} first come out?",
        options = listOf(year.toString()) + wrong.map(Int::toString),
        answerIndex = 0,
        explanation = "${source.title} came out in $year.",
    )
}

private fun directorQuestion(source: TriviaSource, random: Random): TriviaQuestion? {
    val director = source.directors.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val directors = source.directors.map { it.trim().lowercase() }.toSet()
    val wrong = source.cast
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in directors }
        .distinct()
        .shuffled(random)
        .take(OPTION_COUNT - 1)
    if (wrong.size < OPTION_COUNT - 1) return null
    return TriviaQuestion(
        question = "Who directed ${source.title}?",
        options = listOf(director) + wrong,
        answerIndex = 0,
        explanation = "${source.title} was directed by ${namesList(source.directors.take(3))}.",
    )
}

private fun runtimeQuestion(source: TriviaSource, random: Random): TriviaQuestion? {
    val minutes = runtimeMinutes(source.runtime)?.takeIf { it in 20..400 } ?: return null
    val wrong = listOf(-40, -30, -25, -20, -15, -10, 10, 15, 20, 25, 30, 40)
        .map { minutes + it }
        .filter { it >= 10 }
        .shuffled(random)
        .take(OPTION_COUNT - 1)
    if (wrong.size < OPTION_COUNT - 1) return null
    return TriviaQuestion(
        question = "What running time is listed for ${source.title}?",
        options = listOf("$minutes min") + wrong.map { "$it min" },
        answerIndex = 0,
        explanation = "The listed running time is $minutes minutes.",
    )
}

/** "130 min", "2h 10m" and "1 hr 30 min" all give minutes; anything else gives null. */
internal fun runtimeMinutes(raw: String?): Int? {
    val text = raw?.lowercase()?.trim().orEmpty()
    if (text.isEmpty()) return null
    val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    if (hours != null) {
        val extra = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours * 60 + extra
    }
    return Regex("""\d+""").find(text)?.value?.toIntOrNull()
}

private fun namesList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
