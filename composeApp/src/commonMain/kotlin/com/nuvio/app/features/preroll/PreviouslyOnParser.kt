package com.nuvio.app.features.preroll

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

const val PREVIOUSLY_ON_MAX_QUESTIONS = 3
private const val MAX_CARD_CHARS = 200
private const val MIN_CONFIDENCE = 0.8

private enum class PreviouslyOnKind(val order: Int, val max: Int) {
    LastTime(0, 2),
    WhereItEnded(1, 1),
    KeepInMind(2, 2),
    ;

    companion object {
        fun from(raw: String?): PreviouslyOnKind? =
            when (raw?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "")) {
                "lasttime", "previously", "recap" -> LastTime
                "whereitended", "cliffhanger", "ending" -> WhereItEnded
                "keepinmind", "remember", "character", "thread" -> KeepInMind
                else -> null
            }
    }
}

/**
 * The model's reply as a [TriviaPack] the pre-show screen can play. Never throws: an unreadable
 * reply is an empty pack, and an unusable card or question is dropped alone. Cards over the length
 * limit are dropped, not cut, and anything the model rates under 0.8 goes.
 */
fun parsePreviouslyOnPack(raw: String, lastTimeHeading: String = "LAST TIME"): TriviaPack {
    val root = extractJsonObject(raw) ?: return TriviaPack()
    val cards = (root["cards"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val kind = PreviouslyOnKind.from(obj.string("kind") ?: obj.string("category")) ?: return@mapNotNull null
        val text = (obj.string("text") ?: obj.string("fact"))?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_CARD_CHARS } ?: return@mapNotNull null
        if (obj.isLowConfidence(MIN_CONFIDENCE)) return@mapNotNull null
        kind to text
    }
        .distinctBy { it.second.lowercase() }
        .groupBy { it.first }
        .flatMap { (kind, list) -> list.take(kind.max) }
        .sortedBy { it.first.order }
    val slides = cards.map { (kind, text) ->
        TriviaSlide(
            text = text,
            heading = when (kind) {
                PreviouslyOnKind.LastTime -> lastTimeHeading
                PreviouslyOnKind.WhereItEnded -> "WHERE IT ENDED"
                PreviouslyOnKind.KeepInMind -> "KEEP IN MIND"
            },
        )
    }
    val questions = (root["quiz"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toQuestion(minConfidence = MIN_CONFIDENCE, allowSpoilers = true) }
        .distinctBy { it.question.lowercase() }
        .take(PREVIOUSLY_ON_MAX_QUESTIONS)
    return TriviaPack(slides = slides, questions = questions)
}

/**
 * The no-AI recap: the addon's own overview of the episode, as a "last time" card and, when it has
 * a second sentence, a "where it ended" card. No quiz. Null when there is nothing worth showing.
 */
internal fun previouslyOnFromOverview(target: PreviouslyOnTarget, source: TriviaSource): TriviaPack? {
    val sentences = splitSentences(target.overview.orEmpty())
        .map { it.trim() }
        .filter { it.length in 20..MAX_CARD_CHARS }
    if (sentences.isEmpty()) return null
    val first = sentences.first()
    val slides = mutableListOf(TriviaSlide(first, heading = target.lastTimeHeading()))
    if (sentences.size >= 2) slides += TriviaSlide(sentences.last(), heading = "WHERE IT ENDED")
    return TriviaPack(slides = slides, origin = TriviaOrigin.Metadata).withFallbackTheme(source.genres)
}
