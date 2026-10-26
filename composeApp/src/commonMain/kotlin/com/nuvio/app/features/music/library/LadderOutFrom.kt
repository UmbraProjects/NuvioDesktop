package com.nuvio.app.features.music.library

/**
 * Ladder phase 4b: out-from mode, as pure data (no Compose, no network, so it is unit-tested).
 *
 * Into mode is for an artist the person has never heard: the ladder climbs into the artist's genre. Out-from is for
 * an artist they already love: the ladder starts next to that artist and leads away from them, into a *neighbouring*
 * genre, so rung 1 sounds most like the artist and the last rung is the purest form of the new genre.
 *
 * It is the same [buildLadder], with two things changed: the target is the destination genre (the artist's second
 * genre, or a neighbour found by tag co-occurrence), and closeness is measured against the artist alone
 * (`anchors`), not the whole Library. The Library still decides who never stands on a rung. The artist is never on
 * their own ladder.
 */

internal enum class LadderMode { Into, OutFrom }

/** How many destination genres are offered for out-from mode. */
internal const val OUTWARD_OPTIONS = 3

/** A genre found only by co-occurrence must appear on at least this many of the primary genre's candidates. */
internal const val MIN_COOCCURRENCE = 2

/** The artist as the taste a ladder is measured against: their strongest genres in order, plus mood and style. */
internal fun ArtistTarget.asAnchor(): LibraryArtist = LibraryArtist(
    id = artist.id,
    name = artist.name,
    genres = normalizeWeightedGenres(artist.votes).keys.take(ANCHOR_GENRES).toList(),
    mood = artist.mood,
    style = artist.style,
    favourite = true,
)

private const val ANCHOR_GENRES = 6

/**
 * Genres out-from mode could lead into, best first, at most [OUTWARD_OPTIONS]: the artist's own other genres, then,
 * when [primaryPool] (the primary genre's candidates) is given, genres that often sit beside the primary one
 * (by share of the candidates' votes, on at least [MIN_COOCCURRENCE] of them). Without a pool only the artist's own
 * genres are offered, which can be none.
 */
internal fun outwardOptions(target: ArtistTarget, primaryPool: List<LadderCandidate>? = null): List<String> {
    val primaryKey = genreKey(target.genre)
    val own = target.genres.filter { genreKey(it) != primaryKey }
    if (own.size >= OUTWARD_OPTIONS || primaryPool == null) return own.take(OUTWARD_OPTIONS)

    val skip = (own + target.genre).map(::genreKey).toSet()
    val ownKey = genreKey(target.artist.name)
    val share = HashMap<String, Double>()
    val seen = HashMap<String, Int>()
    val names = HashMap<String, String>()
    for (c in primaryPool) {
        if (c.id == target.artist.id || genreKey(c.name) == ownKey) continue
        val merged = normalizeWeightedGenres(c.votes)
        val total = merged.values.sum()
        if (total <= 0 || purity(c.votes, primaryKey) <= 0.0) continue
        for ((name, count) in merged) {
            val key = genreKey(name)
            if (key in skip) continue
            share.merge(key, count.toDouble() / total, Double::plus)
            seen.merge(key, 1, Int::plus)
            names.putIfAbsent(key, name)
        }
    }
    val extra = share.keys
        .filter { (seen[it] ?: 0) >= MIN_COOCCURRENCE }
        .sortedWith(compareByDescending<String> { share.getValue(it) }.thenBy { it })
        .map { names.getValue(it) }
    return (own + extra).take(OUTWARD_OPTIONS)
}

/**
 * The genre to lead out into: [chosen] when it is a real genre other than the artist's primary one, else the first of
 * [options]. Null when neither exists.
 */
internal fun pickDestination(target: ArtistTarget, options: List<String>, chosen: String?): String? {
    val picked = chosen?.let(::canonicalGenre)
    if (picked != null && genreKey(picked) != genreKey(target.genre)) return picked
    return options.firstOrNull()
}

/**
 * The ladder out from [target] into [destination]: [buildLadder] on the destination over [pool], with closeness
 * measured against the artist alone. Neither the artist (by id and by name) nor a saved artist stands on a rung.
 */
internal fun buildOutFromLadder(
    target: ArtistTarget,
    destination: String,
    artists: List<LibraryArtist>,
    pool: List<LadderCandidate>,
    dial: Double = 0.0,
): Ladder {
    val ownKey = genreKey(target.artist.name)
    val rest = pool.filter { it.id != target.artist.id && genreKey(it.name) != ownKey }
    return buildLadder(
        destination,
        artists,
        rest,
        dial,
        anchors = listOf(target.asAnchor()),
        from = target.artist.name,
    )
}

/** [LadderArtistTargets.ladderOutFrom] with its two sources passed in, so the whole path is tested without a network. */
internal suspend fun ladderOutFrom(
    resolver: ArtistTargetResolver,
    candidates: suspend (genre: String) -> List<LadderCandidate>?,
    query: String,
    artists: List<LibraryArtist>,
    destination: String? = null,
    dial: Double = 0.0,
): ArtistLadderOutcome {
    val target = when (val lookup = resolver.resolve(query)) {
        is ArtistLookup.Failed -> return ArtistLadderOutcome.Failed(lookup.reason)
        is ArtistLookup.Found -> lookup.target
    }
    var options = outwardOptions(target)
    var chosen = pickDestination(target, options, destination)
    if (chosen == null) {
        // The artist has only one genre: look at who else carries it to find its neighbours.
        val primaryPool = candidates(target.genre) ?: return ArtistLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
        options = outwardOptions(target, primaryPool)
        chosen = pickDestination(target, options, null)
            ?: return ArtistLadderOutcome.Failed(ArtistLadderFailure.NoOutward)
    }
    val pool = candidates(chosen) ?: return ArtistLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
    val ladder = buildOutFromLadder(target, chosen, artists, pool, dial)
    val chosenKey = genreKey(chosen)
    return ArtistLadderOutcome.Ready(
        target = target,
        ladder = ladder,
        mode = LadderMode.OutFrom,
        destination = ladder.target,
        alternatives = options.filter { genreKey(it) != chosenKey },
    )
}
