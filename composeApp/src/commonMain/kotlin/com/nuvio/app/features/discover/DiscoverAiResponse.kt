package com.nuvio.app.features.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsing what the model sent back — plan §5.
 *
 * **Nothing here throws and nothing here is trusted.** A model told to reply with bare JSON will
 * still sometimes wrap it in a markdown fence, prepend "Here are my suggestions:", emit `year` as a
 * string, or invent a `type` of `"film"`. Each of those is recoverable and none of them should cost
 * the user their whole row, so every stage degrades: unparseable input yields an empty list, and an
 * unparseable *item* is dropped while its neighbours survive.
 */
data class DiscoverAiSuggestion(
    val title: String,
    val year: Int?,
    /** Normalised to `movie` or `series`. */
    val type: String,
    /** Why this person specifically — the whole differentiator over a plain recommendations feed. */
    val reason: String?,
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Hard ceiling on how many suggestions one response may contribute, whatever it claims. */
const val DISCOVER_AI_MAX_SUGGESTIONS = 50

/** Longest `reason` kept. A model asked for one sentence occasionally writes five. */
private const val MAX_REASON_LENGTH = 240

fun parseDiscoverAiSuggestions(raw: String): List<DiscoverAiSuggestion> {
    val array = extractJsonArray(raw) ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonObject)?.toSuggestion() }
        // Two entries for the same title is one wasted row slot; the model repeats itself more
        // often than you would expect when asked for 24 of anything.
        .distinctBy { "${it.type}:${it.title.lowercase()}:${it.year ?: 0}" }
        .take(DISCOVER_AI_MAX_SUGGESTIONS)
}

/**
 * Finds the JSON array in [raw], whatever it is wrapped in.
 *
 * Scans for the first `[` and the last `]` rather than trying to strip known wrappers: fenced
 * blocks, a preamble sentence, and a trailing "Hope this helps!" are all the same problem, and
 * enumerating the wrappers only works until a model invents another one.
 */
private fun extractJsonArray(raw: String): JsonArray? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    val slice = text.substring(start, end + 1)
    return runCatching { lenientJson.parseToJsonElement(slice) as? JsonArray }.getOrNull()
}

private fun JsonObject.toSuggestion(): DiscoverAiSuggestion? {
    val title = string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return DiscoverAiSuggestion(
        title = title,
        year = year(),
        type = normalizeType(string("type")),
        reason = string("reason")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_REASON_LENGTH),
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * The year, however it arrived. Models emit `1999`, `"1999"`, and occasionally `"1999-10-15"`.
 * A value outside a plausible range is dropped rather than used — a wrong year sends the TMDB
 * lookup to the wrong title, which is worse than no year at all.
 */
private fun JsonObject.year(): Int? {
    val primitive = this["year"] as? JsonPrimitive ?: return null
    val value = primitive.intOrNull
        ?: primitive.doubleOrNull?.toInt()
        ?: primitive.contentOrNull?.take(4)?.toIntOrNull()
        ?: return null
    return value.takeIf { it in 1870..2200 }
}

/**
 * `movie` unless it is clearly a show.
 *
 * Defaulting rather than dropping: an unrecognised type is a labelling mistake about a title that
 * may well be real, and the TMDB lookup can still find it. Films outnumber shows in every response
 * seen, so the default costs less than the alternative.
 */
private fun normalizeType(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "series", "tv", "show", "tv show", "tvshow", "television", "anime" -> "series"
    else -> "movie"
}
