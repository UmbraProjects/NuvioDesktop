package com.nuvio.app.features.music.library

import kotlin.math.roundToInt

/**
 * Ladder phase 4b: the related-artist fallback, as pure data plus one small injected-source function (so it is
 * unit-tested without a network).
 *
 * A small artist often has no usable tag on MusicBrainz, so there is no genre to build a ladder from. Deezer knows
 * who they sound like ([DeezerClient.relatedByName], closest first). The first few of those are looked up on
 * MusicBrainz for their tags ([relatedArtistTags], one paced call each) and their genres are pooled
 * ([borrowGenres]) into votes for the artist, so the rest of the ladder runs unchanged. The screen can say the genres
 * were borrowed ([ArtistTarget.borrowedFrom]).
 */

/** How many of Deezer's related artists get a MusicBrainz tag look-up. Each is one paced call (1.1 s), so this stays small. */
internal const val RELATED_LOOKUPS = 4

/** Fewer related artists than this with usable tags is too thin to name a genre from. */
internal const val MIN_RELATED_TAGGED = 2

/** Each place down the related list weighs this much less: the weight is 1 / (1 + decay * position). */
internal const val RELATED_RANK_DECAY = 0.25

/** Borrowed votes are shares (0..1 per artist); this turns them into whole votes like MusicBrainz's. */
internal const val BORROWED_VOTE_SCALE = 100

/** Genres pooled from related artists: [votes] stand in for the artist's own tag votes, [from] names who lent them. */
internal data class BorrowedGenres(val votes: Map<String, Int>, val from: List<String>)

/**
 * Pools the genres of [related] (closest first). Each artist's votes are scaled so its top tag is 1.0, so one
 * artist with huge counts cannot drown the rest, then weighted by position. Artists with no usable genre lend
 * nothing and are not named. Null when fewer than [MIN_RELATED_TAGGED] artists lent anything.
 */
internal fun borrowGenres(related: List<LadderCandidate>): BorrowedGenres? {
    val sum = LinkedHashMap<String, Double>()
    val lenders = ArrayList<String>()
    related.forEachIndexed { position, artist ->
        val merged = normalizeWeightedGenres(artist.votes).filterValues { it > 0 }
        val top = merged.values.maxOrNull()?.toDouble() ?: return@forEachIndexed
        val weight = 1.0 / (1.0 + RELATED_RANK_DECAY * position)
        for ((name, count) in merged) sum.merge(name, weight * count / top, Double::plus)
        lenders += artist.name
    }
    if (lenders.size < MIN_RELATED_TAGGED) return null
    val votes = sum.mapValues { (it.value * BORROWED_VOTE_SCALE).roundToInt() }.filterValues { it > 0 }
    if (votes.isEmpty()) return null
    return BorrowedGenres(votes, lenders)
}

/**
 * The tagged artists related to [artist], closest first. [related] gives the names of the artists related to a name
 * (null when the source could not be reached, empty when it knows none). [tagged] searches MusicBrainz for a name and
 * answers like [MusicBrainzClient.searchArtistWithTags] (null when unreachable). Only the first [RELATED_LOOKUPS]
 * names are looked up; a name only counts when a hit has exactly that name (so a namesake's tags are never lent), and
 * an artist with no tags is dropped.
 *
 * Null when a source could not be reached before enough was found: nothing is cached then, so asking again tries again.
 */
internal suspend fun relatedArtistTags(
    artist: LadderCandidate,
    related: suspend (name: String) -> List<String>?,
    tagged: suspend (name: String) -> List<LadderCandidate>?,
): List<LadderCandidate>? {
    val names = related(artist.name) ?: return null
    val ownKey = genreKey(artist.name)
    val found = ArrayList<LadderCandidate>()
    for (name in names.filter { genreKey(it) != ownKey }.distinctBy { genreKey(it) }.take(RELATED_LOOKUPS)) {
        val hits = tagged(name) ?: return found.takeIf { it.size >= MIN_RELATED_TAGGED }
        val key = genreKey(name)
        val hit = hits.firstOrNull { genreKey(it.name) == key && it.votes.isNotEmpty() } ?: continue
        found += hit
    }
    return found
}
