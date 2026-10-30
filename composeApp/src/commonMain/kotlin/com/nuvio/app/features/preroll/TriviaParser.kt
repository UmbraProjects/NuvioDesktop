package com.nuvio.app.features.preroll

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsing what the model sent back.
 *
 * **Nothing here throws and nothing here is trusted.** A model told to reply with bare JSON will
 * still wrap it in a fence, prepend a sentence, or number `answer` from one. Every stage degrades:
 * unparseable input yields an empty pack, and an unusable *item* is dropped while its neighbours
 * survive. The filters below are the second line of defence behind the prompt, not a substitute:
 * a slide the model itself flagged as a spoiler or was unsure of is discarded here regardless of
 * what the prompt asked for.
 */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

const val TRIVIA_MAX_SLIDES = 12
const val TRIVIA_MAX_QUESTIONS = 5

private const val MAX_SLIDE_CHARS = 200
private const val MAX_QUESTION_CHARS = 220
private const val MAX_OPTION_CHARS = 90
private const val MAX_EXPLAIN_CHARS = 240

/** Below this the model is telling us it is guessing. */
private const val MIN_CONFIDENCE = 0.6

/** A model that omits `confidence` entirely is not the same as one reporting that it is unsure. */
private const val DEFAULT_CONFIDENCE = 0.7

fun parseTriviaPack(raw: String): TriviaPack {
    val root = extractJsonObject(raw) ?: return TriviaPack()
    val slides = (root["slides"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toSlide() }
        // The model repeats itself more than you would expect when asked for ten of anything.
        .distinctBy { it.text.lowercase() }
        .take(TRIVIA_MAX_SLIDES)
    val questions = (root["quiz"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toQuestion() }
        .distinctBy { it.question.lowercase() }
        .take(TRIVIA_MAX_QUESTIONS)
    return TriviaPack(slides = slides, questions = questions, theme = (root["theme"] as? JsonObject)?.toTheme())
}

private val HEX_COLOUR = Regex("^#?([0-9a-fA-F]{6})$")

/** `#RRGGBB`, upper-case, or null. A model will happily send "red" or "#FFF"; both are dropped. */
internal fun normalizeHexColour(raw: String?): String? =
    raw?.trim()?.let { HEX_COLOUR.matchEntire(it) }?.groupValues?.get(1)?.let { "#${it.uppercase()}" }

private fun JsonObject.toTheme(): TriviaTheme? {
    val primary = normalizeHexColour(string("primary"))
    val secondary = normalizeHexColour(string("secondary"))
    val atmosphere = TriviaAtmosphere.from(string("atmosphere"))
    if (primary == null && secondary == null) return null
    return TriviaTheme(
        primary = primary,
        secondary = secondary,
        atmosphere = atmosphere,
        motif = string("motif")?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
    )
}

/**
 * Finds the JSON object in [raw] whatever it is wrapped in: first `{` to last `}`. Enumerating the
 * wrappers only works until a model invents another one.
 */
private fun extractJsonObject(raw: String): JsonObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { lenientJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }
        .getOrNull()
}

private fun JsonObject.toSlide(): TriviaSlide? {
    val text = (string("fact") ?: string("text"))?.trim()
        // Dropped, not truncated: a sentence cut mid-clause reads as a mistake on a TV.
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_SLIDE_CHARS }
        ?: return null
    if (isSpoilerRisky() || isLowConfidence()) return null
    return TriviaSlide(text = text, category = TriviaCategory.from(string("category")))
}

private fun JsonObject.toQuestion(): TriviaQuestion? {
    val question = (string("q") ?: string("question"))?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_QUESTION_CHARS }
        ?: return null
    val options = (this["options"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?: return null
    if (options.size !in 3..4 || options.any { it.length > MAX_OPTION_CHARS }) return null
    if (options.map { it.lowercase() }.toSet().size != options.size) return null
    val answerIndex = answerIndex(options) ?: return null
    if (isSpoilerRisky() || isLowConfidence()) return null
    return TriviaQuestion(
        question = question,
        options = options,
        answerIndex = answerIndex,
        explanation = (string("explain") ?: string("explanation"))?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_EXPLAIN_CHARS),
    )
}

/**
 * The correct option, found by *text* first.
 *
 * The prompt asks for the option's text rather than its position because models are unreliable at
 * counting from zero, and a quiz whose marked answer is off by one is worse than no quiz. A number
 * is only read as an index when it matches no option's text, which keeps a question like
 * "Which year?" with option "1999" working.
 */
private fun JsonObject.answerIndex(options: List<String>): Int? {
    val primitive = this["answer"] as? JsonPrimitive ?: return null
    val text = primitive.contentOrNull?.trim().orEmpty()
    options.indexOfFirst { it.equals(text, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
    return primitive.intOrNull?.takeIf { it in options.indices }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.isSpoilerRisky(): Boolean =
    when (string("spoiler_risk")?.trim()?.lowercase()) {
        "high", "medium" -> true
        else -> false
    }

private fun JsonObject.isLowConfidence(): Boolean {
    val primitive = this["confidence"] as? JsonPrimitive
    val value = primitive?.doubleOrNull ?: primitive?.contentOrNull?.toDoubleOrNull() ?: DEFAULT_CONFIDENCE
    return value < MIN_CONFIDENCE
}
