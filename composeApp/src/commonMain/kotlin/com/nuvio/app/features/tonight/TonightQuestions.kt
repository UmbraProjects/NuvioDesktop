package com.nuvio.app.features.tonight

import kotlin.random.Random

/*
 * fork:tonight - Three Questions (design-notes/tonight-picker.md). The first question is always
 * "Film or show?"; the other two come from [TonightRotatingQuestions] in an order that changes
 * night to night, skipping any question that would not split what is left.
 */

data class TonightAnswer(
    val label: String,
    val detail: String,
    val test: (TonightTitle) -> Boolean,
)

data class TonightQuestion(
    val id: String,
    val prompt: String,
    val left: TonightAnswer,
    val right: TonightAnswer,
)

enum class TonightSide { Left, Right, Skip }

const val TONIGHT_QUESTION_COUNT = 3

val TonightFilmOrShow = TonightQuestion(
    id = "film_show",
    prompt = "A film, or a show?",
    left = TonightAnswer("A film", "One story, start to finish") { it.kind == TonightKind.Film },
    right = TonightAnswer("A show", "An episode or a few") { it.kind == TonightKind.Show },
)

/** [recentFromYear] is the first year that counts as recent (this year minus three). */
fun tonightRotatingQuestions(recentFromYear: Int): List<TonightQuestion> = listOf(
    TonightQuestion(
        id = "laugh_think",
        prompt = "Laugh, or think?",
        left = TonightAnswer("Laugh", "Light and easy") { tonightLightness(it) >= 0f },
        right = TonightAnswer("Think", "Something with weight") { tonightLightness(it) < 0f },
    ),
    TonightQuestion(
        id = "known_new",
        prompt = "Something you know, or something new?",
        left = TonightAnswer("Comfort", "Rewatches and things you're midway through") { tonightNovelty(it) < 0f },
        right = TonightAnswer("Something new", "Nothing you've started or seen") { tonightNovelty(it) >= 0f },
    ),
    TonightQuestion(
        id = "short_long",
        prompt = "Short and sweet, or settle in?",
        left = TonightAnswer("Short and sweet", "Under 100 minutes, or a short episode") { it.isShort() },
        right = TonightAnswer("Settle in", "Something longer") { !it.isShort() },
    ),
    TonightQuestion(
        id = "recent_older",
        prompt = "Something recent, or an older one?",
        left = TonightAnswer("Recent", "From $recentFromYear on") { (it.year ?: 0) >= recentFromYear },
        right = TonightAnswer("An older one", "Before $recentFromYear") { it.year != null && it.year < recentFromYear },
    ),
    TonightQuestion(
        id = "cosy_edge",
        prompt = "Cosy, or on the edge of your seat?",
        left = TonightAnswer("Cosy", "Warm, funny, gentle") { it.isCosy() && !it.isEdgy() },
        right = TonightAnswer("Exciting", "Tension, mystery, action") { it.isEdgy() },
    ),
)

private fun TonightTitle.isShort(): Boolean {
    val minutes = runtimeMinutes ?: return false
    return if (kind == TonightKind.Film) minutes < 100 else minutes <= 35
}

/** Tonight's order: "Film or show?" first, then the rotating set shuffled by [nightIsoDate]. */
fun tonightQuestionOrder(nightIsoDate: String, recentFromYear: Int): List<TonightQuestion> =
    listOf(TonightFilmOrShow) + tonightRotatingQuestions(recentFromYear).shuffled(Random(nightIsoDate.hashCode()))

/** Titles left after answering [question] with [side]. */
fun tonightApply(pool: List<TonightTitle>, question: TonightQuestion, side: TonightSide): List<TonightTitle> =
    when (side) {
        TonightSide.Left -> pool.filter(question.left.test)
        TonightSide.Right -> pool.filter(question.right.test)
        TonightSide.Skip -> pool
    }

/**
 * The next question worth asking, or null when the questions are done: [TONIGHT_QUESTION_COUNT]
 * answered, or nothing left in [order] that splits [pool] (both answers keep at least one title).
 */
fun tonightNextQuestion(
    order: List<TonightQuestion>,
    asked: List<String>,
    pool: List<TonightTitle>,
): TonightQuestion? {
    if (asked.size >= TONIGHT_QUESTION_COUNT || pool.size < 2) return null
    return order.firstOrNull { q ->
        q.id !in asked && pool.any(q.left.test) && pool.any(q.right.test)
    }
}
