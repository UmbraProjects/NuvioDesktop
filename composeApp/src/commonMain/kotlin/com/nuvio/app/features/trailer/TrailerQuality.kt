package com.nuvio.app.features.trailer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One video from a YouTube search. Resolution is not in search results; it is found by resolving. */
data class YouTubeSearchHit(
    val videoId: String,
    val title: String,
    val channel: String,
    /** The channel carries YouTube's verified badge (studios and distributors do). */
    val verified: Boolean,
    val durationSeconds: Int,
    val views: Long,
)

/**
 * A trailer upload that was resolved, with what it takes to compare it with the others.
 * [order] is its place in the preference order (lower is preferred): listed uploads in their usual
 * order first, then search results, so a tie in resolution keeps the listed trailer.
 */
internal data class TrailerOption(
    val key: String,
    val source: TrailerPlaybackSource,
    val order: Int,
)

/** Uploads at or above this height are accepted without looking for a sharper one. */
internal const val GOOD_ENOUGH_TRAILER_HEIGHT = 1080

/** How many listed alternatives and how many search results are resolved when the first is soft. */
internal const val MAX_LISTED_ALTERNATIVES = 3
internal const val MAX_SEARCH_ALTERNATIVES = 6

/**
 * YouTube's rendition ladder, so 1072p and 1080p, or 1438p and 1440p, count as the same quality
 * and the preferred upload is not displaced by a sliver of height. Unknown (0) ranks lowest.
 */
internal fun trailerQualityClass(heightPx: Int): Int = when {
    heightPx >= 2000 -> 2160
    heightPx >= 1300 -> 1440
    heightPx >= 1000 -> 1080
    heightPx >= 680 -> 720
    heightPx >= 460 -> 480
    heightPx >= 340 -> 360
    heightPx > 0 -> 240
    else -> 0
}

/**
 * The sharpest upload that will actually play; among equals the most preferred one. A portrait
 * upload ranks below every landscape one, and one that can only be reached through a restricted
 * client ([TrailerPlaybackSource.reliable]) below any that can, whatever height it claims. Null
 * for an empty list.
 */
internal fun pickBestTrailer(options: List<TrailerOption>): TrailerOption? =
    options.sortedWith(
        compareBy<TrailerOption> { it.source.portrait }
            .thenByDescending { it.source.reliable }
            .thenByDescending { trailerQualityClass(it.source.heightPx) }
            .thenBy { it.order },
    ).firstOrNull()

internal fun trailerSearchQuery(title: String, year: String?): String =
    listOfNotNull(title.trim().takeIf { it.isNotEmpty() }, year?.trim()?.takeIf { it.isNotEmpty() }, "official trailer")
        .joinToString(" ")

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

private fun tokens(text: String): List<String> =
    text.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }

/** Words that mark a video as something other than the studio's trailer for the film. */
private val UNWANTED_WORDS = setOf(
    "reaction", "reacts", "reacting", "review", "reviews", "breakdown", "explained", "analysis",
    "ending", "recap", "parody", "spoof", "fan", "fanmade", "concept", "remake", "reimagined",
    "ranked", "ranking", "podcast", "interview", "featurette", "scene", "clip", "bloopers",
    "asl", "described", "description", "sign", "cover", "mashup", "game", "gameplay", "lego",
)

private const val MIN_TRAILER_SECONDS = 45
private const val MAX_TRAILER_SECONDS = 330

/** Words that may appear in a studio's trailer title without naming a different film. */
private val FILLER_WORDS = setOf(
    "official", "trailer", "trailers", "teaser", "final", "new", "hd", "4k", "uhd", "8k", "hdr",
    "movie", "film", "in", "cinemas", "theaters", "theatres", "now", "imax", "dolby", "full",
    "first", "main", "international", "the", "a", "an", "of", "and", "with", "from", "pictures",
    "studios", "entertainment", "films", "movies", "presents", "official",
)

/**
 * The search results worth resolving for [title] ([year] when known): trailers of trailer length
 * without reaction/review/fan-made markers, whose title names the film and nothing else. A title
 * with extra words ("Dune Part Two" for "Dune") is only accepted when it states the film's [year],
 * so a sequel or remake is not mistaken for it; the uploader's own name is not counted as extra
 * ("... | A24"). Verified channels (the studios) come first, then by views; at most [limit] are
 * returned, and any of [excludeKeys] (already tried) are skipped.
 */
internal fun filterTrailerSearchHits(
    hits: List<YouTubeSearchHit>,
    title: String,
    year: String? = null,
    excludeKeys: Set<String> = emptySet(),
    limit: Int = MAX_SEARCH_ALTERNATIVES,
): List<YouTubeSearchHit> {
    val wanted = tokens(title)
    if (wanted.isEmpty()) return emptyList()
    val wantedSet = wanted.toSet()
    val wantedYear = year?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    return hits
        .asSequence()
        .filter { it.videoId !in excludeKeys }
        .filter { it.durationSeconds in MIN_TRAILER_SECONDS..MAX_TRAILER_SECONDS }
        .filter { hit ->
            val words = tokens(hit.title)
            val wordSet = words.toSet()
            if ("trailer" !in wordSet || !wordSet.containsAll(wantedSet)) return@filter false
            if (words.any { it in UNWANTED_WORDS && it !in wantedSet }) return@filter false
            val statesYear = wantedYear != null && wantedYear in wordSet
            val channelWords = tokens(hit.channel).toSet()
            val extra = words.filter { word ->
                word !in wantedSet && word !in FILLER_WORDS && word !in channelWords &&
                    !(word.length <= 2 && word.all(Char::isDigit))
            }
            statesYear || extra.isEmpty()
        }
        .distinctBy { it.videoId }
        .sortedWith(compareByDescending<YouTubeSearchHit> { it.verified }.thenByDescending { it.views })
        .take(limit)
        .toList()
}

private val SEARCH_JSON = Json { ignoreUnknownKeys = true }

/**
 * The videos in an InnerTube search response. Walks the whole tree for `videoRenderer` objects
 * instead of following the layout path, which YouTube rearranges between site versions.
 */
internal fun parseYouTubeSearchHits(responseBody: String): List<YouTubeSearchHit> {
    val root = runCatching { SEARCH_JSON.parseToJsonElement(responseBody) }.getOrNull() ?: return emptyList()
    val hits = ArrayList<YouTubeSearchHit>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                (node["videoRenderer"] as? JsonObject)?.let { renderer -> renderer.toSearchHit()?.let(hits::add) }
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return hits.distinctBy { it.videoId }
}

private fun JsonObject.toSearchHit(): YouTubeSearchHit? {
    val id = (this["videoId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length == 11 } ?: return null
    val title = textOf(this["title"]).ifBlank { return null }
    val badges = (this["ownerBadges"] as? JsonArray).orEmpty()
    val verified = badges.any { badge ->
        val style = (((badge as? JsonObject)?.get("metadataBadgeRenderer") as? JsonObject)?.get("style") as? JsonPrimitive)?.content
        style?.contains("VERIFIED", ignoreCase = true) == true
    }
    return YouTubeSearchHit(
        videoId = id,
        title = title,
        channel = textOf(this["ownerText"]),
        verified = verified,
        durationSeconds = parseDurationSeconds(textOf(this["lengthText"])),
        views = parseViewCount(textOf(this["viewCountText"])),
    )
}

/** Text of an InnerTube text node: either `simpleText`, or `runs` to be joined. */
private fun textOf(node: JsonElement?): String {
    val obj = node as? JsonObject ?: return ""
    (obj["simpleText"] as? JsonPrimitive)?.let { return it.content }
    return (obj["runs"] as? JsonArray)
        ?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
        .orEmpty()
}

/** "2:35" or "1:02:03" to seconds; 0 when it does not parse (live, premiere). */
internal fun parseDurationSeconds(text: String): Int {
    val parts = text.trim().split(':').map { it.toIntOrNull() ?: return 0 }
    if (parts.isEmpty() || parts.size > 3) return 0
    return parts.fold(0) { total, part -> total * 60 + part }
}

/** "12,345,678 views" to 12345678; 0 when it does not parse. */
internal fun parseViewCount(text: String): Long =
    text.filter { it.isDigit() }.toLongOrNull() ?: 0L
