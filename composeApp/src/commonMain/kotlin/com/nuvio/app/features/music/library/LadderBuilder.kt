package com.nuvio.app.features.music.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

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
internal const val MIN_PURITY = 0.15

/** At the far end of the dial the first rung starts this fraction of the way up the purity range. */
internal const val MAX_DIAL_START = 0.5

/** How hard a miss on the rung's purity counts against proximity. A miss of the whole pool range costs this much. */
internal const val PURITY_FIT_PENALTY = 3.0
/**
 * Imaginary votes for other genres added to every candidate's tag total before purity is read, so a single tag vote
 * for the genre no longer counts as 100% pure (from the 2026-10-29 "Into boogie" log: a 1-vote artist took the top
 * rung and beat real boogie acts by 3 x a 0.6 purity miss). Rungs, misses and the climb order all use this adjusted
 * purity; [LadderPoolStats] and the eligibility bar still use the plain share ([RungPick.rawPurity] keeps it).
 */
internal const val PURITY_PRIOR_VOTES = 3.0
internal const val PLAYABLE_BONUS = 0.1
internal const val FANS_BONUS = 0.05
private const val FANS_FOR_FULL_BONUS = 8.0 // log10 of 100 million fans

/** One artist that could stand on a rung, from a tag search. Everything but [id], [name] and [votes] is optional. */
@Serializable
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
    /** Phase 10: the year the artist began, when the source gave one. Only an album ladder reads it (era match). */
    val beginYear: Int? = null,
)

/**
 * Phase 10: what an album adds to a ladder beyond its genre. [profile] is the record's own sound (its blended genres,
 * style and mood, as a [TagProfile]); [year] is its era. A candidate that shares the record's sound beyond the target
 * genre, or began around the record's time, scores higher for a rung. Null in [buildLadder] is the plain ladder.
 */
internal data class LadderFlavour(
    val profile: TagProfile = emptyMap(),
    val year: Int? = null,
) {
    val isEmpty: Boolean get() = profile.isEmpty() && year == null
}

/** How much a candidate's sound matching the record's counts toward a rung (proximity counts 1.0). */
private const val FLAVOUR_WEIGHT = 0.6

/** How much beginning in the record's era counts toward a rung. */
private const val ERA_WEIGHT = 0.3

/** Years apart at which an artist stops counting as of the record's era. */
internal const val ERA_SPAN_YEARS = 25

/** Within this many years the why line says "around the same time". */
private const val SAME_ERA_YEARS = 5

/** 1.0 for the same year down to 0.0 at [ERA_SPAN_YEARS] apart; 0.0 when either year is unknown. */
internal fun eraFit(candidateYear: Int?, recordYear: Int?): Double {
    if (candidateYear == null || recordYear == null) return 0.0
    val apart = abs(candidateYear - recordYear)
    return (1.0 - apart.toDouble() / ERA_SPAN_YEARS).coerceIn(0.0, 1.0)
}

@Serializable
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
    /** How this rung was picked, for the debug view and the log. Never stored: a ladder read back from disk has none. */
    @Transient val pick: RungPick? = null,
)

@Serializable
internal data class Ladder(
    /** The target as named on screen ("drum and bass" for a typed "DnB"). */
    val target: String,
    val rungs: List<LadderRung>,
    /** True when the Library gave nothing to measure closeness by, so rungs follow purity and popularity only. */
    val coldStart: Boolean,
    /** What the pool looked like and the dial it was built at, for the debug view and the log. Never stored. */
    @Transient val pool: LadderPoolStats? = null,
) {
    val isEmpty: Boolean get() = rungs.isEmpty()
}

/** How one rung was chosen: which slot it was picked for and why it beat the rest. */
internal data class RungPick(
    /** The slot (1 to the number of rungs) it was picked for; the rung's final step can differ after the purity sort. */
    val slot: Int,
    /** The purity the slot asked for, read off the pool. */
    val rungTarget: Double,
    /** The share of the pool's purity range missed, 0.0 to 1.0. */
    val miss: Double,
    /** The score it won with: proximity + flavour + bonuses - [PURITY_FIT_PENALTY] x miss. */
    val fit: Double,
    /** Playable and popularity bonuses. */
    val bonus: Double,
    /** Album-query flavour and era score (0.0 for a plain ladder). */
    val flavour: Double,
    /** All the genre votes the artist has, once variants are merged: how much evidence sits behind the purity. */
    val tagVotes: Int,
    /** The plain share of the artist's genre votes that are the target's, before [PURITY_PRIOR_VOTES] is applied. */
    val rawPurity: Double = 0.0,
    /** The two best candidates it beat for the slot, with their scores. */
    val runnersUp: List<String>,
)

/** What a ladder was built from. */
internal data class LadderPoolStats(
    val candidates: Int,
    val owned: Int,
    val offGenre: Int,
    val scored: Int,
    /** Candidates at or above the minimum purity (all scored ones when none reach it). */
    val eligible: Int,
    val purityMin: Double,
    val purityMedian: Double,
    val purityMax: Double,
    val dial: Double,
    /** Where on the purity range the first rung starts (0.0 to 0.5). */
    val start: Double,
)

private fun totalTagVotes(votes: Map<String, Int>): Int = normalizeWeightedGenres(votes).values.sum()

private fun two(x: Double): Double = (x * 100).roundToInt() / 100.0

private class Scored(
    val candidate: LadderCandidate,
    val profile: TagProfile,
    val purity: Double,
    val proximity: Double,
    /** The plain share, for the eligibility bar and the pool statistics. */
    val raw: Double = purity,
    val flavourFit: Double = 0.0,
    val eraFit: Double = 0.0,
) {
    val flavourScore: Double get() = FLAVOUR_WEIGHT * flavourFit + ERA_WEIGHT * eraFit
}

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
 *
 * Phase 10, album query: [flavour] is the record's sound and era. It never changes who counts as of the genre (purity);
 * it adds to a candidate's score for a rung when it shares the record's sound beyond the target, or began in its time,
 * so a record gives a different ladder from its artist's even in the same genre. Null is the plain ladder.
 */
internal fun buildLadder(
    target: String,
    artists: List<LibraryArtist>,
    candidates: List<LadderCandidate>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
    anchors: List<LibraryArtist>? = null,
    from: String? = null,
    flavour: LadderFlavour? = null,
): Ladder {
    val targetName = canonicalGenre(target) ?: return Ladder(target.trim(), emptyList(), coldStart = true)
    val targetKey = genreKey(targetName)
    val excluded = setOf(targetKey)

    val taste = anchors ?: artists
    val me = footprint(taste)
    val cold = unitProfile(me.filterKeys { it !in excluded }).isEmpty()

    val record = flavour?.takeUnless { it.isEmpty }
    val recordSound = record?.let { unitProfile(it.profile.filterKeys { k -> k !in excluded }) }.orEmpty()

    val ownedIds = artists.map { it.id }.toSet()
    val ownedNames = artists.map { genreKey(it.name) }.toSet()

    val distinct = candidates.distinctBy { it.id }
    val unowned = distinct.filter { it.id !in ownedIds && genreKey(it.name) !in ownedNames }
    val scored = unowned
        .mapNotNull { c ->
            val raw = purity(c.votes, targetKey)
            if (raw <= 0.0) return@mapNotNull null
            val p = shrunkPurity(c.votes, targetKey, PURITY_PRIOR_VOTES)
            val profile = tagProfileFromVotes(c.votes, c.mood, c.style)
            Scored(
                c, profile, p, if (cold) 0.0 else proximity(profile, me, excluded),
                raw = raw,
                flavourFit = if (recordSound.isEmpty()) 0.0 else proximity(profile, recordSound, excluded),
                eraFit = eraFit(c.beginYear, record?.year),
            )
        }
    val eligible = scored.filter { it.raw >= MIN_PURITY }.ifEmpty { scored }
    val count = minOf(rungs.coerceAtLeast(1), eligible.size)
    val start = dial.coerceIn(0.0, 1.0) * MAX_DIAL_START
    val poolStats = LadderPoolStats(
        candidates = distinct.size,
        owned = distinct.size - unowned.size,
        offGenre = unowned.size - scored.size,
        scored = scored.size,
        eligible = eligible.size,
        purityMin = two(eligible.minOfOrNull { it.raw } ?: 0.0),
        purityMedian = two(eligible.map { it.raw }.sorted().let { if (it.isEmpty()) 0.0 else quantile(it, 0.5) }),
        purityMax = two(eligible.maxOfOrNull { it.raw } ?: 0.0),
        dial = dial.coerceIn(0.0, 1.0),
        start = start,
    )
    if (count == 0) return Ladder(targetName, emptyList(), cold, poolStats)

    val sortedPurities = eligible.map { it.purity }.sorted()
    val range = sortedPurities.last() - sortedPurities.first()

    fun missOf(c: Scored, rungTarget: Double): Double = if (range > 0.0) abs(c.purity - rungTarget) / range else 0.0
    fun fitOf(c: Scored, rungTarget: Double): Double =
        c.proximity + c.flavourScore + bonuses(c.candidate) - PURITY_FIT_PENALTY * missOf(c, rungTarget)

    val remaining = eligible.toMutableList()
    val picked = ArrayList<Scored>(count)
    val picks = HashMap<String, RungPick>()
    for (k in 0 until count) {
        val along = if (count == 1) 0.0 else k.toDouble() / (count - 1)
        val rungTarget = quantile(sortedPurities, start + (1.0 - start) * along)
        val best = remaining.maxWithOrNull(
            compareBy<Scored> { fitOf(it, rungTarget) }
                // A stable answer when two candidates score the same: the one with more fans, then the one with more
                // tag votes behind its purity (MusicBrainz candidates have no fans, and a single vote for the genre
                // is 100% pure just like a hundred), then by id.
                .thenBy { it.candidate.fans ?: 0L }
                .thenBy { totalTagVotes(it.candidate.votes) }
                .thenByDescending { it.candidate.id },
        ) ?: break
        picks[best.candidate.id] = RungPick(
            slot = k + 1,
            rungTarget = two(rungTarget),
            miss = two(missOf(best, rungTarget)),
            fit = two(fitOf(best, rungTarget)),
            bonus = two(bonuses(best.candidate)),
            flavour = two(best.flavourScore),
            tagVotes = totalTagVotes(best.candidate.votes),
            rawPurity = two(best.raw),
            runnersUp = remaining.filter { it !== best }
                .sortedByDescending { fitOf(it, rungTarget) }
                .take(2)
                .map { "${it.candidate.name} ${two(fitOf(it, rungTarget))}" },
        )
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
            why = whyLine(index, ordered.size, targetName, targetKey, s, me, anchor, cold, from) +
                flavourNote(s, recordSound, record?.year),
            pick = picks[s.candidate.id],
        )
    }
    return Ladder(targetName, built, cold, poolStats)
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

/** Phase 10: what the rung shares with the record, as a sentence to add to the why line (empty when nothing). */
private fun flavourNote(s: Scored, recordSound: TagProfile, recordYear: Int?): String {
    val names = namesByKey(s.candidate.votes)
    val shared = s.profile.entries
        .filter { !it.key.startsWith(MOOD_PREFIX) && it.key in recordSound }
        .sortedByDescending { minOf(it.value, recordSound.getValue(it.key)) }
        .mapNotNull { names[it.key] }
        .take(2)
    val parts = ArrayList<String>()
    if (shared.isNotEmpty()) parts += "Echoes the record's ${listAnd(shared)}."
    val year = s.candidate.beginYear
    if (year != null && recordYear != null && abs(year - recordYear) <= SAME_ERA_YEARS) {
        parts += "Began around the same time ($year)."
    }
    return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
}

// endregion
