package com.nuvio.app.features.home

import com.nuvio.app.core.i18n.localizedString
import nuvio.composeapp.generated.resources.*

/**
 * What clicking a hero discovery badge opens — the reverse of the badge itself.
 *
 * A badge says "this title has X"; its target answers "what else has X". Only badges whose
 * answer has a real source get one: the award lists are local id sets, the rest are single
 * TMDB queries. Badges driven by MDBList-native keywords with no TMDB equivalent (festival
 * winners, Metacritic must-see) and badges computed from per-title structure (mini series,
 * binge ready) return null and stay hover-only.
 */
sealed interface HeroDiscoveryBadgeTarget {
    /** One of the hardcoded award sets in [HeroDiscoveryAwards]. */
    data class AwardList(val award: HeroAward, val won: Boolean) : HeroDiscoveryBadgeTarget

    /**
     * `discover` filtered by TMDB keyword ids, ORed. [movieOnly] is for keywords TMDB applies
     * per episode on television (the stingers), where a series-level query returns nothing.
     */
    data class Keyword(val keywordIds: List<Int>, val movieOnly: Boolean) : HeroDiscoveryBadgeTarget

    /** `discover` by original language. [code] is whatever the metadata carried — 2- or 3-letter. */
    data class Language(val code: String) : HeroDiscoveryBadgeTarget

    data object Trending : HeroDiscoveryBadgeTarget

    /** Films that became available at home recently — the "New" badge's window. */
    data object NewAtHome : HeroDiscoveryBadgeTarget

    /** The `release_status` badge reading "Cinema". */
    data object NowPlaying : HeroDiscoveryBadgeTarget

    /** The `release_status` badge reading "Production". */
    data object Upcoming : HeroDiscoveryBadgeTarget

    data object ShortFilm : HeroDiscoveryBadgeTarget

    /** Handled by the caller: opens the existing production-company browse screen. */
    data class Company(val tmdbId: Int, val name: String) : HeroDiscoveryBadgeTarget

    /** Handled by the caller: resolved to a person via search, then the person screen. */
    data class Director(val name: String) : HeroDiscoveryBadgeTarget
}

enum class HeroAward(val key: String) {
    BEST_PICTURE("best_picture"),
    GOLDEN_GLOBE("globe"),
    EMMY("emmy");

    companion object {
        fun fromKey(key: String): HeroAward? = entries.firstOrNull { it.key == key }
    }
}

/** TMDB keyword ids behind the keyword-driven badges. MDBList passes these names through verbatim. */
object HeroDiscoveryKeywordIds {
    const val AFTER_CREDITS_STINGER = 179430
    const val DURING_CREDITS_STINGER = 179431
    const val CULT_FILM = 374649
    const val BASED_ON_TRUE_STORY = 9672
}

fun HeroDiscoveryFact.browseTarget(): HeroDiscoveryBadgeTarget? {
    if (category.startsWith("foreign:")) {
        val code = category.removePrefix("foreign:").trim()
        return code.takeIf { it.isNotBlank() }?.let { HeroDiscoveryBadgeTarget.Language(it) }
    }
    return when (category) {
        "award:best_picture" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.BEST_PICTURE, won = true)
        "award:best_picture_nom" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.BEST_PICTURE, won = false)
        "award:globe_win" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.GOLDEN_GLOBE, won = true)
        "award:globe_nom" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.GOLDEN_GLOBE, won = false)
        "award:emmy_win" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.EMMY, won = true)
        "award:emmy_nom" -> HeroDiscoveryBadgeTarget.AwardList(HeroAward.EMMY, won = false)
        // The badge label distinguishes mid from post, but the browse is "titles with a stinger":
        // the two keywords are tagged independently and a title with both is one title.
        "stinger" -> HeroDiscoveryBadgeTarget.Keyword(
            keywordIds = listOf(
                HeroDiscoveryKeywordIds.AFTER_CREDITS_STINGER,
                HeroDiscoveryKeywordIds.DURING_CREDITS_STINGER,
            ),
            movieOnly = true,
        )
        "cult" -> HeroDiscoveryBadgeTarget.Keyword(listOf(HeroDiscoveryKeywordIds.CULT_FILM), movieOnly = false)
        "true_story" -> HeroDiscoveryBadgeTarget.Keyword(listOf(HeroDiscoveryKeywordIds.BASED_ON_TRUE_STORY), movieOnly = false)
        "trending" -> HeroDiscoveryBadgeTarget.Trending
        "new_release", "digital_release" -> HeroDiscoveryBadgeTarget.NewAtHome
        "short_film" -> HeroDiscoveryBadgeTarget.ShortFilm
        "release_status" -> when (label.trim().lowercase()) {
            "cinema" -> HeroDiscoveryBadgeTarget.NowPlaying
            "production" -> HeroDiscoveryBadgeTarget.Upcoming
            else -> null
        }
        "studio" -> companyTmdbId?.takeIf { it > 0 }?.let { HeroDiscoveryBadgeTarget.Company(it, label) }
        "director" -> directorName?.trim()?.takeIf { it.isNotBlank() }?.let { HeroDiscoveryBadgeTarget.Director(it) }
        else -> null
    }
}

/**
 * Encoded form for the navigation route: `kind` selects the branch, `value` carries its data.
 * [HeroDiscoveryBadgeTarget.Company] and [HeroDiscoveryBadgeTarget.Director] never travel through
 * the badge browse route (they open other screens), so they have no encoding.
 */
fun HeroDiscoveryBadgeTarget.encodeForRoute(): Pair<String, String>? = when (this) {
    is HeroDiscoveryBadgeTarget.AwardList -> "award" to "${award.key}:${if (won) "win" else "nom"}"
    is HeroDiscoveryBadgeTarget.Keyword ->
        "keyword" to keywordIds.joinToString(",") + (if (movieOnly) ";movie" else "")
    is HeroDiscoveryBadgeTarget.Language -> "language" to code
    HeroDiscoveryBadgeTarget.Trending -> "trending" to ""
    HeroDiscoveryBadgeTarget.NewAtHome -> "new_at_home" to ""
    HeroDiscoveryBadgeTarget.NowPlaying -> "now_playing" to ""
    HeroDiscoveryBadgeTarget.Upcoming -> "upcoming" to ""
    HeroDiscoveryBadgeTarget.ShortFilm -> "short_film" to ""
    is HeroDiscoveryBadgeTarget.Company, is HeroDiscoveryBadgeTarget.Director -> null
}

fun decodeHeroDiscoveryBadgeTarget(kind: String, value: String): HeroDiscoveryBadgeTarget? = when (kind) {
    "award" -> {
        val award = HeroAward.fromKey(value.substringBefore(':'))
        val won = when (value.substringAfter(':', "")) {
            "win" -> true
            "nom" -> false
            else -> null
        }
        if (award != null && won != null) HeroDiscoveryBadgeTarget.AwardList(award, won) else null
    }
    "keyword" -> {
        val ids = value.substringBefore(';').split(',').mapNotNull { it.trim().toIntOrNull() }
        if (ids.isEmpty()) null else HeroDiscoveryBadgeTarget.Keyword(ids, movieOnly = value.substringAfter(';', "") == "movie")
    }
    "language" -> value.trim().takeIf { it.isNotBlank() }?.let { HeroDiscoveryBadgeTarget.Language(it) }
    "trending" -> HeroDiscoveryBadgeTarget.Trending
    "new_at_home" -> HeroDiscoveryBadgeTarget.NewAtHome
    "now_playing" -> HeroDiscoveryBadgeTarget.NowPlaying
    "upcoming" -> HeroDiscoveryBadgeTarget.Upcoming
    "short_film" -> HeroDiscoveryBadgeTarget.ShortFilm
    else -> null
}

/** Stable identity for caches: the encoded form, or the target's own string for the un-routable ones. */
fun HeroDiscoveryBadgeTarget.cacheKey(): String =
    encodeForRoute()?.let { (kind, value) -> "$kind:$value" } ?: toString()

/**
 * The name the browse screen shows for a target. The badge label is right for most ("Best
 * Picture", "Japanese Film", "Trending"); the release-status labels are the *title's* status
 * ("Cinema", "Production", "New") and read wrong as a list name, so those three get their own.
 */
fun HeroDiscoveryBadgeTarget.browseTitle(badgeLabel: String): String = when (this) {
    HeroDiscoveryBadgeTarget.NewAtHome -> localizedString(Res.string.hero_browse_title_new_at_home)
    HeroDiscoveryBadgeTarget.NowPlaying -> localizedString(Res.string.hero_browse_title_in_cinemas)
    HeroDiscoveryBadgeTarget.Upcoming -> localizedString(Res.string.hero_browse_title_coming_soon)
    is HeroDiscoveryBadgeTarget.Keyword -> when {
        // "Mid-Credits Scene" would promise a filter the query does not make; both stingers are listed.
        keywordIds.size > 1 -> localizedString(Res.string.hero_browse_title_post_credits)
        else -> badgeLabel
    }
    else -> badgeLabel
}
