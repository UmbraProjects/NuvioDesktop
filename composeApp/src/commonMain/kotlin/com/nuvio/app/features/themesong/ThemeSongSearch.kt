package com.nuvio.app.features.themesong

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.math.log10

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

internal fun tokens(text: String): List<String> =
    text.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }

internal val ARTICLES = setOf("the", "a", "an")

/** Shortest and longest upload that can be a theme: a jingle at one end, a long suite at the other. */
internal const val THEME_MIN_SECONDS = 45

/** Under this a hit is more likely a snippet than the full track, so it ranks lower. */
private const val THEME_FULL_TRACK_SECONDS = 90
internal const val THEME_MAX_SECONDS = 7 * 60

/** What the viewer is looking for, as a YouTube search. Films have a main theme, shows a theme song. */
internal fun themeSongSearchQuery(title: String, year: String?, isSeries: Boolean): String {
    val name = title.trim()
    return if (isSeries) {
        listOf(name, "theme song").joinToString(" ")
    } else {
        listOfNotNull(name.takeIf { it.isNotEmpty() }, year?.trim()?.takeIf { it.isNotEmpty() }, "main theme")
            .joinToString(" ")
    }
}

/** Words that mark an upload as something other than the title's own music. */
internal val UNWANTED = setOf(
    "reaction", "reacts", "reacting", "review", "cover", "remix", "mashup", "piano", "guitar",
    "tutorial", "lesson", "karaoke", "ringtone", "nightcore", "slowed", "reverb", "8d", "boosted",
    "trailer", "teaser", "scene", "clip", "parody", "spoof", "hour", "hours", "loop", "ukulele",
    "violin", "orchestral", "epic", "version", "lofi", "tiktok", "shorts",
    // Dialogue and talk about the film rather than its music: a character's speech with the score
    // underneath is still not the theme.
    "speech", "dialogue", "monologue", "quote", "quotes", "scenes", "clips", "interview",
    "explained", "analysis", "breakdown", "moments", "compilation", "featurette", "behind",
    "movie", "audiobook", "podcast", "commentary", "talks", "says",
)

/**
 * Words that usually mean someone else is performing the title's music (a talk-show slot, a tribute act, an
 * acoustic version) but can also sit in a real title. They cost [PERFORMANCE_PENALTY] points instead of removing
 * the hit, so a genuine upload that carries one still wins when nothing better is there.
 */
internal val PERFORMANCE_WORDS = setOf("live", "performance", "performs", "sings", "acoustic", "tribute")

/** Enough to drop a verified, much-viewed live clip below a plain upload of the original, once however many such words there are. */
internal const val PERFORMANCE_PENALTY = 4

/** At least one of these must be in the upload's title, or it is probably not the music at all. */
private val MUSIC_WORDS = setOf(
    "theme", "soundtrack", "ost", "score", "opening", "intro", "music", "title", "credits",
)

/** Weighted: how much a word says "this is the real theme". */
private fun keywordScore(words: Set<String>): Int {
    var score = 0
    if ("theme" in words) score += 3
    if ("main" in words && "title" in words) score += 2
    if ("opening" in words || "intro" in words) score += 1
    if ("soundtrack" in words || "ost" in words || "score" in words) score += 2
    if ("official" in words) score += 1
    return score
}

/**
 * Ranks the search results for [title] and returns up to [limit] worth trying, best first.
 *
 * An upload has to carry every significant word of the title and say it is music, last between
 * [THEME_MIN_SECONDS] and [THEME_MAX_SECONDS], and not be a cover, remix or reaction. Among those, an
 * official "Theme" upload from a verified channel or a YouTube Music "- Topic" channel wins, with
 * views as the tie-break. A different year in the upload's title costs points: it is another film.
 * So does a word that points to a live or re-performed version ([PERFORMANCE_WORDS]).
 */
internal fun rankThemeSongHits(
    hits: List<YouTubeSearchHit>,
    title: String,
    year: String?,
    limit: Int = 3,
): List<YouTubeSearchHit> {
    val titleTokens = tokens(title).filterNot { it in ARTICLES }
    if (titleTokens.isEmpty()) return emptyList()
    return hits.withIndex()
        .mapNotNull { (index, hit) ->
            if (hit.durationSeconds !in THEME_MIN_SECONDS..THEME_MAX_SECONDS) return@mapNotNull null
            val words = tokens(hit.title).toSet()
            val channelWords = tokens(hit.channel).toSet()
            if (titleTokens.any { it !in words && it !in channelWords }) return@mapNotNull null
            // A word that is part of the film's own name ("The King's Speech", "The Hours") is not a red flag.
            if (words.any { it in UNWANTED && it !in titleTokens }) return@mapNotNull null
            if (words.none { it in MUSIC_WORDS }) return@mapNotNull null
            var score = keywordScore(words)
            // A full-length track beats a short excerpt of it.
            if (hit.durationSeconds < THEME_FULL_TRACK_SECONDS) score -= 2
            if (hit.verified) score += 2
            if ("topic" in channelWords) score += 2
            if (hit.views > 0) score += (log10(hit.views.toDouble()) / 2.5).toInt().coerceIn(0, 3)
            val otherYear = YEAR.findAll(hit.title).map { it.value }.firstOrNull { it != year }
            if (otherYear != null) score -= 3
            // A word that is part of the title itself ("Live and Let Die") is not a sign of someone else's performance.
            if (words.any { it in PERFORMANCE_WORDS && it !in titleTokens }) score -= PERFORMANCE_PENALTY
            Triple(score, index, hit)
        }
        .sortedWith(compareByDescending<Triple<Int, Int, YouTubeSearchHit>> { it.first }.thenBy { it.second })
        .take(limit)
        .map { it.third }
}
