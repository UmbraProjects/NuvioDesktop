package com.nuvio.app.features.music.library

import kotlin.math.abs
import kotlin.math.log10

/**
 * Ladder phase 2: choosing the rungs, as pure data (no Compose, no network, so it is unit-tested).
 *
 * Given a target genre, the listener's saved artists and a pool of candidate artists, [buildLadder] picks
 * up to five rungs so purity rises and proximity falls: rung 1 is a bridge from the listener's taste, the
 * last rung is the core of the genre. Scores come from phase 1; this file only decides which candidate
 * stands on which rung, and says why in one plain sentence (a template, no AI).
 *
 * How a rung is chosen: each rung has a target purity, read off where the pool's purities actually fall (so
 * a pool with few near-pure artists does not leave the top rungs empty). The candidate with the best score
 * takes it, where the score rewards proximity and a playable release, and penalises distance from the rung's
 * target purity, measured against the pool's purity range. Rungs are filled in order and a candidate stands
 * on at most one.
 */

internal const val LADDER_RUNGS = 5

/** A candidate under this share of target votes is not really of the genre; it is skipped when enough others exist. */
private const val MIN_PURITY = 0.15

/** At the far end of the dial the first rung starts this fraction of the way up the purity range. */
private const val MAX_DIAL_START = 0.5

/** How hard a miss on the rung's purity counts against proximity. A miss of the whole pool range costs this much. */
private const val PURITY_FIT_PENALTY = 3.0
private const val PLAYABLE_BONUS = 0.1
private const val FANS_BONUS = 0.05
private const val FANS_FOR_FULL_BONUS = 8.0 // log10 of 100 million fans

/** One artist that could stand on a rung, from a tag search. Everything but [id], [name] and [votes] is optional. */
internal data class LadderCandidate(
    val id: String,
    val name: String,
    /** Tag votes as the source gave them: spelling variants and junk are cleaned up when scoring. */
    val votes: Map<String, Int>,
    val mood: String? = null,
    val style: String? = null,
    val fans: Long? = null,
    /** False when no release of this artist can be played here. It still takes a rung if it fits best, flagged. */
    val playable: Boolean = true,
)

internal data class LadderRung(
    /** 1 for the bridge, up to the number of rungs for the core. */
    val step: Int,
    val candidate: LadderCandidate,
    val purity: Double,
    val proximity: Double,
    /** The saved artist this rung is most like, or null when nothing in the Library is close. */
    val anchorName: String?,
    /** One sentence for the screen. */
    val why: String,
    /** Phase 7: what the person said about this rung, when it is one they have already climbed. */
    val mark: RungFeedback? = null,
)

internal data class Ladder(
    /** The target as named on screen ("drum and bass" for a typed "DnB"). */
    val target: String,
    val rungs: List<LadderRung>,
    /** True when the Library gave nothing to measure closeness by, so rungs follow purity and popularity only. */
    val coldStart: Boolean,
) {
    val isEmpty: Boolean get() = rungs.isEmpty()
}

private class Scored(
    val candidate: LadderCandidate,
    val profile: TagProfile,
    val purity: Double,
    val proximity: Double,
)

/** The popularity bonus: 0.0 for no fans up to [FANS_BONUS] at the top of the scale. A tie-breaker, never a driver. */
private fun fansBonus(fans: Long?): Double {
    if (fans == null || fans <= 0L) return 0.0
    return (log10(fans.toDouble() + 1.0) / FANS_FOR_FULL_BONUS).coerceIn(0.0, 1.0) * FANS_BONUS
}

/**
 * The ladder into [target] for this listener.
 *
 * [dial] runs from 0.0 (Known: start close to the listener's taste) to 1.0 (Unknown: the first rung starts
 * further up the genre). Artists the listener has already saved never stand on a rung. An unusable [target]
 * (blank or not a genre) or a pool with nothing of the genre gives an empty ladder.
 *
 * Phase 4b, out-from mode: [anchors] is the taste closeness is measured against instead of [artists] (the artist the
 * person already loves), and [from] is that artist's name, which the why lines use instead of "your saved ...".
 * [artists] still decides who never stands on a rung. Both null is the plain ladder.
 */
internal fun buildLadder(
    target: String,
    artists: List<LibraryArtist>,
    candidates: List<LadderCandidate>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
    anchors: List<LibraryArtist>? = null,
    from: String? = null,
): Ladder {
    val targetName = canonicalGenre(target) ?: return Ladder(target.trim(), emptyList(), coldStart = true)
    val targetKey = genreKey(targetName)
    val excluded = setOf(targetKey)

    val taste = anchors ?: artists
    val me = footprint(taste)
    val cold = unitProfile(me.filterKeys { it !in excluded }).isEmpty()

    val ownedIds = artists.map { it.id }.toSet()
    val ownedNames = artists.map { genreKey(it.name) }.toSet()

    val scored = candidates
        .distinctBy { it.id }
        .filter { it.id !in ownedIds && genreKey(it.name) !in ownedNames }
        .mapNotNull { c ->
            val p = purity(c.votes, targetKey)
            if (p <= 0.0) return@mapNotNull null
            val profile = tagProfileFromVotes(c.votes, c.mood, c.style)
            Scored(c, profile, p, if (cold) 0.0 else proximity(profile, me, excluded))
        }
    val eligible = scored.filter { it.purity >= MIN_PURITY }.ifEmpty { scored }
    val count = minOf(rungs.coerceAtLeast(1), eligible.size)
    if (count == 0) return Ladder(targetName, emptyList(), cold)

    val sortedPurities = eligible.map { it.purity }.sorted()
    val range = sortedPurities.last() - sortedPurities.first()
    val start = dial.coerceIn(0.0, 1.0) * MAX_DIAL_START

    val remaining = eligible.toMutableList()
    val picked = ArrayList<Scored>(count)
    for (k in 0 until count) {
        val along = if (count == 1) 0.0 else k.toDouble() / (count - 1)
        val rungTarget = quantile(sortedPurities, start + (1.0 - start) * along)
        val best = remaining.maxWithOrNull(
            compareBy<Scored> {
                val miss = if (range > 0.0) abs(it.purity - rungTarget) / range else 0.0
                it.proximity + bonuses(it.candidate) - PURITY_FIT_PENALTY * miss
            }
                // A stable answer when two candidates score the same: the one with more fans, then by id.
                .thenBy { it.candidate.fans ?: 0L }
                .thenByDescending { it.candidate.id },
        ) ?: break
        picked += best
        remaining -= best
    }

    // Purity rises up the ladder even when two picks swapped places inside a band.
    val ordered = picked.sortedWith(compareBy<Scored> { it.purity }.thenBy { it.candidate.id })
    val built = ordered.mapIndexed { index, s ->
        val anchor = if (cold) null else closestArtist(s.profile, taste, excluded)
        LadderRung(
            step = index + 1,
            candidate = s.candidate,
            purity = s.purity,
            proximity = s.proximity,
            anchorName = anchor?.name,
            why = whyLine(index, ordered.size, targetName, targetKey, s, me, anchor, cold, from),
        )
    }
    return Ladder(targetName, built, cold)
}

/** The value [fraction] (0.0 to 1.0) of the way up [sorted], interpolating between neighbours. [sorted] is not empty. */
private fun quantile(sorted: List<Double>, fraction: Double): Double {
    val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val below = position.toInt()
    val above = minOf(below + 1, sorted.size - 1)
    return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
}

private fun bonuses(c: LadderCandidate): Double = (if (c.playable) PLAYABLE_BONUS else 0.0) + fansBonus(c.fans)

/** The saved artist most like [profile] once the target is ignored, or null when none shares anything. */
private fun closestArtist(profile: TagProfile, artists: List<LibraryArtist>, excluded: Set<String>): LibraryArtist? =
    artists
        .map { it to proximity(profile, it.tagProfile(), excluded) }
        .filter { it.second > 0.0 }
        .maxByOrNull { it.second }
        ?.first

// region the sentence

private fun listAnd(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

/** Readable genre names for a candidate's profile keys, from its own votes. */
private fun namesByKey(votes: Map<String, Int>): Map<String, String> =
    normalizeWeightedGenres(votes).keys.associateBy { genreKey(it) }

private fun whyLine(
    index: Int,
    total: Int,
    target: String,
    targetKey: String,
    s: Scored,
    me: TagProfile,
    anchor: LibraryArtist?,
    cold: Boolean,
    from: String?,
): String {
    val names = namesByKey(s.candidate.votes)
    val first = index == 0
    val last = index == total - 1 && total > 1

    val shared = s.profile.entries
        .filter { it.key != targetKey && !it.key.startsWith(MOOD_PREFIX) && it.key in me }
        .sortedByDescending { minOf(it.value, me.getValue(it.key)) }
        .mapNotNull { names[it.key] }
        .take(2)

    if (!cold && anchor != null && shared.isNotEmpty()) {
        val common = listAnd(shared)
        if (from != null) {
            return when {
                first -> "Nearest to $from: shares $common, with a little more $target."
                last -> "The core of $target, and still shares $common with $from."
                else -> "Shares $common with $from, with more $target."
            }
        }
        return when {
            first -> "Closest to your taste: shares $common with your saved ${anchor.name}."
            last -> "The core of $target, and still shares $common with your saved ${anchor.name}."
            else -> "Shares $common with your saved ${anchor.name}, with more $target."
        }
    }

    val tags = normalizeWeightedGenres(s.candidate.votes).keys
        .filter { genreKey(it) != targetKey }
        .take(2)
        .ifEmpty { listOf(target) }
    val tagged = listAnd(tags)
    return when {
        last -> "The core of $target: tagged $tagged."
        first && cold -> "A popular way into $target: tagged $tagged."
        first -> "A way into $target: tagged $tagged."
        else -> "Deeper into $target: tagged $tagged."
    }
}

// endregion
