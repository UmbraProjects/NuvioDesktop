package com.nuvio.app.features.preroll

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

const val POST_SHOW_MAX_CARDS = 6
const val POST_SHOW_MAX_QUESTIONS = 3

private const val MAX_CARD_CHARS = 200

/**
 * Stricter than the pre-show's 0.6: this feature interprets and points at details, and a wrong
 * interpretation on a screen the viewer trusts is worse than a thin deck.
 */
const val POST_SHOW_MIN_CONFIDENCE = 0.8

/**
 * Parses the model's reply into a [PostShowPack]. Like [parseTriviaPack] it never throws: an
 * unparseable reply is an empty pack, and an unusable item is dropped without costing its
 * neighbours. Spoiler flags are ignored on purpose (the viewer has just watched), but confidence is
 * not.
 */
fun parsePostShowPack(raw: String): PostShowPack {
    val root = extractJsonObject(raw) ?: return PostShowPack()
    val cards = (root["cards"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toPostShowCard() }
        .distinctBy { it.text.lowercase() }
        .take(POST_SHOW_MAX_CARDS)
    val questions = (root["quiz"] as? JsonArray).orEmpty()
        .mapNotNull {
            (it as? JsonObject)?.toQuestion(minConfidence = POST_SHOW_MIN_CONFIDENCE, allowSpoilers = true)
        }
        .distinctBy { it.question.lowercase() }
        .take(POST_SHOW_MAX_QUESTIONS)
    return PostShowPack(cards = cards, questions = questions)
}

private fun JsonObject.toPostShowCard(): PostShowCard? {
    val kind = PostShowCardKind.from(string("kind") ?: string("category")) ?: return null
    // Dropped, not truncated: a sentence cut mid-clause reads as a mistake on a TV.
    val text = (string("text") ?: string("fact"))?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CARD_CHARS }
        ?: return null
    if (isLowConfidence(POST_SHOW_MIN_CONFIDENCE)) return null
    return PostShowCard(kind = kind, text = text)
}
