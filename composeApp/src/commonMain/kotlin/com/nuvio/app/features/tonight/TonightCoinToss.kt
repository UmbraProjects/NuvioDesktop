package com.nuvio.app.features.tonight

import kotlin.random.Random

/*
 * fork:tonight - Coin Toss (design-notes/tonight-picker.md, phase 2). Nuvio picks one title and
 * counts down; doing nothing plays it. Each "not this" is read for what put the user off (too
 * long, too heavy...) and the next toss leans away from it.
 */

/** What a veto said about the vetoed title, compared with the rest of tonight's pool. */
enum class TonightTrait(val reason: String, val leanWord: String) {
    Long("too long", "shorter"),
    Short("too short", "longer"),
    Heavy("too heavy", "lighter"),
    Light("too light", "weightier"),
    Film("not a film night", "towards shows"),
    Show("not a show night", "towards films"),
    Unfamiliar("too unfamiliar", "familiar"),
    Familiar("seen it before", "something new"),
}

data class TonightVeto(val title: TonightTitle, val trait: TonightTrait?)

private fun List<Int>.medianOrNull(): Int? = if (isEmpty()) null else sorted()[size / 2]

/**
 * The trait that best explains a veto: whatever about [title] stands furthest from the middle of
 * [pool]. Null when nothing stands out (a plain "not that one").
 */
fun tonightVetoTrait(title: TonightTitle, pool: List<TonightTitle>): TonightTrait? {
    val scores = mutableListOf<Pair<TonightTrait, Float>>()
    val sameKind = pool.filter { it.kind == title.kind }
    val minutes = title.minutesTonight()
    val median = sameKind.mapNotNull { it.minutesTonight() }.medianOrNull()
    if (minutes != null && median != null && median > 0) {
        val ratio = minutes.toFloat() / median
        // Length counts half again: the lightness score already leans on length, which would otherwise win.
        if (ratio >= 1.2f) scores += TonightTrait.Long to (ratio - 1f) * 1.5f
        if (ratio <= 0.75f) scores += TonightTrait.Short to (1f - ratio)
    }
    val light = tonightLightness(title)
    if (light <= -0.3f) scores += TonightTrait.Heavy to -light
    if (light >= 0.5f) scores += TonightTrait.Light to light * 0.6f
    when {
        title.started || title.watched -> scores += TonightTrait.Familiar to 0.4f
        title.isUnfamiliar() ->
            scores += TonightTrait.Unfamiliar to 0.3f
    }
    // Film or show is the weakest reading: only when the pool really is a mix of both.
    val kinds = pool.map { it.kind }.toSet()
    if (kinds.size == 2) scores += (if (title.kind == TonightKind.Film) TonightTrait.Film else TonightTrait.Show) to 0.25f
    return scores.maxByOrNull { it.second }?.takeIf { it.second >= 0.2f }?.first
}

/**
 * Whether this title shares what put the user off [veto]. Length is judged against the vetoed
 * title itself (as long as it, or longer), not the pool's middle, so a pool full of long films
 * still leans away from them.
 */
fun TonightTitle.sharesVeto(veto: TonightVeto): Boolean = when (veto.trait) {
    null -> false
    TonightTrait.Long, TonightTrait.Short -> {
        val minutes = minutesTonight()
        val vetoed = veto.title.minutesTonight()
        if (minutes == null || vetoed == null || kind != veto.title.kind) false
        else if (veto.trait == TonightTrait.Long) minutes >= vetoed * 0.85f else minutes <= vetoed * 1.15f
    }
    TonightTrait.Heavy -> tonightLightness(this) <= -0.3f
    TonightTrait.Light -> tonightLightness(this) >= 0.5f
    TonightTrait.Film -> kind == TonightKind.Film
    TonightTrait.Show -> kind == TonightKind.Show
    TonightTrait.Familiar -> started || watched
    TonightTrait.Unfamiliar -> isUnfamiliar()
}

/** Came from outside what the user keeps: trending, or a Discover suggestion. */
private fun TonightTitle.isUnfamiliar(): Boolean =
    TonightOrigin.Library !in origins &&
        (TonightOrigin.Trending in origins || TonightOrigin.Recommended in origins)

/** "leaning shorter and lighter", or null before any veto said anything. */
fun tonightLeanLabel(vetoes: List<TonightVeto>): String? {
    val words = vetoes.mapNotNull { it.trait }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(2).map { it.key.leanWord }
    if (words.isEmpty()) return null
    return "leaning " + words.joinToString(" and ")
}

/**
 * The next toss: a title not vetoed tonight, drawn with weight from taste and rating, each
 * vetoed trait it shares halving its chances. Null when the pool is used up.
 */
fun tonightToss(
    pool: List<TonightTitle>,
    taste: TonightTaste,
    vetoes: List<TonightVeto>,
    seed: Long,
): TonightTitle? {
    val vetoed = vetoes.map { it.title.key }.toSet()
    val open = pool.filter { it.key !in vetoed }
    if (open.isEmpty()) return null
    val weighted = open.map { t ->
        var w = 0.2f + taste.score(t) + ((t.rating ?: 6.5) / 10.0).toFloat()
        for (veto in vetoes) if (t.sharesVeto(veto)) w *= 0.5f
        t to w
    }
    // Only the best twelve are in the hat, so a toss is never something obscure for its own sake.
    val hat = weighted.sortedByDescending { it.second }.take(12)
    val total = hat.sumOf { it.second.toDouble() }
    var roll = Random(seed).nextDouble() * total
    for ((title, w) in hat) {
        roll -= w
        if (roll <= 0) return title
    }
    return hat.last().first
}

const val TONIGHT_COIN_SECONDS = 10
