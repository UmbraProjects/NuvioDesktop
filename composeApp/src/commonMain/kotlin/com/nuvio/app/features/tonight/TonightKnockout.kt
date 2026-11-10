package com.nuvio.app.features.tonight

import kotlin.random.Random

/*
 * fork:tonight - Knockout (design-notes/tonight-picker.md, phase 2): eight titles, two at a time,
 * three rounds. Pure: the screen holds a [TonightBracket] and replaces it on every pick or undo.
 */

const val TONIGHT_KNOCKOUT_SIZE = 8

/**
 * [entrants] in bracket order (match 1 is entrants 0 and 1, and so on); [winners] holds each
 * decided match's winner in the order the matches are played: four first-round matches, then two
 * semi-finals, then the final.
 */
data class TonightBracket(
    val entrants: List<TonightTitle>,
    val winners: List<TonightTitle> = emptyList(),
) {
    /**
     * The bracket column for [index]: column 0 is [entrants]; column k holds the winners of round k
     * so far, with null for matches not yet played. Always the full width of that column, so the
     * drawn bracket never changes shape.
     */
    fun column(index: Int): List<TonightTitle?> {
        if (index == 0) return entrants
        val sizes = roundSizes
        if (index > sizes.size) return emptyList()
        val start = sizes.take(index - 1).sum()
        val size = sizes[index - 1]
        return List(size) { winners.getOrNull(start + it) }
    }

    private val roundSizes: List<Int>
        get() = generateSequence(entrants.size / 2) { if (it > 1) it / 2 else null }.toList()

    val totalMatches: Int get() = roundSizes.sum()
    val isFinished: Boolean get() = entrants.size >= 2 && winners.size >= totalMatches
    val champion: TonightTitle? get() = if (isFinished) winners.last() else if (entrants.size == 1) entrants.first() else null

    /** 1-based round of the match being played (1 to 3 for eight entrants). */
    val roundNumber: Int
        get() {
            var played = winners.size
            roundSizes.forEachIndexed { i, size ->
                if (played < size) return i + 1
                played -= size
            }
            return roundSizes.size
        }

    val roundCount: Int get() = roundSizes.size

    /** The two titles in the match being played, or null once it is over. */
    val currentMatch: Pair<TonightTitle, TonightTitle>?
        get() {
            if (isFinished || entrants.size < 2) return null
            var field = entrants
            var played = winners
            for (size in roundSizes) {
                if (played.size < size) {
                    val i = played.size
                    return field[i * 2] to field[i * 2 + 1]
                }
                field = played.take(size)
                played = played.drop(size)
            }
            return null
        }

    fun keep(left: Boolean): TonightBracket {
        val match = currentMatch ?: return this
        return copy(winners = winners + if (left) match.first else match.second)
    }

    fun undo(): TonightBracket = if (winners.isEmpty()) this else copy(winners = winners.dropLast(1))
}

/**
 * Eight entrants for tonight: the strongest by taste and rating, spread over genres so no match
 * is two of the same, then shuffled by [seed]. Fewer when the pool is smaller (a power of two:
 * 8, 4 or 2); an empty bracket with fewer than two titles.
 */
fun tonightKnockoutEntrants(pool: List<TonightTitle>, taste: TonightTaste, seed: Long): List<TonightTitle> {
    val distinct = pool.distinctBy { it.key }
    val size = when {
        distinct.size >= 8 -> 8
        distinct.size >= 4 -> 4
        distinct.size >= 2 -> 2
        else -> return emptyList()
    }
    val random = Random(seed)
    val ranked = distinct.sortedByDescending { taste.score(it) * 0.5f + ((it.rating ?: 6.5) / 10.0).toFloat() * 0.5f }
    // A shortlist three times the bracket, then one per genre first so the bracket is varied.
    val shortlist = ranked.take(size * 3).shuffled(random)
    val chosen = mutableListOf<TonightTitle>()
    val genres = mutableSetOf<String>()
    for (t in shortlist) {
        if (chosen.size == size) break
        val g = t.genres.firstOrNull()?.genreKey()
        if (g == null || g !in genres) {
            chosen += t
            g?.let(genres::add)
        }
    }
    for (t in shortlist) {
        if (chosen.size == size) break
        if (t !in chosen) chosen += t
    }
    return chosen.shuffled(random)
}
