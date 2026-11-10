package com.nuvio.app.features.tonight

/*
 * fork:tonight - the rules that place a title, with no AI (design-notes/tonight-picker.md, phase 0).
 * Light <-> Heavy comes from genre, length and rating; Comfort <-> New from the user's own history.
 * Both run from -1 to +1. The AI refinement of phase 5 will only nudge these numbers.
 */

private val LightGenres = setOf(
    "comedy", "family", "animation", "adventure", "romance", "music", "musical", "fantasy",
    "kids", "sci-fi & fantasy", "action & adventure", "reality",
)
private val HeavyGenres = setOf(
    "drama", "war", "war & politics", "crime", "thriller", "horror", "history", "mystery",
    "documentary",
)
private val CosyGenres = setOf("comedy", "family", "animation", "romance", "music", "musical", "kids")
private val EdgeGenres = setOf("thriller", "horror", "crime", "mystery", "action", "action & adventure", "war")

internal fun String.genreKey(): String = trim().lowercase()

/** -1 = heavy, +1 = light. */
fun tonightLightness(title: TonightTitle): Float {
    val genres = title.genres.map { it.genreKey() }
    var score = 0f
    if (genres.isNotEmpty()) {
        val light = genres.count { it in LightGenres }
        val heavy = genres.count { it in HeavyGenres }
        score += (light - heavy).toFloat() / genres.size
    }
    val minutes = title.runtimeMinutes
    if (title.kind == TonightKind.Film && minutes != null) {
        score += when {
            minutes < 100 -> 0.2f
            minutes > 150 -> -0.3f
            minutes > 130 -> -0.15f
            else -> 0f
        }
    }
    // A very highly rated drama tends to be the demanding kind; a high rating on a comedy does not.
    val rating = title.rating
    if (rating != null && rating >= 8.0 && genres.any { it in HeavyGenres }) score -= 0.1f
    return score.coerceIn(-1f, 1f)
}

/** -1 = comfort (something the user knows), +1 = brand new to them. */
fun tonightNovelty(title: TonightTitle): Float = when {
    title.started -> -1f
    title.watched -> -0.8f
    TonightOrigin.Library in title.origins -> 0.3f
    else -> 0.8f
}

fun TonightTitle.isCosy(): Boolean = genres.any { it.genreKey() in CosyGenres }
fun TonightTitle.isEdgy(): Boolean = genres.any { it.genreKey() in EdgeGenres }

/**
 * What the user leans towards: a weight per genre from the titles they keep and the ones they are
 * watching (Continue watching counts double: it is what they actually finish).
 */
class TonightTaste(private val weights: Map<String, Float>) {
    /** 0 = nothing the user keeps, 1 = their favourite genres. */
    fun score(title: TonightTitle): Float {
        if (weights.isEmpty() || title.genres.isEmpty()) return 0.5f
        val values = title.genres.map { weights[it.genreKey()] ?: 0f }
        return (values.sum() / values.size).coerceIn(0f, 1f)
    }

    fun topGenre(title: TonightTitle): String? =
        title.genres.maxByOrNull { weights[it.genreKey()] ?: 0f }?.takeIf { (weights[it.genreKey()] ?: 0f) > 0f }

    companion object {
        val Empty = TonightTaste(emptyMap())

        fun from(pool: List<TonightTitle>): TonightTaste {
            val counts = HashMap<String, Float>()
            for (title in pool) {
                val weight = when {
                    title.started -> 2f
                    title.watched -> 1.5f
                    TonightOrigin.Library in title.origins -> 1f
                    else -> 0f
                }
                if (weight == 0f) continue
                for (genre in title.genres) counts[genre.genreKey()] = (counts[genre.genreKey()] ?: 0f) + weight
            }
            val max = counts.values.maxOrNull() ?: return Empty
            return TonightTaste(counts.mapValues { it.value / max })
        }
    }
}

// ---- Bedtime -------------------------------------------------------------------------------

/** Minutes from now until bedtime; bedtime after midnight (or earlier than now) is tomorrow. */
fun tonightMinutesUntilBed(nowMinuteOfDay: Int, bedMinuteOfDay: Int): Int {
    var gap = bedMinuteOfDay - nowMinuteOfDay
    // 23:30 bed at 21:10 is 140 minutes; 00:30 bed at 21:10 is 200; a bedtime more than 6 hours
    // behind the clock is read as tomorrow's, one only a little behind as "already past".
    if (gap < -6 * 60) gap += 24 * 60
    return gap
}

/** How long tonight's viewing of [title] runs: a film to its end, a show one episode. */
fun TonightTitle.minutesTonight(): Int? = when (kind) {
    TonightKind.Film -> resumeMinutesLeft ?: runtimeMinutes
    TonightKind.Show -> runtimeMinutes
}

/** Episodes of a show that fit before bed (at least one is always offered). */
fun tonightEpisodesBeforeBed(episodeMinutes: Int, minutesUntilBed: Int): Int =
    if (episodeMinutes <= 0) 1 else (minutesUntilBed / episodeMinutes).coerceAtLeast(1)

/**
 * Whether [title] fits before bed. A film must end by bedtime (with a 10 minute grace); a show
 * fits when one episode does. An unknown length is given the benefit of the doubt.
 */
fun TonightTitle.fitsBedtime(minutesUntilBed: Int): Boolean {
    val minutes = minutesTonight() ?: return true
    return minutes <= minutesUntilBed + 10
}

/** "23:06" for a film starting now; null without a length. */
fun TonightTitle.endsAtLabel(nowMinuteOfDay: Int): String? {
    val minutes = minutesTonight() ?: return null
    return clockLabel(nowMinuteOfDay + minutes)
}

fun clockLabel(minuteOfDay: Int): String {
    val m = ((minuteOfDay % (24 * 60)) + 24 * 60) % (24 * 60)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** "1h 56m", "45m". */
fun lengthLabel(minutes: Int): String = when {
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

/**
 * The night a moment belongs to, as an ISO date: before 6 am still counts as the evening before,
 * so a reshuffle used at 00:30 is not handed back.
 */
fun tonightNightOf(localIsoDate: String, minuteOfDay: Int, previousIsoDate: String): String =
    if (minuteOfDay < 6 * 60) previousIsoDate else localIsoDate
